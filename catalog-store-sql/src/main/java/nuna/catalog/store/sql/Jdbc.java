package nuna.catalog.store.sql;

import nuna.catalog.store.Page;
import nuna.catalog.store.PageRequest;
import nuna.catalog.store.PageToken;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/** Statement helpers. Parameters bind by type: Instant as UTC timestamptz, UUID[] and String[] as SQL arrays. */
final class Jdbc {

    private Jdbc() {
    }

    @FunctionalInterface
    interface Row<T> {
        T map(ResultSet row) throws SQLException;
    }

    static int update(Connection connection, String sql, Object... parameters) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(connection, statement, parameters);
            return statement.executeUpdate();
        }
    }

    static <T> List<T> query(Connection connection, String sql, Row<T> row, Object... parameters) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(connection, statement, parameters);
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

    static void bind(Connection connection, PreparedStatement statement, Object... parameters) throws SQLException {
        for (int i = 0; i < parameters.length; i++) {
            Object value = parameters[i];
            if (value instanceof Instant instant) {
                statement.setObject(i + 1, utc(instant));
            } else if (value instanceof UUID[] ids) {
                statement.setArray(i + 1, connection.createArrayOf("uuid", ids));
            } else if (value instanceof String[] texts) {
                statement.setArray(i + 1, connection.createArrayOf("text", texts));
            } else {
                statement.setObject(i + 1, value);
            }
        }
    }

    static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    static Instant instant(ResultSet row, String column) throws SQLException {
        OffsetDateTime value = row.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    static UUID uuid(ResultSet row, String column) throws SQLException {
        return row.getObject(column, UUID.class);
    }

    static List<UUID> uuids(ResultSet row, String column) throws SQLException {
        return List.of((UUID[]) row.getArray(column).getArray());
    }

    static List<String> texts(ResultSet row, String column) throws SQLException {
        return List.of((String[]) row.getArray(column).getArray());
    }

    /**
     * A page from rows fetched with LIMIT {@code size + 1}: the extra row only shows that another
     * page exists; the token names the last row returned.
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
