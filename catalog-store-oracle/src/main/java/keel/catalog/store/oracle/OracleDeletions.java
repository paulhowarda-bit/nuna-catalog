package keel.catalog.store.oracle;

import static keel.catalog.store.oracle.Jdbc.first;
import static keel.catalog.store.oracle.Jdbc.instant;
import static keel.catalog.store.oracle.Jdbc.query;
import static keel.catalog.store.oracle.Jdbc.update;

import keel.catalog.store.Change;
import keel.catalog.store.Page;
import keel.catalog.store.PageRequest;
import keel.catalog.store.PageToken;
import keel.catalog.store.PendingDeletion;
import keel.catalog.store.WriteResult;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Files the maintenance worker deletes once the database's PITR window has passed (D30). Recording
 * locks the object (Oracle has no shared row lock), so a purge completing at the same moment waits,
 * then deletes them with it.
 */
final class OracleDeletions {

    private static final String COLUMNS = "d.id, d.object_id, d.catalog_id, d.location, d.not_before, d.recorded_at";

    private final Transactions transactions;

    OracleDeletions(Transactions transactions) {
        this.transactions = transactions;
    }

    WriteResult record(UUID objectId, List<String> locations, Instant notBefore, Change change) {
        return transactions.change(change, "deletions record", connection -> {
            Optional<UUID> catalogId = first(connection, "SELECT catalog_id FROM catalog_object WHERE id = ?"
                    + " AND state IN ('ACTIVE', 'DROPPED') FOR UPDATE", row -> Jdbc.uuid(row, "catalog_id"), objectId);
            if (catalogId.isEmpty()) {
                return new WriteResult.Missing(WriteResult.Subject.OBJECT);
            }
            Jdbc.batch(connection, "INSERT INTO pending_deletion (id, object_id, catalog_id, location, not_before,"
                    + " recorded_at) VALUES (?, ?, ?, ?, ?, ?)", locations.stream().map(location -> new Object[] {
                    UUID.randomUUID(), objectId, catalogId.get(), location, notBefore, change.at()}).toList());
            return WriteResult.APPLIED;
        });
    }

    Page<PendingDeletion> listDue(Instant now, PageRequest page) {
        Optional<List<Object>> after = Jdbc.after(page, 2,
                parts -> List.of(Instant.parse(parts.get(0)), UUID.fromString(parts.get(1))));
        return transactions.autocommit("pending deletion scan", connection -> {
            List<Object> parameters = new ArrayList<>(List.of(now));
            String keyset = after.isPresent()
                    ? " AND " + Jdbc.greater(List.of("d.not_before", "d.id"), after.get(), parameters) : "";
            parameters.add(page.size() + 1);
            List<PendingDeletion> due = query(connection, "SELECT " + COLUMNS + " FROM pending_deletion d"
                    + " JOIN catalog_object o ON o.id = d.object_id WHERE d.not_before <= ?" + keyset
                    + " AND o.state IN ('ACTIVE', 'DROPPED') AND " + OracleObjects.UNCOVERED
                    + " ORDER BY d.not_before, d.id FETCH FIRST ? ROWS ONLY", row -> new PendingDeletion(
                    Jdbc.uuid(row, "id"), Jdbc.uuid(row, "object_id"), Jdbc.uuid(row, "catalog_id"),
                    Jdbc.text(row, "location"), instant(row, "not_before"), instant(row, "recorded_at")),
                    parameters.toArray());
            return Jdbc.page(due, page,
                    deletion -> PageToken.encode(deletion.notBefore().toString(), deletion.id().toString()));
        });
    }

    WriteResult complete(UUID objectId, List<UUID> ids, Change change) {
        return transactions.change(change, "deletions complete", connection -> {
            List<Object> parameters = new ArrayList<>(List.of(objectId));
            String in = Jdbc.in("id", ids, parameters);
            return update(connection, "DELETE FROM pending_deletion WHERE object_id = ? AND " + in,
                    parameters.toArray()) == 0 ? WriteResult.UNCHANGED : WriteResult.APPLIED;
        });
    }
}
