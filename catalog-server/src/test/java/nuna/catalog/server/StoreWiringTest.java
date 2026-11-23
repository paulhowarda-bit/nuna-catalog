package nuna.catalog.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.sql.DataSource;
import nuna.catalog.store.CatalogStore;
import nuna.catalog.store.SafetyFinding;
import nuna.catalog.store.mongo.MongoCatalogStore;
import nuna.catalog.store.oracle.OracleCatalogStore;
import nuna.catalog.store.sql.SqlCatalogStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;

/**
 * Each backend {@code nuna.database.backend} names gets its own store (D7), without a database: only the store's
 * {@code migrate()} reaches one. OracleBackendTest and MongodbBackendTest run the server on each; a server given
 * the wrong store does not start, so they cannot tell that apart from a database that is not up.
 *
 * <p>Before it migrates, the store's self-check must find nothing, or the server does not start (D8.2), unless
 * {@code nuna.database.allow-unsafe} names each finding's code. Checked here with a store double.
 */
class StoreWiringTest {

    private static final String MONGODB_URI = "mongodb://h1,h2,h3/catalog?replicaSet=rs0&socketTimeoutMS=30000";
    private static final SafetyFinding NO_STANDBY = new SafetyFinding("pg.no-sync-standby",
            "synchronous_standby_names is '' and 0 standbys stream in sync or quorum state");
    private static final SafetyFinding FSYNC_OFF = new SafetyFinding("pg.fsync-off",
            "fsync is off and full_page_writes is on; both must be on");

    private final ListAppender<ILoggingEvent> logged = new ListAppender<>();
    private final Logger wiring = (Logger) LoggerFactory.getLogger(StoreWiring.class);

    @BeforeEach
    void listen() {
        logged.start();
        wiring.addAppender(logged);
    }

    @AfterEach
    void stopListening() {
        wiring.detachAppender(logged);
    }

    @Test
    void eachBackendGetsItsOwnStore() {
        ObjectProvider<DataSource> dataSource = provider(DataSource.class, new SimpleDriverDataSource());
        ObjectProvider<MongoClient> none = provider(MongoClient.class, null);

        assertThat(StoreWiring.store(settings(DatabaseSettings.Backend.POSTGRESQL), dataSource, none))
                .isInstanceOf(SqlCatalogStore.class);
        assertThat(StoreWiring.store(settings(DatabaseSettings.Backend.ORACLE), dataSource, none))
                .isInstanceOf(OracleCatalogStore.class);
        // A client connects in the background, when first asked for something; the store asks for nothing here.
        try (MongoClient client = MongoClients.create(MONGODB_URI)) {
            assertThat(StoreWiring.store(new DatabaseSettings(DatabaseSettings.Backend.MONGODB,
                    new DatabaseSettings.Mongodb(MONGODB_URI), null), provider(DataSource.class, null),
                    provider(MongoClient.class, client)))
                    .isInstanceOf(MongoCatalogStore.class);
        }
    }

    @Test
    void aFindingRefusesToStartTheServer() {
        StoreDouble store = new StoreDouble(NO_STANDBY, FSYNC_OFF);

        assertThatThrownBy(() -> StoreWiring.ready(store.store, Set.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("does not start")
                .hasMessageContaining(NO_STANDBY.code() + ": " + NO_STANDBY.detail())
                .hasMessageContaining(FSYNC_OFF.code() + ": " + FSYNC_OFF.detail())
                .hasMessageContaining("nuna.database.allow-unsafe");
        assertThat(store.calls).as("nothing changes in an unsafe database").containsExactly("selfCheck");
    }

    @Test
    void eachFindingMustBeAllowedByItsCode() {
        StoreDouble store = new StoreDouble(NO_STANDBY, FSYNC_OFF);

        assertThatThrownBy(() -> StoreWiring.ready(store.store, Set.of(NO_STANDBY.code(), "pg.sync-commit-off")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(FSYNC_OFF.code() + ": " + FSYNC_OFF.detail())
                .message().doesNotContain(NO_STANDBY.detail());
        assertThat(store.calls).containsExactly("selfCheck");
    }

    @Test
    void eachFindingAllowedIsLoggedAsAWarningAndTheServerStarts() {
        StoreDouble store = new StoreDouble(NO_STANDBY, FSYNC_OFF);

        assertThat(StoreWiring.ready(store.store, Set.of(NO_STANDBY.code(), FSYNC_OFF.code())))
                .as("the findings allowed, for Health's Store panel").containsExactly(NO_STANDBY, FSYNC_OFF);
        assertThat(store.calls).containsExactly("selfCheck", "migrate");
        assertThat(logged.list).allSatisfy(event -> assertThat(event.getLevel()).isEqualTo(Level.WARN))
                .extracting(ILoggingEvent::getFormattedMessage)
                .containsExactly(
                        "The catalog's database is unsafe, allowed by nuna.database.allow-unsafe: "
                                + NO_STANDBY.code() + ": " + NO_STANDBY.detail(),
                        "The catalog's database is unsafe, allowed by nuna.database.allow-unsafe: "
                                + FSYNC_OFF.code() + ": " + FSYNC_OFF.detail());
    }

    @Test
    void aSafeDatabaseStartsEitherWay() {
        for (Set<String> allowUnsafe : List.of(Set.<String>of(), Set.of(NO_STANDBY.code()))) {
            StoreDouble store = new StoreDouble();
            assertThat(StoreWiring.ready(store.store, allowUnsafe)).isEmpty();
            assertThat(store.calls).containsExactly("selfCheck", "migrate");
        }
        assertThat(logged.list).isEmpty();
    }

    @Test
    void aSelfCheckThatCannotRunRefusesToStartTheServer() {
        IllegalStateException cannotRead = new IllegalStateException("self-check failed");
        StoreDouble store = new StoreDouble(cannotRead);

        assertThatThrownBy(() -> StoreWiring.ready(store.store, Set.of(NO_STANDBY.code(), FSYNC_OFF.code())))
                .isSameAs(cannotRead);
        assertThat(store.calls).containsExactly("selfCheck");
    }

    private static DatabaseSettings settings(DatabaseSettings.Backend backend) {
        return new DatabaseSettings(backend, null, null);
    }

    private static <T> ObjectProvider<T> provider(Class<T> type, T bean) {
        StaticListableBeanFactory beans = bean == null ? new StaticListableBeanFactory()
                : new StaticListableBeanFactory(Map.of("bean", bean));
        return beans.getBeanProvider(type);
    }

    /** A store whose self-check reports {@code findings}, or throws; it records which of the two it was asked. */
    private static final class StoreDouble {

        final List<String> calls = new ArrayList<>();
        final CatalogStore store;

        StoreDouble(SafetyFinding... findings) {
            this(null, findings);
        }

        StoreDouble(RuntimeException selfCheckFails, SafetyFinding... findings) {
            store = (CatalogStore) Proxy.newProxyInstance(CatalogStore.class.getClassLoader(),
                    new Class<?>[] {CatalogStore.class}, (proxy, method, args) -> {
                        calls.add(method.getName());
                        return switch (method.getName()) {
                            case "selfCheck" -> {
                                if (selfCheckFails != null) {
                                    throw selfCheckFails;
                                }
                                yield List.of(findings);
                            }
                            case "migrate" -> null;
                            default -> throw new UnsupportedOperationException(method.getName());
                        };
                    });
        }
    }
}
