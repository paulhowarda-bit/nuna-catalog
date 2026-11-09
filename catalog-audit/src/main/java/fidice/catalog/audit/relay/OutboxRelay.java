package fidice.catalog.audit.relay;

import fidice.catalog.audit.CatalogEvent;
import fidice.catalog.audit.EventCodec;
import fidice.catalog.audit.publish.RelayTarget;
import fidice.catalog.store.OutboxBatch;
import fidice.catalog.store.OutboxEntry;
import fidice.catalog.store.OutboxStore;
import fidice.catalog.store.WriteResult;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Relays the transactional outbox to its targets in outbox order (D23, storage-design 9 b): claim
 * the next batch under the relay lease, deliver it whole, then mark it relayed. A batch whose
 * delivery fails stays in the outbox and is claimed again, so delivery is at least once and
 * consumers deduplicate by event id. Only one relay drains at a time; the others find the lease
 * held and relay nothing.
 */
public final class OutboxRelay {

    private static final System.Logger LOG = System.getLogger(OutboxRelay.class.getName());

    private final OutboxStore store;
    private final RelayTarget target;
    private final Clock clock;
    private final Duration lease;
    private final int batchSize;
    private final UUID relayId = UUID.randomUUID();

    /**
     * @param lease     how long a claim holds the outbox; longer than one batch takes to deliver
     * @param batchSize the most entries one claim takes
     */
    public OutboxRelay(OutboxStore store, RelayTarget target, Clock clock, Duration lease, int batchSize) {
        this.store = Objects.requireNonNull(store, "store");
        this.target = Objects.requireNonNull(target, "target");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.lease = Objects.requireNonNull(lease, "lease");
        if (batchSize < 1) {
            throw new IllegalArgumentException("batch size must be at least 1, got " + batchSize);
        }
        this.batchSize = batchSize;
    }

    /**
     * One claim, delivery and mark.
     *
     * @return the entries relayed: 0 when the outbox is empty, another relay holds the lease, or this
     *         relay lost the lease while delivering (the next holder delivers the batch again)
     * @throws fidice.catalog.audit.publish.AuditUnavailableException if delivery failed; nothing is marked
     * @throws IllegalArgumentException if an entry is not an event the codec reads; nothing is delivered
     */
    public int relayOnce() {
        Optional<OutboxBatch> claimed = store.claimOutbox(relayId, clock.instant(), lease, batchSize);
        if (claimed.isEmpty() || claimed.get().entries().isEmpty()) {
            return 0;
        }
        List<OutboxEntry> entries = claimed.get().entries();
        List<CatalogEvent> events = entries.stream()
                .map(entry -> {
                    try {
                        return EventCodec.decode(entry.payloadJson().getBytes(StandardCharsets.UTF_8));
                    } catch (IllegalArgumentException e) {
                        throw new IllegalArgumentException("outbox entry " + entry.id() + " of change "
                                + entry.changeId() + " is not a catalog event: " + e.getMessage(), e);
                    }
                })
                .toList();
        target.deliver(events);
        WriteResult marked = store.markRelayed(relayId, entries.stream().map(OutboxEntry::id).toList(), clock.instant());
        if (!(marked instanceof WriteResult.Applied)) {
            LOG.log(System.Logger.Level.WARNING, "the outbox lease lapsed while {0} events were delivered ({1});"
                    + " the next relay delivers them again", entries.size(), marked);
            return 0;
        }
        return entries.size();
    }

    /**
     * Relays batch after batch until the outbox is empty or a claim comes back short.
     *
     * @return the entries relayed
     */
    public int drain() {
        int total = 0;
        int relayed;
        do {
            relayed = relayOnce();
            total += relayed;
        } while (relayed == batchSize);
        return total;
    }
}
