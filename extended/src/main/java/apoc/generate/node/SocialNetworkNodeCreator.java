package apoc.generate.node;

import org.neo4j.graphdb.Label;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Transaction;

import static apoc.util.Faker.getRandomFullName;

/**
 * A {@link NodeCreator} that assigns every {@link Node} a "Person" {@link Label}, and a randomly generated English
 * name under the property key "name".
 */
public class SocialNetworkNodeCreator implements NodeCreator {

    private static final Label PERSON_LABEL = Label.label("Person");
    private static final String NAME = "name";

    /**
     * {@inheritDoc}
     */
    @Override
    public Node createNode(Transaction tx) {
        Node node = tx.createNode(PERSON_LABEL);
        node.setProperty(NAME, getRandomFullName());
        return node;
    }
}
