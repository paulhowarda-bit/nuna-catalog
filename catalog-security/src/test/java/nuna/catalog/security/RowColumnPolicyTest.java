package nuna.catalog.security;

import static org.assertj.core.api.Assertions.assertThat;

import nuna.catalog.store.RowColumnRule;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RowColumnPolicyTest {

    private static final UUID CATALOG = UUID.randomUUID();
    private static final UUID TABLE = UUID.randomUUID();

    private static RowColumnRule rule(String group, String filter, Map<String, String> masks) {
        return RowColumnRule.of(CATALOG, TABLE, group, Optional.ofNullable(filter), masks);
    }

    @Test
    void aTableWithNoRulesFiltersAndMasksNothing() {
        assertThat(RowColumnPolicy.forUser(List.of(), Set.of("traders"))).isEqualTo(RowColumnPolicy.Applied.NOTHING);
    }

    @Test
    void aUserNoneOfWhoseGroupsHasARuleGetsNoRows() {
        RowColumnPolicy.Applied applied = RowColumnPolicy.forUser(List.of(rule("traders", "desk = 'EQ'", Map.of())),
                Set.of("auditors"));

        assertThat(applied.rowFilter()).contains("false");
        assertThat(applied.columnMasks()).isEmpty();
    }

    @Test
    void oneRuleAppliesAsItIs() {
        RowColumnPolicy.Applied applied = RowColumnPolicy.forUser(List.of(
                rule("traders", "desk = 'EQ'", Map.of("ssn", "'***'")),
                rule("auditors", "true", Map.of())), Set.of("traders", "readers"));

        assertThat(applied.rowFilter()).contains("desk = 'EQ'");
        assertThat(applied.columnMasks()).isEqualTo(Map.of("ssn", "'***'"));
    }

    @Test
    void severalFiltersAreJoinedWithOrAndAFilterlessRuleAdmitsEveryRow() {
        List<RowColumnRule> rules = List.of(rule("traders", "desk = 'EQ'", Map.of()),
                rule("sales", "region = 'EU'", Map.of()), rule("auditors", null, Map.of("ssn", "NULL")));

        assertThat(RowColumnPolicy.forUser(rules, Set.of("traders", "sales")).rowFilter())
                .contains("(region = 'EU') OR (desk = 'EQ')");
        assertThat(RowColumnPolicy.forUser(rules, Set.of("traders", "auditors")).rowFilter()).isEmpty();
    }

    @Test
    void aColumnIsUnmaskedWhenAnyOfTheUsersRulesLeavesItSoAndNullWhenTheirMasksDiffer() {
        List<RowColumnRule> rules = List.of(
                rule("traders", "true", Map.of("ssn", "'***'", "salary", "NULL", "name", "upper(name)")),
                rule("sales", "true", Map.of("ssn", "'***'", "name", "lower(name)")));

        assertThat(RowColumnPolicy.forUser(rules, Set.of("traders", "sales")).columnMasks())
                .isEqualTo(Map.of("name", "NULL", "ssn", "'***'"));
    }
}
