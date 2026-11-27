package nuna.datagen.live;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import nuna.datagen.kit.Kit;
import nuna.datagen.world.Feed;

/**
 * The world's tables in SingleStore's database {@code brokerage} (demo/singlestore/brokerage.sql and
 * brokerage-feeds.sql). Each order event replaces the order's row in {@code orders}, so the table holds every order's
 * latest state and its change feed carries each change into the lake. The order management system's other feeds
 * (order events, routes, executions) are inserted into tables of their own, numbered by {@code seq}, and so, while
 * Kafka is off, is every other feed; a change feed copies each table into the lake.
 *
 * <p>Times are written as UTC; DATETIME has no zone. On the real clock nothing is written overnight or over a weekend,
 * longer than the server keeps an idle connection, so a write that fails opens a new connection and is tried once more.
 */
public final class SingleStore implements Publisher.Orders, Publisher.Rows, AutoCloseable {

    static final List<String> COLUMNS = List.of("order_id", "order_ref", "brokerage_account", "security_id", "symbol",
        "side", "order_type", "limit_price", "quantity", "filled_quantity", "average_price", "status", "locate_id",
        "accepted_financial_transaction_id", "trade_date", "accepted_at", "updated_at");

    static final String UPSERT = "INSERT INTO orders (" + String.join(", ", COLUMNS) + ") VALUES ("
        + COLUMNS.stream().map(c -> "?").collect(Collectors.joining(", ")) + ") ON DUPLICATE KEY UPDATE "
        + COLUMNS.stream().skip(1).map(c -> c + " = VALUES(" + c + ")").collect(Collectors.joining(", "));

    private final String url;
    private final String user;
    private final String password;
    private final Map<Feed, List<String>> feedColumns;
    private Connection connection;
    private PreparedStatement upsert;
    private final Map<Feed, PreparedStatement> inserts = new EnumMap<>(Feed.class);

    public SingleStore(String url, String user, String password, Kit kit) throws SQLException {
        this.url = url;
        this.user = user;
        this.password = password;
        this.feedColumns = SingleStoreTables.columns(kit);
        connect();
    }

    private void connect() throws SQLException {
        connection = DriverManager.getConnection(url, user, password);
        upsert = connection.prepareStatement(UPSERT);
        inserts.clear();
    }

    @Override
    public void upsert(Map<String, Object> row) {
        retried("order " + row.get("order_id"), () -> {
            for (int i = 0; i < COLUMNS.size(); i++) {
                upsert.setObject(i + 1, jdbc(row.get(COLUMNS.get(i))));
            }
            upsert.executeUpdate();
        });
    }

    @Override
    public void insert(Feed feed, long seq, Map<String, Object> row) {
        retried(feed.table + " row " + seq, () -> {
            List<String> columns = feedColumns.get(feed);
            PreparedStatement insert = inserts.get(feed);
            if (insert == null) {
                insert = connection.prepareStatement("INSERT INTO " + feed.table + " (" + SingleStoreTables.SEQ + ", "
                    + String.join(", ", columns) + ") VALUES (?" + ", ?".repeat(columns.size()) + ")");
                inserts.put(feed, insert);
            }
            insert.setLong(1, seq);
            for (int i = 0; i < columns.size(); i++) {
                insert.setObject(i + 2, jdbc(Encoding.column(row.get(columns.get(i)))));
            }
            insert.executeUpdate();
        });
    }

    /** The rows each feed but the orders has in its SingleStore table: the records it sent this way. */
    public Map<Feed, Long> counts() throws SQLException {
        Map<Feed, Long> counts = new EnumMap<>(Feed.class);
        try (Statement s = connection.createStatement()) {
            for (Feed f : feedColumns.keySet()) {
                try (ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM " + f.table)) {
                    rs.next();
                    counts.put(f, rs.getLong(1));
                }
            }
        }
        return counts;
    }

    private interface Write {
        void run() throws SQLException;
    }

    private void retried(String what, Write write) {
        try {
            write.run();
        } catch (SQLException first) {
            try {
                connection.close();
            } catch (SQLException ignored) {
                // The old connection is gone either way.
            }
            try {
                connect();
                write.run();
                System.out.println("SingleStore: reconnected after " + first.getMessage());
            } catch (SQLException again) {
                again.addSuppressed(first);
                throw new IllegalStateException(what + " was not written", again);
            }
        }
    }

    static Object jdbc(Object value) {
        return value instanceof Instant i ? LocalDateTime.ofInstant(i, ZoneOffset.UTC) : value;
    }

    @Override
    public void close() throws SQLException {
        connection.close();
    }
}
