package nuna.catalog.store.sql;

import static nuna.catalog.store.sql.Jdbc.exists;
import static nuna.catalog.store.sql.Jdbc.instant;
import static nuna.catalog.store.sql.Jdbc.query;
import static nuna.catalog.store.sql.Jdbc.update;

import nuna.catalog.store.Change;
import nuna.catalog.store.Grant;
import nuna.catalog.store.GrantGroup;
import nuna.catalog.store.GrantRecord;
import nuna.catalog.store.Page;
import nuna.catalog.store.PageRequest;
import nuna.catalog.store.PageToken;
import nuna.catalog.store.Securable;
import nuna.catalog.store.WriteResult;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Grants to groups (D18, D19). A put share-locks its securable, so a drop or purge that deletes grants waits for it.
 * A conditional put locks its catalog's row first, so conditional puts in one catalog run one at a time.
 *
 * <p>A grant that names a branch (backlog 113) lives in {@code ref_grant}, the others in {@code grant_record}; reads
 * go through the view {@code any_grant}, whose {@code ref_scope} is {@link #EVERY_REF} or the branch's id. A put on a
 * branch locks the branch's row for update, as a deletion or merge does before deleting its grants, so neither
 * leaves a grant on a branch that is gone.
 */
final class SqlGrants {

    /** {@code any_grant.ref_scope} for a grant that holds on every ref. Never empty: Oracle reads '' as NULL. */
    static final String EVERY_REF = "*";

    private static final String FIELDS = "catalog_id, securable_kind, securable_id, group_name, privilege, granted_by,"
            + " granted_at";
    private static final String COLUMNS = FIELDS + ", ref_scope";

    private final Transactions transactions;

    SqlGrants(Transactions transactions) {
        this.transactions = transactions;
    }

    WriteResult put(Grant grant, String grantedBy, Change change) {
        return transactions.change(change, "grant", connection -> insert(connection, grant, grantedBy, change));
    }

    /**
     * The group's grants in the catalog are read only once the catalog's row is locked, and READ COMMITTED reads
     * from that moment, so each conditional put sees every grant the one before it committed.
     */
    WriteResult putIf(Grant grant, String grantedBy, Change change, Predicate<List<String>> admits) {
        return transactions.change(change, "grant", connection -> {
            if (!exists(connection, "SELECT 1 FROM catalog WHERE id = ? FOR UPDATE", grant.catalogId())) {
                return new WriteResult.Missing(WriteResult.Subject.CATALOG);
            }
            List<String> held = query(connection, "SELECT privilege FROM any_grant WHERE catalog_id = ?"
                    + " AND group_name = ?", row -> row.getString("privilege"), grant.catalogId(), grant.group());
            return admits.test(held) ? insert(connection, grant, grantedBy, change)
                    : new WriteResult.Refused(WriteResult.Reason.NOT_ADMITTED);
        });
    }

    private static WriteResult insert(Connection connection, Grant grant, String grantedBy, Change change)
            throws SQLException {
        // A branch grant locks in the order every write takes these rows (storage-design.md §9(f) rule 8): the
        // catalog, then the branch, then the securable, a catalog securable included.
        if (grant.branch().isPresent()) {
            if (!exists(connection, "SELECT 1 FROM catalog WHERE id = ? FOR SHARE", grant.catalogId())) {
                return new WriteResult.Missing(WriteResult.Subject.CATALOG);
            }
            if (!exists(connection, "SELECT 1 FROM ref WHERE id = ? AND catalog_id = ? AND kind = 'BRANCH' FOR UPDATE",
                    grant.branch().get(), grant.catalogId())) {
                return new WriteResult.Missing(WriteResult.Subject.REF);
            }
        }
        Optional<WriteResult> missing = Guards.missing(connection, grant.securable(), Optional.of(grant.catalogId()));
        if (missing.isPresent()) {
            return missing.get();
        }
        if (grant.branch().isPresent()) {
            int inserted = update(connection, "INSERT INTO ref_grant (" + FIELDS + ", ref_id)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (securable_id, group_name, privilege, ref_id)"
                            + " DO NOTHING", grant.catalogId(), grant.securable().kind().name(), grant.securable().id(),
                    grant.group(), grant.privilege(), grantedBy, change.at(), grant.branch().get());
            return inserted == 1 ? WriteResult.APPLIED : WriteResult.UNCHANGED;
        }
        int inserted = update(connection, "INSERT INTO grant_record (" + FIELDS + ") VALUES (?, ?, ?, ?, ?, ?, ?)"
                        + " ON CONFLICT (securable_id, group_name, privilege) DO NOTHING",
                grant.catalogId(), grant.securable().kind().name(), grant.securable().id(), grant.group(),
                grant.privilege(), grantedBy, change.at());
        return inserted == 1 ? WriteResult.APPLIED : WriteResult.UNCHANGED;
    }

    WriteResult revoke(Grant grant, Change change) {
        return transactions.change(change, "revoke", connection -> {
            int deleted = grant.branch().isPresent()
                    ? update(connection, "DELETE FROM ref_grant WHERE securable_id = ? AND group_name = ?"
                            + " AND privilege = ? AND ref_id = ? AND securable_kind = ? AND catalog_id = ?",
                    grant.securable().id(), grant.group(), grant.privilege(), grant.branch().get(),
                    grant.securable().kind().name(), grant.catalogId())
                    : update(connection, "DELETE FROM grant_record WHERE securable_id = ? AND group_name = ?"
                            + " AND privilege = ? AND securable_kind = ? AND catalog_id = ?",
                    grant.securable().id(), grant.group(), grant.privilege(), grant.securable().kind().name(),
                    grant.catalogId());
            return deleted == 1 ? WriteResult.APPLIED : new WriteResult.Missing(WriteResult.Subject.GRANT);
        });
    }

    List<GrantRecord> grantsFor(Set<Securable> securables, Set<String> groups) {
        return transactions.autocommit("grant check", connection -> query(connection, "SELECT " + COLUMNS
                        + " FROM any_grant WHERE securable_id = ANY(?) AND group_name = ANY(?)", SqlGrants::grant,
                securables.stream().map(Securable::id).toArray(UUID[]::new), groups.toArray(String[]::new)))
                .stream().filter(found -> securables.contains(found.grant().securable())).toList();
    }

    Page<GrantRecord> listOfGroups(UUID catalogId, Set<String> groups, PageRequest page) {
        Optional<List<Object>> after = Jdbc.after(page, 4,
                parts -> List.of(parts.get(0), UUID.fromString(parts.get(1)), parts.get(2), parts.get(3)));
        return transactions.autocommit("grant listing", connection -> {
            List<Object> parameters = new ArrayList<>(List.of(catalogId, groups.toArray(String[]::new)));
            String keyset = "";
            if (after.isPresent()) {
                keyset = " AND (group_name, securable_id, privilege, ref_scope) > (?, ?, ?, ?)";
                parameters.addAll(after.get());
            }
            parameters.add(page.size() + 1);
            List<GrantRecord> grants = query(connection, "SELECT " + COLUMNS + " FROM any_grant"
                            + " WHERE catalog_id = ? AND group_name = ANY(?)" + keyset
                            + " ORDER BY group_name, securable_id, privilege, ref_scope LIMIT ?", SqlGrants::grant,
                    parameters.toArray());
            return Jdbc.page(grants, page, found -> PageToken.encode(found.grant().group(),
                    found.grant().securable().id().toString(), found.grant().privilege(), refScope(found.grant())));
        });
    }

    Page<GrantRecord> listOn(Securable securable, PageRequest page) {
        Optional<List<String>> after = Jdbc.after(page, 3, parts -> parts);
        return transactions.autocommit("grant listing", connection -> {
            List<Object> parameters = new ArrayList<>(List.of(securable.id(), securable.kind().name()));
            String keyset = "";
            if (after.isPresent()) {
                keyset = " AND (group_name, privilege, ref_scope) > (?, ?, ?)";
                parameters.addAll(after.get());
            }
            parameters.add(page.size() + 1);
            List<GrantRecord> grants = query(connection, "SELECT " + COLUMNS + " FROM any_grant"
                    + " WHERE securable_id = ? AND securable_kind = ?" + keyset
                    + " ORDER BY group_name, privilege, ref_scope LIMIT ?", SqlGrants::grant, parameters.toArray());
            return Jdbc.page(grants, page, found -> PageToken.encode(found.grant().group(), found.grant().privilege(),
                    refScope(found.grant())));
        });
    }

    /** The page's groups come from an inner keyset read on {@code grant_by_group}; their privileges from the same read. */
    Page<GrantGroup> listGroups(UUID catalogId, PageRequest page) {
        Optional<String> after = Jdbc.after(page, 1, List::getFirst);
        return transactions.autocommit("grant group listing", connection -> {
            List<Object> parameters = new ArrayList<>(List.of(catalogId));
            String keyset = "";
            if (after.isPresent()) {
                keyset = " AND group_name > ?";
                parameters.add(after.get());
            }
            parameters.add(page.size() + 1);
            parameters.add(catalogId);
            List<Map.Entry<String, String>> rows = query(connection, "SELECT DISTINCT g.group_name, g.privilege"
                            + " FROM any_grant g JOIN (SELECT DISTINCT group_name FROM any_grant"
                            + " WHERE catalog_id = ?" + keyset + " ORDER BY group_name LIMIT ?) p"
                            + " ON g.group_name = p.group_name WHERE g.catalog_id = ? ORDER BY g.group_name, g.privilege",
                    row -> Map.entry(row.getString("group_name"), row.getString("privilege")), parameters.toArray());
            return Jdbc.page(GrantGroup.byGroup(rows), page, found -> PageToken.encode(found.group()));
        });
    }

    private static GrantRecord grant(ResultSet row) throws SQLException {
        Securable securable = new Securable(Securable.Kind.valueOf(row.getString("securable_kind")),
                Jdbc.uuid(row, "securable_id"));
        String scope = row.getString("ref_scope");
        Optional<UUID> branch = scope.equals(EVERY_REF) ? Optional.empty() : Optional.of(UUID.fromString(scope));
        return new GrantRecord(new Grant(Jdbc.uuid(row, "catalog_id"), securable, row.getString("group_name"),
                row.getString("privilege"), branch), row.getString("granted_by"), instant(row, "granted_at"));
    }

    private static String refScope(Grant grant) {
        return grant.branch().map(UUID::toString).orElse(EVERY_REF);
    }
}
