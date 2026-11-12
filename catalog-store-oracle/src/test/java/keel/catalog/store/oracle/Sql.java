package keel.catalog.store.oracle;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import javax.sql.DataSource;
import oracle.jdbc.pool.OracleDataSource;
import oracle.ucp.jdbc.PoolDataSource;
import oracle.ucp.jdbc.PoolDataSourceFactory;

/** Small JDBC helpers for the test harness: data sources, direct statements, polling. */
final class Sql {

    private Sql() {
    }

    /**
     * A started UCP pool that keeps {@code minimum} connections open: while the log writer is
     * frozen a new logon waits for it, so the stores must find connections that already exist.
     */
    static PoolDataSource pool(String name, String host, int port, String service, String user, String password,
                               int readTimeoutSeconds, int minimum) {
        try {
            PoolDataSource pool = PoolDataSourceFactory.getPoolDataSource();
            pool.setConnectionPoolName(name);
            pool.setConnectionFactoryClassName(OracleDataSource.class.getName());
            pool.setURL(url(host, port, service));
            pool.setUser(user);
            pool.setPassword(password);
            pool.setConnectionProperties(timeouts(readTimeoutSeconds));
            pool.setInitialPoolSize(minimum);
            pool.setMinPoolSize(minimum);
            pool.setMaxPoolSize(32);
            pool.setValidateConnectionOnBorrow(true);
            pool.setConnectionWaitDuration(Duration.ofSeconds(10));
            pool.getConnection().close();
            return pool;
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    /** One new connection per call; {@code sysdba} logs on with SYSDBA. */
    static DataSource direct(String host, int port, String service, String user, String password, boolean sysdba) {
        try {
            OracleDataSource dataSource = new OracleDataSource();
            dataSource.setURL(url(host, port, service));
            dataSource.setUser(user);
            dataSource.setPassword(password);
            Properties properties = timeouts(120);
            if (sysdba) {
                properties.setProperty("internal_logon", "sysdba");
            }
            dataSource.setConnectionProperties(properties);
            return dataSource;
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String url(String host, int port, String service) {
        return "jdbc:oracle:thin:@//" + host + ":" + port + "/" + service;
    }

    private static Properties timeouts(int readTimeoutSeconds) {
        Properties properties = new Properties();
        properties.setProperty("oracle.jdbc.ReadTimeout", String.valueOf(readTimeoutSeconds * 1000));
        properties.setProperty("oracle.net.CONNECT_TIMEOUT", "5000");
        return properties;
    }

    static void execute(DataSource dataSource, String sql, Object... parameters) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, parameters);
            statement.execute();
        } catch (SQLException e) {
            throw new IllegalStateException(sql, e);
        }
    }

    /** The first column of every row, as text. */
    static List<String> column(DataSource dataSource, String sql, Object... parameters) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, parameters);
            List<String> values = new ArrayList<>();
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    values.add(rows.getString(1));
                }
            }
            return values;
        } catch (SQLException e) {
            throw new IllegalStateException(sql, e);
        }
    }

    static String value(DataSource dataSource, String sql, Object... parameters) {
        return column(dataSource, sql, parameters).getFirst();
    }

    static void await(Duration limit, BooleanSupplier condition, String what) {
        long deadline = System.nanoTime() + limit.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new IllegalStateException("timed out waiting for " + what);
            }
            pause(Duration.ofMillis(50));
        }
    }

    static void pause(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /** Binds each parameter; a UUID binds as the store's RAW(16), null as a NULL. */
    private static void bind(PreparedStatement statement, Object... parameters) throws SQLException {
        for (int i = 0; i < parameters.length; i++) {
            if (parameters[i] instanceof UUID id) {
                statement.setBytes(i + 1, OracleCatalogStore.raw(id));
            } else if (parameters[i] == null) {
                statement.setNull(i + 1, Types.VARCHAR);
            } else {
                statement.setObject(i + 1, parameters[i]);
            }
        }
    }
}
