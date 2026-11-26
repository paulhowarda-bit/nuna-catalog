package nuna.catalog.server.governance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import nuna.catalog.store.Name;
import nuna.catalog.store.Ref;
import nuna.catalog.store.RefLimits;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/** P11's limits and expiry as settings (D33, item 26's choice 11). */
class RefSettingsTest {

    private static final Instant MADE = Instant.parse("2026-11-20T12:00:00Z");
    private static final Duration DAY = Duration.ofDays(1);
    private static final RefSettings P11 = new RefSettings(50, 1000, Duration.ofDays(30), Duration.ofDays(180),
            Duration.ofDays(7));

    @Test
    void theDefaultsAreP11s() {
        assertThat(P11.limits()).isEqualTo(RefLimits.P11);
    }

    @Test
    void anExpiryIsThirtyDaysAheadNeverPastTheWholeLife() {
        assertThat(P11.expiryAfter(MADE, MADE)).isEqualTo(MADE.plus(DAY.multipliedBy(30)));
        assertThat(P11.expiryAfter(MADE, MADE.plus(DAY.multipliedBy(100)))).isEqualTo(MADE.plus(DAY.multipliedBy(130)));
        assertThat(P11.expiryAfter(MADE, MADE.plus(DAY.multipliedBy(150)))).isEqualTo(MADE.plus(DAY.multipliedBy(180)));
        assertThat(P11.expiryAfter(MADE, MADE.plus(DAY.multipliedBy(179)))).isEqualTo(P11.lastExpiry(MADE));
    }

    @Test
    void aBranchIsExpiringWithinTheWarningOfItsExpiryUntilItExpires() {
        Ref branch = new Ref(UUID.randomUUID(), UUID.randomUUID(), new Name("dev", "dev"), Ref.Kind.BRANCH, MADE,
                "bea", Optional.empty(), Ref.MAIN, Optional.of(MADE.plus(DAY.multipliedBy(30))), Optional.empty());
        Ref tag = new Ref(UUID.randomUUID(), UUID.randomUUID(), new Name("v1", "v1"), Ref.Kind.TAG, MADE, "bea",
                Optional.empty(), Ref.MAIN, Optional.empty(), Optional.empty());

        assertThat(P11.expiring(branch, MADE.plus(DAY.multipliedBy(23)).minusMillis(1))).isFalse();
        assertThat(P11.expiring(branch, MADE.plus(DAY.multipliedBy(23)))).isTrue();
        assertThat(P11.expiring(branch, MADE.plus(DAY.multipliedBy(30)).minusMillis(1))).isTrue();
        assertThat(P11.expiring(branch, MADE.plus(DAY.multipliedBy(30)))).as("expired, not expiring").isFalse();
        assertThat(P11.expiring(tag, MADE.plus(DAY.multipliedBy(1000)))).isFalse();
    }

    @Test
    void aBranchChangesAtMostAHundredTablesUnlessConfigured() {
        assertThat(bind(Map.of()).maxMergeTables()).isEqualTo(100);
        assertThat(bind(Map.of("nuna.refs.max-merge-tables", "40")).maxMergeTables()).isEqualTo(40);
        assertThat(bind(Map.of("nuna.refs.max-branches", "7")).maxBranches()).isEqualTo(7);
        assertThat(P11.maxMergeTables()).isEqualTo(100);
        assertThatThrownBy(() -> new RefSettings(1, 10, DAY, DAY, DAY, 0)).hasMessageContaining("max-merge-tables");
    }

    private static RefSettings bind(Map<String, String> properties) {
        return new Binder(new MapConfigurationPropertySource(properties)).bindOrCreate("nuna.refs",
                RefSettings.class);
    }

    @Test
    void limitsAndDurationsMustMakeSense() {
        assertThatThrownBy(() -> new RefSettings(-1, 10, DAY, DAY, DAY)).hasMessageContaining("max-branches");
        assertThatThrownBy(() -> new RefSettings(1, 10, Duration.ZERO, DAY, DAY))
                .hasMessageContaining("branch-idle-expiry");
        assertThatThrownBy(() -> new RefSettings(1, 10, DAY, DAY.negated(), DAY))
                .hasMessageContaining("branch-max-life");
        assertThatThrownBy(() -> new RefSettings(1, 10, DAY, DAY, DAY.negated()))
                .hasMessageContaining("expiry-warning");
    }
}
