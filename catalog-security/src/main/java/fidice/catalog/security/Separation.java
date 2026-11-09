package fidice.catalog.security;

import java.util.Collection;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

/**
 * Separation of duties (D18), checked whenever a grant is made. Grants go to groups (D19), so a duty
 * belongs to a group, and one group may not hold two conflicting duties anywhere in one catalog:
 * <ul>
 *   <li><b>grant admin</b> ({@code manage-grants}) holds nothing else: no data access, no tagging;</li>
 *   <li><b>tag steward</b> ({@code manage-tags}) may read but not change data or definitions, since
 *       classification decides access (D27);</li>
 *   <li><b>data/DDL admin</b> ({@code write}, {@code modify}, {@code create}) may read, not tag or grant;</li>
 *   <li><b>platform operator</b> groups, named in configuration, run the deployment (the trusted-engine
 *       registry) and may hold no grant at all;</li>
 *   <li><b>deployment grant admins</b>, named in configuration, hold {@code manage-grants} on every
 *       catalog, so they may hold no other grant either.</li>
 * </ul>
 * There is no superuser: no group or configuration gives every privilege.
 */
public final class Separation {

    /** The duties of D18; reading is no duty and goes with anything but grant administration. */
    enum Duty {
        READ, DATA, GRANTS, TAGS;

        static Duty of(Privilege privilege) {
            return switch (privilege) {
                case DESCRIBE, SELECT -> READ;
                case WRITE, MODIFY, CREATE -> DATA;
                case MANAGE_GRANTS -> GRANTS;
                case MANAGE_TAGS -> TAGS;
            };
        }

        boolean conflictsWith(Duty other) {
            if (this == other) {
                return false;
            }
            if (this == GRANTS || other == GRANTS) {
                return true;
            }
            return EnumSet.of(this, other).equals(EnumSet.of(DATA, TAGS));
        }
    }

    private final Set<String> grantAdminGroups;
    private final Set<String> platformOperatorGroups;

    /**
     * @param grantAdminGroups       groups that hold {@code manage-grants} on every catalog
     * @param platformOperatorGroups groups that operate the deployment and hold no data access
     */
    public Separation(Set<String> grantAdminGroups, Set<String> platformOperatorGroups) {
        this.grantAdminGroups = Set.copyOf(grantAdminGroups);
        this.platformOperatorGroups = Set.copyOf(platformOperatorGroups);
        Set<String> both = new java.util.HashSet<>(this.grantAdminGroups);
        both.retainAll(this.platformOperatorGroups);
        if (!both.isEmpty()) {
            throw new IllegalArgumentException("groups " + both + " are both grant admins and platform operators");
        }
    }

    public Set<String> grantAdminGroups() {
        return grantAdminGroups;
    }

    public Set<String> platformOperatorGroups() {
        return platformOperatorGroups;
    }

    /**
     * Why {@code group} may not be granted {@code privilege}, given every privilege it already holds
     * anywhere in the same catalog; empty when it may.
     */
    public Optional<String> refusal(String group, Privilege privilege, Collection<Privilege> heldInCatalog) {
        if (platformOperatorGroups.contains(group)) {
            return Optional.of("group " + group + " operates the platform and may hold no grant");
        }
        if (grantAdminGroups.contains(group)) {
            return Optional.of("group " + group + " administers grants on every catalog and may hold no other grant");
        }
        Duty wanted = Duty.of(privilege);
        return heldInCatalog.stream()
                .filter(held -> Duty.of(held).conflictsWith(wanted))
                .findFirst()
                .map(held -> "group " + group + " holds " + held.wireName() + " in this catalog, a "
                        + Duty.of(held).name().toLowerCase(java.util.Locale.ROOT) + " duty that "
                        + privilege.wireName() + " (" + wanted.name().toLowerCase(java.util.Locale.ROOT)
                        + ") must stay separate from");
    }
}
