package keel.catalog.store.sql;

import static keel.catalog.store.sql.Jdbc.first;
import static keel.catalog.store.sql.Jdbc.instant;
import static keel.catalog.store.sql.Jdbc.query;
import static keel.catalog.store.sql.Jdbc.update;

import keel.catalog.store.Change;
import keel.catalog.store.Page;
import keel.catalog.store.PageRequest;
import keel.catalog.store.PageToken;
import keel.catalog.store.PendingDeletion;
import keel.catalog.store.WriteResult;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Files the maintenance worker deletes once the database's PITR window has passed (D30). Recording
 * share-locks the object, so a purge completing at the same moment waits, then deletes them with it.
 * A file already pending for the object is skipped and keeps its earlier time.
 */
final class SqlDeletions {

    private static final String COLUMNS = "d.id, d.object_id, d.catalog_id, d.location, d.not_before, d.recorded_at";

    private final Transactions transactions;

    SqlDeletions(Transactions transactions) {
        this.transactions = transactions;
    }

    WriteResult record(UUID objectId, List<String> locations, Instant notBefore, Change change) {
        return transactions.change(change, "deletions record", connection -> {
            Optional<UUID> catalogId = first(connection, "SELECT catalog_id FROM catalog_object WHERE id = ?"
                    + " AND state IN ('ACTIVE', 'DROPPED') FOR SHARE", row -> Jdbc.uuid(row, "catalog_id"), objectId);
            if (catalogId.isEmpty()) {
                return new WriteResult.Missing(WriteResult.Subject.OBJECT);
            }
            Set<String> fresh = new LinkedHashSet<>(locations);
            fresh.removeAll(query(connection, "SELECT location FROM pending_deletion WHERE object_id = ?"
                    + " AND location = ANY(?)", row -> row.getString("location"), objectId,
                    fresh.toArray(String[]::new)));
            if (fresh.isEmpty()) {
                return WriteResult.UNCHANGED;
            }
            // A recorder racing this one under the same share lock may record the same file first.
            try (PreparedStatement insert = connection.prepareStatement("INSERT INTO pending_deletion (id, object_id,"
                    + " catalog_id, location, not_before, recorded_at) VALUES (?, ?, ?, ?, ?, ?)"
                    + " ON CONFLICT (object_id, location) DO NOTHING")) {
                for (String location : fresh) {
                    Jdbc.bind(connection, insert, UUID.randomUUID(), objectId, catalogId.get(), location, notBefore,
                            change.at());
                    insert.addBatch();
                }
                insert.executeBatch();
            }
            return WriteResult.APPLIED;
        });
    }

    Page<PendingDeletion> listDue(Instant now, PageRequest page) {
        Optional<List<Object>> after = Jdbc.after(page, 2,
                parts -> List.of(Instant.parse(parts.get(0)), UUID.fromString(parts.get(1))));
        return transactions.autocommit("pending deletion scan", connection -> {
            List<Object> parameters = new ArrayList<>(List.of(now));
            String keyset = "";
            if (after.isPresent()) {
                keyset = " AND (d.not_before, d.id) > (?, ?)";
                parameters.addAll(after.get());
            }
            parameters.add(page.size() + 1);
            List<PendingDeletion> due = query(connection, "SELECT " + COLUMNS + " FROM pending_deletion d"
                    + " JOIN catalog_object o ON o.id = d.object_id WHERE d.not_before <= ?" + keyset
                    + " AND o.state IN ('ACTIVE', 'DROPPED') AND " + SqlObjects.UNCOVERED
                    + " ORDER BY d.not_before, d.id LIMIT ?", row -> new PendingDeletion(Jdbc.uuid(row, "id"),
                    Jdbc.uuid(row, "object_id"), Jdbc.uuid(row, "catalog_id"), row.getString("location"),
                    instant(row, "not_before"), instant(row, "recorded_at")), parameters.toArray());
            return Jdbc.page(due, page,
                    deletion -> PageToken.encode(deletion.notBefore().toString(), deletion.id().toString()));
        });
    }

    WriteResult complete(UUID objectId, List<UUID> ids, Change change) {
        return transactions.change(change, "deletions complete", connection -> update(connection,
                "DELETE FROM pending_deletion WHERE object_id = ? AND id = ANY(?)", objectId,
                ids.toArray(UUID[]::new)) == 0 ? WriteResult.UNCHANGED : WriteResult.APPLIED);
    }
}
