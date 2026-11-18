package nuna.catalog.store;

import java.time.Instant;
import java.util.UUID;

/**
 * An outbox event as the relay sees it. {@code id} is opaque, stable and unique: consumers
 * deduplicate by it, because delivery is at least once (D23).
 */
public record OutboxEntry(String id, UUID changeId, Instant createdAt, String type, String payloadJson) {
}
