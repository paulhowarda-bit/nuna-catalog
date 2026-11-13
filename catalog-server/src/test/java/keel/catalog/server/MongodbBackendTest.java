package keel.catalog.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import keel.catalog.store.CatalogStore;
import keel.catalog.store.SafetyFinding;
import keel.catalog.store.mongo.MongoCatalogStore;
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
 * The server on MongoDB ({@code keel.database.backend=mongodb}): no {@code spring.datasource.*} and no DataSource,
 * a one-member replica set named by {@code keel.database.mongodb.uri}, and the REST flow works on it.
 */
@Tag("heavy")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "keel.database.backend=mongodb",
        "keel.security.grant-admin-groups=" + BackendFlow.GRANT_ADMINS,
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
        assertThat(store).isInstanceOf(MongoCatalogStore.class);
        assertThat(dataSource.getIfAvailable()).isNull();
        // One member, so a majority cannot survive losing it; everything else the store checks holds.
        assertThat(store.selfCheck()).extracting(SafetyFinding::code).containsExactly("mongo.too-few-voters");
        new BackendFlow(port).run();
    }

    /**
     * A one-member replica set (transactions need a replica set, not three members) and the server's URI for it.
     * An initializer, as IssuerForTests is: the store is made while the web server starts, before property
     * registrar beans run. One container for the JVM; Testcontainers removes it at exit.
     */
    static class Mongodb implements ApplicationContextInitializer<ConfigurableApplicationContext> {

        private static GenericContainer<?> mongodb;

        @Override
        public void initialize(ConfigurableApplicationContext context) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("mongodb-for-tests",
                    Map.of("keel.database.mongodb.uri", uri(started(), "catalog"))));
        }

        private static synchronized GenericContainer<?> started() {
            if (mongodb != null) {
                return mongodb;
            }
            mongodb = new GenericContainer<>(DockerImageName.parse(System.getProperty("mongo.image")))
                    .withCommand("--replSet", "rs0", "--bind_ip_all")
                    .withExposedPorts(27017)
                    .waitingFor(Wait.forLogMessage(".*Waiting for connections.*", 1));
            mongodb.start();
            try (MongoClient admin = MongoClients.create(uri(mongodb, ""))) {
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
            return mongodb;
        }

        /** Direct to the one member: its configured host is the container's own name for itself. */
        private static String uri(GenericContainer<?> mongodb, String database) {
            return "mongodb://" + mongodb.getHost() + ":" + mongodb.getMappedPort(27017) + "/" + database
                    + "?directConnection=true&socketTimeoutMS=60000";
        }
    }
}
