package keel.catalog.store;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Governance records (build-plan 2.3): catalogs and their storage boundary (D28), storage
 * profiles (D21), governance attributes with provenance (D20, D24, D27), legal holds (D24) and
 * trusted engines (D17). Writes behave as in {@link NamespaceStore}.
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

    /** @return {@code Applied} or {@code Missing(ATTRIBUTE)} */
    WriteResult removeAttribute(Securable target, GovernanceAttribute.Kind kind, Change change);

    /** Every attribute of any of {@code targets}, in one read. */
    List<GovernanceAttribute> attributesOf(Set<Securable> targets);

    /** @return {@code Applied} or {@code Missing} naming the target's kind */
    WriteResult placeHold(LegalHold hold, Change change);

    /** @return {@code Applied} or {@code Missing(HOLD)} */
    WriteResult releaseHold(UUID holdId, Change change);

    /** Every hold placed directly on any of {@code targets}, in one read. */
    List<LegalHold> holdsOn(Set<Securable> targets);

    /** Registers or replaces the engine. @return {@code Applied} */
    WriteResult putTrustedEngine(TrustedEngine engine, Change change);

    /** @return {@code Applied} or {@code Missing(ENGINE)} */
    WriteResult removeTrustedEngine(String issuer, String clientId, Change change);

    Optional<TrustedEngine> findTrustedEngine(String issuer, String clientId);

    List<TrustedEngine> listTrustedEngines();
}
