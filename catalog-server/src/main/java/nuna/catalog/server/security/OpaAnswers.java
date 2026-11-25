package nuna.catalog.server.security;

import nuna.catalog.core.principal.Caller;
import nuna.catalog.security.RowColumnPolicy;
import nuna.catalog.store.RowColumnRule;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * What nuna answers Trino's OPA access control (D20, backlog 115), apart from HTTP: the row filter and column masks
 * the user running a query gets on a table, from the rules stored for their groups ({@link RowColumnPolicy}).
 *
 * <p>Trino asks with {@code {"input": {"context": {"identity": {"user", "groups", "extraCredentials"}}, "action":
 * {"operation", "resource" | "filterResources"}}}}. Who the user is comes only from their own catalog token, which
 * Trino forwards as the extra credential {@code token} ({@code opa.identity.extra-credentials-keys=token}) and nuna
 * verifies; Trino's own idea of the user and their groups is not trusted. Only the Trino catalogs mapped to a
 * warehouse ({@code nuna.opa.catalogs}) have rules; any other gets none. In a mapped one, a query whose token is
 * missing or not accepted, or that names a table the user holds nothing on or that does not exist, gets no rows
 * and every column {@code NULL}: fail closed, and the same answer for a table that is not there as for one the user
 * may not know of (D18).
 *
 * <p>Trino asks for the masks of every column of a table it reads in one batch (its {@code analyzeFiltersAndMasks}
 * passes the table's whole relation), so the batch shows the columns as Trino's schema names them. When the user's
 * rules mask a column the batch does not name, the rules and that schema disagree: the column was renamed past the
 * rename guard ({@link RuleColumnGuard}), or the query reads a snapshot or branch whose schema named it otherwise.
 * Serving the masks by name would leave it unmasked under its other name, so every column of that table is
 * {@code NULL} instead (backlog 240).
 */
final class OpaAnswers {

    /** The user a token names, as themselves; empty for a token that is missing or not accepted. */
    interface Users {
        Optional<Caller> of(Optional<String> token);
    }

    /**
     * Every rule on the table {@code table} in the namespace {@code levels} of the warehouse (a catalog, or
     * {@code catalog@ref}), when the user holds something on it; empty when it is no table they hold anything on.
     */
    interface Rules {
        Optional<List<RowColumnRule>> on(String warehouse, List<String> levels, String table, Caller user);
    }

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final Map<String, String> catalogs;
    private final Users users;
    private final Rules rules;

    /** @param catalogs each Trino catalog with rules, lowercased, to the warehouse it reads */
    OpaAnswers(Map<String, String> catalogs, Users users, Rules rules) {
        Map<String, String> lowered = new HashMap<>();
        catalogs.forEach((catalog, warehouse) -> lowered.put(catalog.toLowerCase(Locale.ROOT), warehouse));
        this.catalogs = Map.copyOf(lowered);
        this.users = users;
        this.rules = rules;
    }

    /** Every other question Trino asks: nuna's own routes decide what a user may reach, so OPA allows it. */
    String allow() {
        return "{\"result\":true}";
    }

    /** {@code GetRowFilters}: the user's filter on the table, or none. */
    String rowFilters(JsonNode body) {
        JsonNode input = body.path("input");
        JsonNode table = input.path("action").path("resource").path("table");
        Answer answer = answer(input, new Named(text(table, "catalogName"), text(table, "schemaName"),
                text(table, "tableName")));
        ArrayNode result = JSON.createArrayNode();
        answer.rowFilter().ifPresent(filter -> result.addObject().put("expression", filter));
        return wrap(result);
    }

    /**
     * {@code GetColumnMask}, batched: the mask of each column asked for that has one, by its index in the ask; every
     * column of a table whose masked columns the ask does not all name, {@code NULL}.
     */
    String columnMasks(JsonNode body) {
        JsonNode input = body.path("input");
        JsonNode asked = input.path("action").path("filterResources");
        Map<Named, Set<String>> columns = new HashMap<>();
        for (int index = 0; index < asked.size(); index++) {
            JsonNode column = asked.get(index).path("column");
            Optional.ofNullable(text(column, "columnName")).ifPresent(name -> columns
                    .computeIfAbsent(named(column), table -> new HashSet<>()).add(name.toLowerCase(Locale.ROOT)));
        }
        Map<Named, Answer> answers = new HashMap<>();
        ArrayNode result = JSON.createArrayNode();
        for (int index = 0; index < asked.size(); index++) {
            JsonNode column = asked.get(index).path("column");
            Optional<String> mask = answers.computeIfAbsent(named(column), table -> answer(input, table)
                            .within(columns.getOrDefault(table, Set.of())))
                    .mask().apply(text(column, "columnName"));
            if (mask.isPresent()) {
                ObjectNode entry = result.addObject();
                entry.put("index", index);
                entry.putObject("viewExpression").put("expression", mask.get());
            }
        }
        return wrap(result);
    }

    private Answer answer(JsonNode input, Named table) {
        String warehouse = catalogs.get(table.catalog().toLowerCase(Locale.ROOT));
        if (warehouse == null) {
            return Answer.NONE;
        }
        Optional<String> token = Optional.ofNullable(text(input.path("context").path("identity")
                .path("extraCredentials"), "token")).filter(t -> !t.isBlank());
        Optional<Caller> user = users.of(token);
        if (user.isEmpty() || table.schema() == null || table.table() == null) {
            return Answer.CLOSED;
        }
        return rules.on(warehouse, List.of(table.schema().split("\\.", -1)), table.table(), user.get())
                .map(found -> Answer.of(RowColumnPolicy.forUser(found, user.get().subject().groups())))
                .orElse(Answer.CLOSED);
    }

    private static Named named(JsonNode column) {
        return new Named(text(column, "catalogName"), text(column, "schemaName"), text(column, "tableName"));
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isString() ? value.asString() : null;
    }

    private static String wrap(ArrayNode result) {
        ObjectNode body = JSON.createObjectNode();
        body.set("result", result);
        return JSON.writeValueAsString(body);
    }

    /** A table as Trino names it: its catalog, its schema (namespace levels joined by dots) and its name. */
    private record Named(String catalog, String schema, String table) {
    }

    /** The filter on a table and each column's mask, by name in any case; {@code masked}, those names lowercased. */
    private record Answer(Optional<String> rowFilter, Function<String, Optional<String>> mask, Set<String> masked) {

        static final Answer NONE = new Answer(Optional.empty(), column -> Optional.empty(), Set.of());
        static final Answer CLOSED = new Answer(Optional.of(RowColumnPolicy.NO_ROWS),
                column -> Optional.of(RowColumnPolicy.NO_VALUE), Set.of());

        static Answer of(RowColumnPolicy.Applied applied) {
            Map<String, String> masks = new HashMap<>();
            applied.columnMasks().forEach((column, mask) -> masks.put(column.toLowerCase(Locale.ROOT), mask));
            return new Answer(applied.rowFilter(),
                    column -> column == null ? Optional.empty()
                            : Optional.ofNullable(masks.get(column.toLowerCase(Locale.ROOT))),
                    Set.copyOf(masks.keySet()));
        }

        /** This answer when {@code columns}, lowercased, name every masked column; otherwise every column NULL. */
        Answer within(Set<String> columns) {
            return columns.containsAll(masked) ? this : CLOSED;
        }
    }
}
