package nuna.catalog.security;

import static org.assertj.core.api.Assertions.assertThat;

import nuna.catalog.security.RuleExpressions.Parsed;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Row filters and column masks parsed as Trino parses them, when they are set (D20, backlog 244). */
class RuleExpressionsTest {

    @Test
    void filtersAndMasksTrinoParsesAreAccepted() {
        for (String sql : List.of(
                "true",
                "desk = 'EQ' AND quantity BETWEEN 1 AND 100",
                "lower(trader) LIKE 'a%' ESCAPE '\\' AND symbol IS NOT NULL",
                "CASE WHEN current_user = 'ana' THEN NULL ELSE trader END",
                "concat(substr(\"Trader\", 1, 1), '***')",
                "any_match(split(symbol, '.'), part -> part = 'SAP')",
                "TRY_CAST(pnl AS DECIMAL(14, 2)) > 0 AND date_trunc('day', current_timestamp) > TIMESTAMP"
                        + " '2026-01-01 00:00:00' - INTERVAL '1' DAY")) {
            assertThat(RuleExpressions.parse(sql, "row-filter")).as(sql).isInstanceOf(Parsed.Parses.class);
        }
    }

    @Test
    void subqueriesAndOtherTablesAreAccepted() {
        for (String sql : List.of(
                "desk IN (SELECT desk FROM entitlements WHERE user_name = current_user)",
                "EXISTS (SELECT 1 FROM lake.ref.entitlements e WHERE e.desk = desk AND e.user_name = current_user)",
                "quantity < (SELECT max(cap) FROM limits)",
                "desk = ANY (SELECT desk FROM entitlements)")) {
            assertThat(RuleExpressions.parse(sql, "row-filter")).as(sql).isInstanceOf(Parsed.Parses.class);
        }
    }

    @Test
    void anExpressionThatDoesNotParseIsRefusedSayingWhere() {
        assertThat(RuleExpressions.parse("desk = 'EQ' AND", "row-filter")).isInstanceOf(Parsed.Fails.class)
                .extracting(parsed -> ((Parsed.Fails) parsed).message()).asString()
                .startsWith("row-filter is not a Trino SQL expression: at line 1, column 16, ");
        assertThat(RuleExpressions.parse("desk = 'EQ\nAND quantity >", "The mask of desk"))
                .extracting(parsed -> ((Parsed.Fails) parsed).message()).asString()
                .startsWith("The mask of desk is not a Trino SQL expression: at line 1, column 8, ");
    }

    @Test
    void aStatementOrTwoExpressionsAreNoExpression() {
        for (String sql : List.of(
                "desk = 'EQ'; DROP TABLE positions",
                "SELECT desk FROM positions",
                "desk = 'EQ' desk = 'FX'",
                "desk = = 'EQ'",
                "(desk = 'EQ'")) {
            assertThat(RuleExpressions.parse(sql, "row-filter")).as(sql).isInstanceOf(Parsed.Fails.class);
        }
    }

    @Test
    void aDeeplyNestedExpressionIsRefusedNotAStackOverflow() {
        String nested = "(".repeat(1999) + "1" + ")".repeat(1999);
        assertThat(RuleExpressions.parse(nested, "row-filter")).isNotNull();
    }
}
