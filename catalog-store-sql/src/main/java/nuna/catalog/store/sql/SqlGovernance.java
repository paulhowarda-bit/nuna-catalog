package nuna.catalog.store.sql;

import static nuna.catalog.store.sql.Jdbc.exists;
import static nuna.catalog.store.sql.Jdbc.first;
import static nuna.catalog.store.sql.Jdbc.instant;
import static nuna.catalog.store.sql.Jdbc.query;
import static nuna.catalog.store.sql.Jdbc.update;

import nuna.catalog.store.CatalogRecord;
import nuna.catalog.store.CatalogSettings;
import nuna.catalog.store.Change;
import nuna.catalog.store.GovernanceAttribute;
import nuna.catalog.store.LegalHold;
import nuna.catalog.store.Securable;
import nuna.catalog.store.StorageProfile;
import nuna.catalog.store.TrustedEngine;
import nuna.catalog.store.WriteResult;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Catalogs and their boundary (D28), storage profiles (D21), attributes (D20, D24, D27), holds (D24), engines (D17). */
final class SqlGovernance {

    private final Transactions transactions;

    SqlGovernance(Transactions transactions) {
        this.transactions = transactions;
    }

    WriteResult createCatalog(CatalogRecord catalog, CatalogSettings settings, Change change) {
        return transactions.change(change, "catalog create", connection -> {
            if (!exists(connection, "SELECT 1 FROM storage_profile WHERE id = ? FOR SHARE", settings.storageProfileId())) {
                return new WriteResult.Missing(WriteResult.Subject.STORAGE_PROFILE);
            }
            int inserted = update(connection, "INSERT INTO catalog (id, name_norm, name, storage_profile_id, kms_key_set,"
                            + " legal_entity) VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT (name_norm) DO NOTHING",
                    catalog.id(), catalog.normalizedName(), catalog.name(), settings.storageProfileId(),
                    settings.kmsKeySet(), settings.legalEntity());
            if (inserted == 0) {
                return Guards.nameTaken(connection, "SELECT id FROM catalog WHERE name_norm = ?", catalog.normalizedName());
            }
            return WriteResult.APPLIED;
        });
    }

    Optional<CatalogSettings> catalogSettings(UUID catalogId) {
        return transactions.autocommit("catalog settings read", connection -> first(connection,
                "SELECT storage_profile_id, kms_key_set, legal_entity FROM catalog WHERE id = ?"
                        + " AND storage_profile_id IS NOT NULL",
                row -> new CatalogSettings(Jdbc.uuid(row, "storage_profile_id"), row.getString("kms_key_set"),
                        row.getString("legal_entity")), catalogId));
    }

    WriteResult putStorageProfile(StorageProfile profile, Change change) {
        return transactions.change(change, "storage profile put", connection -> {
            update(connection, "INSERT INTO storage_profile (id, name, kind, endpoint, sts_role, allowed_locations,"
                            + " kms_key) VALUES (?, ?, ?, ?, ?, ?, ?) ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name,"
                            + " kind = EXCLUDED.kind, endpoint = EXCLUDED.endpoint, sts_role = EXCLUDED.sts_role,"
                            + " allowed_locations = EXCLUDED.allowed_locations, kms_key = EXCLUDED.kms_key",
                    profile.id(), profile.name(), profile.kind().name(), profile.endpoint().orElse(null), profile.stsRole(),
                    profile.allowedLocations().toArray(String[]::new), profile.kmsKey().orElse(null));
            return WriteResult.APPLIED;
        });
    }

    Optional<StorageProfile> readStorageProfile(UUID profileId) {
        return transactions.autocommit("storage profile read", connection -> first(connection,
                "SELECT id, name, kind, endpoint, sts_role, allowed_locations, kms_key FROM storage_profile WHERE id = ?",
                row -> new StorageProfile(Jdbc.uuid(row, "id"), row.getString("name"),
                        StorageProfile.Kind.valueOf(row.getString("kind")), Optional.ofNullable(row.getString("endpoint")),
                        row.getString("sts_role"), Jdbc.texts(row, "allowed_locations"),
                        Optional.ofNullable(row.getString("kms_key"))), profileId));
    }

    WriteResult putAttribute(GovernanceAttribute attribute, Change change) {
        return transactions.change(change, "attribute put", connection -> {
            Optional<WriteResult> missing = Guards.missing(connection, attribute.target(), Optional.empty());
            if (missing.isPresent()) {
                return missing.get();
            }
            update(connection, "INSERT INTO governance_attribute (target_kind, target_id, kind, value, provenance, set_by,"
                            + " set_at) VALUES (?, ?, ?, ?, ?, ?, ?) ON CONFLICT (target_id, kind) DO UPDATE SET"
                            + " target_kind = EXCLUDED.target_kind, value = EXCLUDED.value,"
                            + " provenance = EXCLUDED.provenance, set_by = EXCLUDED.set_by, set_at = EXCLUDED.set_at",
                    attribute.target().kind().name(), attribute.target().id(), attribute.kind().name(), attribute.value(),
                    attribute.provenance(), attribute.setBy(), attribute.setAt());
            return WriteResult.APPLIED;
        });
    }

    WriteResult removeAttribute(Securable target, GovernanceAttribute.Kind kind, Change change) {
        return transactions.change(change, "attribute remove", connection -> update(connection,
                "DELETE FROM governance_attribute WHERE target_id = ? AND target_kind = ? AND kind = ?",
                target.id(), target.kind().name(), kind.name()) == 1
                ? WriteResult.APPLIED : new WriteResult.Missing(WriteResult.Subject.ATTRIBUTE));
    }

    List<GovernanceAttribute> attributesOf(Set<Securable> targets) {
        return transactions.autocommit("attribute read", connection -> query(connection,
                "SELECT target_kind, target_id, kind, value, provenance, set_by, set_at FROM governance_attribute"
                        + " WHERE target_id = ANY(?)",
                row -> new GovernanceAttribute(target(row), GovernanceAttribute.Kind.valueOf(row.getString("kind")),
                        row.getString("value"), row.getString("provenance"), row.getString("set_by"),
                        instant(row, "set_at")), (Object) ids(targets)))
                .stream().filter(attribute -> targets.contains(attribute.target())).toList();
    }

    WriteResult placeHold(LegalHold hold, Change change) {
        return transactions.change(change, "hold place", connection -> {
            Optional<WriteResult> missing = Guards.missing(connection, hold.target(), Optional.empty());
            if (missing.isPresent()) {
                return missing.get();
            }
            update(connection, "INSERT INTO legal_hold (id, target_kind, target_id, reason, placed_by, placed_at)"
                            + " VALUES (?, ?, ?, ?, ?, ?)", hold.id(), hold.target().kind().name(), hold.target().id(),
                    hold.reason(), hold.placedBy(), hold.placedAt());
            return WriteResult.APPLIED;
        });
    }

    WriteResult releaseHold(UUID holdId, Change change) {
        return transactions.change(change, "hold release", connection -> update(connection,
                "DELETE FROM legal_hold WHERE id = ?", holdId) == 1
                ? WriteResult.APPLIED : new WriteResult.Missing(WriteResult.Subject.HOLD));
    }

    List<LegalHold> holdsOn(Set<Securable> targets) {
        return transactions.autocommit("hold read", connection -> query(connection,
                "SELECT id, target_kind, target_id, reason, placed_by, placed_at FROM legal_hold WHERE target_id = ANY(?)",
                row -> new LegalHold(Jdbc.uuid(row, "id"), target(row), row.getString("reason"),
                        row.getString("placed_by"), instant(row, "placed_at")), (Object) ids(targets)))
                .stream().filter(hold -> targets.contains(hold.target())).toList();
    }

    WriteResult putTrustedEngine(TrustedEngine engine, Change change) {
        return transactions.change(change, "trusted engine put", connection -> {
            update(connection, "INSERT INTO trusted_engine (issuer, client_id, end_user_claim, flags) VALUES (?, ?, ?, ?)"
                            + " ON CONFLICT (issuer, client_id) DO UPDATE SET end_user_claim = EXCLUDED.end_user_claim,"
                            + " flags = EXCLUDED.flags",
                    engine.issuer(), engine.clientId(), engine.endUserClaim(), engine.flags().stream().sorted()
                            .toArray(String[]::new));
            return WriteResult.APPLIED;
        });
    }

    WriteResult removeTrustedEngine(String issuer, String clientId, Change change) {
        return transactions.change(change, "trusted engine remove", connection -> update(connection,
                "DELETE FROM trusted_engine WHERE issuer = ? AND client_id = ?", issuer, clientId) == 1
                ? WriteResult.APPLIED : new WriteResult.Missing(WriteResult.Subject.ENGINE));
    }

    Optional<TrustedEngine> findTrustedEngine(String issuer, String clientId) {
        return transactions.autocommit("trusted engine read", connection -> first(connection,
                "SELECT issuer, client_id, end_user_claim, flags FROM trusted_engine WHERE issuer = ? AND client_id = ?",
                SqlGovernance::engine, issuer, clientId));
    }

    List<TrustedEngine> listTrustedEngines() {
        return transactions.autocommit("trusted engine listing", connection -> query(connection,
                "SELECT issuer, client_id, end_user_claim, flags FROM trusted_engine ORDER BY issuer, client_id",
                SqlGovernance::engine));
    }

    private static TrustedEngine engine(ResultSet row) throws SQLException {
        return new TrustedEngine(row.getString("issuer"), row.getString("client_id"), row.getString("end_user_claim"),
                Set.copyOf(Jdbc.texts(row, "flags")));
    }

    private static Securable target(ResultSet row) throws SQLException {
        return new Securable(Securable.Kind.valueOf(row.getString("target_kind")), Jdbc.uuid(row, "target_id"));
    }

    private static UUID[] ids(Set<Securable> targets) {
        return targets.stream().map(Securable::id).toArray(UUID[]::new);
    }
}
