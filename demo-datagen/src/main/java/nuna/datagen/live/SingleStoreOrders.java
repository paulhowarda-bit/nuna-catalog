package nuna.datagen.live;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * SingleStore's {@code brokerage.orders} (demo/singlestore/seed.sql): each order event replaces the order's row, so
 * the table holds every order's latest state and its change feed carries each change into the lake. Times are written
 * as UTC; DATETIME has no zone. On the real clock no order is written overnight or over a weekend, longer than the
 * server keeps an idle connection, so a write that fails opens a new connection and is tried once more.
 */
public final class SingleStoreOrders implements Publisher.Orders, AutoCloseable {

    static final List<String> COLUMNS = List.of("order_id", "order_ref", "brokerage_account", "security_id", "symbol",
        "side", "order_type", "limit_price", "quantity", "filled_quantity", "average_price", "status", "locate_id",
        "accepted_financial_transaction_id", "trade_date", "accepted_at", "updated_at");

    static final String UPSERT = "INSERT INTO orders (" + String.join(", ", COLUMNS) + ") VALUES ("
        + COLUMNS.stream().map(c -> "?").collect(Collectors.joining(", ")) + ") ON DUPLICATE KEY UPDATE "
        + COLUMNS.stream().skip(1).map(c -> c + " = VALUES(" + c + ")").collect(Collectors.joining(", "));

    private final String url;
    private final String user;
    private final String password;
    private Connection connection;
    private PreparedStatement upsert;

    public SingleStoreOrders(String url, String user, String password) throws SQLException {
        this.url = url;
        this.user = user;
        this.password = password;
        connect();
    }

    private void connect() throws SQLException {
        connection = DriverManager.getConnection(url, user, password);
        upsert = connection.prepareStatement(UPSERT);
    }

    @Override
    public void upsert(Map<String, Object> row) {
        try {
            write(row);
        } catch (SQLException first) {
            try {
                connection.close();
            } catch (SQLException ignored) {
                // The old connection is gone either way.
            }
            try {
                connect();
                write(row);
                System.out.println("SingleStore: reconnected after " + first.getMessage());
            } catch (SQLException again) {
                again.addSuppressed(first);
                throw new IllegalStateException("order " + row.get("order_id") + " was not written", again);
            }
        }
    }

    private void write(Map<String, Object> row) throws SQLException {
        for (int i = 0; i < COLUMNS.size(); i++) {
            upsert.setObject(i + 1, jdbc(row.get(COLUMNS.get(i))));
        }
        upsert.executeUpdate();
    }

    static Object jdbc(Object value) {
        return value instanceof Instant i ? LocalDateTime.ofInstant(i, ZoneOffset.UTC) : value;
    }

    @Override
    public void close() throws SQLException {
        connection.close();
    }
}
