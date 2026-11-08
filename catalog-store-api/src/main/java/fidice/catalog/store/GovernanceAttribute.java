package fidice.catalog.store;

import java.time.Instant;
import java.util.Objects;

/**
 * An enforcement copy of one governance fact about a securable, with where it came from (D27):
 * its retention class (D24), classification, owner, regulated flag, or row/column policy flag
 * (D20). One value per (target, kind).
 */
public record GovernanceAttribute(Securable target, Kind kind, String value, String provenance,
                                  String setBy, Instant setAt) {

    public enum Kind { RETENTION_CLASS, CLASSIFICATION, OWNER, REGULATED, ROW_COLUMN_POLICY }

    public GovernanceAttribute {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(provenance, "provenance");
        Objects.requireNonNull(setBy, "setBy");
        Objects.requireNonNull(setAt, "setAt");
    }
}
