/*
 * Copyright (c) "Neo4j"
 * Neo4j Sweden AB [http://neo4j.com]
 *
 * This file is part of Neo4j.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package apoc.custom;

import apoc.path.PathExplorer;
import apoc.util.FileUtils;
import apoc.util.TestUtil;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.contrib.java.lang.system.ProvideSystemProperty;
import org.junit.rules.TemporaryFolder;
import org.neo4j.configuration.GraphDatabaseSettings;
import org.neo4j.dbms.api.DatabaseManagementService;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Result;
import org.neo4j.internal.helpers.collection.Iterators;
import org.neo4j.test.TestDatabaseManagementServiceBuilder;

import java.io.File;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static apoc.custom.CypherProceduresHandler.CUSTOM_PROCEDURES_REFRESH;
import static apoc.util.MapUtil.map;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * @author mh
 * @since 18.08.18
 */
public class CypherProceduresStorageTest {
    private final static String QUERY_CREATE = "RETURN $input1 + $input2 as answer";
    private final static String QUERY_OVERWRITE = "RETURN $input1 + $input2 + 123 as answer";

    // we cannot set via apocConfig().setProperty(apoc.custom.procedures.refresh, ...) in `@Before`, because is too late
    @ClassRule
    public static final ProvideSystemProperty systemPropertyRule =
            new ProvideSystemProperty(CUSTOM_PROCEDURES_REFRESH, "10");

    @Rule
    public TemporaryFolder STORE_DIR = new TemporaryFolder();

    private GraphDatabaseService db;
    private DatabaseManagementService databaseManagementService;

    @Before
    public void setUp() throws Exception {
        databaseManagementService = new TestDatabaseManagementServiceBuilder(STORE_DIR.getRoot().toPath()).build();
        db = databaseManagementService.database(GraphDatabaseSettings.DEFAULT_DATABASE_NAME);
        TestUtil.registerProcedure(db, CypherProcedures.class, PathExplorer.class);
    }

    private void restartDb() {
        databaseManagementService.shutdown();
        databaseManagementService = new TestDatabaseManagementServiceBuilder(STORE_DIR.getRoot().toPath()).build();
        db = databaseManagementService.database(GraphDatabaseSettings.DEFAULT_DATABASE_NAME);
        assertTrue(db.isAvailable(1000));
        TestUtil.registerProcedure(db, CypherProcedures.class, PathExplorer.class);
    }

    @Test
    public void testRestoreProcedureWorksCorrectlyWithoutConflicts() {
        // create a list of ["proc1", "proc2", "proc3" ....] strings
        List<String> listProcNames = IntStream.range(0, 200)
                .mapToObj(i -> "proc" + i)
                .collect(Collectors.toList());

        // for each element, declare a procedure with that name,
        // then call the custom procedure and finally overwrite it
        listProcNames.forEach(name -> {
            String declareProc = String.format("CALL apoc.custom.declareProcedure('%s() :: (answer::INT)', $query)", name);

            db.executeTransactionally(declareProc,
                    Map.of("query", "RETURN 42 AS answer"),
                    Result::resultAsString
            );

            TestUtil.testCall(db,
                    String.format("call custom.%s", name),
                    (row) -> assertEquals(42L, row.get("answer"))
            );

            // overwriting
            db.executeTransactionally(declareProc,
                    Map.of("query", "RETURN 1 AS answer"),
                    Result::resultAsString
            );
        });

        // check that the previous overwrite works correctly after the `db.clearQueryCaches`
        db.executeTransactionally("call db.clearQueryCaches");
        listProcNames.forEach(name -> TestUtil.testCall(db,
                    String.format("call custom.%s", name),
                    (row) -> assertEquals(1L, row.get("answer"))
                )
        );

        // check that everything works correctly after a db restart
        restartDb();
        listProcNames.forEach(name -> TestUtil.testCall(db,
                    String.format("call custom.%s", name),
                    (row) -> assertEquals(1L, row.get("answer"))
                )
        );
    }

    @Test
    public void testRestoreFunctionWorksCorrectlyWithoutConflicts() {
        // create a list of ["fun1", "fun2", "fun3" ....] strings
        List<String> listFunNames = IntStream.range(0, 200)
                .mapToObj(i -> "fun" + i)
                .collect(Collectors.toList());
        final String funQuery = "return custom.%s() as row";

        // for each element, declare a function with that name,
        // then call the custom function and finally overwrite it
        listFunNames.forEach(name -> {
            final String declareFunction = String.format("CALL apoc.custom.declareFunction('%s() :: INT', $query)", name);

            db.executeTransactionally(declareFunction,
                    Map.of("query", "RETURN 42 as answer"),
                    Result::resultAsString
            );

            TestUtil.testCall(db,
                    String.format(funQuery, name),
                    (row) -> assertEquals(42L, row.get("row"))
            );

            // overwriting
            db.executeTransactionally(declareFunction,
                    Map.of("query", "RETURN 1 as answer"),
                    Result::resultAsString
            );
        });

        // check that the previous overwrite works correctly after the `db.clearQueryCaches`
        db.executeTransactionally("call db.clearQueryCaches");
        listFunNames.forEach(name -> TestUtil.testCall(db,
                        String.format(funQuery, name),
                        (row) -> assertEquals(1L, row.get("row"))
                )
        );

        // check that everything works correctly after a db restart
        restartDb();
        listFunNames.forEach(name -> TestUtil.testCall(db,
                    String.format(funQuery, name),
                    (row) -> assertEquals(1L, row.get("row"))
                )
        );
    }

    @Test
    public void registerSimpleStatement() {
        db.executeTransactionally("call apoc.custom.asProcedure('answer','RETURN 42 as answer')");
        restartDb();
        TestUtil.testCall(db, "call custom.answer()", (row) -> assertEquals(42L, ((Map)row.get("row")).get("answer")));
        TestUtil.testCall(db, "call apoc.custom.list()", row -> {
            assertEquals("answer", row.get("name"));
            assertEquals("procedure", row.get("type"));
        });
    }

    @Test
    public void registerSimpleFunctionWithDotInName() {
        db.executeTransactionally("call apoc.custom.asFunction('foo.bar.baz','RETURN 42 as answer')");
        TestUtil.testCall(db, "return custom.foo.bar.baz() as row", (row) -> assertEquals(42L, ((Map)((List)row.get("row")).get(0)).get("answer")));
        TestUtil.testCall(db, "call apoc.custom.list()", row -> {
            assertEquals("foo.bar.baz", row.get("name"));
            assertEquals("function", row.get("type"));
        });
        restartDb();
        TestUtil.testCall(db, "return custom.foo.bar.baz() as row", (row) -> assertEquals(42L, ((Map)((List)row.get("row")).get(0)).get("answer")));
        TestUtil.testCall(db, "call apoc.custom.list()", row -> {
            assertEquals("foo.bar.baz", row.get("name"));
            assertEquals("function", row.get("type"));
        });
    }

    @Test
    public void registerSimpleProcedureWithDotInName() {
        db.executeTransactionally("call apoc.custom.asProcedure('foo.bar.baz','RETURN 42 as answer')");
        TestUtil.testCall(db, "call custom.foo.bar.baz()", (row) -> assertEquals(42L, ((Map)row.get("row")).get("answer")));
        TestUtil.testCall(db, "call apoc.custom.list()", row -> {
            assertEquals("foo.bar.baz", row.get("name"));
            assertEquals("procedure", row.get("type"));
        });
        restartDb();
        TestUtil.testCall(db, "call custom.foo.bar.baz()", (row) -> assertEquals(42L, ((Map)row.get("row")).get("answer")));
        TestUtil.testCall(db, "call apoc.custom.list()", row -> {
            assertEquals("foo.bar.baz", row.get("name"));
            assertEquals("procedure", row.get("type"));
        });
    }

    @Test
    public void registerSimpleStatementConcreteResults() {
        db.executeTransactionally("call apoc.custom.asProcedure('answer','RETURN 42 as answer','read',[['answer','long']])");
        restartDb();
        TestUtil.testCall(db, "call custom.answer()", (row) -> assertEquals(42L, row.get("answer")));
    }

    @Test
    public void registerParameterStatement() {
        db.executeTransactionally("call apoc.custom.asProcedure('answer','RETURN $answer as answer')");
        restartDb();
        TestUtil.testCall(db, "call custom.answer({answer:42})", (row) -> assertEquals(42L, ((Map)row.get("row")).get("answer")));
    }

    @Test
    public void registerConcreteParameterStatement() {
        db.executeTransactionally("call apoc.custom.asProcedure('answer','RETURN $input as answer','read',null,[['input','number']])");
        restartDb();
        TestUtil.testCall(db, "call custom.answer(42)", (row) -> assertEquals(42L, ((Map)row.get("row")).get("answer")));
    }

    @Test
    public void registerConcreteParameterAndReturnStatement() {
        db.executeTransactionally("call apoc.custom.asProcedure('answer','RETURN $input as answer','read',[['answer','number']],[['input','int','42']])");
        restartDb();
        TestUtil.testCall(db, "call custom.answer()", (row) -> assertEquals(42L, row.get("answer")));
    }

    @Test
    public void testAllParameterTypes() {
        db.executeTransactionally("call apoc.custom.asProcedure('answer','RETURN [$int,$float,$string,$map,$`list int`,$bool,$date,$datetime,$point] as data','read',null," +
                "[['int','int'],['float','float'],['string','string'],['map','map'],['list int','list int'],['bool','bool'],['date','date'],['datetime','datetime'],['point','point']])");
        restartDb();
        TestUtil.testCall(db, "call custom.answer(42,3.14,'foo',{a:1},[1],true,date(),datetime(),point({x:1,y:2}))", (row) -> assertEquals(9, ((List)((Map)row.get("row")).get("data")).size()));
    }

    @Test
    public void registerSimpleStatementFunction() {
        db.executeTransactionally("call apoc.custom.asFunction('answer','RETURN 42 as answer')");
        TestUtil.testCall(db, "return custom.answer() as row", (row) -> assertEquals(42L, ((Map)((List)row.get("row")).get(0)).get("answer")));
        restartDb();
        TestUtil.testCall(db, "return custom.answer() as row", (row) -> assertEquals(42L, ((Map)((List)row.get("row")).get(0)).get("answer")));
        TestUtil.testCall(db, "call apoc.custom.list()", row -> {
            assertEquals("answer", row.get("name"));
            assertEquals("function", row.get("type"));
        });
    }

    @Test
    public void registerSimpleStatementFunctionWithDotInName() {
        db.executeTransactionally("call apoc.custom.asFunction('foo.bar.baz','RETURN 42 as answer')");
        TestUtil.testCall(db, "return custom.foo.bar.baz() as row", (row) -> assertEquals(42L, ((Map)((List)row.get("row")).get(0)).get("answer")));
        TestUtil.testCall(db, "call apoc.custom.list()", row -> {
            assertEquals("foo.bar.baz", row.get("name"));
            assertEquals("function", row.get("type"));
        });
        restartDb();
        TestUtil.testCall(db, "return custom.foo.bar.baz() as row", (row) -> assertEquals(42L, ((Map)((List)row.get("row")).get(0)).get("answer")));
        TestUtil.testCall(db, "call apoc.custom.list()", row -> {
            assertEquals("foo.bar.baz", row.get("name"));
            assertEquals("function", row.get("type"));
        });
    }

    @Test
    public void registerSimpleStatementConcreteResultsFunction() {
        db.executeTransactionally("call apoc.custom.asFunction('answer','RETURN 42 as answer','long')");
        restartDb();
        TestUtil.testCall(db, "return custom.answer() as answer", (row) -> assertEquals(42L, row.get("answer")));
    }

    @Test
    public void registerSimpleStatementConcreteResultsFunctionUnnamedResultColumn() {
        db.executeTransactionally("call apoc.custom.asFunction('answer','RETURN 42','long')");
        restartDb();
        TestUtil.testCall(db, "return custom.answer() as answer", (row) -> assertEquals(42L, row.get("answer")));
    }

    @Test
    public void registerParameterStatementFunction() {
        db.executeTransactionally("call apoc.custom.asFunction('answer','RETURN $answer as answer','long')");
        restartDb();
        TestUtil.testCall(db, "return custom.answer({answer:42}) as answer", (row) -> assertEquals(42L, row.get("answer")));
    }

    @Test
    public void registerConcreteParameterAndReturnStatementFunction() {
        db.executeTransactionally("call apoc.custom.asFunction('answer','RETURN $input as answer','long',[['input','number']])");
        restartDb();
        TestUtil.testCall(db, "return custom.answer(42) as answer", (row) -> assertEquals(42L, row.get("answer")));
    }

    @Test
    public void testAllParameterTypesFunction() {
        db.executeTransactionally("call apoc.custom.asFunction('answer','RETURN [$int,$float,$string,$map,$`list int`,$bool,$date,$datetime,$point] as data','list of any'," +
                "[['int','int'],['float','float'],['string','string'],['map','map'],['list int','list int'],['bool','bool'],['date','date'],['datetime','datetime'],['point','point']], true)");
        restartDb();
        TestUtil.testCall(db, "return custom.answer(42,3.14,'foo',{a:1},[1],true,date(),datetime(),point({x:1,y:2})) as data", (row) -> assertEquals(9, ((List)row.get("data")).size()));
    }

    @Test
    public void testIssue1744() throws Exception {
        db.executeTransactionally("CREATE (:Area {name: 'foo'})-[:CURRENT]->(:VantagePoint {alpha: 'beta'})");
        db.executeTransactionally("CALL apoc.custom.asProcedure('vantagepoint_within_area',\n" +
            "  \"MATCH (start:Area {name: $areaName} )\n" +
            "    CALL apoc.path.expand(start,'CONTAINS>|<SEES|CURRENT','',0,100) YIELD path\n" +
            "    UNWIND nodes(path) as node\n" +
            "    WITH node\n" +
            "    WHERE node:VantagePoint\n" +
            "    RETURN DISTINCT node as resource\",\n" +
            "  'read',\n" +
            "  [['resource','NODE']],\n" +
            "  [['areaName', 'STRING']],\n" +
            "  \"Get vantage points within an area and all included areas\");");

        // function analogous to procedure
        db.executeTransactionally("CALL apoc.custom.asFunction('vantagepoint_within_area',\n" +
            "  \"MATCH (start:Area {name: $areaName} )\n" +
            "    CALL apoc.path.expand(start,'CONTAINS>|<SEES|CURRENT','',0,100) YIELD path\n" +
            "    UNWIND nodes(path) as node\n" +
            "    WITH node\n" +
            "    WHERE node:VantagePoint\n" +
            "    RETURN DISTINCT node as resource\",\n" +
            "  'read',\n" +
            "  [['areaName', 'STRING']]);");

        testCallIssue1744();
        restartDb();

        final String logFileContent = Files.readString(new File(FileUtils.getLogDirectory(), "debug.log").toPath());
        assertFalse(logFileContent.contains("Could not register function: custom.vantagepoint_within_area"));
        assertFalse(logFileContent.contains("Could not register procedure: custom.vantagepoint_within_area"));
        testCallIssue1744();
    }

    @Test
    public void functionSignatureShouldNotChangeBeforeAndAfterRestart() {
        functionsCreation();

        functionAssertions();
        restartDb();
        functionAssertions();
    }

    @Test
    public void procedureSignatureShouldNotChangeBeforeAndAfterRestart() {
        proceduresCreation();

        procedureAssertions();
        restartDb();
        procedureAssertions();
    }

    @Test
    public void functionSignatureShouldNotChangeBeforeAndAfterRestartAndOverwrite() {
        functionsCreation();

        functionAssertions();
        restartDb();

        // overwrite function
        db.executeTransactionally("call apoc.custom.declareFunction('sumFun2(input1::INT, input2::INT) :: INT',$query)",
                map("query", QUERY_OVERWRITE));
        db.executeTransactionally("call apoc.custom.declareFunction('sumFun1(input1 = null::INT, input2 = null::INT) :: INT',$query)",
                map("query", QUERY_OVERWRITE));
        functionAssertions();
    }

    @Test
    public void procedureSignatureShouldNotChangeBeforeAndAfterRestartAndOverwrite() {
        proceduresCreation();

        procedureAssertions();
        restartDb();

        // overwrite function
        db.executeTransactionally("call apoc.custom.declareProcedure('sum1(input1 = null::INT, input2 = null::INT) :: (answer::INT)',$query)",
                map("query", QUERY_OVERWRITE));
        db.executeTransactionally("call apoc.custom.declareProcedure('sum2(input1::INT, input2::INT) :: (answer::INT)',$query)",
                map("query", QUERY_OVERWRITE));
        procedureAssertions();
    }

    private void functionsCreation() {
        // 1 declareFunction with default null, 1 declareFunction without default 
        db.executeTransactionally("call apoc.custom.declareFunction('sumFun1(input1 = null::INT, input2 = null::INT) :: INT',$query)",
                map("query", QUERY_CREATE));
        db.executeTransactionally("call apoc.custom.declareFunction('sumFun2(input1::INT, input2::INT) :: INT',$query)",
                map("query", QUERY_CREATE));
    }

    private void functionAssertions() {
        TestUtil.testResult(db, "SHOW FUNCTIONS YIELD signature, name WHERE name STARTS WITH 'custom.sumFun' RETURN DISTINCT name, signature ORDER BY name",
                r -> {
                    Map<String, Object> row = r.next();
                    assertEquals("custom.sumFun1(input1 = null :: INTEGER?, input2 = null :: INTEGER?) :: (INTEGER?)", row.get("signature"));
                    row = r.next();
                    assertEquals("custom.sumFun2(input1 :: INTEGER?, input2 :: INTEGER?) :: (INTEGER?)", row.get("signature"));
                    assertFalse(r.hasNext());
                });
        TestUtil.testResult(db, "call apoc.custom.list() YIELD name RETURN name ORDER BY name",
                row -> {
                    final List<String> sumFun1 = List.of("sumFun1", "sumFun2");
                    assertEquals(sumFun1, Iterators.asList(row.columnAs("name")));
                });

        TestUtil.testCall(db, String.format("RETURN %s()", "custom.sumFun1"), (row) -> assertNull(row.get("answer")));
        try {
            TestUtil.testCall(db, String.format("RETURN %s()", "custom.sumFun2"), (row) -> fail("Should fail because of missing params"));
        } catch (RuntimeException e) {
            assertTrue(e.getMessage().contains("Function call does not provide the required number of arguments: expected 2 got 0"));
        }
        
    }

    private void proceduresCreation() {
        // 1 declareProcedure with default null, 1 declareProcedure without default 
        db.executeTransactionally("call apoc.custom.declareProcedure('sum1(input1 = null::INT, input2 = null::INT) :: (answer::INT)',$query)",
                map("query", QUERY_CREATE));
        db.executeTransactionally("call apoc.custom.declareProcedure('sum2(input1::INT, input2::INT) :: (answer::INT)',$query)",
                map("query", QUERY_CREATE));
    }

    private void procedureAssertions() {
        TestUtil.testResult(db, "SHOW PROCEDURES YIELD signature, name WHERE name STARTS WITH 'custom.sum' RETURN DISTINCT name, signature ORDER BY name",
                r -> {
                    Map<String, Object> row = r.next();
                    assertEquals("custom.sum1(input1 = null :: INTEGER?, input2 = null :: INTEGER?) :: (answer :: INTEGER?)", row.get("signature"));
                    row = r.next();
                    assertEquals("custom.sum2(input1 :: INTEGER?, input2 :: INTEGER?) :: (answer :: INTEGER?)", row.get("signature"));
                    assertFalse(r.hasNext());
                });
        TestUtil.testResult(db, "call apoc.custom.list() YIELD name RETURN name ORDER BY name",
                row -> {
                    final List<String> sumFun1 = List.of("sum1", "sum2");
                    assertEquals(sumFun1, Iterators.asList(row.columnAs("name")));
                });
        
        TestUtil.testCall(db, String.format("CALL %s", "custom.sum1"), (row) -> assertNull(row.get("answer")));
        try {
            TestUtil.testCall(db, String.format("CALL %s()", "custom.sum2"), (row) -> fail("Should fail because of missing params"));
        } catch (RuntimeException e) {
            assertTrue(e.getMessage().contains("Procedure call does not provide the required number of arguments: got 0 expected at least 2"));
        }
    }

    private void testCallIssue1744() {
        TestUtil.testCall(db, "CALL custom.vantagepoint_within_area('foo')", this::assertCallIssue1744);
        TestUtil.testCall(db, "RETURN custom.vantagepoint_within_area('foo') as resource", this::assertCallIssue1744);
    }

    private void assertCallIssue1744(Map<String, Object> row) {
        final Node resource = (Node) row.get("resource");
        assertEquals("VantagePoint", resource.getLabels().iterator().next().name());
        assertEquals("beta", resource.getProperty("alpha"));
    }

    @Test
    public void testIssue1714WithRestartDb() throws Exception {
        db.executeTransactionally("CREATE (i:Target {value: 2});");
        db.executeTransactionally("CALL apoc.custom.asFunction('n', 'MATCH (t:Target {value : $val}) RETURN t', 'NODE', [['val', 'INTEGER']])");
        restartDb();
        TestUtil.testCall(db, "RETURN custom.n(2) as row", (row) -> assertEquals(2L, ((Node) row.get("row")).getProperty("value")));
    }
}
