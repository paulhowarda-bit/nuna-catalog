package keel.catalog.objectstore;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The overlap index of D21 at its root: no allowed prefix of one storage profile may equal, contain or lie
 * within an allowed prefix of another, so each location belongs to at most one profile, and so to one
 * catalog (D28). Within a profile every table lives at {@code <table root>/<table id>} (see
 * {@link StorageRules}), so no two tables' locations overlap either.
 */
public final class ProfileOverlap {

    private ProfileOverlap() {
    }

    /**
     * @param profiles every profile the deployment knows, by name
     * @throws IllegalArgumentException naming the first pair of overlapping prefixes
     */
    public static void requireDisjoint(Map<String, StorageProfile> profiles) {
        List<Map.Entry<String, ObjectLocation>> prefixes = new ArrayList<>();
        profiles.forEach((name, profile) -> profile.allowedPrefixes()
                .forEach(prefix -> prefixes.add(Map.entry(name, prefix))));
        for (int i = 0; i < prefixes.size(); i++) {
            for (int j = i + 1; j < prefixes.size(); j++) {
                Map.Entry<String, ObjectLocation> a = prefixes.get(i);
                Map.Entry<String, ObjectLocation> b = prefixes.get(j);
                if (!a.getKey().equals(b.getKey()) && overlap(a.getValue(), b.getValue())) {
                    throw new IllegalArgumentException("storage profiles " + a.getKey() + " and " + b.getKey()
                            + " overlap: " + a.getValue() + " and " + b.getValue());
                }
            }
        }
    }

    /** The profile one of whose allowed prefixes {@code location} lies beneath, if any. */
    public static Optional<String> owner(Map<String, StorageProfile> profiles, ObjectLocation location) {
        return profiles.entrySet().stream()
                .filter(entry -> entry.getValue().allowedPrefixes().stream().anyMatch(location::isBeneath))
                .map(Map.Entry::getKey)
                .findFirst();
    }

    static boolean overlap(ObjectLocation a, ObjectLocation b) {
        return a.equals(b) || a.isBeneath(b) || b.isBeneath(a);
    }
}
