package keel.catalog.store.oracle;

import static keel.catalog.store.oracle.Jdbc.instant;
import static keel.catalog.store.oracle.Jdbc.query;
import static keel.catalog.store.oracle.Jdbc.update;

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
import java.sql.SQLIntegrityConstraintViolationException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Grants to groups (D18, D19). A put locks its securable, so a drop or purge that deletes grants waits for it. */
final class OracleGrants {

    private static final String COLUMNS = "catalog_id, securable_kind, securable_id, group_name, privilege, granted_by,"
            + " granted_at";

    private final Transactions transactions;

    OracleGrants(Transactions transactions) {
        this.transactions = transactions;
    }

    WriteResult put(Grant grant, String grantedBy, Change change) {
        return transactions.change(change, "grant", connection -> {
            Optional<WriteResult> missing = Guards.missing(connection, grant.securable(), Optional.of(grant.catalogId()));
            if (missing.isPresent()) {
                return missing.get();
            }
            try {
                update(connection, "INSERT INTO grant_record (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?)",
                        grant.catalogId(), grant.securable().kind().name(), grant.securable().id(), grant.group(),
                        grant.privilege(), grantedBy, change.at());
            } catch (SQLIntegrityConstraintViolationException e) {
                if (!Jdbc.violates(e, "GRANT_RECORD_PK")) {
                    throw e;
                }
                return WriteResult.UNCHANGED;
            }
            return WriteResult.APPLIED;
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
        List<Object> parameters = new ArrayList<>();
        String where = Jdbc.in("securable_id", securables.stream().map(Securable::id).toList(), parameters)
                + " AND " + Jdbc.in("group_name", groups, parameters);
        return transactions.autocommit("grant check", connection -> query(connection, "SELECT " + COLUMNS
                        + " FROM grant_record WHERE " + where, OracleGrants::grant, parameters.toArray()))
                .stream().filter(found -> securables.contains(found.grant().securable())).toList();
    }

    Page<GrantRecord> listOfGroups(UUID catalogId, Set<String> groups, PageRequest page) {
        Optional<List<Object>> after = Jdbc.after(page, 3,
                parts -> List.of(parts.get(0), UUID.fromString(parts.get(1)), parts.get(2)));
        return transactions.autocommit("grant listing", connection -> {
            List<Object> parameters = new ArrayList<>(List.of(catalogId));
            String where = "catalog_id = ? AND " + Jdbc.in("group_name", groups, parameters);
            if (after.isPresent()) {
                where += " AND " + Jdbc.greater(List.of("group_name", "securable_id", "privilege"), after.get(), parameters);
            }
            parameters.add(page.size() + 1);
            List<GrantRecord> grants = query(connection, "SELECT " + COLUMNS + " FROM grant_record WHERE " + where
                    + " ORDER BY group_name, securable_id, privilege FETCH FIRST ? ROWS ONLY", OracleGrants::grant,
                    parameters.toArray());
            return Jdbc.page(grants, page, found -> PageToken.encode(found.grant().group(),
                    found.grant().securable().id().toString(), found.grant().privilege()));
        });
    }

    Page<GrantRecord> listOn(Securable securable, PageRequest page) {
        Optional<List<String>> after = Jdbc.after(page, 2, parts -> parts);
        return transactions.autocommit("grant listing", connection -> {
            List<Object> parameters = new ArrayList<>(List.of(securable.id(), securable.kind().name()));
            String keyset = after.isPresent()
                    ? " AND " + Jdbc.greater(List.of("group_name", "privilege"), after.get(), parameters) : "";
            parameters.add(page.size() + 1);
            List<GrantRecord> grants = query(connection, "SELECT " + COLUMNS + " FROM grant_record"
                    + " WHERE securable_id = ? AND securable_kind = ?" + keyset
                    + " ORDER BY group_name, privilege FETCH FIRST ? ROWS ONLY", OracleGrants::grant, parameters.toArray());
            return Jdbc.page(grants, page, found -> PageToken.encode(found.grant().group(), found.grant().privilege()));
        });
    }

    private static GrantRecord grant(ResultSet row) throws SQLException {
        Securable securable = new Securable(Securable.Kind.valueOf(row.getString("securable_kind")),
                Jdbc.uuid(row, "securable_id"));
        return new GrantRecord(new Grant(Jdbc.uuid(row, "catalog_id"), securable, Jdbc.text(row, "group_name"),
                Jdbc.text(row, "privilege")), Jdbc.text(row, "granted_by"), instant(row, "granted_at"));
    }
}
