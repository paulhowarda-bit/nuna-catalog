package fidice.catalog.store;

import java.util.Objects;
import java.util.UUID;

/**
 * Identifies one pointer: an object (table or view) on one branch. Every v1 commit is on
 * {@link #MAIN}; branches in v1.x add rows, never a migration (D33).
 *
 * <p>Keys order by object id as an unsigned 128-bit number (the order of the canonical text
 * form, and PostgreSQL's), then by branch. Every backend applies swaps in this order (D15).
 */
public record PointerKey(UUID objectId, String branch) implements Comparable<PointerKey> {

    public static final String MAIN = "main";

    public PointerKey {
        Objects.requireNonNull(objectId, "objectId");
        if (branch == null || branch.isBlank()) {
            throw new IllegalArgumentException("branch must not be blank");
        }
    }

    @Override
    public int compareTo(PointerKey other) {
        int order = Long.compareUnsigned(objectId.getMostSignificantBits(), other.objectId.getMostSignificantBits());
        if (order == 0) {
            order = Long.compareUnsigned(objectId.getLeastSignificantBits(), other.objectId.getLeastSignificantBits());
        }
        return order != 0 ? order : branch.compareTo(other.branch);
    }
}
