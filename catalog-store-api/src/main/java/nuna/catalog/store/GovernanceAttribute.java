package nuna.catalog.store;

import java.time.Instant;
import java.util.Objects;

/**
 * An enforcement copy of one governance fact about a securable, with where it came from (D27):
 * its retention class (D24), classification, owner, regulated flag, or row/column policy flag
 * (D20); and the catalog's own maintenance policy (D30) and DR freeze (D37). One value per
 * (target, kind). Backends store the kind by name, so a new kind needs no schema step.
 */
public record GovernanceAttribute(Securable target, Kind kind, String value, String provenance,
                                  String setBy, Instant setAt) {

    public enum Kind {
        RETENTION_CLASS, CLASSIFICATION, OWNER, REGULATED, ROW_COLUMN_POLICY,
        /** The maintenance policy set on this level, as canonical JSON (D30). */
        MAINTENANCE_POLICY,
        /** Cleanup and writes are frozen during a DR reconcile; the value is the reason (D37). */
        DR_FREEZE
    }

    public GovernanceAttribute {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(provenance, "provenance");
        Objects.requireNonNull(setBy, "setBy");
        Objects.requireNonNull(setAt, "setAt");
    }
}
