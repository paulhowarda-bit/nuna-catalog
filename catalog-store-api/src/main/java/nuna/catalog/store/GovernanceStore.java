package nuna.catalog.store;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Governance records (build-plan 2.3): catalogs and their storage boundary (D28), storage
 * profiles (D21), governance attributes with provenance (D20, D24, D27), legal holds (D24), the
 * holds released and DR freezes lifted (backlog 133) and trusted engines (D17). Writes behave as in
 * {@link NamespaceStore}.
 */
public interface GovernanceStore {

    /** @return {@code Applied}, {@code NameTaken}, or {@code Missing(STORAGE_PROFILE)} */
    WriteResult createCatalog(CatalogRecord catalog, CatalogSettings settings, Change change);

    Optional<CatalogSettings> catalogSettings(UUID catalogId);

    /** Creates or replaces the profile, with its allowed locations. @return {@code Applied} */
    WriteResult putStorageProfile(StorageProfile profile, Change change);

    Optional<StorageProfile> readStorageProfile(UUID profileId);

    /** Sets the attribute, replacing the target's earlier value of that kind. @return {@code Applied} or {@code Missing} naming the target's kind */
    WriteResult putAttribute(GovernanceAttribute attribute, Change change);

    /**
     * Removes the target's attribute of {@code kind}. The removal of a DR freeze is its lift, which the store keeps
     * with who removed it and when ({@link #liftedIn}); the other kinds leave no record but the change's.
     *
     * @param removedBy who removes it, as audit records name the principal
     * @return {@code Applied} or {@code Missing(ATTRIBUTE)}
     */
    WriteResult removeAttribute(Securable target, GovernanceAttribute.Kind kind, String removedBy, Change change);

    /** Every attribute of any of {@code targets}, in one read. */
    List<GovernanceAttribute> attributesOf(Set<Securable> targets);

    /** @return {@code Applied} or {@code Missing} naming the target's kind */
    WriteResult placeHold(LegalHold hold, Change change);

    /**
     * Releases the hold, keeping its release with who released it and when ({@link #liftedIn}).
     *
     * @param releasedBy who releases it, as audit records name the principal
     * @return {@code Applied} or {@code Missing(HOLD)}
     */
    WriteResult releaseHold(UUID holdId, String releasedBy, Change change);

    /** Every hold placed directly on any of {@code targets}, in one read. */
    List<LegalHold> holdsOn(Set<Securable> targets);

    /**
     * Every hold released and DR freeze lifted on the catalog or any level in it, at or after {@code since}, the most
     * recent first; a level dropped since included. Holds and freezes are placed by hand, so a window holds few.
     */
    List<GovernanceLift> liftedIn(UUID catalogId, Instant since);

    /** Registers or replaces the engine. @return {@code Applied} */
    WriteResult putTrustedEngine(TrustedEngine engine, Change change);

    /** @return {@code Applied} or {@code Missing(ENGINE)} */
    WriteResult removeTrustedEngine(String issuer, String clientId, Change change);

    Optional<TrustedEngine> findTrustedEngine(String issuer, String clientId);

    List<TrustedEngine> listTrustedEngines();
}
