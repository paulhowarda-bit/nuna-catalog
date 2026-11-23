package nuna.catalog.server.health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoDatabase;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLTransientConnectionException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import javax.sql.DataSource;
import nuna.catalog.audit.CatalogEvent;
import nuna.catalog.audit.Denial;
import nuna.catalog.core.principal.Caller;
import nuna.catalog.core.principal.Principal;
import nuna.catalog.security.Separation;
import nuna.catalog.server.DatabaseSettings;
import nuna.catalog.server.StoreSelfCheck;
import nuna.catalog.server.rest.AccessDenied;
import nuna.catalog.server.rest.RestSettings;
import nuna.catalog.server.security.DenialsForTests;
import nuna.catalog.server.telemetry.RequestIds;
import nuna.catalog.store.SafetyFinding;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The {@code store} field of {@code GET /management/v1/health/server?store=true} (backlog 139): the catalog's
 * database as this server finds it, each backend told by what it answers on its own connection, and the self-check
 * the server ran at start. Only platform operators read it, and a refusal comes before the database is asked. The
 * database is a double that answers as each backend does, so this needs only Java.
 */
class StoreFactsRouteTest {

    private static final Instant NOW = Instant.parse("2026-11-22T12:00:00Z");
    private static final Instant STARTED = NOW.minusSeconds(3600);
    private static final Caller OLGA = person("olga", "platform-ops");
    private static final Caller ANN = person("ann", "analysts");
    private static final SafetyFinding NO_STANDBY = new SafetyFinding("pg.no-sync-standby",
            "synchronous_standby_names is '' and 0 standbys stream in sync or quorum state");
    private static final StoreSelfCheck PASSED = new StoreSelfCheck(STARTED, List.of());

    private final List<CatalogEvent> audit = new ArrayList<>();
    private final List<String> asked = new ArrayList<>();
    private final MockHttpServletRequest http = new MockHttpServletRequest();
    private Caller caller = OLGA;

    StoreFactsRouteTest() {
        http.setAttribute(RequestIds.class.getName(), "request-1");
    }

    @Test
    void postgresqlIsNamedWithItsVersion() {
        JsonNode store = store(DatabaseSettings.Backend.POSTGRESQL, jdbc("PostgreSQL 17.2 (Debian 17.2-1.pgdg120+1) on"
                + " x86_64-pc-linux-gnu, compiled by gcc (Debian 12.2.0-14) 12.2.0, 64-bit", null), null, PASSED);

        assertThat(store.get("configured").asString()).isEqualTo("postgresql");
        assertThat(store.get("backend").asString()).isEqualTo("PostgreSQL");
        assertThat(store.get("version").asString()).isEqualTo("17.2");
        assertThat(store.has("unreadable")).isFalse();
        assertThat(asked).containsExactly("SELECT version()");
    }

    @Test
    void cockroachdbIsToldApartFromPostgresqlOnTheSameWire() {
        JsonNode store = store(DatabaseSettings.Backend.POSTGRESQL, jdbc("CockroachDB CCL v24.3.1 (x86_64-pc-linux-gnu,"
                + " built 2024/12/12 12:00:00, go1.22.8)", null), null, PASSED);

        assertThat(store.get("configured").asString()).isEqualTo("postgresql");
        assertThat(store.get("backend").asString()).isEqualTo("CockroachDB");
        assertThat(store.get("version").asString()).isEqualTo("v24.3.1");
    }

    @Test
    void oracleIsNamedWithItsFullVersion() {
        JsonNode store = store(DatabaseSettings.Backend.ORACLE, jdbc(null, "Oracle Database 23ai Free Release"
                + " 23.0.0.0.0 - Develop, Learn, and Run for Free\nVersion 23.5.0.24.07"), null, PASSED);

        assertThat(store.get("backend").asString()).isEqualTo("Oracle");
        assertThat(store.get("version").asString()).isEqualTo("23.5.0.24.07");
        assertThat(StoreFacts.oracle("Oracle Database 12c Enterprise Edition Release 12.2.0.1.0 - 64bit Production")
                .version()).as("a release with no Version line").isEqualTo("12.2.0.1.0");
    }

    @Test
    void mongodbIsNamedWithItsVersion() {
        JsonNode store = store(DatabaseSettings.Backend.MONGODB, null, mongo("8.0.4"), PASSED);

        assertThat(store.get("configured").asString()).isEqualTo("mongodb");
        assertThat(store.get("backend").asString()).isEqualTo("MongoDB");
        assertThat(store.get("version").asString()).isEqualTo("8.0.4");
        assertThat(asked).containsExactly("buildInfo");
    }

    @Test
    void aDatabaseThatDoesNotAnswerIsSaidSoAndTheSelfCheckStillShown() {
        DataSource down = (DataSource) Proxy.newProxyInstance(DataSource.class.getClassLoader(),
                new Class<?>[] {DataSource.class}, (proxy, method, args) -> {
                    if (method.getName().equals("getConnection")) {
                        throw new SQLTransientConnectionException("HikariPool-1 - Connection is not available,"
                                + " request timed out after 30000ms");
                    }
                    throw new UnsupportedOperationException(method.getName());
                });

        JsonNode store = store(DatabaseSettings.Backend.POSTGRESQL, down, null, PASSED);

        assertThat(store.get("configured").asString()).isEqualTo("postgresql");
        assertThat(store.has("backend")).isFalse();
        assertThat(store.get("unreadable").asString()).contains("Connection is not available");
        assertThat(store.get("self-check").get("at").asString()).isEqualTo(STARTED.toString());
    }

    @Test
    void theSelfCheckAtStartIsShownWithEachFindingAllowed() {
        JsonNode passed = store(DatabaseSettings.Backend.POSTGRESQL, jdbc("PostgreSQL 16.4", null), null, PASSED);
        assertThat(passed.get("self-check").get("at").asString()).isEqualTo(STARTED.toString());
        assertThat(passed.get("self-check").get("allowed")).isEmpty();

        JsonNode allowed = store(DatabaseSettings.Backend.POSTGRESQL, jdbc("PostgreSQL 16.4", null), null,
                new StoreSelfCheck(STARTED, List.of(NO_STANDBY)));
        JsonNode finding = allowed.get("self-check").get("allowed").get(0);
        assertThat(allowed.get("self-check").get("allowed")).hasSize(1);
        assertThat(finding.get("code").asString()).isEqualTo(NO_STANDBY.code());
        assertThat(finding.get("detail").asString()).isEqualTo(NO_STANDBY.detail());
    }

    @Test
    void onlyAskedForDoesTheServerReadItsDatabase() {
        JsonNode body = json(routes(DatabaseSettings.Backend.POSTGRESQL, jdbc("PostgreSQL 16.4", null), null, PASSED)
                .server(false, http));

        assertThat(body.has("store")).as("the other servers' figures never wait on the database").isFalse();
        assertThat(body.has("figures")).isTrue();
        assertThat(asked).isEmpty();
    }

    @Test
    void aCallerWhoIsNotAPlatformOperatorIsRefusedBeforeTheDatabaseIsAsked() {
        caller = ANN;
        CatalogHealthRoutes routes = routes(DatabaseSettings.Backend.POSTGRESQL, jdbc("PostgreSQL 16.4", null), null,
                PASSED);

        assertThatThrownBy(() -> routes.server(true, http)).isInstanceOf(AccessDenied.class);
        assertThat(asked).as("the database is never asked for a refused caller").isEmpty();
        assertThat(audit).singleElement().extracting(CatalogEvent::data).isInstanceOfSatisfying(Denial.class, denial -> {
            assertThat(denial.operation()).isEqualTo("readServerHealth");
            assertThat(denial.reason()).startsWith("no_privilege");
        });
    }

    // ---- helpers ----

    private JsonNode store(DatabaseSettings.Backend backend, DataSource dataSource, MongoClient mongo,
                           StoreSelfCheck check) {
        return json(routes(backend, dataSource, mongo, check).server(true, http)).get("store");
    }

    private CatalogHealthRoutes routes(DatabaseSettings.Backend backend, DataSource dataSource, MongoClient mongo,
                                       StoreSelfCheck check) {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        RestSettings settings = new RestSettings(100, 1000, 1_048_576, 1000, 268_435_456, Duration.ofDays(30),
                Duration.ofMinutes(5), "the identity provider", URI.create("urn:nuna:test"), 100, null);
        Separation separation = new Separation(Set.of("grant-admins"), Set.of("platform-ops"));
        StoreFacts facts = new StoreFacts(backend, () -> dataSource, () -> mongo, check);
        return new CatalogHealthRoutes(new HealthWindow(new SimpleMeterRegistry(), clock),
                new Peers(new HealthSettings(List.of(), Duration.ofSeconds(1))), separation, request -> caller,
                DenialsForTests.denials(audit::add, settings, clock), facts);
    }

    /**
     * A JDBC database that answers {@code SELECT version()} with {@code version} and gives {@code productVersion} as
     * its driver's product version; each statement run is recorded.
     */
    private DataSource jdbc(String version, String productVersion) {
        ResultSet row = proxy(ResultSet.class, (proxy, method, args) -> switch (method.getName()) {
            case "next" -> true;
            case "getString" -> version;
            case "close" -> null;
            default -> throw new UnsupportedOperationException(method.getName());
        });
        Statement statement = proxy(Statement.class, (proxy, method, args) -> switch (method.getName()) {
            case "executeQuery" -> {
                asked.add((String) args[0]);
                yield row;
            }
            case "close" -> null;
            default -> throw new UnsupportedOperationException(method.getName());
        });
        DatabaseMetaData metaData = proxy(DatabaseMetaData.class, (proxy, method, args) -> switch (method.getName()) {
            case "getDatabaseProductVersion" -> {
                asked.add("getDatabaseProductVersion");
                yield productVersion;
            }
            default -> throw new UnsupportedOperationException(method.getName());
        });
        Connection connection = proxy(Connection.class, (proxy, method, args) -> switch (method.getName()) {
            case "createStatement" -> statement;
            case "getMetaData" -> metaData;
            case "close" -> null;
            default -> throw new UnsupportedOperationException(method.getName());
        });
        return proxy(DataSource.class, (proxy, method, args) -> {
            if (method.getName().equals("getConnection")) {
                return connection;
            }
            throw new UnsupportedOperationException(method.getName());
        });
    }

    /** A MongoDB deployment whose {@code buildInfo} answers {@code version}. */
    private MongoClient mongo(String version) {
        MongoDatabase admin = proxy(MongoDatabase.class, (proxy, method, args) -> {
            if (method.getName().equals("runCommand") && args.length == 1) {
                asked.add(((Document) args[0]).keySet().iterator().next());
                return new Document("version", version).append("ok", 1.0);
            }
            throw new UnsupportedOperationException(method.getName());
        });
        return proxy(MongoClient.class, (proxy, method, args) -> {
            if (method.getName().equals("getDatabase") && "admin".equals(args[0])) {
                return admin;
            }
            throw new UnsupportedOperationException(method.getName());
        });
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler);
    }

    private static JsonNode json(ResponseEntity<String> response) {
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        return JsonMapper.builder().build().readTree(response.getBody());
    }

    private static Caller person(String id, String... groups) {
        return new Caller(new Principal(id, Set.of(groups)), "console", false, Optional.empty());
    }
}
