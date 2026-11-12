package keel.catalog.store;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The transactional outbox (D23), drained by one relay at a time under a lease. The order a relay
 * may rely on is in storage-design 9 b: every committed event is claimed eventually, at least once;
 * events of one change keep their order; and of two changes that touched the same record, the one
 * that committed first is claimed first. There is no order across unrelated changes.
 */
public interface OutboxStore {

    /**
     * Takes or renews the relay lease until {@code now + lease} and returns the next {@code limit}
     * unrelayed entries; empty while another relay holds a live lease. Entries stay until marked
     * relayed, so a relay that dies leaves them to the next.
     */
    Optional<OutboxBatch> claimOutbox(UUID relayId, Instant now, Duration lease, int limit);

    /**
     * Deletes relayed entries, if {@code relayId} still holds a live lease at {@code now}.
     *
     * @return {@code Applied} or {@code Refused(NOT_HOLDER)}
     */
    WriteResult markRelayed(UUID relayId, List<String> entryIds, Instant now);
}
