package nuna.catalog.store.oracle;

import static nuna.catalog.store.suite.Commits.advance;
import static nuna.catalog.store.suite.Commits.batch;
import static nuna.catalog.store.suite.Commits.newKey;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import nuna.catalog.store.CatalogStore;
import nuna.catalog.store.CommitBatch;
import nuna.catalog.store.PointerKey;
import nuna.catalog.store.StoreException;
import nuna.catalog.store.StoreUnavailableException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import java.util.Optional;
import java.util.Properties;
import javax.sql.DataSource;
import oracle.jdbc.driver.DatabaseError;
import oracle.jdbc.pool.OracleDataSource;
import oracle.ucp.UniversalConnectionPoolException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Which failures before COMMIT mean Oracle could not be reached (backlog 7): those become
 * {@link StoreUnavailableException}, which the REST layer answers 503 with Retry-After; the rest stay plain store
 * failures. Each ORA error is made by ojdbc's own factory, so it has the class and SQLState the driver gives that
 * code; the connection failures come from the driver itself, connecting to a port on this machine that refuses or
 * drops the connection. None needs a database.
 */
class UnavailableFailuresTest {

    @ParameterizedTest(name = "ORA-{0}")
    @ValueSource(ints = {
            18, 20,                         // sessions or processes exhausted
            28,                             // session killed
            1033, 1034, 1089, 1090, 1092,   // instance starting, not available, shutting down, terminated
            3113, 3114, 3135,               // connection lost
            12514, 12521, 12528,            // listener: service or instance not registered, instances blocking logons
            12516, 12519, 12520,            // listener: no handler free (the database is out of processes)
            12537, 12541, 12170,            // connection closed, no listener, connect timeout
            16000, 16456})                  // database open read-only (a standby), switchover in progress
    void anUnreachableOrFailingOverDatabaseIsUnavailable(int code) {
        assertThat(Transactions.failed("x failed", ora(code))).isInstanceOf(StoreUnavailableException.class);
    }

    @ParameterizedTest(name = "ORA-{0}")
    @ValueSource(ints = {
            1,      // unique constraint
            54,     // resource busy, NOWAIT
            60,     // deadlock
            942,    // no such table
            1013,   // statement cancelled (a query timeout)
            1017,   // wrong user or password: a misconfiguration, not an outage
            1555,   // snapshot too old
            8177,   // cannot serialize
            12899}) // value too large
    void aDatabaseThatAnsweredIsNotUnavailable(int code) {
        assertThat(Transactions.failed("x failed", ora(code))).isExactlyInstanceOf(StoreException.class);
    }

    @Test
    void aPoolThatTimesOutWaitingForAConnectionIsUnavailable() {
        SQLException hikari = new SQLTransientConnectionException(
                "HikariPool-1 - Connection is not available, request timed out after 30000ms (total=10, active=10,"
                        + " idle=0, waiting=3)");
        assertThat(Transactions.failed("x failed", hikari)).isInstanceOf(StoreUnavailableException.class);
    }

    @Test
    void aConnectionFailureFoundAmongThePoolsCausesIsUnavailable() {
        UniversalConnectionPoolException empty =
                new UniversalConnectionPoolException("UCP-45069: Universal Connection Pool is empty", ora(3113));
        SQLException ucp = new SQLException("UCP-29: Failed to get a connection", null, 29, empty);
        assertThat(Transactions.failed("x failed", ucp)).isInstanceOf(StoreUnavailableException.class);
    }

    @Test
    void aRefusedConnectionIsUnavailableToReadsCommitsAndLookups() throws IOException {
        int port;
        try (ServerSocket closed = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            port = closed.getLocalPort();
        }
        CatalogStore store = new OracleCatalogStore(dataSource(port));
        PointerKey key = newKey();
        CommitBatch batch = batch(advance(key, Optional.empty(), "never"));

        assertThatThrownBy(() -> store.readPointer(key)).isInstanceOf(StoreUnavailableException.class)
                .hasRootCauseInstanceOf(ConnectException.class);
        assertThatThrownBy(() -> store.commit(batch)).isInstanceOf(StoreUnavailableException.class)
                .hasRootCauseInstanceOf(ConnectException.class);
        assertThatThrownBy(() -> store.findCommit(batch.commitId(), key)).isInstanceOf(StoreUnavailableException.class)
                .hasRootCauseInstanceOf(ConnectException.class);
    }

    @Test
    void aConnectionDroppedAtOnceIsUnavailable() throws IOException {
        try (ServerSocket dropping = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
            Thread.ofVirtual().start(() -> {
                while (true) {
                    try (Socket ignored = dropping.accept()) {
                        // Closed at once, before the driver's handshake.
                    } catch (IOException closed) {
                        return;
                    }
                }
            });
            CatalogStore store = new OracleCatalogStore(dataSource(dropping.getLocalPort()));
            PointerKey key = newKey();

            assertThatThrownBy(() -> store.readPointer(key)).isInstanceOf(StoreUnavailableException.class);
            assertThatThrownBy(() -> store.commit(batch(advance(key, Optional.empty(), "never"))))
                    .isInstanceOf(StoreUnavailableException.class);
        }
    }

    /** The exception ojdbc raises for a database error with this ORA code. */
    private static SQLException ora(int code) {
        return DatabaseError.newSQLException(null, String.format("ORA-%05d: from the database", code), code);
    }

    private static DataSource dataSource(int port) {
        try {
            OracleDataSource dataSource = new OracleDataSource();
            dataSource.setURL("jdbc:oracle:thin:@//127.0.0.1:" + port + "/catalog_tg");
            dataSource.setUser("catalog");
            dataSource.setPassword("catalog");
            Properties properties = new Properties();
            properties.setProperty("oracle.net.CONNECT_TIMEOUT", "5000");
            properties.setProperty("oracle.jdbc.ReadTimeout", "5000");
            dataSource.setConnectionProperties(properties);
            return dataSource;
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}
