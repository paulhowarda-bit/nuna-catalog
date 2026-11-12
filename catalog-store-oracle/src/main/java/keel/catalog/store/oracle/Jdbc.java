package keel.catalog.store.oracle;

import keel.catalog.store.Page;
import keel.catalog.store.PageRequest;
import keel.catalog.store.PageToken;
import java.nio.ByteBuffer;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/**
 * Statement helpers. Parameters bind by type: UUID as RAW(16), Instant as UTC TIMESTAMP WITH TIME
 * ZONE, String through the text codec; read text back with {@link #text}.
 *
 * <p>The text codec: Oracle stores '' as NULL, so '' is written as CHR(0), and a value that starts
 * with CHR(0) gets one more in front. Other values are written as they are, so rows written by the
 * previous release read back unchanged. The mapping keeps byte order, so comparisons and ORDER BY
 * on encoded values match the values' own order.
 */
final class Jdbc {

    /** The most values Oracle accepts in one IN list (ORA-01795). */
    private static final int IN_LIST_LIMIT = 1000;
    private static final int UNIQUE_VIOLATION = 1;
    private static final char ZERO = '\0';

    private Jdbc() {
    }

    @FunctionalInterface
    interface Row<T> {
        T map(ResultSet row) throws SQLException;
    }

    static String encode(String value) {
        return value != null && (value.isEmpty() || value.charAt(0) == ZERO) ? ZERO + value : value;
    }

    static String decode(String stored) {
        return stored != null && !stored.isEmpty() && stored.charAt(0) == ZERO ? stored.substring(1) : stored;
    }

    static int update(Connection connection, String sql, Object... parameters) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, parameters);
            return statement.executeUpdate();
        }
    }

    /** Runs {@code sql} once per row of parameters, as one batch. */
    static void batch(Connection connection, String sql, List<Object[]> rows) throws SQLException {
        if (rows.isEmpty()) {
            return;
        }
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (Object[] row : rows) {
                bind(statement, row);
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    static <T> List<T> query(Connection connection, String sql, Row<T> row, Object... parameters) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, parameters);
            List<T> results = new ArrayList<>();
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    results.add(row.map(rows));
                }
            }
            return results;
        }
    }

    static <T> Optional<T> first(Connection connection, String sql, Row<T> row, Object... parameters) throws SQLException {
        List<T> results = query(connection, sql, row, parameters);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.getFirst());
    }

    static boolean exists(Connection connection, String sql, Object... parameters) throws SQLException {
        return first(connection, sql, row -> true, parameters).isPresent();
    }

    static void bind(PreparedStatement statement, Object... parameters) throws SQLException {
        for (int i = 0; i < parameters.length; i++) {
            Object value = parameters[i];
            switch (value) {
                case null -> statement.setNull(i + 1, Types.VARCHAR);
                case String text -> statement.setString(i + 1, encode(text));
                case UUID id -> statement.setBytes(i + 1, raw(id));
                case Instant instant -> statement.setObject(i + 1, utc(instant));
                default -> statement.setObject(i + 1, value);
            }
        }
    }

    /**
     * {@code column IN (?, ...)} for every value, split into lists Oracle accepts, with the values
     * appended to {@code parameters}. An empty collection matches nothing.
     */
    static String in(String column, Collection<?> values, List<Object> parameters) {
        if (values.isEmpty()) {
            return "1 = 0";
        }
        List<String> lists = new ArrayList<>();
        List<?> all = List.copyOf(values);
        for (int from = 0; from < all.size(); from += IN_LIST_LIMIT) {
            List<?> chunk = all.subList(from, Math.min(all.size(), from + IN_LIST_LIMIT));
            lists.add(column + " IN (" + String.join(", ", Collections.nCopies(chunk.size(), "?")) + ")");
            parameters.addAll(chunk);
        }
        return lists.size() == 1 ? lists.getFirst() : "(" + String.join(" OR ", lists) + ")";
    }

    /**
     * The keyset condition {@code (columns) > (values)}, expanded since Oracle has no row-value
     * comparison: {@code c1 > ? OR (c1 = ? AND (c2 > ? OR ...))}, with the values appended to
     * {@code parameters}. Each column is one an index on the listing leads with, in order.
     */
    static String greater(List<String> columns, List<?> values, List<Object> parameters) {
        String column = columns.getFirst();
        parameters.add(values.getFirst());
        if (columns.size() == 1) {
            return column + " > ?";
        }
        parameters.add(values.getFirst());
        return "(" + column + " > ? OR (" + column + " = ? AND "
                + greater(columns.subList(1, columns.size()), values.subList(1, values.size()), parameters) + "))";
    }

    /** Whether {@code e} is a unique violation (ORA-00001) of the named constraint or unique index. */
    static boolean violates(SQLException e, String constraint) {
        return e instanceof SQLIntegrityConstraintViolationException && e.getErrorCode() == UNIQUE_VIOLATION
                && e.getMessage() != null && e.getMessage().toUpperCase(Locale.ROOT).contains(constraint.toUpperCase(Locale.ROOT));
    }

    static String text(ResultSet row, String column) throws SQLException {
        return decode(row.getString(column));
    }

    static String text(ResultSet row, int column) throws SQLException {
        return decode(row.getString(column));
    }

    static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    static Instant instant(ResultSet row, String column) throws SQLException {
        OffsetDateTime value = row.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    static UUID uuid(ResultSet row, String column) throws SQLException {
        return uuid(row.getBytes(column));
    }

    static UUID uuid(ResultSet row, int column) throws SQLException {
        return uuid(row.getBytes(column));
    }

    /** A UUID as RAW(16), most significant byte first, so RAW order is the unsigned order of D15. */
    static byte[] raw(UUID id) {
        return ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array();
    }

    static UUID uuid(byte[] raw) {
        if (raw == null) {
            return null;
        }
        ByteBuffer bytes = ByteBuffer.wrap(raw);
        return new UUID(bytes.getLong(), bytes.getLong());
    }

    /**
     * A page from rows fetched with FETCH FIRST {@code size + 1} ROWS ONLY: the extra row only
     * shows that another page exists; the token names the last row returned.
     */
    static <T> Page<T> page(List<T> fetched, PageRequest request, Function<T, String> token) {
        if (fetched.size() <= request.size()) {
            return new Page<>(fetched, Optional.empty());
        }
        List<T> items = fetched.subList(0, request.size());
        return new Page<>(items, Optional.of(token.apply(items.getLast())));
    }

    /** The token's parts, parsed; a token the store did not issue is an IllegalArgumentException. */
    static <T> Optional<T> after(PageRequest request, int parts, Function<List<String>, T> parse) {
        if (request.token().isEmpty()) {
            return Optional.empty();
        }
        List<String> decoded = PageToken.decode(request.token().get(), parts);
        try {
            return Optional.of(parse.apply(decoded));
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("not a page token: " + request.token().get(), e);
        }
    }
}
