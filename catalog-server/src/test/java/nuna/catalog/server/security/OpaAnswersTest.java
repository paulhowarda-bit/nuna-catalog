package nuna.catalog.server.security;

import static org.assertj.core.api.Assertions.assertThat;

import nuna.catalog.core.principal.Caller;
import nuna.catalog.core.principal.Principal;
import nuna.catalog.store.RowColumnRule;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** What nuna answers Trino's OPA access control (D20, backlog 115), apart from HTTP and the stores. */
class OpaAnswersTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final UUID CATALOG = UUID.randomUUID();
    private static final UUID TABLE = UUID.randomUUID();

    /** Two users, each known by their own token; any other token is not accepted. */
    private static final Map<String, Caller> USERS = Map.of(
            "ana-token", new Caller(new Principal("ana", Set.of("traders")), "opa", false, Optional.empty()),
            "sid-token", new Caller(new Principal("sid", Set.of("sales")), "opa", false, Optional.empty()));

    /** The rules on brokerage.trading.orders in warehouse lakehouse, or in its branch dev. */
    private static final List<RowColumnRule> ORDERS = List.of(
            RowColumnRule.of(CATALOG, TABLE, "traders", Optional.of("desk = 'EQ'"),
                    Map.of("Account_Id", "'***'", "price", "NULL")),
            RowColumnRule.of(CATALOG, TABLE, "auditors", Optional.of("true"), Map.of()));

    private final List<String> asked = new ArrayList<>();

    private final OpaAnswers answers = new OpaAnswers(Map.of("Lakehouse", "lakehouse", "lakehouse_dev", "lakehouse@dev"),
            token -> token.map(USERS::get),
            (warehouse, levels, table, user) -> {
                asked.add(warehouse + ":" + String.join("/", levels) + ":" + table);
                if (!levels.equals(List.of("brokerage", "trading"))) {
                    return Optional.empty();
                }
                return switch (table) {
                    case "orders" -> Optional.of(ORDERS);
                    case "fills" -> Optional.of(List.of());
                    default -> Optional.empty();
                };
            });

    @Test
    void allowingIsAlwaysYes() {
        assertThat(answers.allow()).isEqualTo("{\"result\":true}");
    }

    @Test
    void aUserGetsTheirGroupsFilter() {
        assertThat(answers.rowFilters(rowFilters("lakehouse", "brokerage.trading", "orders", "ana-token")))
                .isEqualTo("{\"result\":[{\"expression\":\"desk = 'EQ'\"}]}");
        assertThat(asked).containsExactly("lakehouse:brokerage/trading:orders");
    }

    @Test
    void aUserNoneOfWhoseGroupsHasARuleGetsNoRows() {
        assertThat(answers.rowFilters(rowFilters("lakehouse", "brokerage.trading", "orders", "sid-token")))
                .isEqualTo("{\"result\":[{\"expression\":\"false\"}]}");
    }

    @Test
    void aTableWithoutRulesAndACatalogNunaDoesNotServeGetNone() {
        assertThat(answers.rowFilters(rowFilters("lakehouse", "brokerage.trading", "fills", "sid-token")))
                .isEqualTo("{\"result\":[]}");
        assertThat(answers.rowFilters(rowFilters("postgres", "public", "orders", null)))
                .isEqualTo("{\"result\":[]}");
        assertThat(asked).containsExactly("lakehouse:brokerage/trading:fills");
    }

    @Test
    void aMissingOrUnacceptedTokenOrATableTheUserCannotSeeGetsNoRows() {
        String none = "{\"result\":[{\"expression\":\"false\"}]}";
        assertThat(answers.rowFilters(rowFilters("lakehouse", "brokerage.trading", "orders", null))).isEqualTo(none);
        assertThat(answers.rowFilters(rowFilters("lakehouse", "brokerage.trading", "orders", "forged")))
                .isEqualTo(none);
        assertThat(answers.rowFilters(rowFilters("lakehouse", "brokerage.trading", "secret", "ana-token")))
                .isEqualTo(none);
        assertThat(answers.rowFilters(rowFilters("lakehouse", "brokerage", "orders", "ana-token"))).isEqualTo(none);
    }

    @Test
    void aBranchCatalogAsksForItsWarehouseByTrinosCatalogInAnyCase() {
        assertThat(answers.rowFilters(rowFilters("LAKEHOUSE_DEV", "brokerage.trading", "orders", "ana-token")))
                .isEqualTo("{\"result\":[{\"expression\":\"desk = 'EQ'\"}]}");
        assertThat(asked).containsExactly("lakehouse@dev:brokerage/trading:orders");
    }

    @Test
    void columnMasksAnswerEachMaskedColumnByItsIndexInAnyCase() {
        JsonNode ask = columnMasks("ana-token", column("lakehouse", "orders", "id"),
                column("lakehouse", "orders", "account_id"), column("lakehouse", "orders", "price"),
                column("lakehouse", "fills", "price"), column("postgres", "orders", "price"));

        assertThat(answers.columnMasks(ask)).isEqualTo("{\"result\":["
                + "{\"index\":1,\"viewExpression\":{\"expression\":\"'***'\"}},"
                + "{\"index\":2,\"viewExpression\":{\"expression\":\"NULL\"}}]}");
        assertThat(asked).as("each table is resolved once").containsExactly("lakehouse:brokerage/trading:orders",
                "lakehouse:brokerage/trading:fills");
    }

    @Test
    void withoutAnAcceptedTokenEveryColumnOfAServedCatalogIsNull() {
        JsonNode ask = columnMasks("forged", column("lakehouse", "orders", "id"), column("postgres", "orders", "id"));

        assertThat(answers.columnMasks(ask))
                .isEqualTo("{\"result\":[{\"index\":0,\"viewExpression\":{\"expression\":\"NULL\"}}]}");
    }

    private static JsonNode rowFilters(String catalog, String schema, String table, String token) {
        return JSON.readTree("""
                {"input":{"context":{"identity":{"user":"anyone","groups":["auditors"],"extraCredentials":%s},
                  "softwareStack":{"trinoVersion":"484"}},
                 "action":{"operation":"GetRowFilters","resource":{"table":{"catalogName":"%s","schemaName":"%s",
                  "tableName":"%s"}}}}}""".formatted(credentials(token), catalog, schema, table));
    }

    private static String column(String catalog, String table, String column) {
        return """
                {"column":{"catalogName":"%s","schemaName":"brokerage.trading","tableName":"%s","columnName":"%s",
                 "columnType":"varchar"}}""".formatted(catalog, table, column);
    }

    private static JsonNode columnMasks(String token, String... columns) {
        return JSON.readTree("""
                {"input":{"context":{"identity":{"user":"anyone","groups":["auditors"],"extraCredentials":%s}},
                 "action":{"operation":"GetColumnMask","filterResources":[%s]}}}""".formatted(credentials(token),
                String.join(",", columns)));
    }

    private static String credentials(String token) {
        return token == null ? "{}" : "{\"token\":\"" + token + "\"}";
    }
}
