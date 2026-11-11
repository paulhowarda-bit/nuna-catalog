package fidice.catalog.store.sql;

import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.time.Duration;
import java.util.logging.Logger;
import javax.sql.DataSource;

/**
 * The store's DataSource for schema steps and journal partitioning: each connection waits up to
 * {@link #TIMEOUT} for an answer instead of the socket timeout the caller tuned for commits, and
 * runs in UTC. A schema step on CockroachDB runs as a job, which takes longer than a
 * commit's timeout allows. On CockroachDB, Flyway serializes
 * migrating nodes with a lock row stamped with the session's local time and expired against UTC,
 * so a session west of UTC would see another node's lock as expired and run the same step.
 */
final class MaintenanceDataSource implements DataSource {

    static final Duration TIMEOUT = Duration.ofMinutes(30);

    private final DataSource dataSource;

    MaintenanceDataSource(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public Connection getConnection() throws SQLException {
        return withTimeout(dataSource.getConnection());
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return withTimeout(dataSource.getConnection(username, password));
    }

    private static Connection withTimeout(Connection connection) throws SQLException {
        try {
            connection.setNetworkTimeout(Runnable::run, (int) TIMEOUT.toMillis());
            try (Statement utc = connection.createStatement()) {
                utc.execute("SET TIME ZONE 'UTC'");
            }
            return connection;
        } catch (SQLException e) {
            try {
                connection.close();
            } catch (SQLException closeFailure) {
                e.addSuppressed(closeFailure);
            }
            throw e;
        }
    }

    @Override
    public PrintWriter getLogWriter() throws SQLException {
        return dataSource.getLogWriter();
    }

    @Override
    public void setLogWriter(PrintWriter out) throws SQLException {
        dataSource.setLogWriter(out);
    }

    @Override
    public void setLoginTimeout(int seconds) throws SQLException {
        dataSource.setLoginTimeout(seconds);
    }

    @Override
    public int getLoginTimeout() throws SQLException {
        return dataSource.getLoginTimeout();
    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        return dataSource.getParentLogger();
    }

    @Override
    public <T> T unwrap(Class<T> type) throws SQLException {
        return type.isInstance(this) ? type.cast(this) : dataSource.unwrap(type);
    }

    @Override
    public boolean isWrapperFor(Class<?> type) throws SQLException {
        return type.isInstance(this) || dataSource.isWrapperFor(type);
    }
}
