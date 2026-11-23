package nuna.catalog.core.commit;

import nuna.catalog.core.commit.CommitRule.Rejection;
import nuna.catalog.iceberg.Facts;
import nuna.catalog.iceberg.Facts.TableFacts;
import nuna.catalog.store.GovernanceAttribute;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * The enterprise policy of D11: approved locations, property policy, an immutable encryption key id,
 * the retention-hold guard and the clock-skew guard; and the governance guards of M7: the DR freeze
 * (D37) and the minimum snapshot age, the deployment's and a retention class's (D24, D30). Correctness backstops that need Iceberg's own
 * types live in catalog-iceberg and run inside the builder step.
 */
public final class Rules {

    /** Table properties that send a client's files somewhere; each must stay inside the table's location. */
    static final List<String> PATH_PROPERTIES = List.of(
            "write.data.path", "write.metadata.path", "write.object-storage.path", "write.folder-storage.path");

    static final String ENCRYPTION_KEY_ID = "encryption.key-id";

    private Rules() {
    }

    /** Every rule that needs no deployment settings, in the order they are judged. */
    public static List<CommitRule> standard(TableFiles files, Duration clockSkew) {
        return List.of(
                Rules::drFreeze,
                Rules::assignedLocation,
                change -> pathProperties(change, files),
                Rules::encryptionKeyId,
                Rules::retentionHold,
                change -> clockSkew(change, clockSkew));
    }

    /** The table or view stays where the catalog put it (D28); SetLocation elsewhere is refused. */
    static Optional<Rejection> assignedLocation(CommitRule.Change change) {
        String root = change.object().locationRoot();
        if (!root.equals(change.after().location())) {
            return Optional.of(Rejection.badRequest("Location is assigned by the catalog: " + root
                    + ", not " + change.after().location()));
        }
        return Optional.empty();
    }

    /** Write-path properties set or changed by the change point inside the table's location (D21). */
    static Optional<Rejection> pathProperties(CommitRule.Change change, TableFiles files) {
        Map<String, String> before = properties(change.before());
        String root = change.object().locationRoot();
        for (String key : PATH_PROPERTIES) {
            String value = change.after().properties().get(key);
            if (value != null && !value.equals(before.get(key)) && !files.isWithin(value, root)) {
                return Optional.of(Rejection.badRequest("Property " + key + " must lie within the table location "
                        + root + ", not " + value));
            }
        }
        return Optional.empty();
    }

    /** encryption.key-id is fixed when the table is created (D26). */
    static Optional<Rejection> encryptionKeyId(CommitRule.Change change) {
        if (change.before() != null && !Objects.equals(change.before().properties().get(ENCRYPTION_KEY_ID),
                change.after().properties().get(ENCRYPTION_KEY_ID))) {
            return Optional.of(Rejection.badRequest("Property " + ENCRYPTION_KEY_ID
                    + " is fixed when the table is created"));
        }
        return Optional.empty();
    }

    /** A held table keeps every snapshot (D24). */
    static Optional<Rejection> retentionHold(CommitRule.Change change) {
        if (change.before() instanceof TableFacts before && change.after() instanceof TableFacts after
                && !after.snapshotTimestamps().keySet().containsAll(before.snapshotTimestamps().keySet())
                && change.governance().held()) {
            return Optional.of(Rejection.refused("legal_hold", "Table " + change.object().id()
                    + " is under legal hold: its snapshots cannot be removed"));
        }
        return Optional.empty();
    }

    /** Nothing commits to a table or view while it, a namespace above it or its catalog is frozen for a DR reconcile (D37). */
    static Optional<Rejection> drFreeze(CommitRule.Change change) {
        return change.governance().nearest(GovernanceAttribute.Kind.DR_FREEZE)
                .map(freeze -> Rejection.refused("dr_freeze", "Writes to " + change.object().id()
                        + " are frozen for DR reconcile: " + freeze.value()));
    }

    /**
     * A snapshot younger than the deployment's minimum snapshot age, or than its table's retention class allows,
     * may not be removed (D24, D30): the guardrail holds whoever expires snapshots, the maintenance worker or an
     * engine. The larger of the two minimums applies; governance is read only when a commit removes a snapshot
     * the deployment's minimum alone lets go.
     *
     * @param deploymentMinimum the minimum age of every snapshot ({@code nuna.governance.minimum-snapshot-age})
     * @param classMinimum      the minimum snapshot age of a retention class, empty for a class with none
     */
    public static CommitRule snapshotMinimumAge(Duration deploymentMinimum,
                                                Function<String, Optional<Duration>> classMinimum) {
        return change -> {
            if (!(change.before() instanceof TableFacts before) || !(change.after() instanceof TableFacts after)
                    || after.snapshotTimestamps().keySet().containsAll(before.snapshotTimestamps().keySet())) {
                return Optional.empty();
            }
            Optional<Rejection> deployment = youngestRemoved(change, before, after, deploymentMinimum,
                    "the deployment's minimum snapshot age");
            if (deployment.isPresent()) {
                return deployment;
            }
            Optional<GovernanceAttribute> retentionClass = change.governance()
                    .nearest(GovernanceAttribute.Kind.RETENTION_CLASS);
            Optional<Duration> minimum = retentionClass.flatMap(c -> classMinimum.apply(c.value()));
            if (minimum.isEmpty() || minimum.get().compareTo(deploymentMinimum) <= 0) {
                return Optional.empty();
            }
            return youngestRemoved(change, before, after, minimum.get(),
                    "the minimum age of retention class " + retentionClass.get().value());
        };
    }

    private static Optional<Rejection> youngestRemoved(CommitRule.Change change, TableFacts before, TableFacts after,
                                                       Duration minimum, String whose) {
        Instant youngestRemovable = change.now().minus(minimum);
        for (Map.Entry<Long, Long> snapshot : before.snapshotTimestamps().entrySet()) {
            if (!after.snapshotTimestamps().containsKey(snapshot.getKey())
                    && Instant.ofEpochMilli(snapshot.getValue()).isAfter(youngestRemovable)) {
                return Optional.of(Rejection.forbidden("Snapshot " + snapshot.getKey() + " of " + change.object().id()
                        + " is younger than " + minimum + ", " + whose + ": it cannot be removed yet"));
            }
        }
        return Optional.empty();
    }

    /** A new snapshot may not be stamped further ahead of the catalog's clock than the skew allows (D9). */
    static Optional<Rejection> clockSkew(CommitRule.Change change, Duration skew) {
        if (!(change.after() instanceof TableFacts after)) {
            return Optional.empty();
        }
        Map<Long, Long> before = change.before() instanceof TableFacts b ? b.snapshotTimestamps() : Map.of();
        Instant latest = change.now().plus(skew);
        for (Map.Entry<Long, Long> snapshot : after.snapshotTimestamps().entrySet()) {
            if (!before.containsKey(snapshot.getKey()) && Instant.ofEpochMilli(snapshot.getValue()).isAfter(latest)) {
                return Optional.of(Rejection.badRequest("Snapshot " + snapshot.getKey() + " is stamped "
                        + Instant.ofEpochMilli(snapshot.getValue()) + ", more than " + skew
                        + " ahead of the catalog clock"));
            }
        }
        return Optional.empty();
    }

    private static Map<String, String> properties(Facts facts) {
        return facts == null ? Map.of() : facts.properties();
    }
}
