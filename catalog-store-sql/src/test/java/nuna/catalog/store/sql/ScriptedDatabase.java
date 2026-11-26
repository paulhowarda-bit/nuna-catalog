package nuna.catalog.store.sql;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;

/**
 * A database that answers each statement from a script and records every statement it was sent, in order, so a test
 * can see what a store method locks and reads, and in what order, without a database. A query answers the rows of the
 * first rule whose text it contains, else none; an update answers one row changed. It says it is PostgreSQL.
 */
final class ScriptedDatabase {

    private final Map<String, List<Map<String, Object>>> rules = new LinkedHashMap<>();
    private final List<String> sent = Collections.synchronizedList(new ArrayList<>());

    /** Answers a query containing {@code text} with {@code rows}, each a column-to-value map in column order. */
    @SafeVarargs
    final ScriptedDatabase answer(String text, Map<String, Object>... rows) {
        rules.put(text, List.of(rows));
        return this;
    }

    /** Every statement sent, in order. */
    List<String> sent() {
        return List.copyOf(sent);
    }

    /** Where the first statement containing {@code text} was sent, or -1. */
    int indexOf(String text) {
        List<String> statements = sent();
        for (int i = 0; i < statements.size(); i++) {
            if (statements.get(i).contains(text)) {
                return i;
            }
        }
        return -1;
    }

    Transactions transactions() {
        return new Transactions(proxy(DataSource.class, (method, args) -> method.getName().equals("getConnection")
                ? connection() : fallback(method)), commitId -> { });
    }

    private Connection connection() {
        return proxy(Connection.class, (method, args) -> switch (method.getName()) {
            case "prepareStatement" -> statement((String) args[0]);
            case "createStatement" -> statement(null);
            case "createArrayOf" -> array((Object[]) args[1]);
            default -> fallback(method);
        });
    }

    /** A statement prepared with {@code prepared}, or a plain one ({@code null}) that is given its text on execution. */
    private PreparedStatement statement(String prepared) {
        return proxy(PreparedStatement.class, (method, args) -> switch (method.getName()) {
            case "executeQuery" -> rows(record(prepared != null ? prepared : (String) args[0]));
            case "executeUpdate" -> {
                record(prepared != null ? prepared : (String) args[0]);
                yield 1;
            }
            case "executeBatch" -> {
                record(prepared);
                yield new int[0];
            }
            default -> fallback(method);
        });
    }

    private String record(String sql) {
        sent.add(sql);
        return sql;
    }

    private ResultSet rows(String sql) {
        List<Map<String, Object>> answer = sql.equals("SELECT version()")
                ? List.of(Map.of("version", "PostgreSQL 16.4"))
                : rules.entrySet().stream().filter(rule -> sql.contains(rule.getKey())).map(Map.Entry::getValue)
                        .findFirst().orElse(List.of());
        int[] at = {-1};
        return proxy(ResultSet.class, (method, args) -> switch (method.getName()) {
            case "next" -> ++at[0] < answer.size();
            case "getString", "getObject", "getLong", "getArray" -> {
                Map<String, Object> row = answer.get(at[0]);
                Object value = args[0] instanceof Integer index ? List.copyOf(row.values()).get(index - 1)
                        : row.get((String) args[0]);
                yield method.getName().equals("getArray") ? array((Object[]) value) : value;
            }
            case "wasNull" -> false;
            default -> fallback(method);
        });
    }

    private static Array array(Object[] values) {
        return proxy(Array.class, (method, args) -> method.getName().equals("getArray") ? values : fallback(method));
    }

    @FunctionalInterface
    private interface Handler {
        Object handle(Method method, Object[] args) throws Exception;
    }

    private static <T> T proxy(Class<T> type, Handler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type},
                (proxy, method, args) -> handler.handle(method, args)));
    }

    /** What a method the script does not answer returns: nothing, so setters, commits and closes do nothing. */
    private static Object fallback(Method method) {
        Class<?> type = method.getReturnType();
        if (type == boolean.class) {
            return false;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type.isPrimitive() && type != void.class) {
            throw new UnsupportedOperationException(method.getName());
        }
        return null;
    }
}
