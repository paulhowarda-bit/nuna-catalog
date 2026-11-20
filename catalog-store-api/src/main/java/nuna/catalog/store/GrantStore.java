package nuna.catalog.store;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/** Grants to groups (D18, D19). Writes behave as in {@link NamespaceStore}. */
public interface GrantStore {

    /**
     * @return {@code Applied}, {@code Unchanged} (already granted), or {@code Missing} naming the
     *         securable's kind when it does not exist
     */
    WriteResult putGrant(Grant grant, String grantedBy, Change change);

    /**
     * Puts the grant as {@link #putGrant} does, but only if {@code admits} accepts the privileges, by wire name, that
     * the grant's group already holds anywhere in the grant's catalog (one entry per grant). The read and the write
     * are one unit: conditional puts in one catalog run one at a time, each reading what the one before it wrote, so
     * two puts made at once cannot both pass on a read that misses the other (D18's separation of duties, backlog
     * 13). {@code admits} is asked before anything is written, and again whenever the store runs the write again,
     * so its last answer is the one that counts; it must not touch the store.
     *
     * @return as {@link #putGrant}; {@code Missing(CATALOG)} when the catalog does not exist; or
     *         {@code Refused(NOT_ADMITTED)} when {@code admits} refused, having written nothing
     */
    WriteResult putGrantIf(Grant grant, String grantedBy, Change change, Predicate<List<String>> admits);

    /** @return {@code Applied} or {@code Missing(GRANT)} */
    WriteResult revokeGrant(Grant grant, Change change);

    /** The batch check: every grant on any of {@code securables} to any of {@code groups}, in one read. */
    List<GrantRecord> grantsFor(Set<Securable> securables, Set<String> groups);

    /** Grants to any of {@code groups} in the catalog, by group, securable id, privilege. */
    Page<GrantRecord> listGrantsOfGroups(UUID catalogId, Set<String> groups, PageRequest page);

    /** Grants on one securable, by group, privilege. */
    Page<GrantRecord> listGrantsOn(Securable securable, PageRequest page);
}
