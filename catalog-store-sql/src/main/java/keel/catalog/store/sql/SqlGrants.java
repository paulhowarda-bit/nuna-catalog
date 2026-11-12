package keel.catalog.store.sql;

import static keel.catalog.store.sql.Jdbc.instant;
import static keel.catalog.store.sql.Jdbc.query;
import static keel.catalog.store.sql.Jdbc.update;

import keel.catalog.store.Change;
import keel.catalog.store.Grant;
import keel.catalog.store.GrantRecord;
import keel.catalog.store.Page;
import keel.catalog.store.PageRequest;
import keel.catalog.store.PageToken;
import keel.catalog.store.Securable;
import keel.catalog.store.WriteResult;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Grants to groups (D18, D19). A put share-locks its securable, so a drop or purge that deletes grants waits for it. */
final class SqlGrants {

    private static final String COLUMNS = "catalog_id, securable_kind, securable_id, group_name, privilege, granted_by,"
            + " granted_at";

    private final Transactions transactions;

    SqlGrants(Transactions transactions) {
        this.transactions = transactions;
    }

    WriteResult put(Grant grant, String grantedBy, Change change) {
        return transactions.change(change, "grant", connection -> {
            Optional<WriteResult> missing = Guards.missing(connection, grant.securable(), Optional.of(grant.catalogId()));
            if (missing.isPresent()) {
                return missing.get();
            }
            int inserted = update(connection, "INSERT INTO grant_record (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?)"
                            + " ON CONFLICT (securable_id, group_name, privilege) DO NOTHING",
                    grant.catalogId(), grant.securable().kind().name(), grant.securable().id(), grant.group(),
                    grant.privilege(), grantedBy, change.at());
            return inserted == 1 ? WriteResult.APPLIED : WriteResult.UNCHANGED;
        });
    }

    WriteResult revoke(Grant grant, Change change) {
        return transactions.change(change, "revoke", connection -> {
            int deleted = update(connection, "DELETE FROM grant_record WHERE securable_id = ? AND group_name = ?"
                            + " AND privilege = ? AND securable_kind = ? AND catalog_id = ?",
                    grant.securable().id(), grant.group(), grant.privilege(), grant.securable().kind().name(),
                    grant.catalogId());
            return deleted == 1 ? WriteResult.APPLIED : new WriteResult.Missing(WriteResult.Subject.GRANT);
        });
    }

    List<GrantRecord> grantsFor(Set<Securable> securables, Set<String> groups) {
        return transactions.autocommit("grant check", connection -> query(connection, "SELECT " + COLUMNS
                        + " FROM grant_record WHERE securable_id = ANY(?) AND group_name = ANY(?)", SqlGrants::grant,
                securables.stream().map(Securable::id).toArray(UUID[]::new), groups.toArray(String[]::new)))
                .stream().filter(found -> securables.contains(found.grant().securable())).toList();
    }

    Page<GrantRecord> listOfGroups(UUID catalogId, Set<String> groups, PageRequest page) {
        Optional<List<Object>> after = Jdbc.after(page, 3,
                parts -> List.of(parts.get(0), UUID.fromString(parts.get(1)), parts.get(2)));
        return transactions.autocommit("grant listing", connection -> {
            List<Object> parameters = new ArrayList<>(List.of(catalogId, groups.toArray(String[]::new)));
            String keyset = "";
            if (after.isPresent()) {
                keyset = " AND (group_name, securable_id, privilege) > (?, ?, ?)";
                parameters.addAll(after.get());
            }
            parameters.add(page.size() + 1);
            List<GrantRecord> grants = query(connection, "SELECT " + COLUMNS + " FROM grant_record"
                            + " WHERE catalog_id = ? AND group_name = ANY(?)" + keyset
                            + " ORDER BY group_name, securable_id, privilege LIMIT ?", SqlGrants::grant,
                    parameters.toArray());
            return Jdbc.page(grants, page, found -> PageToken.encode(found.grant().group(),
                    found.grant().securable().id().toString(), found.grant().privilege()));
        });
    }

    Page<GrantRecord> listOn(Securable securable, PageRequest page) {
        Optional<List<String>> after = Jdbc.after(page, 2, parts -> parts);
        return transactions.autocommit("grant listing", connection -> {
            List<Object> parameters = new ArrayList<>(List.of(securable.id(), securable.kind().name()));
            String keyset = "";
            if (after.isPresent()) {
                keyset = " AND (group_name, privilege) > (?, ?)";
                parameters.addAll(after.get());
            }
            parameters.add(page.size() + 1);
            List<GrantRecord> grants = query(connection, "SELECT " + COLUMNS + " FROM grant_record"
                    + " WHERE securable_id = ? AND securable_kind = ?" + keyset + " ORDER BY group_name, privilege LIMIT ?",
                    SqlGrants::grant, parameters.toArray());
            return Jdbc.page(grants, page, found -> PageToken.encode(found.grant().group(), found.grant().privilege()));
        });
    }

    private static GrantRecord grant(ResultSet row) throws SQLException {
        Securable securable = new Securable(Securable.Kind.valueOf(row.getString("securable_kind")),
                Jdbc.uuid(row, "securable_id"));
        return new GrantRecord(new Grant(Jdbc.uuid(row, "catalog_id"), securable, row.getString("group_name"),
                row.getString("privilege")), row.getString("granted_by"), instant(row, "granted_at"));
    }
}
