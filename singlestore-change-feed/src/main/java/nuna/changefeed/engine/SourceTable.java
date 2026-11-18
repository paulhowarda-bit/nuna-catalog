package nuna.changefeed.engine;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.ByteBuffer;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.apache.iceberg.types.Type;
import org.apache.iceberg.types.Types;

/**
 * A SingleStore table as the change feed sees it: its columns in order, each with the Iceberg type it maps to and
 * how its value is read, its primary key, and the number of partitions of its database (one change-feed offset each).
 *
 * <p>The primary key must equal the shard key. SingleStore refuses to update a shard-key column, so a row keeps its
 * key for life, and the key alone identifies the row a delete event names (deletes carry only the key).
 */
public record SourceTable(String database, String table, List<Column> columns, List<String> primaryKey, int partitions) {

    /** A column: its name, whether it may be null, its Iceberg type, and how a change-feed value is read. */
    public record Column(String name, boolean nullable, Type icebergType, Reader reader) {}

    /** Reads a column of the current change-feed row as the value Iceberg's generic records hold. */
    @FunctionalInterface
    public interface Reader {
        Object read(ResultSet rs, int index) throws SQLException;
    }

    public static SourceTable read(Connection c, String database, String table) throws SQLException {
        List<Column> columns = new ArrayList<>();
        try (PreparedStatement s = c.prepareStatement("SELECT COLUMN_NAME, DATA_TYPE, COLUMN_TYPE, NUMERIC_PRECISION, "
                + "NUMERIC_SCALE, IS_NULLABLE FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ? "
                + "ORDER BY ORDINAL_POSITION")) {
            s.setString(1, database);
            s.setString(2, table);
            try (ResultSet rs = s.executeQuery()) {
                while (rs.next()) {
                    String name = rs.getString(1);
                    columns.add(column(database + "." + table + "." + name, name, rs.getString(2).toLowerCase(),
                            rs.getString(3).toLowerCase(), rs.getInt(4), rs.getInt(5), "YES".equals(rs.getString(6))));
                }
            }
        }
        if (columns.isEmpty()) {
            throw new IllegalArgumentException("SingleStore has no table " + database + "." + table);
        }
        List<String> primaryKey = indexColumns(c, database, table, "INDEX_NAME = 'PRIMARY'");
        Set<String> shardKey = new LinkedHashSet<>(indexColumns(c, database, table, "INDEX_TYPE = 'SHARD'"));
        if (primaryKey.isEmpty()) {
            throw new IllegalArgumentException(database + "." + table + " has no primary key; the change feed names "
                    + "deleted rows by their key, so it needs one");
        }
        if (!shardKey.equals(new LinkedHashSet<>(primaryKey))) {
            throw new IllegalArgumentException(database + "." + table + ": the primary key " + primaryKey
                    + " must equal the shard key " + shardKey + ", or an update can change a row's key and the "
                    + "Iceberg copy would keep the old row");
        }
        int partitions;
        try (PreparedStatement s = c.prepareStatement(
                "SELECT NUM_PARTITIONS FROM information_schema.DISTRIBUTED_DATABASES WHERE DATABASE_NAME = ?")) {
            s.setString(1, database);
            try (ResultSet rs = s.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalArgumentException("SingleStore has no database " + database);
                }
                partitions = rs.getInt(1);
            }
        }
        return new SourceTable(database, table, List.copyOf(columns), List.copyOf(primaryKey), partitions);
    }

    private static List<String> indexColumns(Connection c, String database, String table, String which)
            throws SQLException {
        List<String> names = new ArrayList<>();
        try (PreparedStatement s = c.prepareStatement("SELECT COLUMN_NAME FROM information_schema.STATISTICS "
                + "WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ? AND " + which + " ORDER BY SEQ_IN_INDEX")) {
            s.setString(1, database);
            s.setString(2, table);
            try (ResultSet rs = s.executeQuery()) {
                while (rs.next()) {
                    names.add(rs.getString(1));
                }
            }
        }
        return names;
    }

    /**
     * The Iceberg type of a SingleStore column. Types whose meaning a copy could change are refused rather than
     * guessed at: TIMESTAMP (stored in UTC, read back through the session time zone), unsigned BIGINT, and any type
     * not listed here.
     */
    static Column column(String where, String name, String dataType, String columnType, int precision, int scale,
            boolean nullable) {
        boolean unsigned = columnType.contains("unsigned");
        Type type;
        Reader reader;
        switch (dataType) {
            case "tinyint" -> {
                if (columnType.startsWith("tinyint(1)")) {
                    type = Types.BooleanType.get();
                    reader = (rs, i) -> rs.getObject(i, Boolean.class);
                } else {
                    type = Types.IntegerType.get();
                    reader = (rs, i) -> rs.getObject(i, Integer.class);
                }
            }
            case "smallint", "mediumint", "year" -> {
                type = Types.IntegerType.get();
                reader = (rs, i) -> rs.getObject(i, Integer.class);
            }
            case "int" -> {
                if (unsigned) {
                    type = Types.LongType.get();
                    reader = (rs, i) -> rs.getObject(i, Long.class);
                } else {
                    type = Types.IntegerType.get();
                    reader = (rs, i) -> rs.getObject(i, Integer.class);
                }
            }
            case "bigint" -> {
                if (unsigned) {
                    throw unsupported(where, columnType, "values above 2^63 have no Iceberg long");
                }
                type = Types.LongType.get();
                reader = (rs, i) -> rs.getObject(i, Long.class);
            }
            case "float" -> {
                type = Types.FloatType.get();
                reader = (rs, i) -> rs.getObject(i, Float.class);
            }
            case "double" -> {
                type = Types.DoubleType.get();
                reader = (rs, i) -> rs.getObject(i, Double.class);
            }
            case "decimal" -> {
                if (precision > 38) {
                    throw unsupported(where, columnType, "Iceberg decimals hold at most 38 digits");
                }
                type = Types.DecimalType.of(precision, scale);
                reader = (rs, i) -> {
                    BigDecimal value = rs.getObject(i, BigDecimal.class);
                    return value == null ? null : value.setScale(scale, RoundingMode.UNNECESSARY);
                };
            }
            case "char", "varchar", "tinytext", "text", "mediumtext", "longtext", "json", "enum", "set" -> {
                type = Types.StringType.get();
                reader = (rs, i) -> rs.getString(i);
            }
            case "date" -> {
                type = Types.DateType.get();
                reader = (rs, i) -> rs.getObject(i, LocalDate.class);
            }
            case "datetime" -> {
                // DATETIME has no time zone, and neither does Iceberg's timestamp: the wall-clock value is copied.
                type = Types.TimestampType.withoutZone();
                reader = (rs, i) -> rs.getObject(i, LocalDateTime.class);
            }
            case "time" -> {
                type = Types.TimeType.get();
                reader = (rs, i) -> rs.getObject(i, LocalTime.class);
            }
            case "binary", "varbinary", "tinyblob", "blob", "mediumblob", "longblob" -> {
                type = Types.BinaryType.get();
                reader = (rs, i) -> {
                    byte[] value = rs.getBytes(i);
                    return value == null ? null : ByteBuffer.wrap(value);
                };
            }
            default -> throw unsupported(where, columnType, "no Iceberg type is mapped for it");
        }
        return new Column(name, nullable, type, reader);
    }

    private static IllegalArgumentException unsupported(String where, String columnType, String why) {
        return new IllegalArgumentException(where + " is " + columnType + ", which the change feed does not copy: " + why);
    }
}
