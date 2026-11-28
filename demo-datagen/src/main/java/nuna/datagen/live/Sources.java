package nuna.datagen.live;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import nuna.datagen.kit.Kit;
import nuna.datagen.world.Event;
import nuna.datagen.world.Feed;
import nuna.datagen.world.LakeSchemas;
import nuna.datagen.world.Sink;
import org.apache.iceberg.types.Type;
import org.apache.iceberg.types.Types;

/**
 * The CDC leg's other books of record (item 152): PostgreSQL {@code backoffice}, the back office's own database (each
 * party, account, role, CIP check, suitability profile, beneficial owner and trusted contact; each payment, ACH return
 * and ACATS transfer; each AML alert and case and each complaint), and Oracle {@code treasury}, the firm's bank payments
 * (each ACH and wire as its bank sees it). Each table holds its rows as they stand after the lake's last business day:
 * a record's latest version, the same row the lake's {@code <table>_current} view gives. Debezium captures each change
 * into {@code history.postgres.<schema>.<table>} and {@code history.oracle.treasury.bank_payments}. Item 153 adds the
 * General Ledger to treasury ({@link GeneralLedger}): {@code gl_journal} and {@code gl_balances}, captured into
 * {@code history.oracle.treasury.gl_journal} and {@code gl_balances}, read through views in {@code brokerage.finance}.
 *
 * <p>As {@link Custody} does for CockroachDB, Main's {@code cdc} command replays the world to the lake's last tagged day,
 * collecting the rows in a {@link Book}, and writes only what differs from what the tables hold: rows changed or new are
 * merged, rows no longer in the books deleted. The tables' DDL ({@link #ddl}) is what demo/cdc/backoffice.sql and
 * demo/cdc/oracle.sql make, which a test holds them to.
 */
public final class Sources implements AutoCloseable {

    /** A database of the CDC leg, and its SQL. */
    public enum Db {
        BACKOFFICE, TREASURY;

        /** A column type in this database's SQL. */
        String sql(Type type) {
            boolean pg = this == BACKOFFICE;
            return switch (type.typeId()) {
                case LONG -> pg ? "bigint" : "NUMBER(18)";
                case INTEGER -> pg ? "integer" : "NUMBER(9)";
                case STRING -> pg ? "varchar" : "VARCHAR2(400)";
                case BOOLEAN -> pg ? "boolean" : "BOOLEAN";
                case DATE -> pg ? "date" : "DATE";
                case TIMESTAMP -> pg ? "timestamp(3)" : "TIMESTAMP(3)";
                case DECIMAL -> (pg ? "numeric(" : "NUMBER(") + ((Types.DecimalType) type).precision() + ", "
                    + ((Types.DecimalType) type).scale() + ")";
                default -> throw new IllegalArgumentException("no column type for " + type);
            };
        }
    }

    /** A column of a source table: its name and its type in the lake. */
    record Column(String name, Type type) {}

    /** The firm's own bank account each rail settles through (treasury.bank_payments). */
    static final Map<String, String> FIRM_BANK_ACCOUNTS = Map.of("ach", "DEMO-ACH-SETTLEMENT", "wire",
        "DEMO-WIRE-SETTLEMENT");

    /** The source tables, each the latest version of each record of one feed, keyed by the feed's key. */
    public enum Table {
        PARTIES(Db.BACKOFFICE, "party", Feed.PARTIES),
        ACCOUNTS(Db.BACKOFFICE, "party", Feed.ACCOUNTS),
        ACCOUNT_ROLES(Db.BACKOFFICE, "party", Feed.ACCOUNT_ROLES),
        CIP_CHECKS(Db.BACKOFFICE, "party", Feed.CIP_CHECKS),
        SUITABILITY_PROFILES(Db.BACKOFFICE, "party", Feed.SUITABILITY_PROFILES),
        BENEFICIAL_OWNERS(Db.BACKOFFICE, "party", Feed.BENEFICIAL_OWNERS),
        TRUSTED_CONTACTS(Db.BACKOFFICE, "party", Feed.TRUSTED_CONTACTS),
        PAYMENTS(Db.BACKOFFICE, "money_movement", Feed.PAYMENTS),
        ACH_RETURNS(Db.BACKOFFICE, "money_movement", Feed.ACH_RETURNS),
        ACATS_TRANSFERS(Db.BACKOFFICE, "money_movement", Feed.ACATS_TRANSFERS),
        AML_ALERTS(Db.BACKOFFICE, "compliance", Feed.AML_ALERTS),
        AML_CASES(Db.BACKOFFICE, "compliance", Feed.AML_CASES),
        COMPLAINTS(Db.BACKOFFICE, "compliance", Feed.COMPLAINTS),
        /** Each ACH and wire as the firm's bank sees it, from the payments' rows ({@link Book#bankPayment}). */
        BANK_PAYMENTS(Db.TREASURY, "treasury", Feed.PAYMENTS),
        /**
         * The General Ledger's journal (item 153): per business day and GL control account, the day's debits and credits
         * of the sub-ledger definition it controls ({@link Book}).
         */
        GL_JOURNAL(Db.TREASURY, "treasury", Feed.JOURNAL_LINES),
        /** The General Ledger's balances (item 153): each GL control account at each day's end. */
        GL_BALANCES(Db.TREASURY, "treasury", Feed.JOURNAL_LINES);

        public final Db db;
        public final String schema;
        public final String table;
        public final Feed feed;
        final List<Column> columns;

        Table(Db db, String schema, Feed feed) {
            this.db = db;
            this.schema = schema;
            this.feed = feed;
            this.table = db == Db.TREASURY ? name().toLowerCase(java.util.Locale.ROOT) : feed.table;
            this.columns = switch (name()) {
                case "BANK_PAYMENTS" -> bankColumns();
                case "GL_JOURNAL" -> glJournalColumns();
                case "GL_BALANCES" -> glBalanceColumns();
                default -> LakeSchemas.schema(feed).columns().stream().map(c -> new Column(c.name(), c.type())).toList();
            };
        }

        /** The table's primary key. */
        public String key() {
            return switch (name()) {
                case "BANK_PAYMENTS" -> "payment_id";
                case "GL_JOURNAL" -> "journal_id";
                case "GL_BALANCES" -> "balance_id";
                default -> LakeSchemas.key(feed);
            };
        }

        /** The table's columns, in order, without the load's {@code updated_at}. */
        public List<String> columnNames() {
            return columns.stream().map(Column::name).toList();
        }

        /** The table's name in SQL: {@code schema.table}. */
        public String qualified() {
            return schema + "." + table;
        }

        /** The history table Debezium's sink writes this table's changes into. */
        public String history() {
            return "history." + (db == Db.TREASURY ? "oracle" : "postgres") + "." + qualified();
        }

        static List<Table> of(Db db) {
            return java.util.Arrays.stream(values()).filter(t -> t.db == db).toList();
        }
    }

    private static List<Column> bankColumns() {
        Type text = Types.StringType.get();
        return List.of(new Column("payment_id", text), new Column("version", Types.IntegerType.get()),
            new Column("brokerage_account", Types.LongType.get()), new Column("rail", text),
            new Column("direction", text), new Column("status", text),
            new Column("amount", Types.DecimalType.of(18, 2)), new Column("firm_bank_account", text),
            new Column("counterparty_routing_number", text), new Column("counterparty_account_number", text),
            new Column("counterparty_name", text), new Column("value_date", Types.DateType.get()),
            new Column("event_time", Types.TimestampType.withZone()));
    }

    private static List<Column> glJournalColumns() {
        Type text = Types.StringType.get(), money = Types.DecimalType.of(20, 2);
        return List.of(new Column("journal_id", text), new Column("business_date", Types.DateType.get()),
            new Column("gl_account", text), new Column("gl_account_name", text),
            new Column("definition_id", Types.IntegerType.get()), new Column("debit_total", money),
            new Column("credit_total", money), new Column("line_count", Types.LongType.get()),
            new Column("posted_at", Types.TimestampType.withZone()));
    }

    private static List<Column> glBalanceColumns() {
        Type text = Types.StringType.get();
        return List.of(new Column("balance_id", text), new Column("business_date", Types.DateType.get()),
            new Column("gl_account", text), new Column("gl_account_name", text),
            new Column("definition_id", Types.IntegerType.get()), new Column("normal_side", text),
            new Column("balance", Types.DecimalType.of(20, 2)));
    }

    /**
     * A database's tables as its SQL file makes them (demo/cdc/backoffice.sql, demo/cdc/oracle.sql); a test holds each
     * file to this. PostgreSQL's keep the whole row in a delete's change event (REPLICA IDENTITY FULL), Oracle's log
     * every column (Debezium's LogMiner needs it); {@code updated_at} is the time of the load that last wrote the row.
     */
    public static String ddl(Db db) {
        StringBuilder sql = new StringBuilder();
        if (db == Db.BACKOFFICE) {
            Table.of(db).stream().map(t -> t.schema).distinct()
                .forEach(s -> sql.append("CREATE SCHEMA ").append(s).append(";\n"));
        }
        for (Table t : Table.of(db)) {
            sql.append("CREATE TABLE ").append(t.qualified()).append(" (\n");
            for (Column c : t.columns) {
                sql.append("    ").append(c.name()).append(' ').append(db.sql(c.type()))
                    .append(c.name().equals(t.key()) ? " PRIMARY KEY" : "").append(",\n");
            }
            sql.append(db == Db.BACKOFFICE ? "    updated_at timestamp(3) NOT NULL DEFAULT now()\n);\n"
                : "    updated_at TIMESTAMP(3) DEFAULT SYSTIMESTAMP NOT NULL\n);\n");
            sql.append(db == Db.BACKOFFICE ? "ALTER TABLE " + t.qualified() + " REPLICA IDENTITY FULL;\n"
                : "ALTER TABLE " + t.qualified() + " ADD SUPPLEMENTAL LOG DATA (ALL) COLUMNS;\n");
        }
        return sql.toString();
    }

    /** What a load did to one table. */
    public record Counts(int written, int deleted, int held) {
        @Override
        public String toString() {
            return written + " written, " + deleted + " deleted, " + held + " held";
        }
    }

    /** Collects the source tables' rows from the world's events: each record's latest version, by key. */
    public static final class Book implements Sink {
        final Map<Table, Map<String, List<Object>>> rows = new EnumMap<>(Table.class);
        private LocalDate last;
        private final GeneralLedger gl = new GeneralLedger(Kit.load());

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
            for (Table t : Table.values()) {
                if (t.feed == e.feed() && t.db == Db.BACKOFFICE) {
                    put(t, e.row());
                }
            }
            if (e.feed() == Feed.PAYMENTS) {
                Map<String, Object> bank = bankPayment(e.row());
                if (bank != null) {
                    put(Table.BANK_PAYMENTS, bank);
                }
            }
            if (e.feed() == Feed.JOURNAL_LINES) {
                gl.line(e.row());
            }
            // A full ACATS delivery takes everything out: the account is closed.
            if (e.feed() == Feed.ACATS_TRANSFERS && "deliver".equals(e.row().get("direction"))
                    && "full".equals(e.row().get("transfer_type"))) {
                List<Object> account = rows.get(Table.ACCOUNTS).get(normal(e.row().get("brokerage_account")));
                if (account != null) {
                    account.set(index(Table.ACCOUNTS, "status"), "closed");
                }
            }
        }

        /** A payment as the firm's bank sees it: ACH and wires only (cash and journals never reach the bank). */
        static Map<String, Object> bankPayment(Map<String, Object> p) {
            String method = (String) p.get("method");
            // A request the payments systems have not taken yet, or refused, never reached the bank (item 321).
            if (!FIRM_BANK_ACCOUNTS.containsKey(method) || "requested".equals(p.get("status"))
                    || "rejected".equals(p.get("status"))) {
                return null;
            }
            Map<String, Object> bank = new HashMap<>();
            bank.put("payment_id", p.get("payment_id"));
            bank.put("version", p.get("version"));
            bank.put("brokerage_account", p.get("brokerage_account"));
            bank.put("rail", method.equals("ach") ? "ACH" : "FEDWIRE");
            bank.put("direction", "deposit".equals(p.get("direction")) ? "incoming" : "outgoing");
            bank.put("status", switch ((String) p.get("status")) {
                case "initiated" -> "pending";
                case "received", "settled" -> "settled";
                default -> p.get("status");
            });
            bank.put("amount", p.get("amount"));
            bank.put("firm_bank_account", FIRM_BANK_ACCOUNTS.get(method));
            bank.put("counterparty_routing_number", p.get("bank_routing_number"));
            bank.put("counterparty_account_number", p.get("bank_account_number"));
            bank.put("counterparty_name", p.get("counterparty_name"));
            Object expected = p.get("expected_settlement_date");
            bank.put("value_date", expected != null ? expected : p.get("business_date"));
            bank.put("event_time", p.get("event_time"));
            return bank;
        }

        private void put(Table t, Map<String, Object> r) {
            List<Object> values = new ArrayList<>();
            for (Column c : t.columns) {
                values.add(r.get(c.name()));
            }
            rows.get(t).put(normal(values.get(index(t, t.key()))), values);
        }

        @Override
        public void endOfDay(LocalDate day) {
            last = day;
            for (Map<String, Object> r : gl.journal(day)) {
                put(Table.GL_JOURNAL, r);
            }
            for (Map<String, Object> r : gl.balances(day)) {
                put(Table.GL_BALANCES, r);
            }
        }
    }

    private static int index(Table t, String column) {
        for (int i = 0; i < t.columns.size(); i++) {
            if (t.columns.get(i).name().equals(column)) {
                return i;
            }
        }
        throw new IllegalArgumentException(t + " has no column " + column);
    }

    private final Db db;
    private final Connection connection;

    Sources(Db db, Connection connection) {
        this.db = db;
        this.connection = connection;
    }

    public static Sources open(Db db, String url, String user, String password) throws SQLException {
        return new Sources(db, DriverManager.getConnection(url, user, password));
    }

    /** Writes what differs between the books and the database's tables, a transaction a table; returns what it did. */
    public Map<Table, Counts> load(Book book) throws SQLException {
        Map<Table, Counts> done = new LinkedHashMap<>();
        for (Table t : Table.of(db)) {
            List<String> names = t.columns.stream().map(Column::name).toList();
            int key = index(t, t.key());
            Map<String, List<String>> held = new HashMap<>();
            try (Statement s = connection.createStatement();
                 ResultSet rs = s.executeQuery("SELECT " + String.join(", ", names) + " FROM " + t.qualified())) {
                while (rs.next()) {
                    List<String> values = new ArrayList<>();
                    for (int i = 0; i < names.size(); i++) {
                        values.add(read(rs, i + 1, t.columns.get(i).type()));
                    }
                    held.put(values.get(key), values);
                }
            }
            int written = 0, deleted = 0;
            boolean auto = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try (PreparedStatement merge = connection.prepareStatement(merge(t));
                 PreparedStatement delete = connection.prepareStatement(
                     "DELETE FROM " + t.qualified() + " WHERE " + t.key() + " = ?")) {
                for (List<Object> row : book.rows.get(t).values()) {
                    List<String> values = row.stream().map(Sources::normal).toList();
                    if (values.equals(held.remove(values.get(key)))) {
                        continue;
                    }
                    for (int i = 0; i < row.size(); i++) {
                        set(merge, i + 1, row.get(i), t.columns.get(i).type());
                    }
                    merge.addBatch();
                    written++;
                }
                for (List<String> gone : held.values()) {
                    set(delete, 1, typed(gone.get(key), t.columns.get(key).type()), t.columns.get(key).type());
                    delete.addBatch();
                    deleted++;
                }
                merge.executeBatch();
                delete.executeBatch();
                connection.commit();
            } catch (SQLException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(auto);
            }
            done.put(t, new Counts(written, deleted, book.size(t)));
        }
        return done;
    }

    /**
     * The table's MERGE (SQL:2003, which PostgreSQL 15 and later, Oracle and H2 all run): each value cast to its column's
     * type, as PostgreSQL types a bare parameter as text.
     */
    String merge(Table t) {
        String source = t.columns.stream().map(c -> "CAST(? AS " + db.sql(c.type()) + ") AS " + c.name())
            .collect(Collectors.joining(", "));
        String others = t.columns.stream().map(Column::name).filter(n -> !n.equals(t.key()))
            .map(n -> n + " = s." + n).collect(Collectors.joining(", "));
        String names = t.columns.stream().map(Column::name).collect(Collectors.joining(", "));
        String values = t.columns.stream().map(c -> "s." + c.name()).collect(Collectors.joining(", "));
        return "MERGE INTO " + t.qualified() + " d USING (SELECT " + source + (db == Db.TREASURY ? " FROM dual" : "")
            + ") s ON (d." + t.key() + " = s." + t.key() + ")"
            + " WHEN MATCHED THEN UPDATE SET " + others + ", updated_at = LOCALTIMESTAMP"
            + " WHEN NOT MATCHED THEN INSERT (" + names + ", updated_at) VALUES (" + values + ", LOCALTIMESTAMP)";
    }

    private static void set(PreparedStatement s, int i, Object v, Type type) throws SQLException {
        if (v == null || "".equals(v)) {
            s.setNull(i, switch (type.typeId()) {
                case LONG -> java.sql.Types.BIGINT;
                case INTEGER -> java.sql.Types.INTEGER;
                case BOOLEAN -> java.sql.Types.BOOLEAN;
                case DATE -> java.sql.Types.DATE;
                case TIMESTAMP -> java.sql.Types.TIMESTAMP;
                case DECIMAL -> java.sql.Types.NUMERIC;
                default -> java.sql.Types.VARCHAR;
            });
            return;
        }
        switch (v) {
            case Instant at -> s.setObject(i, LocalDateTime.ofInstant(at.truncatedTo(ChronoUnit.MILLIS), ZoneOffset.UTC));
            case LocalDate d -> s.setObject(i, d);
            case BigDecimal d -> s.setBigDecimal(i, d);
            case Long n -> s.setLong(i, n);
            case Integer n -> s.setInt(i, n);
            case Boolean b -> s.setBoolean(i, b);
            default -> s.setString(i, v.toString());
        }
    }

    /** A column's value as the database returns it, as text ({@link #normal}). */
    private static String read(ResultSet rs, int i, Type type) throws SQLException {
        Object v = switch (type.typeId()) {
            case LONG -> rs.getLong(i);
            case INTEGER -> rs.getInt(i);
            case BOOLEAN -> rs.getBoolean(i);
            // As the wall-clock values they are, never through the JVM's time zone.
            case DATE -> rs.getObject(i, LocalDate.class);
            case TIMESTAMP -> rs.getObject(i, LocalDateTime.class);
            case DECIMAL -> rs.getBigDecimal(i);
            default -> rs.getString(i);
        };
        return rs.wasNull() ? null : normal(v);
    }

    /** A key, held as text, back as the value its column binds. */
    private static Object typed(String key, Type type) {
        return switch (type.typeId()) {
            case LONG -> Long.parseLong(key);
            case INTEGER -> Integer.parseInt(key);
            default -> key;
        };
    }

    /**
     * A value as text, the same whether the world made it or the database returned it: an instant as its UTC time to
     * the millisecond, which is what the tables keep, and an empty string as null, which is what Oracle keeps.
     */
    static String normal(Object v) {
        return switch (v) {
            case null -> null;
            case String s when s.isEmpty() -> null;
            case Instant at -> LocalDateTime.ofInstant(at.truncatedTo(ChronoUnit.MILLIS), ZoneOffset.UTC).toString();
            case BigDecimal d -> d.signum() == 0 ? "0" : d.stripTrailingZeros().toPlainString();
            case Number n -> String.valueOf(n.longValue());
            default -> Objects.toString(v);
        };
    }

    @Override
    public void close() throws SQLException {
        connection.close();
    }
}
