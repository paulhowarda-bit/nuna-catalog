package keel.catalog.store;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Namespaces (build-plan 2.3), nested to any depth (D28). Names are unique per parent by their
 * normalized form (storage-design 9 d). Every write is one atomic unit with its {@link Change},
 * throws {@link CommitOutcomeUnknownException} when its COMMIT's outcome is unknown and
 * {@link StoreException} when it failed cleanly.
 */
public interface NamespaceStore {

    /**
     * Creates {@code namespace} (version 1) under its parent.
     *
     * @return {@code Applied}, {@code NameTaken} (a sibling holds the normalized name), or
     *         {@code Missing(NAMESPACE)} / {@code Missing(CATALOG)} when the parent does not exist
     */
    WriteResult createNamespace(NamespaceRecord namespace, Change change);

    /** Finds a namespace by its path of normalized names from the catalog down. */
    Optional<NamespaceRecord> findNamespace(UUID catalogId, List<String> normalizedPath);

    Optional<NamespaceRecord> readNamespace(UUID namespaceId);

    /** The namespaces directly under {@code parentId} (a namespace or a catalog id), by normalized name. */
    Page<NamespaceRecord> listNamespaces(UUID parentId, PageRequest page);

    /**
     * Replaces the properties if the namespace is at {@code expectedVersion}, and advances it by 1.
     *
     * @return {@code Applied}, {@code VersionMismatch}, or {@code Missing(NAMESPACE)}
     */
    WriteResult updateNamespaceProperties(UUID namespaceId, long expectedVersion, Map<String, String> properties,
                                          Change change);

    /**
     * Deletes an empty namespace, with its properties and the grants on it. Dropped objects do not
     * count as content; they keep its id and are undropped into a namespace the caller names.
     *
     * @return {@code Applied}, {@code Missing(NAMESPACE)}, {@code Refused(NOT_EMPTY)} (child
     *         namespaces or active objects), or {@code Refused(HELD)}
     */
    WriteResult dropNamespace(UUID namespaceId, Change change);
}
