package nuna.catalog.audit;

import java.net.URI;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One audit or change event, carried as a CloudEvents 1.0 envelope (D23). The type, data schema,
 * subject and partition key all follow from {@code data}, so they cannot disagree with it.
 *
 * <p>Build the event once, where the change happens: a commit stores its encoded events in the
 * outbox, so a relay that sends a batch twice sends the same ids, and consumers drop the copy.
 *
 * @param id     unique for this {@code source}
 * @param source the deployment that produced the event
 * @param time   when it happened
 * @param data   the versioned payload
 */
public record CatalogEvent(UUID id, URI source, Instant time, EventData data) {

    public CatalogEvent {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(time, "time");
        Objects.requireNonNull(data, "data");
        if (source.toString().isEmpty()) {
            throw new IllegalArgumentException("source must not be empty");
        }
    }

    /** A new event with a random id. */
    public static CatalogEvent create(URI source, Instant time, EventData data) {
        return new CatalogEvent(UUID.randomUUID(), source, time, data);
    }

    public EventType type() {
        return data.type();
    }

    /** What the event is about, or null; also the Kafka key, so one object's events stay in order. */
    public String subject() {
        return data.subject();
    }
}
