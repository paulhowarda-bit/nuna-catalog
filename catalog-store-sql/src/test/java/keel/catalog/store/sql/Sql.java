package keel.catalog.store.sql;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import javax.sql.DataSource;
import org.postgresql.ds.PGSimpleDataSource;

/** Small JDBC helpers for the test harness: direct statements, polling, data sources. */
final class Sql {

    private Sql() {
    }

    static PGSimpleDataSource dataSource(String host, int port, String user, int socketTimeoutSeconds) {
        return dataSource(host, port, ReplicatedPostgres.DATABASE, user, socketTimeoutSeconds);
    }

    static PGSimpleDataSource dataSource(String host, int port, String database, String user, int socketTimeoutSeconds) {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setServerNames(new String[] {host});
        dataSource.setPortNumbers(new int[] {port});
        dataSource.setDatabaseName(database);
        dataSource.setUser(user);
        dataSource.setConnectTimeout(5);
        dataSource.setSocketTimeout(socketTimeoutSeconds);
        dataSource.setApplicationName("keel-store-suite");
        return dataSource;
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

    private static void bind(PreparedStatement statement, Object... parameters) throws SQLException {
        for (int i = 0; i < parameters.length; i++) {
            statement.setObject(i + 1, parameters[i]);
        }
    }
}
