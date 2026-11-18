package nuna.catalog.store.sql;

import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.time.Duration;
import java.util.logging.Logger;
import javax.sql.DataSource;

/**
 * The stores' data source through the link. It remembers the connection each thread opened last:
 * a commit hook runs on the committing thread, after that thread opened the connection that is
 * about to send COMMIT, so a fault struck from the hook can shorten that one connection's wait.
 */
final class LinkDataSource implements DataSource {

    private final DataSource dataSource;
    private final ThreadLocal<Connection> lastOpened = new ThreadLocal<>();

    LinkDataSource(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /** Makes the connection this thread opened last, if it is still open, wait at most {@code wait} for an answer. */
    void shortenWaitOfThisThreadsConnection(Duration wait) {
        Connection connection = lastOpened.get();
        try {
            if (connection != null && !connection.isClosed()) {
                connection.setNetworkTimeout(Runnable::run, Math.toIntExact(wait.toMillis()));
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public Connection getConnection() throws SQLException {
        Connection connection = dataSource.getConnection();
        lastOpened.set(connection);
        return connection;
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        Connection connection = dataSource.getConnection(username, password);
        lastOpened.set(connection);
        return connection;
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
