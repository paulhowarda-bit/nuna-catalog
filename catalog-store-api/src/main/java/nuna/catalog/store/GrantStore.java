package nuna.catalog.store;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Grants to groups (D18, D19). Writes behave as in {@link NamespaceStore}. */
public interface GrantStore {

    /**
     * @return {@code Applied}, {@code Unchanged} (already granted), or {@code Missing} naming the
     *         securable's kind when it does not exist
     */
    WriteResult putGrant(Grant grant, String grantedBy, Change change);

    /** @return {@code Applied} or {@code Missing(GRANT)} */
    WriteResult revokeGrant(Grant grant, Change change);

    /** The batch check: every grant on any of {@code securables} to any of {@code groups}, in one read. */
    List<GrantRecord> grantsFor(Set<Securable> securables, Set<String> groups);

    /** Grants to any of {@code groups} in the catalog, by group, securable id, privilege. */
    Page<GrantRecord> listGrantsOfGroups(UUID catalogId, Set<String> groups, PageRequest page);

    /** Grants on one securable, by group, privilege. */
    Page<GrantRecord> listGrantsOn(Securable securable, PageRequest page);
}
