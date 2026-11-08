package fidice.catalog.store;

import java.util.List;
import java.util.UUID;

/** Outbox entries claimed by the relay that holds the lease, in relay order (storage-design 9 b). */
public record OutboxBatch(UUID relayId, List<OutboxEntry> entries) {

    public OutboxBatch {
        entries = List.copyOf(entries);
    }
}
