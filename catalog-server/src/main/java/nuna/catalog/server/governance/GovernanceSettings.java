package nuna.catalog.server.governance;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Governance settings ({@code nuna.governance.*}): how fast purges run, the retention classes
 * the governance tool may push, and the maintenance policy's defaults and guardrails (D24, D25, D30).
 *
 * @param purgesPerMinute       purges this server accepts per minute, from all maintenance principals together
 * @param retentionClasses      the retention classes by name; a push of any other name is refused
 * @param pitrWindow            the database's point-in-time-recovery window: orphan removal only ever
 *                              touches files older than this, so a restore never points at a removed file
 * @param minimumSnapshotAge    no policy expires snapshots younger than this
 * @param maintenanceDefaults   the policy every catalog starts from, in the keys of {@link MaintenancePolicy}
 */
@ConfigurationProperties("nuna.governance")
public record GovernanceSettings(
        @DefaultValue("10") int purgesPerMinute,
        @DefaultValue Map<String, RetentionClass> retentionClasses,
        @DefaultValue("7d") Duration pitrWindow,
        @DefaultValue("1h") Duration minimumSnapshotAge,
        @DefaultValue Map<String, String> maintenanceDefaults) {

    /**
     * One retention class (D24).
     *
     * @param minimumSnapshotAge snapshots younger than this cannot be removed from a table of the class,
     *                           at commit time or by maintenance; none when unset
     */
    public record RetentionClass(Duration minimumSnapshotAge) {
    }

    public GovernanceSettings {
        if (purgesPerMinute < 1) {
            throw new IllegalArgumentException("nuna.governance.purges-per-minute must be at least 1");
        }
    }

    /** The minimum snapshot age of a class; empty for an unknown class or one without a minimum. */
    Optional<Duration> minimumAgeOf(String retentionClass) {
        return Optional.ofNullable(retentionClasses.get(retentionClass)).map(RetentionClass::minimumSnapshotAge);
    }
}
