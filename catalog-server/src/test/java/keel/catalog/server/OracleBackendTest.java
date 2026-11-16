package keel.catalog.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Properties;
import javax.sql.DataSource;
import keel.catalog.store.CatalogStore;
import keel.catalog.store.SafetyFinding;
import oracle.jdbc.pool.OracleDataSource;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.MapPropertySource;
import org.springframework.test.context.ContextConfiguration;
import org.testcontainers.oracle.OracleContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * The server on Oracle ({@code keel.database.backend=oracle}): Boot's own pool reaches the catalog service, which
 * has Transaction Guard on, with the read timeout set as deploy/README.md says, and the REST flow works on it.
 */
@Tag("heavy")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "keel.database.backend=oracle",
        "keel.security.grant-admin-groups=" + BackendFlow.GRANT_ADMINS,
})
@ContextConfiguration(initializers = {IssuerForTests.class, OracleBackendTest.Oracle.class})
@Import({S3ForTests.class, AuditSinkForTests.class})
class OracleBackendTest {

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private CatalogStore store;

    @Autowired
    private DataSource dataSource;

    @Test
    void theServerRunsOnOracle() throws SQLException {
        // The documented key reaches the pool as written, where the driver looks for it.
        assertThat(dataSource.unwrap(HikariDataSource.class).getDataSourceProperties())
                .containsEntry("oracle.jdbc.ReadTimeout", "120000");
        // Only Oracle's store reports this (the bean is that store, observed): Free has no Data Guard. Everything
        // else the store checks holds for the session the pool hands out.
        assertThat(store.selfCheck()).extracting(SafetyFinding::code).containsExactly("ora.no-data-guard");
        new BackendFlow(port).run();
    }

    /**
     * Oracle Free with the catalog service and the catalog role's grants (OracleCatalogStore's class comment), and
     * the server's datasource on it. An initializer, as IssuerForTests is: the store is made while the web server
     * starts, before property registrar beans run. One container for the JVM; Testcontainers removes it at exit.
     */
    static class Oracle implements ApplicationContextInitializer<ConfigurableApplicationContext> {

        private static final String USER = "catalog";
        private static final String PASSWORD = "catalog";
        private static final String SERVICE = "catalog_tg";
        /** Free caps itself at 2 GB of memory; the container gets a little headroom and no more. */
        private static final long MEMORY_BYTES = 2560L * 1024 * 1024;

        private static OracleContainer oracle;

        @Override
        public void initialize(ConfigurableApplicationContext context) {
            OracleContainer oracle = started();
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("oracle-for-tests", Map.of(
                    "spring.datasource.url", url(oracle, SERVICE),
                    "spring.datasource.username", USER,
                    "spring.datasource.password", PASSWORD,
                    "spring.datasource.hikari.data-source-properties.oracle.jdbc.ReadTimeout", "120000")));
        }

        private static synchronized OracleContainer started() {
            if (oracle != null) {
                return oracle;
            }
            oracle = new OracleContainer(DockerImageName.parse(System.getProperty("oracle.image")))
                    .withUsername(USER)
                    .withPassword(PASSWORD)
                    .withStartupTimeout(Duration.ofMinutes(5))
                    .withCreateContainerCmdModifier(command -> command.getHostConfig().withMemory(MEMORY_BYTES));
            oracle.start();
            DataSource sysdba = direct(oracle, "freepdb1", "sys", true);
            execute(sysdba, """
                    DECLARE
                      parameters DBMS_SERVICE.SVC_PARAMETER_ARRAY;
                    BEGIN
                      parameters('COMMIT_OUTCOME') := 'true';
                      parameters('RETENTION_TIMEOUT') := 86400;
                      DBMS_SERVICE.CREATE_SERVICE('%s', '%s', parameters);
                      DBMS_SERVICE.START_SERVICE('%s');
                    END;""".formatted(SERVICE, SERVICE, SERVICE));
            for (String grant : new String[] {"EXECUTE ON SYS.DBMS_APP_CONT", "SELECT ON SYS.V_$DATABASE",
                    "SELECT ON SYS.V_$SERVICES", "SELECT ON SYS.V_$PARAMETER", "CREATE JOB"}) {
                execute(sysdba, "GRANT " + grant + " TO " + USER);
            }
            awaitService(direct(oracle, SERVICE, USER, false));
            return oracle;
        }

        private static String url(OracleContainer oracle, String service) {
            return "jdbc:oracle:thin:@//" + oracle.getHost() + ":" + oracle.getOraclePort() + "/" + service;
        }

        private static DataSource direct(OracleContainer oracle, String service, String user, boolean sysdba) {
            try {
                OracleDataSource dataSource = new OracleDataSource();
                dataSource.setURL(url(oracle, service));
                dataSource.setUser(user);
                dataSource.setPassword(PASSWORD);
                Properties properties = new Properties();
                properties.setProperty("oracle.jdbc.ReadTimeout", "120000");
                if (sysdba) {
                    properties.setProperty("internal_logon", "sysdba");
                }
                dataSource.setConnectionProperties(properties);
                return dataSource;
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
        }

        private static void execute(DataSource dataSource, String sql) {
            try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
                statement.execute(sql);
            } catch (SQLException e) {
                throw new IllegalStateException(sql, e);
            }
        }

        /** The listener offers a new service a moment after it starts. */
        private static void awaitService(DataSource service) {
            Instant deadline = Instant.now().plusSeconds(60);
            while (true) {
                try (Connection ignored = service.getConnection()) {
                    return;
                } catch (SQLException notOfferedYet) {
                    if (Instant.now().isAfter(deadline)) {
                        throw new IllegalStateException("the listener never offered " + SERVICE, notOfferedYet);
                    }
                }
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
            }
        }
    }
}
