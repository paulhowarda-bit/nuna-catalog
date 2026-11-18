package nuna.catalog.store.oracle;

import static nuna.catalog.store.oracle.Jdbc.exists;
import static nuna.catalog.store.oracle.Jdbc.first;
import static nuna.catalog.store.oracle.Jdbc.instant;
import static nuna.catalog.store.oracle.Jdbc.query;
import static nuna.catalog.store.oracle.Jdbc.update;

import nuna.catalog.store.CatalogRecord;
import nuna.catalog.store.CatalogSettings;
import nuna.catalog.store.Change;
import nuna.catalog.store.GovernanceAttribute;
import nuna.catalog.store.LegalHold;
import nuna.catalog.store.Securable;
import nuna.catalog.store.StorageProfile;
import nuna.catalog.store.TrustedEngine;
import nuna.catalog.store.WriteResult;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** Catalogs and their boundary (D28), storage profiles (D21), attributes (D20, D24, D27), holds (D24), engines (D17). */
final class OracleGovernance {

    private final Transactions transactions;

    OracleGovernance(Transactions transactions) {
        this.transactions = transactions;
    }

    WriteResult createCatalog(CatalogRecord catalog, CatalogSettings settings, Change change) {
        return transactions.change(change, "catalog create", connection -> {
            if (!exists(connection, "SELECT 1 FROM storage_profile WHERE id = ? FOR UPDATE", settings.storageProfileId())) {
                return new WriteResult.Missing(WriteResult.Subject.STORAGE_PROFILE);
            }
            try {
                update(connection, "INSERT INTO catalog (id, name_norm, name, storage_profile_id, kms_key_set, legal_entity)"
                                + " VALUES (?, ?, ?, ?, ?, ?)", catalog.id(), catalog.normalizedName(), catalog.name(),
                        settings.storageProfileId(), settings.kmsKeySet(), settings.legalEntity());
            } catch (SQLIntegrityConstraintViolationException e) {
                if (!Jdbc.violates(e, "CATALOG_NAME_NORM_UK")) {
                    throw e;
                }
                return Guards.nameTaken(connection, "SELECT id FROM catalog WHERE name_norm = ?", catalog.normalizedName());
            }
            return WriteResult.APPLIED;
        });
    }

    Optional<CatalogSettings> catalogSettings(UUID catalogId) {
        return transactions.autocommit("catalog settings read", connection -> first(connection,
                "SELECT storage_profile_id, kms_key_set, legal_entity FROM catalog WHERE id = ?"
                        + " AND storage_profile_id IS NOT NULL",
                row -> new CatalogSettings(Jdbc.uuid(row, "storage_profile_id"), Jdbc.text(row, "kms_key_set"),
                        Jdbc.text(row, "legal_entity")), catalogId));
    }

    /** MERGE locks the row it updates or inserts; the allowed locations are replaced under that lock. */
    WriteResult putStorageProfile(StorageProfile profile, Change change) {
        return transactions.change(change, "storage profile put", connection -> {
            upsert(connection, "storage_profile_pk", "MERGE INTO storage_profile t USING (SELECT ? id, ? name, ? kind,"
                            + " ? endpoint, ? sts_role FROM dual) s ON (t.id = s.id)"
                            + " WHEN MATCHED THEN UPDATE SET t.name = s.name, t.kind = s.kind, t.endpoint = s.endpoint,"
                            + " t.sts_role = s.sts_role"
                            + " WHEN NOT MATCHED THEN INSERT (id, name, kind, endpoint, sts_role)"
                            + " VALUES (s.id, s.name, s.kind, s.endpoint, s.sts_role)",
                    profile.id(), profile.name(), profile.kind().name(), profile.endpoint().orElse(null), profile.stsRole());
            update(connection, "DELETE FROM storage_profile_location WHERE profile_id = ?", profile.id());
            List<Object[]> locations = new ArrayList<>();
            for (int i = 0; i < profile.allowedLocations().size(); i++) {
                locations.add(new Object[] {profile.id(), i, profile.allowedLocations().get(i)});
            }
            Jdbc.batch(connection, "INSERT INTO storage_profile_location (profile_id, ordinal, location) VALUES (?, ?, ?)",
                    locations);
            return WriteResult.APPLIED;
        });
    }

    Optional<StorageProfile> readStorageProfile(UUID profileId) {
        return transactions.snapshot("storage profile read", connection -> {
            List<String> locations = query(connection, "SELECT location FROM storage_profile_location"
                    + " WHERE profile_id = ? ORDER BY ordinal", row -> Jdbc.text(row, "location"), profileId);
            return first(connection, "SELECT id, name, kind, endpoint, sts_role FROM storage_profile WHERE id = ?",
                    row -> new StorageProfile(Jdbc.uuid(row, "id"), Jdbc.text(row, "name"),
                            StorageProfile.Kind.valueOf(row.getString("kind")),
                            Optional.ofNullable(Jdbc.text(row, "endpoint")), Jdbc.text(row, "sts_role"), locations),
                    profileId);
        });
    }

    WriteResult putAttribute(GovernanceAttribute attribute, Change change) {
        return transactions.change(change, "attribute put", connection -> {
            Optional<WriteResult> missing = Guards.missing(connection, attribute.target(), Optional.empty());
            if (missing.isPresent()) {
                return missing.get();
            }
            upsert(connection, "governance_attribute_pk", "MERGE INTO governance_attribute t USING (SELECT ? target_kind,"
                            + " ? target_id, ? kind, ? value, ? provenance, ? set_by, ? set_at FROM dual) s"
                            + " ON (t.target_id = s.target_id AND t.kind = s.kind)"
                            + " WHEN MATCHED THEN UPDATE SET t.target_kind = s.target_kind, t.value = s.value,"
                            + " t.provenance = s.provenance, t.set_by = s.set_by, t.set_at = s.set_at"
                            + " WHEN NOT MATCHED THEN INSERT (target_kind, target_id, kind, value, provenance, set_by, set_at)"
                            + " VALUES (s.target_kind, s.target_id, s.kind, s.value, s.provenance, s.set_by, s.set_at)",
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
        List<Object> parameters = new ArrayList<>();
        String in = Jdbc.in("target_id", ids(targets), parameters);
        return transactions.autocommit("attribute read", connection -> query(connection,
                "SELECT target_kind, target_id, kind, value, provenance, set_by, set_at FROM governance_attribute"
                        + " WHERE " + in,
                row -> new GovernanceAttribute(target(row), GovernanceAttribute.Kind.valueOf(row.getString("kind")),
                        Jdbc.text(row, "value"), Jdbc.text(row, "provenance"), Jdbc.text(row, "set_by"),
                        instant(row, "set_at")), parameters.toArray()))
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
        List<Object> parameters = new ArrayList<>();
        String in = Jdbc.in("target_id", ids(targets), parameters);
        return transactions.autocommit("hold read", connection -> query(connection,
                "SELECT id, target_kind, target_id, reason, placed_by, placed_at FROM legal_hold WHERE " + in,
                row -> new LegalHold(Jdbc.uuid(row, "id"), target(row), Jdbc.text(row, "reason"),
                        Jdbc.text(row, "placed_by"), instant(row, "placed_at")), parameters.toArray()))
                .stream().filter(hold -> targets.contains(hold.target())).toList();
    }

    WriteResult putTrustedEngine(TrustedEngine engine, Change change) {
        return transactions.change(change, "trusted engine put", connection -> {
            upsert(connection, "trusted_engine_pk", "MERGE INTO trusted_engine t USING (SELECT ? issuer, ? client_id,"
                            + " ? end_user_claim, ? flags FROM dual) s ON (t.issuer = s.issuer AND t.client_id = s.client_id)"
                            + " WHEN MATCHED THEN UPDATE SET t.end_user_claim = s.end_user_claim, t.flags = s.flags"
                            + " WHEN NOT MATCHED THEN INSERT (issuer, client_id, end_user_claim, flags)"
                            + " VALUES (s.issuer, s.client_id, s.end_user_claim, s.flags)",
                    engine.issuer(), engine.clientId(), engine.endUserClaim(),
                    engine.flags().stream().sorted().collect(Collectors.joining(" ")));
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
                OracleGovernance::engine, issuer, clientId));
    }

    List<TrustedEngine> listTrustedEngines() {
        return transactions.autocommit("trusted engine listing", connection -> query(connection,
                "SELECT issuer, client_id, end_user_claim, flags FROM trusted_engine ORDER BY issuer, client_id",
                OracleGovernance::engine));
    }

    /**
     * Runs a MERGE, once more if it lost a race to insert the same key: Oracle's MERGE is not an
     * atomic upsert, but the losing insert waited for the winner's commit, so the second run updates.
     * Returns the rows it merged.
     */
    static int upsert(Connection connection, String key, String merge, Object... parameters) throws SQLException {
        try {
            return update(connection, merge, parameters);
        } catch (SQLIntegrityConstraintViolationException e) {
            if (!Jdbc.violates(e, key)) {
                throw e;
            }
            return update(connection, merge, parameters);
        }
    }

    private static TrustedEngine engine(ResultSet row) throws SQLException {
        String flags = Jdbc.text(row, "flags");
        return new TrustedEngine(Jdbc.text(row, "issuer"), Jdbc.text(row, "client_id"), Jdbc.text(row, "end_user_claim"),
                flags.isEmpty() ? Set.of() : Set.copyOf(Arrays.asList(flags.split(" "))));
    }

    private static Securable target(ResultSet row) throws SQLException {
        return new Securable(Securable.Kind.valueOf(row.getString("target_kind")), Jdbc.uuid(row, "target_id"));
    }

    private static List<UUID> ids(Set<Securable> targets) {
        return targets.stream().map(Securable::id).toList();
    }
}
