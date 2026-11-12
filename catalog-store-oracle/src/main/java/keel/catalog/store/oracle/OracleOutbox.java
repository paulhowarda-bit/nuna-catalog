package keel.catalog.store.oracle;

import static keel.catalog.store.oracle.Jdbc.first;
import static keel.catalog.store.oracle.Jdbc.instant;
import static keel.catalog.store.oracle.Jdbc.query;
import static keel.catalog.store.oracle.Jdbc.update;

import keel.catalog.store.JournalEntry;
import keel.catalog.store.JournalRecord;
import keel.catalog.store.OutboxBatch;
import keel.catalog.store.OutboxEntry;
import keel.catalog.store.Page;
import keel.catalog.store.PageRequest;
import keel.catalog.store.PageToken;
import keel.catalog.store.PointerKey;
import keel.catalog.store.WriteResult;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The outbox relay (D23) and the journal range read (D5). The relay takes the lowest remaining
 * sequence numbers and deletes what it relayed, so an event whose transaction commits after
 * higher-numbered ones were relayed is still taken: there is no high-water mark to skip it.
 */
final class OracleOutbox {

    private final Transactions transactions;

    OracleOutbox(Transactions transactions) {
        this.transactions = transactions;
    }

    private record Lease(UUID relayId, Instant until) {

        boolean heldByAnotherThan(UUID relayId, Instant now) {
            return this.relayId != null && !this.relayId.equals(relayId) && until.isAfter(now);
        }
    }

    Optional<OutboxBatch> claim(UUID relayId, Instant now, Duration lease, int limit) {
        return transactions.writeAndRetryOnFailure("outbox claim", connection -> {
            if (lockLease(connection).heldByAnotherThan(relayId, now)) {
                return Optional.<OutboxBatch>empty();
            }
            update(connection, "UPDATE outbox_relay SET relay_id = ?, lease_until = ?", relayId, now.plus(lease));
            List<OutboxEntry> entries = query(connection, "SELECT seq, commit_id, created_at, type, payload FROM outbox"
                            + " ORDER BY seq FETCH FIRST ? ROWS ONLY", row -> new OutboxEntry(Long.toString(row.getLong("seq")),
                            Jdbc.uuid(row, "commit_id"), instant(row, "created_at"), Jdbc.text(row, "type"),
                            Jdbc.text(row, "payload")), limit);
            return Optional.of(new OutboxBatch(relayId, entries));
        }, Optional::isPresent);
    }

    WriteResult markRelayed(UUID relayId, List<String> entryIds, Instant now) {
        List<Long> sequences = entryIds.stream().map(Long::valueOf).toList();
        return transactions.writeAndRetryOnFailure("outbox relay", connection -> {
            Lease lease = lockLease(connection);
            if (!relayId.equals(lease.relayId()) || !lease.until().isAfter(now)) {
                return new WriteResult.Refused(WriteResult.Reason.NOT_HOLDER);
            }
            List<Object> parameters = new ArrayList<>();
            update(connection, "DELETE FROM outbox WHERE " + Jdbc.in("seq", sequences, parameters), parameters.toArray());
            return WriteResult.APPLIED;
        }, WriteResult.APPLIED::equals);
    }

    Page<JournalRecord> readJournal(PointerKey key, Instant from, Instant to, PageRequest page) {
        Optional<Long> after = Jdbc.after(page, 1, parts -> Long.valueOf(parts.getFirst()));
        return transactions.autocommit("journal read", connection -> {
            List<Object> parameters = new ArrayList<>(List.of(key.objectId(), key.branch(), from, to));
            String keyset = after.isPresent() ? " AND " + Jdbc.greater(List.of("new_version"), List.of(after.get()), parameters)
                    : "";
            parameters.add(page.size() + 1);
            List<JournalRecord> records = query(connection, "SELECT commit_id, committed_at, principal, end_user, engine,"
                    + " requirements, updates, old_location, new_location, new_version FROM journal"
                    + " WHERE object_id = ? AND branch = ? AND committed_at >= ? AND committed_at < ?" + keyset
                    + " ORDER BY new_version FETCH FIRST ? ROWS ONLY", row -> new JournalRecord(Jdbc.uuid(row, "commit_id"),
                    key, instant(row, "committed_at"), new JournalEntry(Jdbc.text(row, "principal"),
                    Jdbc.text(row, "end_user"), Jdbc.text(row, "engine"), Jdbc.text(row, "requirements"),
                    Jdbc.text(row, "updates")), Optional.ofNullable(Jdbc.text(row, "old_location")),
                    Jdbc.text(row, "new_location"), row.getLong("new_version")), parameters.toArray());
            return Jdbc.page(records, page, record -> PageToken.encode(Long.toString(record.newVersion())));
        });
    }

    private static Lease lockLease(Connection connection) throws SQLException {
        return first(connection, "SELECT relay_id, lease_until FROM outbox_relay FOR UPDATE",
                row -> new Lease(Jdbc.uuid(row, "relay_id"), instant(row, "lease_until")))
                .orElseThrow(() -> new SQLException("the outbox_relay row is missing"));
    }
}
