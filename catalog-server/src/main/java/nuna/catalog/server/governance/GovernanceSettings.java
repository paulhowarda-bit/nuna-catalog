package nuna.catalog.server.governance;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Governance settings ({@code nuna.governance.*}): how fast purges and audit-trail reads run, the retention
 * classes the governance tool may push, and the maintenance policy's defaults and guardrails (D24, D25, D30).
 *
 * @param purgesPerMinute       purges this server accepts per minute, from all maintenance principals together
 * @param auditReadsPerMinute   audit-trail reads ({@code GET .../audit}) this server accepts per minute from one
 *                              caller: the principal its token names, or the end user a trusted engine acts for.
 *                              Each read opens a Kafka consumer. The console reads once each time an object's
 *                              Audit tab opens, its count changes or Refresh is pressed, so the default, one a
 *                              second, never holds a person back; it holds a script to that pace (backlog 19)
 * @param retentionClasses      the retention classes by name; a push of any other name is refused
 * @param pitrWindow            the database's point-in-time-recovery window: orphan removal only ever
 *                              touches files older than this, so a restore never points at a removed file
 * @param minimumSnapshotAge    no policy expires snapshots younger than this
 * @param maintenanceDefaults   the policy every catalog starts from, in the keys of {@link MaintenancePolicy}
 */
@ConfigurationProperties("nuna.governance")
public record GovernanceSettings(
        @DefaultValue("10") int purgesPerMinute,
        @DefaultValue("60") int auditReadsPerMinute,
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
        if (auditReadsPerMinute < 1) {
            throw new IllegalArgumentException("nuna.governance.audit-reads-per-minute must be at least 1");
        }
    }

    /** The minimum snapshot age of a class; empty for an unknown class or one without a minimum. */
    Optional<Duration> minimumAgeOf(String retentionClass) {
        return Optional.ofNullable(retentionClasses.get(retentionClass)).map(RetentionClass::minimumSnapshotAge);
    }
}
