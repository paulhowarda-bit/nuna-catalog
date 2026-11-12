package keel.catalog.server.governance;

import static keel.catalog.server.governance.MaintenancePolicy.COMPACTION_ENABLED;
import static keel.catalog.server.governance.MaintenancePolicy.EXPIRY_ENABLED;
import static keel.catalog.server.governance.MaintenancePolicy.EXPIRY_MAX_AGE;
import static keel.catalog.server.governance.MaintenancePolicy.ORPHAN_ENABLED;
import static keel.catalog.server.governance.MaintenancePolicy.ORPHAN_OLDER_THAN;
import static keel.catalog.server.governance.MaintenancePolicy.REWRITE_MIN_MANIFESTS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import keel.catalog.core.CatalogError;
import java.time.Duration;
import java.util.AbstractMap.SimpleEntry;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MaintenancePolicyTest {

    private static final GovernanceSettings SETTINGS = new GovernanceSettings(10, Map.of(),
            Duration.ofDays(7), Duration.ofHours(1), Map.of(EXPIRY_ENABLED, "true", ORPHAN_ENABLED, "true"));

    @Test
    void canonicalFormChecksKeysTypesAndFloors() {
        assertThat(MaintenancePolicy.canonical(Map.of(EXPIRY_MAX_AGE, "P2D", REWRITE_MIN_MANIFESTS, "050",
                COMPACTION_ENABLED, "true"), SETTINGS))
                .containsExactly(Map.entry(COMPACTION_ENABLED, "true"), Map.entry(REWRITE_MIN_MANIFESTS, "50"),
                        Map.entry(EXPIRY_MAX_AGE, "PT48H"));
        assertRefused(Map.of("vacuum", "true"));
        assertRefused(Map.of(COMPACTION_ENABLED, "yes"));
        assertRefused(Map.of(EXPIRY_MAX_AGE, "-P1D"));
        assertRefused(Map.of(EXPIRY_MAX_AGE, "PT30M"));
        assertRefused(Map.of(ORPHAN_OLDER_THAN, "P7D"));
        assertRefused(Map.of(REWRITE_MIN_MANIFESTS, "0"));
    }

    @Test
    void levelsOverrideKeyByKeyFromOutermost() {
        Map<String, MaintenancePolicy.Setting> resolved = MaintenancePolicy.resolve(SETTINGS, List.of(
                new SimpleEntry<>("catalog:c", Map.of(EXPIRY_MAX_AGE, "P10D", COMPACTION_ENABLED, "true")),
                new SimpleEntry<>("table:t", Map.of(EXPIRY_MAX_AGE, "P3D"))), false, false, null);

        assertThat(resolved.get(EXPIRY_MAX_AGE)).isEqualTo(new MaintenancePolicy.Setting("P3D", "table:t", null));
        assertThat(resolved.get(COMPACTION_ENABLED).source()).isEqualTo("catalog:c");
        assertThat(resolved.get(EXPIRY_ENABLED).source()).isEqualTo("default");
        assertThat(resolved.get(REWRITE_MIN_MANIFESTS).source()).isEqualTo("built-in");
    }

    @Test
    void guardrailsWin() {
        Map<String, MaintenancePolicy.Setting> held = MaintenancePolicy.resolve(SETTINGS, List.of(
                new SimpleEntry<>("table:t", Map.of(EXPIRY_MAX_AGE, "P1D", ORPHAN_OLDER_THAN, "P1D"))),
                true, false, Duration.ofDays(30));
        assertThat(held.get(EXPIRY_MAX_AGE).value()).isEqualTo("PT720H");
        assertThat(held.get(EXPIRY_MAX_AGE).reason()).contains("retention class");
        assertThat(held.get(ORPHAN_OLDER_THAN).value()).isEqualTo("PT192H");
        assertThat(held.get(EXPIRY_ENABLED).value()).isEqualTo("false");
        assertThat(held.get(ORPHAN_ENABLED).value()).isEqualTo("false");
        assertThat(held.get(COMPACTION_ENABLED).source()).isEqualTo("built-in");

        Map<String, MaintenancePolicy.Setting> frozen = MaintenancePolicy.resolve(SETTINGS, List.of(
                new SimpleEntry<>("table:t", Map.of(COMPACTION_ENABLED, "true"))), false, true, null);
        assertThat(frozen.get(COMPACTION_ENABLED)).isEqualTo(
                new MaintenancePolicy.Setting("false", "guardrail", "frozen for DR reconcile"));
        assertThat(frozen.get(EXPIRY_ENABLED).value()).isEqualTo("false");
    }

    private static void assertRefused(Map<String, String> policy) {
        assertThatThrownBy(() -> MaintenancePolicy.canonical(policy, SETTINGS))
                .isInstanceOfSatisfying(CatalogError.class, e -> assertThat(e.status()).isEqualTo(400));
    }
}
