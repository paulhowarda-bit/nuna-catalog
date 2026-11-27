package nuna.catalog.security;

import static org.assertj.core.api.Assertions.assertThat;

import nuna.catalog.security.RuleColumns.Checked;
import nuna.catalog.store.RowColumnRule;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** A rule's columns against its table's schema (D20, backlog 240): when it is set, and when the schema changes. */
class RuleColumnsTest {

    private static final UUID CATALOG = UUID.randomUUID();
    private static final UUID TABLE = UUID.randomUUID();
    /** The positions table's columns, as the demo's lake writer makes them. */
    private static final List<String> POSITIONS = List.of("desk", "Trader", "symbol", "quantity", "pnl");

    private static RowColumnRule rule(String group, String filter, Map<String, String> masks) {
        return RowColumnRule.of(CATALOG, TABLE, group, Optional.ofNullable(filter), masks);
    }

    private static Checked check(String filter, Map<String, String> masks) {
        return RuleColumns.check(POSITIONS, Optional.ofNullable(filter), masks);
    }

    @Test
    void aMaskOnAColumnTheSchemaLacksIsRefusedNamingIt() {
        assertThat(check(null, Map.of("pnl", "NULL", "tradr", "'***'"))).isEqualTo(new Checked.Unfit(
                "column-masks names tradr, which the table's current schema does not have; a mask names a column of"
                        + " the table as it is now"));
    }

    @Test
    void aMaskIsKeptUnderTheSchemasSpellingOfItsColumn() {
        assertThat(check(null, Map.of("trader", "'***'", "PNL", "NULL")))
                .isEqualTo(new Checked.Fits(new java.util.TreeMap<>(Map.of("Trader", "'***'", "pnl", "NULL"))));
    }

    @Test
    void aColumnMaskedTwiceInDifferentCaseIsRefused() {
        assertThat(check(null, Map.of("pnl", "NULL", "PnL", "0"))).isInstanceOf(Checked.Unfit.class);
    }

    @Test
    void aFilterOnAColumnTheSchemaLacksIsRefusedNamingIt() {
        assertThat(check("dsk = 'EQ' AND quantity > 0", Map.of())).isEqualTo(new Checked.Unfit(
                "row-filter names dsk, which the table's current schema does not have; a filter's columns are the"
                        + " table's as it is now"));
        assertThat(check("\"Desk\" = 'EQ' OR \"book\" = 'x'", Map.of())).isInstanceOf(Checked.Unfit.class);
    }

    @Test
    void aFiltersKeywordsFunctionsLiteralsSubqueriesAndLambdasNameNoColumn() {
        for (String filter : List.of(
                "true",
                "desk = 'EQ' -- the equity desk's rows only, not book",
                "/* book */ desk IN ('EQ', 'FX') AND quantity BETWEEN 1 AND 100",
                "desk IN (SELECT desk FROM entitlements WHERE user_name = current_user)",
                "lower(trader) LIKE 'a%' ESCAPE '\\' AND symbol IS NOT NULL",
                "CAST(quantity AS varchar) <> '0' AND TRY_CAST(pnl AS DECIMAL(14, 2)) > 0",
                "date_trunc('day', current_timestamp) > TIMESTAMP '2026-01-01 00:00:00' - INTERVAL '1' DAY",
                "any_match(split(symbol, '.'), part -> part = 'SAP')",
                "CASE WHEN desk = 'EQ' THEN true ELSE false END",
                "\"desk\" = 'it''s book' AND e.desk IS NULL",
                "EXISTS (SELECT 1 FROM entitlements e WHERE e.book = desk AND e.user_name = current_user)",
                "reduce(ARRAY[quantity], 0, (s, x) -> s + x, s -> s) > 0",
                "CAST(ROW(desk, 1) AS ROW(book varchar, n integer)).book = 'EQ'")) {
            assertThat(check(filter, Map.of())).as(filter).isInstanceOf(Checked.Fits.class);
        }
    }

    @Test
    void aFilterTheParserFindsColumnsInIsReadFromItsTree() {
        assertThat(RuleColumns.columnsReferenced(((RuleExpressions.Parsed.Parses) RuleExpressions.parse(
                "desk = 'EQ' AND lower(\"Trader\") IN (SELECT trader FROM book) AND any_match(ARRAY[pnl], p -> p > 0)"
                        + " AND CAST(quantity AS bigint) > 0", "row-filter")).expression()))
                .containsExactly("desk", "trader", "pnl", "quantity");
    }

    @Test
    void aFilterThatDoesNotParseIsUnfitSayingWhere() {
        assertThat(check("desk = 'EQ' AND", Map.of())).isInstanceOf(Checked.Unfit.class).extracting(
                unfit -> ((Checked.Unfit) unfit).message()).asString()
                .startsWith("row-filter is not a Trino SQL expression: at line 1, column 16, ");
    }

    @Test
    void aRenameOrDropOfAMaskedColumnIsRefusedNamingTheRule() {
        List<RowColumnRule> rules = List.of(rule("analysts", "desk = 'EQ'", Map.of("Trader", "'***'")),
                rule("stewards", null, Map.of("pnl", "NULL")));

        assertThat(RuleColumns.taken(POSITIONS, List.of("desk", "trader_name", "symbol", "quantity", "pnl"), rules))
                .contains("The change renames or drops a column a row filter or column mask names: group analysts's"
                        + " rule names trader. A rename would leave the column unmasked; the grant admin changes or"
                        + " removes the rule first");
        assertThat(RuleColumns.taken(POSITIONS, List.of("desk", "Trader", "symbol", "quantity"), rules)).get()
                .asString().contains("group stewards's rule names pnl").doesNotContain("analysts");
    }

    @Test
    void aRenameOrDropOfAColumnAFilterNamesIsRefused() {
        List<RowColumnRule> rules = List.of(rule("analysts",
                "symbol IN (SELECT symbol FROM watchlist WHERE desk = 'EQ')", Map.of()));

        assertThat(RuleColumns.taken(POSITIONS, List.of("Trader", "symbol", "quantity", "pnl"), rules)).get()
                .asString().contains("group analysts's rule names desk");
        assertThat(RuleColumns.taken(POSITIONS, List.of("desk", "Trader", "ticker", "quantity", "pnl"), rules))
                .isPresent();
    }

    @Test
    void aChangeThatKeepsEveryNamedColumnIsAllowed() {
        List<RowColumnRule> rules = List.of(rule("analysts", "desk = 'EQ'", Map.of("Trader", "'***'")));

        assertThat(RuleColumns.taken(POSITIONS, List.of("desk", "trader", "symbol", "pnl", "book"), rules))
                .as("quantity, which no rule names, dropped; Trader renamed only in case").isEmpty();
        assertThat(RuleColumns.taken(POSITIONS, List.of("desk"), List.of())).as("no rules").isEmpty();
    }
}
