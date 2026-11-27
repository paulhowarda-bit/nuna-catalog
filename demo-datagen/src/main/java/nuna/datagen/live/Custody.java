package nuna.datagen.live;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import nuna.datagen.world.Event;
import nuna.datagen.world.Feed;
import nuna.datagen.world.Sink;
import nuna.datagen.world.World;

/**
 * The CDC leg's books of record (item 151): CockroachDB {@code custody}, schema {@code clearing}, holding the clearing
 * books as they stand after the lake's last business day: the stock record of that day, the CNS obligations and
 * locates of the last five business days, and every fail at its latest event. Debezium captures each change into the
 * history tables {@code history.cockroach.clearing.*}.
 *
 * <p>The generator is stopped while the leg runs (it needs its memory), so Main's {@code custody} command replays the
 * world, from the settings in the lake, to the last day the lake tagged, collecting the clearing feeds' rows in a
 * {@link Book}, and writes only what differs from what the tables hold: rows changed or new are upserted, rows no
 * longer in the books deleted. Run on each start of the leg, it carries the books on to the lake's latest day, and
 * Debezium sees one change per row that moved.
 */
public final class Custody implements AutoCloseable {

    static final String SCHEMA = "clearing";
    static final int DAYS_KEPT = 5;

    /** A column of a custody table: its name, CockroachDB type, Iceberg type for its history table, and nullability. */
    record Column(String name, String sqlType, String icebergType, boolean nullable) {}

    private static Column col(String name, String sqlType, boolean nullable) {
        String iceberg = switch (sqlType) {
            case "INT8" -> "long";
            case "STRING" -> "string";
            case "DATE" -> "date";
            case "BOOL" -> "boolean";
            default -> sqlType.startsWith("DECIMAL") ? "decimal(38, " + sqlType.replaceAll(".*,\\s*(\\d+)\\)", "$1") + ")"
                : sqlType;
        };
        return new Column(name, sqlType, iceberg, nullable);
    }

    /** The custody tables, each the books' current rows of one clearing feed, keyed by its first column. */
    public enum Table {
        STOCK_RECORD("stock_record", List.of(col("position_id", "STRING", false), col("business_date", "DATE", false),
            col("security_id", "INT8", false), col("symbol", "STRING", false), col("cusip", "STRING", false),
            col("side", "STRING", false), col("category", "STRING", false), col("brokerage_account", "INT8", true),
            col("quantity", "INT8", false), col("close_price", "DECIMAL(18, 2)", false),
            col("market_value", "DECIMAL(18, 2)", false))),
        CNS_OBLIGATIONS("cns_obligations", List.of(col("obligation_id", "STRING", false),
            col("security_id", "INT8", false), col("symbol", "STRING", false), col("cusip", "STRING", false),
            col("trade_date", "DATE", false), col("settlement_date", "DATE", false), col("buy_quantity", "INT8", false),
            col("buy_amount", "DECIMAL(18, 2)", false), col("sell_quantity", "INT8", false),
            col("sell_amount", "DECIMAL(18, 2)", false), col("short_sale_quantity", "INT8", false),
            col("net_quantity", "INT8", false), col("net_amount", "DECIMAL(18, 2)", false),
            col("direction", "STRING", false))),
        FAILS("fails", List.of(col("fail_id", "STRING", false), col("obligation_id", "STRING", false),
            col("security_id", "INT8", false), col("symbol", "STRING", false), col("settlement_date", "DATE", false),
            col("quantity", "INT8", false), col("status", "STRING", false), col("reason", "STRING", false),
            col("reg_sho_rule", "STRING", false), col("close_out_date", "DATE", false),
            col("close_out_price", "DECIMAL(18, 2)", true), col("close_out_amount", "DECIMAL(18, 2)", true))),
        LOCATES("locates", List.of(col("locate_id", "STRING", false), col("brokerage_account", "INT8", false),
            col("security_id", "INT8", false), col("symbol", "STRING", false), col("quantity", "INT8", false),
            col("source", "STRING", false), col("easy_to_borrow", "BOOL", false),
            col("borrow_rate", "DECIMAL(9, 6)", false), col("order_ref", "STRING", false),
            col("business_date", "DATE", false)));

        public final String table;
        public final List<Column> columns;

        Table(String table, List<Column> columns) {
            this.table = table;
            this.columns = columns;
        }

        String key() {
            return columns.get(0).name();
        }
    }

    /** The custody tables as cockroach.sql makes them; a test holds that file to this. */
    public static String ddl() {
        StringBuilder sql = new StringBuilder();
        for (Table t : Table.values()) {
            sql.append("CREATE TABLE ").append(SCHEMA).append('.').append(t.table).append(" (\n");
            for (Column c : t.columns) {
                sql.append("    ").append(c.name()).append(' ').append(c.sqlType())
                    .append(c.name().equals(t.key()) ? " PRIMARY KEY" : c.nullable() ? "" : " NOT NULL").append(",\n");
            }
            sql.append("    updated_at TIMESTAMP(3) NOT NULL DEFAULT now()\n);\n");
        }
        return sql.toString();
    }

    /**
     * A custody table's history table, as the catalog's create-table request: CockroachDB's connector sends DECIMAL as a
     * string, which the Iceberg sink would make a string column, so demo.sh makes each history table first, with
     * decimal columns of precision 38 as the sink makes for PostgreSQL's; the sink adds _cdc. updated_at arrives as
     * microseconds, which cockroach-history.json converts to a timestamp.
     */
    public static String history(Table t) {
        StringBuilder json = new StringBuilder("{\"name\": \"" + t.table + "\", \"schema\": {\"type\": \"struct\", \"fields\": [\n");
        int id = 1;
        for (Column c : t.columns) {
            json.append("  {\"id\": ").append(id++).append(", \"name\": \"").append(c.name()).append("\", \"required\": ")
                .append(!c.nullable()).append(", \"type\": \"").append(c.icebergType()).append("\"},\n");
        }
        json.append("  {\"id\": ").append(id).append(", \"name\": \"updated_at\", \"required\": true, \"type\": \"timestamptz\"}]}}\n");
        return json.toString();
    }

    /**
     * Collects the clearing books from the world's events: each table's rows by key, as they stand after the last day
     * played.
     */
    public static final class Book implements Sink {
        final Map<Table, Map<String, List<Object>>> rows = new EnumMap<>(Table.class);
        private final Deque<LocalDate> days = new ArrayDeque<>();
        private LocalDate stockRecordDay;
        private LocalDate last;

        public Book() {
            for (Table t : Table.values()) {
                rows.put(t, new LinkedHashMap<>());
            }
        }

        /** The last business day played. */
        public LocalDate last() {
            return last;
        }

        public int size(Table t) {
            return rows.get(t).size();
        }

        @Override
        public void accept(Event e) {
            Map<String, Object> r = e.row();
            switch (e.feed()) {
                case STOCK_RECORD -> {
                    LocalDate day = (LocalDate) r.get("business_date");
                    if (!day.equals(stockRecordDay)) {
                        rows.get(Table.STOCK_RECORD).clear();
                        stockRecordDay = day;
                    }
                    put(Table.STOCK_RECORD, r);
                }
                case CNS_OBLIGATIONS -> put(Table.CNS_OBLIGATIONS, r);
                case LOCATES -> put(Table.LOCATES, r);
                case FAILS -> {
                    Map<String, Object> f = new HashMap<>(r);
                    f.put("status", r.get("event_type"));
                    f.put("close_out_date", LocalDate.ofInstant((Instant) r.get("close_out_deadline"), World.NEW_YORK));
                    List<Object> before = rows.get(Table.FAILS).get((String) r.get("fail_id"));
                    if (before != null && r.get("close_out_price") == null) {
                        // The close-out's price and amount stay with the fail once it is delivered.
                        f.put("close_out_price", before.get(10));
                        f.put("close_out_amount", before.get(11));
                    }
                    put(Table.FAILS, f);
                }
                default -> {
                }
            }
        }

        private void put(Table t, Map<String, Object> r) {
            List<Object> values = new ArrayList<>();
            for (Column c : t.columns) {
                values.add(r.get(c.name()));
            }
            rows.get(t).put((String) values.get(0), values);
        }

        @Override
        public void endOfDay(LocalDate day) {
            last = day;
            days.addLast(day);
            while (days.size() > DAYS_KEPT) {
                days.removeFirst();
            }
            LocalDate oldest = days.getFirst();
            rows.get(Table.CNS_OBLIGATIONS).values().removeIf(v -> ((LocalDate) v.get(4)).isBefore(oldest));
            rows.get(Table.LOCATES).values().removeIf(v -> ((LocalDate) v.get(9)).isBefore(oldest));
        }
    }

    private final Connection connection;

    private Custody(Connection connection) {
        this.connection = connection;
    }

    public static Custody open(String url, String user, String password) throws SQLException {
        return new Custody(DriverManager.getConnection(url, user, password));
    }

    /** Writes what differs between the books and the tables; returns what it did, table by table. */
    public String load(Book book) throws SQLException {
        Map<String, String> done = new LinkedHashMap<>();
        for (Table t : Table.values()) {
            Map<String, List<String>> held = new HashMap<>();
            List<String> names = t.columns.stream().map(Column::name).toList();
            try (Statement s = connection.createStatement();
                 ResultSet rs = s.executeQuery("SELECT " + String.join(", ", names) + " FROM " + SCHEMA + "." + t.table)) {
                while (rs.next()) {
                    List<String> values = new ArrayList<>();
                    for (int i = 1; i <= names.size(); i++) {
                        values.add(normal(rs.getObject(i)));
                    }
                    held.put(values.get(0), values);
                }
            }
            String upsert = "UPSERT INTO " + SCHEMA + "." + t.table + " (" + String.join(", ", names) + ", updated_at)"
                + " VALUES (" + "?, ".repeat(names.size()) + "now())";
            int written = 0, deleted = 0;
            connection.setAutoCommit(false);
            try (PreparedStatement up = connection.prepareStatement(upsert);
                 PreparedStatement del = connection.prepareStatement(
                     "DELETE FROM " + SCHEMA + "." + t.table + " WHERE " + t.key() + " = ?")) {
                for (List<Object> row : book.rows.get(t).values()) {
                    List<String> values = row.stream().map(Custody::normal).toList();
                    if (values.equals(held.remove(values.get(0)))) {
                        continue;
                    }
                    for (int i = 0; i < row.size(); i++) {
                        set(up, i + 1, row.get(i), t.columns.get(i));
                    }
                    up.addBatch();
                    written++;
                }
                for (String key : held.keySet()) {
                    del.setString(1, key);
                    del.addBatch();
                    deleted++;
                }
                up.executeBatch();
                del.executeBatch();
                connection.commit();
            } finally {
                connection.setAutoCommit(true);
            }
            done.put(t.table, written + " written, " + deleted + " deleted, " + book.size(t) + " held");
        }
        return "custody after " + book.last() + ": " + done;
    }

    private static void set(PreparedStatement s, int i, Object v, Column c) throws SQLException {
        if (v == null) {
            s.setNull(i, switch (c.sqlType()) {
                case "INT8" -> Types.BIGINT;
                case "STRING" -> Types.VARCHAR;
                case "DATE" -> Types.DATE;
                case "BOOL" -> Types.BOOLEAN;
                default -> Types.NUMERIC;
            });
        } else {
            s.setObject(i, v);
        }
    }

    /** A value as text, the same whether the world made it or the database returned it. */
    static String normal(Object v) {
        return switch (v) {
            case null -> null;
            case BigDecimal d -> d.signum() == 0 ? "0" : d.stripTrailingZeros().toPlainString();
            case java.sql.Date d -> d.toLocalDate().toString();
            case Number n -> String.valueOf(n.longValue());
            default -> Objects.toString(v);
        };
    }

    @Override
    public void close() throws SQLException {
        connection.close();
    }
}
