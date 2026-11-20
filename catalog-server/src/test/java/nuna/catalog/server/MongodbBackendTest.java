package nuna.catalog.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import nuna.catalog.store.CatalogStore;
import nuna.catalog.store.SafetyFinding;
import org.bson.Document;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.MapPropertySource;
import org.springframework.test.context.ContextConfiguration;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

/**
 * The server on MongoDB ({@code nuna.database.backend=mongodb}): no {@code spring.datasource.*} and no DataSource,
 * a one-member replica set named by {@code nuna.database.mongodb.uri}, and the REST flow works on it. The client
 * reaches the set through a link the test can cut; while it is cut the routes answer 503 with Retry-After.
 */
@Tag("heavy")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "nuna.database.backend=mongodb",
        "nuna.security.grant-admin-groups=" + BackendFlow.GRANT_ADMINS,
})
@ContextConfiguration(initializers = {IssuerForTests.class, MongodbBackendTest.Mongodb.class})
@Import({S3ForTests.class, AuditSinkForTests.class})
class MongodbBackendTest {

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private CatalogStore store;

    @Autowired
    private ObjectProvider<DataSource> dataSource;

    @Test
    void theServerRunsOnMongodbWithoutADataSource() {
        assertThat(dataSource.getIfAvailable()).isNull();
        // Only MongoDB's store reports this (the bean is that store, observed): one member, so a majority cannot
        // survive losing it. Everything else the store checks holds.
        assertThat(store.selfCheck()).extracting(SafetyFinding::code).containsExactly("mongo.too-few-voters");
        new BackendFlow(port).run();
    }

    /** Backlog 70: what item 7 made the store raise for a database it cannot reach, the routes answer as an outage. */
    @Test
    void anOutageAnswers503WithRetryAfterUntilTheLinkIsBack() {
        new BackendFlow(port).outage(Mongodb.link::cut, Mongodb.link::heal);
    }

    /**
     * A one-member replica set (transactions need a replica set, not three members) and the server's URI for it,
     * through {@link LinkForTests}; the set is initiated directly. An initializer, as IssuerForTests is: the store
     * is made while the web server starts, before property registrar beans run. One container for the JVM;
     * Testcontainers removes it at exit.
     */
    static class Mongodb implements ApplicationContextInitializer<ConfigurableApplicationContext> {

        private static final String ALIAS = "mongodb";
        private static final int PORT = 27017;

        private static GenericContainer<?> mongodb;
        private static LinkForTests link;

        @Override
        public void initialize(ConfigurableApplicationContext context) {
            started();
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("mongodb-for-tests",
                    Map.of("nuna.database.mongodb.uri", uri(link.host(), link.port(), "catalog"))));
        }

        private static synchronized GenericContainer<?> started() {
            if (mongodb != null) {
                return mongodb;
            }
            link = new LinkForTests();
            mongodb = new GenericContainer<>(DockerImageName.parse(System.getProperty("mongo.image")))
                    .withNetwork(link.network())
                    .withNetworkAliases(ALIAS)
                    .withCommand("--replSet", "rs0", "--bind_ip_all")
                    .withExposedPorts(PORT)
                    .waitingFor(Wait.forLogMessage(".*Waiting for connections.*", 1));
            mongodb.start();
            try (MongoClient admin = MongoClients.create(uri(mongodb.getHost(), mongodb.getMappedPort(PORT), ""))) {
                admin.getDatabase("admin").runCommand(new Document("replSetInitiate", new Document("_id", "rs0")
                        .append("members", List.of(new Document("_id", 0).append("host", "localhost:27017")))));
                Instant deadline = Instant.now().plusSeconds(60);
                while (!admin.getDatabase("admin").runCommand(new Document("hello", 1)).getBoolean("isWritablePrimary")) {
                    if (Instant.now().isAfter(deadline)) {
                        throw new IllegalStateException("the replica set never elected its member");
                    }
                    Thread.sleep(200);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
            link.to(ALIAS, PORT);
            return mongodb;
        }

        /** Direct to the one member: its configured host is the container's own name for itself. */
        private static String uri(String host, int port, String database) {
            return "mongodb://" + host + ":" + port + "/" + database + "?directConnection=true&socketTimeoutMS=60000";
        }
    }
}
