package apoc.custom;

import apoc.ApocConfig;
import apoc.ExtendedSystemLabels;
import apoc.ExtendedSystemPropertyKeys;
import apoc.SystemPropertyKeys;
import apoc.util.JsonUtil;
import apoc.util.Util;
import org.apache.commons.lang3.tuple.Pair;
import org.neo4j.collection.RawIterator;
import org.neo4j.graphdb.Entity;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Path;
import org.neo4j.graphdb.QueryExecutionException;
import org.neo4j.graphdb.Result;
import org.neo4j.graphdb.Transaction;
import org.neo4j.internal.helpers.collection.Iterators;
import org.neo4j.internal.kernel.api.exceptions.ProcedureException;
import org.neo4j.internal.kernel.api.procs.DefaultParameterValue;
import org.neo4j.internal.kernel.api.procs.FieldSignature;
import org.neo4j.internal.kernel.api.procs.Neo4jTypes;
import org.neo4j.internal.kernel.api.procs.ProcedureSignature;
import org.neo4j.internal.kernel.api.procs.QualifiedName;
import org.neo4j.internal.kernel.api.procs.UserFunctionSignature;
import org.neo4j.kernel.api.ResourceMonitor;
import org.neo4j.kernel.api.procedure.CallableProcedure;
import org.neo4j.kernel.api.procedure.CallableUserFunction;
import org.neo4j.kernel.api.procedure.GlobalProcedures;
import org.neo4j.kernel.availability.AvailabilityListener;
import org.neo4j.kernel.impl.util.ValueUtils;
import org.neo4j.kernel.internal.GraphDatabaseAPI;
import org.neo4j.kernel.lifecycle.LifecycleAdapter;
import org.neo4j.logging.Log;
import org.neo4j.procedure.Mode;
import org.neo4j.procedure.Name;
import org.neo4j.procedure.impl.ProcedureHolderUtils;
import org.neo4j.scheduler.Group;
import org.neo4j.scheduler.JobHandle;
import org.neo4j.scheduler.JobScheduler;
import org.neo4j.values.AnyValue;
import org.neo4j.values.ValueMapper;
import org.neo4j.values.storable.Values;
import org.neo4j.values.virtual.MapValueBuilder;
import org.neo4j.values.virtual.VirtualValues;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static apoc.ApocConfig.apocConfig;
import static apoc.custom.Signatures.NUMBER_TYPE;
import static apoc.util.MapUtil.map;
import static java.util.Collections.singletonList;
import static org.neo4j.internal.kernel.api.procs.Neo4jTypes.AnyType;
import static org.neo4j.internal.kernel.api.procs.Neo4jTypes.NTAny;
import static org.neo4j.internal.kernel.api.procs.Neo4jTypes.NTBoolean;
import static org.neo4j.internal.kernel.api.procs.Neo4jTypes.NTDate;
import static org.neo4j.internal.kernel.api.procs.Neo4jTypes.NTDateTime;
import static org.neo4j.internal.kernel.api.procs.Neo4jTypes.NTDuration;
import static org.neo4j.internal.kernel.api.procs.Neo4jTypes.NTFloat;
import static org.neo4j.internal.kernel.api.procs.Neo4jTypes.NTGeometry;
import static org.neo4j.internal.kernel.api.procs.Neo4jTypes.NTInteger;
import static org.neo4j.internal.kernel.api.procs.Neo4jTypes.NTList;
import static org.neo4j.internal.kernel.api.procs.Neo4jTypes.NTLocalDateTime;
import static org.neo4j.internal.kernel.api.procs.Neo4jTypes.NTLocalTime;
import static org.neo4j.internal.kernel.api.procs.Neo4jTypes.NTMap;
import static org.neo4j.internal.kernel.api.procs.Neo4jTypes.NTNode;
import static org.neo4j.internal.kernel.api.procs.Neo4jTypes.NTNumber;
import static org.neo4j.internal.kernel.api.procs.Neo4jTypes.NTPath;
import static org.neo4j.internal.kernel.api.procs.Neo4jTypes.NTPoint;
import static org.neo4j.internal.kernel.api.procs.Neo4jTypes.NTRelationship;
import static org.neo4j.internal.kernel.api.procs.Neo4jTypes.NTString;
import static org.neo4j.internal.kernel.api.procs.Neo4jTypes.NTTime;

public class CypherProceduresHandler extends LifecycleAdapter implements AvailabilityListener {
    public static final String PREFIX = "custom";
    public static final String FUNCTION = "function";
    public static final String PROCEDURE = "procedure";
    public static final String CUSTOM_PROCEDURES_REFRESH = "apoc.custom.procedures.refresh";
    public static final List<FieldSignature> DEFAULT_INPUTS = singletonList(FieldSignature.inputField("params", NTMap, DefaultParameterValue.ntMap(Collections.emptyMap())));
    public static final List<FieldSignature> DEFAULT_MAP_OUTPUT = singletonList(FieldSignature.inputField("row", NTMap));
    public static final String ERROR_INVALID_TYPE = "Invalid type name." +
            "\nCheck the documentation to see possible values: https://neo4j.com/labs/apoc/4.1/cypher-execution/cypher-based-procedures-functions/";

    private final GraphDatabaseAPI api;
    private final Log log;
    private final GraphDatabaseService systemDb;
    private final GlobalProcedures globalProceduresRegistry;
    private final JobScheduler jobScheduler;
    private long lastUpdate;
    private final Set<ProcedureSignature> registeredProcedureSignatures = Collections.synchronizedSet(new HashSet<>());
    private final Set<UserFunctionSignature> registeredUserFunctionSignatures = Collections.synchronizedSet(new HashSet<>());
    private static Group REFRESH_GROUP = Group.STORAGE_MAINTENANCE;
    private JobHandle restoreProceduresHandle;


    public CypherProceduresHandler(GraphDatabaseAPI db, JobScheduler jobScheduler, ApocConfig apocConfig, Log userLog, GlobalProcedures globalProceduresRegistry) {
        this.api = db;
        this.log = userLog;
        this.jobScheduler = jobScheduler;
        this.systemDb = apocConfig.getSystemDb();
        this.globalProceduresRegistry = globalProceduresRegistry;

    }

    @Override
    public void available() {
        restoreProceduresAndFunctions();
        long refreshInterval = apocConfig().getInt(CUSTOM_PROCEDURES_REFRESH, 60000);
        restoreProceduresHandle = jobScheduler.scheduleRecurring(REFRESH_GROUP, () -> {
            if (getLastUpdate() > lastUpdate) {
                restoreProceduresAndFunctions();
            }
        }, refreshInterval, refreshInterval, TimeUnit.MILLISECONDS);
    }

    @Override
    public void unavailable() {
        if (restoreProceduresHandle != null) {
            restoreProceduresHandle.cancel();
        }
    }

    public Mode mode(String s) {
        return s == null ? Mode.READ : Mode.valueOf(s.toUpperCase());
    }

    public Stream<ProcedureOrFunctionDescriptor> readSignatures() {
        List<ProcedureOrFunctionDescriptor> descriptors;
        try (Transaction tx = systemDb.beginTx()) {
             descriptors = tx.findNodes( ExtendedSystemLabels.ApocCypherProcedures, SystemPropertyKeys.database.name(), api.databaseName()).stream().map(node -> {
                if (node.hasLabel(ExtendedSystemLabels.Procedure)) {
                    return procedureDescriptor(node);
                } else if (node.hasLabel(ExtendedSystemLabels.Function)) {
                    return userFunctionDescriptor(node);
                } else {
                    throw new IllegalStateException("don't know what to do with systemdb node " + node);
                }
            }).collect(Collectors.toList());
            tx.commit();
        }
        return descriptors.stream();
    }

    private ProcedureDescriptor procedureDescriptor(Node node) {
        String statement = (String) node.getProperty(SystemPropertyKeys.statement.name());

        String name = (String) node.getProperty(SystemPropertyKeys.name.name());
        String description = (String) node.getProperty( ExtendedSystemPropertyKeys.description.name(), null);
        String[] prefix = (String[]) node.getProperty(ExtendedSystemPropertyKeys.prefix.name(), new String[]{PREFIX});

        String property = (String) node.getProperty(ExtendedSystemPropertyKeys.inputs.name());
        List<FieldSignature> inputs = deserializeSignatures(property);

        List<FieldSignature> outputSignature = deserializeSignatures((String) node.getProperty(ExtendedSystemPropertyKeys.outputs.name()));
        return new ProcedureDescriptor(Signatures.createProcedureSignature(
                new QualifiedName(prefix, name),
                inputs,
                outputSignature,
                Mode.valueOf((String) node.getProperty(ExtendedSystemPropertyKeys.mode.name())),
                false,
                null,
                description,
                null,
                false,
                false,
                false,
                false,
                false,
                false), statement);
    }

    private UserFunctionDescriptor userFunctionDescriptor(Node node) {
        String statement = (String) node.getProperty(SystemPropertyKeys.statement.name());

        String name = (String) node.getProperty(SystemPropertyKeys.name.name());
        String description = (String) node.getProperty(ExtendedSystemPropertyKeys.description.name(), null);
        String[] prefix = (String[]) node.getProperty(ExtendedSystemPropertyKeys.prefix.name(), new String[]{PREFIX});

        String property = (String) node.getProperty(ExtendedSystemPropertyKeys.inputs.name());
        List<FieldSignature> inputs = deserializeSignatures(property);

        boolean forceSingle = (boolean) node.getProperty(ExtendedSystemPropertyKeys.forceSingle.name(), false);
        return new UserFunctionDescriptor(new UserFunctionSignature(
                new QualifiedName(prefix, name),
                inputs,
                typeof((String) node.getProperty(ExtendedSystemPropertyKeys.output.name())),
                null,
                description,
                "apoc.custom",
                false,
                false,
                false,
                false
        ), statement, forceSingle);
    }

    public void restoreProceduresAndFunctions() {
        lastUpdate = System.currentTimeMillis();
        Set<ProcedureSignature> currentProceduresToRemove = new HashSet<>(registeredProcedureSignatures);
        Set<UserFunctionSignature> currentUserFunctionsToRemove = new HashSet<>(registeredUserFunctionSignatures);

        readSignatures().forEach(descriptor -> {
            descriptor.register();
            if (descriptor instanceof ProcedureDescriptor) {
                ProcedureSignature signature = ((ProcedureDescriptor) descriptor).getSignature();
                currentProceduresToRemove.remove(signature);
            } else {
                UserFunctionSignature signature = ((UserFunctionDescriptor) descriptor).getSignature();
                currentUserFunctionsToRemove.remove(signature);
            }
        });

        // de-register removed procs/functions
        currentProceduresToRemove.forEach(signature -> registerProcedure(signature, null));
        currentUserFunctionsToRemove.forEach(signature -> registerFunction(signature, null, false));

        api.executeTransactionally("call db.clearQueryCaches()");
    }

    private <T> T withSystemDb(Function<Transaction, T> action) {
        try (Transaction tx = systemDb.beginTx()) {
            T result = action.apply(tx);
            tx.commit();
            return result;
        }
    }

    public void storeFunction(UserFunctionSignature signature, String statement, boolean forceSingle) {
        withSystemDb(tx -> {
            Node node = Util.mergeNode(tx, ExtendedSystemLabels.ApocCypherProcedures, ExtendedSystemLabels.Function,
                    Pair.of(SystemPropertyKeys.database.name(), api.databaseName()),
                    Pair.of(SystemPropertyKeys.name.name(), signature.name().name()),
                    Pair.of(ExtendedSystemPropertyKeys.prefix.name(), signature.name().namespace())
            );
            node.setProperty(ExtendedSystemPropertyKeys.description.name(), signature.description().orElse(null));
            node.setProperty(SystemPropertyKeys.statement.name(), statement);
            node.setProperty(ExtendedSystemPropertyKeys.inputs.name(), serializeSignatures(signature.inputSignature()));
            node.setProperty(ExtendedSystemPropertyKeys.output.name(), signature.outputType().toString());
            node.setProperty(ExtendedSystemPropertyKeys.forceSingle.name(), forceSingle);

            setLastUpdate(tx);
            if (!registerFunction(signature, statement, forceSingle)) {
                throw new IllegalStateException("Error registering function " + signature + ", see log.");
            }
            return null;
        });
    }

    public void storeProcedure(ProcedureSignature signature, String statement) {
        withSystemDb(tx -> {
            Node node = Util.mergeNode(tx, ExtendedSystemLabels.ApocCypherProcedures, ExtendedSystemLabels.Procedure,
                    Pair.of(SystemPropertyKeys.database.name(), api.databaseName()),
                    Pair.of(SystemPropertyKeys.name.name(), signature.name().name()),
                    Pair.of(ExtendedSystemPropertyKeys.prefix.name(), signature.name().namespace())
            );
            node.setProperty(ExtendedSystemPropertyKeys.description.name(), signature.description().orElse(null));
            node.setProperty(SystemPropertyKeys.statement.name(), statement);
            node.setProperty(ExtendedSystemPropertyKeys.inputs.name(), serializeSignatures(signature.inputSignature()));
            node.setProperty(ExtendedSystemPropertyKeys.outputs.name(), serializeSignatures(signature.outputSignature()));
            node.setProperty(ExtendedSystemPropertyKeys.mode.name(), signature.mode().name());
            setLastUpdate(tx);
            if (!registerProcedure(signature, statement)) {
                throw new IllegalStateException("Error registering procedure " + signature.name() + ", see log.");
            }
            return null;
        });
    }

    private String serializeSignatures(List<FieldSignature> signatures) {
        List<Map<String, Object>> mapped = signatures.stream().map(fs -> {
            final Map<String, Object> map = map(
                    "name", fs.name(),
                    "type", fs.neo4jType().toString()
            );
            fs.defaultValue().map(defVal -> map.put("default", defVal.value()));
            return map;
        }).collect(Collectors.toList());
        return Util.toJson(mapped);
    }

    public static List<FieldSignature> deserializeSignatures(String s) {
        List<Map<String, Object>> mapped = Util.fromJson(s, List.class);
        if (mapped.isEmpty()) return ProcedureSignature.VOID;
        return mapped.stream().map(map -> {
            String typeString = (String) map.get("type");
            if (typeString.endsWith("?")) {
                typeString = typeString.substring(0, typeString.length() - 1);
            }
            AnyType type = typeof(typeString);
            // we insert the default value only if is present
            if (map.containsKey("default")) {
                return FieldSignature.inputField((String) map.get("name"), type, new DefaultParameterValue(map.get("default"), type));
            } else {
                return FieldSignature.inputField((String) map.get("name"), type);
            }
        }).collect(Collectors.toList());
    }

    private void setLastUpdate(Transaction tx) {
        Node node = tx.findNode(ExtendedSystemLabels.ApocCypherProceduresMeta, SystemPropertyKeys.database.name(), api.databaseName());
        if (node == null) {
            node = tx.createNode(ExtendedSystemLabels.ApocCypherProceduresMeta);
            node.setProperty(SystemPropertyKeys.database.name(), api.databaseName());
        }
        node.setProperty(SystemPropertyKeys.lastUpdated.name(), System.currentTimeMillis());
    }

    private long getLastUpdate() {
        return withSystemDb( tx -> {
            Node node = tx.findNode(ExtendedSystemLabels.ApocCypherProceduresMeta, SystemPropertyKeys.database.name(), api.databaseName());
            return node == null ? 0L : (long) node.getProperty(SystemPropertyKeys.lastUpdated.name());
        });
    }

    /**
     *
     * @param signature
     * @param statement null indicates a removed procedure
     * @return
     */
    public boolean registerProcedure(ProcedureSignature signature, String statement) {
        QualifiedName name = signature.name();
        try {
            boolean exists = globalProceduresRegistry.getCurrentView().getAllProcedures().stream()
                    .anyMatch(s -> s.name().equals(name));
            if (exists) {
                // we deregister and remove possible homonyms signatures overridden/overloaded
                ProcedureHolderUtils.unregisterProcedure(name, globalProceduresRegistry);
                registeredProcedureSignatures.removeIf(i -> i.name().equals(signature.name()));
            }

            final boolean isStatementNull = statement == null;
            globalProceduresRegistry.register(new CallableProcedure.BasicProcedure(signature) {
                @Override
                public RawIterator<AnyValue[], ProcedureException> apply(org.neo4j.kernel.api.procedure.Context ctx, AnyValue[] input, ResourceMonitor resourceMonitor) throws ProcedureException {
                    if (isStatementNull) {
                        final String error = String.format("There is no procedure with the name `%s` registered for this database instance. " +
                                "Please ensure you've spelled the procedure name correctly and that the procedure is properly deployed.", name);
                        throw new QueryExecutionException(error, null, "Neo.ClientError.Statement.SyntaxError");
                    } else {
                        Map<String, Object> params = params(input, signature.inputSignature(), ctx.valueMapper());
                        Transaction tx = ctx.transaction();
                        Result result = tx.execute(statement, params);
                        resourceMonitor.registerCloseableResource(result);

                        List<FieldSignature> outputs = signature.outputSignature();
                        String[] names = outputs == null ? null : outputs.stream().map(FieldSignature::name).toArray(String[]::new);
                        boolean defaultOutputs = outputs == null || outputs.equals(DEFAULT_MAP_OUTPUT);

                        Stream<AnyValue[]> stream = result.stream().map(row -> toResult(row, names, defaultOutputs));
                        return Iterators.asRawIterator(stream);
                    }
                }
            });
            if (isStatementNull) {
                registeredProcedureSignatures.remove(signature);
            } else {
                registeredProcedureSignatures.add(signature);
            }
            return true;
        } catch (Exception e) {
            log.error("Could not register procedure: " + name + " with " + statement + "\n accepting" + signature.inputSignature() + " resulting in " + signature.outputSignature() + " mode " + signature.mode(), e);
            return false;
        }
    }

    public boolean registerFunction(UserFunctionSignature signature, String statement, boolean forceSingle/*, boolean override*/) {
        try {
            QualifiedName name = signature.name();
            boolean exists = globalProceduresRegistry.getCurrentView().getAllNonAggregatingFunctions()
                    .anyMatch(s -> s.name().equals(name));
            if (exists) {
                // we deregister and remove possible homonyms signatures overridden/overloaded
                ProcedureHolderUtils.unregisterFunction(name, globalProceduresRegistry);
                registeredUserFunctionSignatures.removeIf(i -> i.name().equals(signature.name()));
            }

            final boolean isStatementNull = statement == null;
            globalProceduresRegistry.register(new CallableUserFunction.BasicUserFunction(signature) {
                @Override
                public AnyValue apply(org.neo4j.kernel.api.procedure.Context ctx, AnyValue[] input) throws ProcedureException {
                    if (isStatementNull) {
                        final String error = String.format("Unknown function '%s'", name);
                        throw new QueryExecutionException(error, null, "Neo.ClientError.Statement.SyntaxError");
                    } else {
                        Map<String, Object> params = params(input, signature.inputSignature(), ctx.valueMapper());
                        AnyType outType = signature.outputType();
                        Transaction tx = ctx.transaction();
                        try (Result result = tx.execute(statement, params)) {
//                resourceTracker.registerCloseableResource(result); // TODO
                            if (!result.hasNext()) return Values.NO_VALUE;
                            if (outType.equals(NTAny)) {
                                return ValueUtils.of(result.stream().collect(Collectors.toList()));
                            }
                            List<String> cols = result.columns();
                            if (cols.isEmpty()) return null;
                            if (!forceSingle && outType instanceof Neo4jTypes.ListType) {
                                Neo4jTypes.ListType listType = (Neo4jTypes.ListType) outType;
                                Neo4jTypes.AnyType innerType = listType.innerType();
                                // We wrap the result only if we have a "true" map, and not NodeType or RelationshipType that extends MapType
                                if (innerType.getClass().equals(Neo4jTypes.MapType.class))
                                    return ValueUtils.of(result.stream().collect(Collectors.toList()));
                                if (cols.size() == 1)
                                    return ValueUtils.of(result.stream().map(row -> row.get(cols.get(0))).collect(Collectors.toList()));
                            } else {
                                Map<String, Object> row = result.next();
                                // We wrap the result only if we have a "true" map, and not NodeType or RelationshipType that extends MapType
                                if (outType.getClass().equals(Neo4jTypes.MapType.class)) return ValueUtils.of(row);
                                if (cols.size() == 1) return ValueUtils.of(row.get(cols.get(0)));
                            }
                            throw new IllegalStateException("Result mismatch " + cols + " output type is " + outType);
                        }
                    }

                }
            });
            if (isStatementNull) {
                registeredUserFunctionSignatures.remove(signature);
            } else {
                registeredUserFunctionSignatures.add(signature);
            }
            return true;
        } catch (Exception e) {
            log.error("Could not register function: " + signature + "\nwith: " + statement + "\n single result " + forceSingle, e);
            return false;
        }
    }

    public static QualifiedName qualifiedName(@Name("name") String name) {
        String[] names = name.split("\\.");
        List<String> namespace = new ArrayList<>(names.length);
        namespace.add(PREFIX);
        namespace.addAll(Arrays.asList(names));
        return new QualifiedName(namespace.subList(0, namespace.size() - 1), names[names.length - 1]);
    }

    public List<FieldSignature> inputSignatures(@Name(value = "inputs", defaultValue = "null") List<List<String>> inputs) {
        List<FieldSignature> inputSignature = inputs == null ? singletonList(FieldSignature.inputField("params", NTMap, DefaultParameterValue.ntMap(Collections.emptyMap()))) :
                inputs.stream().map(pair -> {
                    DefaultParameterValue defaultValue = defaultValue(pair.get(1), pair.size() > 2 ? pair.get(2) : null);
                    return defaultValue == null ?
                            FieldSignature.inputField(pair.get(0), typeof(pair.get(1))) :
                            FieldSignature.inputField(pair.get(0), typeof(pair.get(1)), defaultValue);
                }).collect(Collectors.toList());
        return inputSignature;
    }

    private static Neo4jTypes.AnyType typeof(String typeName) {
        typeName = typeName.replaceAll("\\?", "");
        typeName = typeName.toUpperCase();
        if (typeName.startsWith("LIST OF ")) return NTList(typeof(typeName.substring(8)));
        if (typeName.startsWith("LIST ")) return NTList(typeof(typeName.substring(5)));
        if (typeName.startsWith("LIST<") && typeName.endsWith(">")) {
            AnyType typeof = typeof(typeName.substring(5, typeName.length() - 1));
            return NTList(typeof);
        }
        switch (typeName) {
            case "ANY":
                return NTAny;
            case "MAP":
                return NTMap;
            case "NODE":
                return NTNode;
            case "REL":
                return NTRelationship;
            case "RELATIONSHIP":
                return NTRelationship;
            case "EDGE":
                return NTRelationship;
            case "PATH":
                return NTPath;
            case "NUMBER":
            case NUMBER_TYPE:
                return NTNumber;
            case "LONG":
                return NTInteger;
            case "INT":
                return NTInteger;
            case "INTEGER":
                return NTInteger;
            case "FLOAT":
                return NTFloat;
            case "DOUBLE":
                return NTFloat;
            case "BOOL":
                return NTBoolean;
            case "BOOLEAN":
                return NTBoolean;
            case "DATE":
                return NTDate;
            case "TIME":
            case "ZONED TIME":
                return NTTime;
            case "LOCALTIME":
            case "LOCAL TIME":
                return NTLocalTime;
            case "DATETIME":
            case "ZONED DATETIME":
                return NTDateTime;
            case "LOCALDATETIME":
            case "LOCAL DATETIME":
                return NTLocalDateTime;
            case "DURATION":
                return NTDuration;
            case "POINT":
                return NTPoint;
            case "GEO":
                return NTGeometry;
            case "GEOMETRY":
                return NTGeometry;
            case "STRING":
                return NTString;
            case "TEXT":
                return NTString;
            default:
                return NTString;
        }
    }

    private DefaultParameterValue defaultValue(String typeName, String stringValue) {
        if (stringValue == null) return null;
        Object value = JsonUtil.parse(stringValue, null, Object.class);
        if (value == null) return null;
        typeName = typeName.toUpperCase();
        if (typeName.startsWith("LIST "))
            return DefaultParameterValue.ntList((List<?>) value, typeof(typeName.substring(5)));
        switch (typeName) {
            case "MAP":
                return DefaultParameterValue.ntMap((Map<String, Object>) value);
            case "NODE":
            case "REL":
            case "RELATIONSHIP":
            case "EDGE":
            case "PATH":
                return null;
            case "NUMBER":
            case NUMBER_TYPE:
                return value instanceof Float || value instanceof Double ? DefaultParameterValue.ntFloat(((Number) value).doubleValue()) : DefaultParameterValue.ntInteger(((Number) value).longValue());
            case "LONG":
            case "INT":
            case "INTEGER":
                return DefaultParameterValue.ntInteger(((Number) value).longValue());
            case "FLOAT":
            case "DOUBLE":
                return DefaultParameterValue.ntFloat(((Number) value).doubleValue());
            case "BOOL":
            case "BOOLEAN":
                return DefaultParameterValue.ntBoolean((Boolean) value);
            case "DATE":
            case "TIME":
            case "LOCALTIME":
            case "DATETIME":
            case "LOCALDATETIME":
            case "DURATION":
            case "ZONED TIME":
            case "LOCAL TIME":
            case "ZONED DATETIME":
            case "LOCAL DATETIME":
            case "POINT":
            case "GEO":
            case "GEOMETRY":
                return null;
            case "STRING":
            case "TEXT":
                return DefaultParameterValue.ntString(value.toString());
            default:
                return null;
        }
    }

    private AnyValue[] toResult(Map<String, Object> row, String[] names, boolean defaultOutputs) {
        if (defaultOutputs) {
            return new AnyValue[]{convertToValueRecursive(row)};
        } else {
            AnyValue[] result = new AnyValue[names.length];
            for (int i = 0; i < names.length; i++) {
                result[i] = convertToValueRecursive(row.get(names[i]));
            }
            return result;
        }
    }

    private AnyValue convertToValueRecursive(Object... toConverts) {
        switch (toConverts.length) {
            case 0:
                return Values.NO_VALUE;
            case 1:
                Object toConvert = toConverts[0];
                if (toConvert instanceof List) {
                    List list = (List) toConvert;
                    AnyValue[] objects = ((Stream<AnyValue>) list.stream().map(x -> convertToValueRecursive(x))).toArray(AnyValue[]::new);
                    return VirtualValues.list(objects);
                } else if (toConvert instanceof Map) {
                    Map<String, Object> map = (Map) toConvert;
                    MapValueBuilder builder = new MapValueBuilder();
                    map.entrySet().stream().forEach(e -> {
                        builder.add(e.getKey(), convertToValueRecursive(e.getValue()));
                    });
                    return builder.build();
                } else if (toConvert instanceof Entity || toConvert instanceof Path){
                    return ValueUtils.asAnyValue(toConvert);
                } else {
                    return Values.of(toConvert);
                }
            default:
                AnyValue[] values = Arrays.stream(toConverts).map(c -> convertToValueRecursive(c)).toArray(AnyValue[]::new);
                return VirtualValues.list(values);
        }
    }

    public Map<String, Object> params(AnyValue[] input, List<FieldSignature> fieldSignatures, ValueMapper valueMapper) {
        if (input == null || input.length == 0) return Collections.emptyMap();

        if (fieldSignatures == null || fieldSignatures.isEmpty() || fieldSignatures.equals(DEFAULT_INPUTS))
            return (Map<String, Object>) input[0].map(valueMapper);
        Map<String, Object> params = new HashMap<>(input.length);
        for (int i = 0; i < input.length; i++) {
            params.put(fieldSignatures.get(i).name(), input[i].map(valueMapper));
        }
        return params;
    }

    public void removeProcedure(String name) {
        withSystemDb(tx -> {
            QualifiedName qName = qualifiedName(name);
            tx.findNodes(ExtendedSystemLabels.ApocCypherProcedures,
                    SystemPropertyKeys.database.name(), api.databaseName(),
                    SystemPropertyKeys.name.name(), qName.name(),
                    ExtendedSystemPropertyKeys.prefix.name(), qName.namespace()
            ).stream().filter(n -> n.hasLabel(ExtendedSystemLabels.Procedure)).forEach(node -> {
                ProcedureDescriptor descriptor = procedureDescriptor(node);
                registerProcedure(descriptor.getSignature(), null);
                node.delete();
                setLastUpdate(tx);
            });
            return null;
        });
    }

    public void removeFunction(String name) {
        withSystemDb(tx -> {
            QualifiedName qName = qualifiedName(name);
            tx.findNodes(ExtendedSystemLabels.ApocCypherProcedures,
                    SystemPropertyKeys.database.name(), api.databaseName(),
                    SystemPropertyKeys.name.name(), qName.name(),
                    ExtendedSystemPropertyKeys.prefix.name(), qName.namespace()
            ).stream().filter(n -> n.hasLabel(ExtendedSystemLabels.Function)).forEach(node -> {
                UserFunctionDescriptor descriptor = userFunctionDescriptor(node);
                registerFunction(descriptor.getSignature(), null, false);
                node.delete();
                setLastUpdate(tx);
            });
            return null;
        });

    }

    public abstract class ProcedureOrFunctionDescriptor {
        private final String statement;

        protected ProcedureOrFunctionDescriptor(String statement) {
            this.statement = statement;
        }

        public String getStatement() {
            return statement;
        }

        abstract public void register();
    }

    public class ProcedureDescriptor extends ProcedureOrFunctionDescriptor {
        private final ProcedureSignature signature;

        public ProcedureDescriptor(ProcedureSignature signature, String statement) {
            super(statement);
            this.signature = signature;
        }

        public ProcedureSignature getSignature() {
            return signature;
        }

        @Override
        public void register() {
            registerProcedure(getSignature(), getStatement());
        }
    }

    public class UserFunctionDescriptor extends ProcedureOrFunctionDescriptor {
        private final UserFunctionSignature signature;
        private final boolean forceSingle;

        public UserFunctionDescriptor(UserFunctionSignature signature, String statement, boolean forceSingle) {
            super(statement);
            this.signature = signature;
            this.forceSingle = forceSingle;
        }

        public UserFunctionSignature getSignature() {
            return signature;
        }

        public boolean isForceSingle() {
            return forceSingle;
        }

        @Override
        public void register() {
            registerFunction(getSignature(), getStatement(), isForceSingle());
        }
    }
}
