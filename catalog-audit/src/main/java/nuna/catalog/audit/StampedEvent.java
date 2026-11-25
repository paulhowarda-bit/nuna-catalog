package nuna.catalog.audit;

import java.util.Objects;

/**
 * An audit record with where it was logged: its server run and its number there ({@link LogStamp}), as the log
 * {@code nuna.audit} carries them. Kafka's copy of a record is this, byte for byte the log's line ({@link #encode}), so
 * a row the audit tables take from the topic can be placed in its run's archive chain as one from the log can (D23,
 * amended 2026-11-25; item 276).
 */
public record StampedEvent(CatalogEvent event, LogStamp stamp) {

    public StampedEvent {
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(stamp, "stamp");
    }

    /** The record as the log holds it: its CloudEvent JSON, carrying the stamp. */
    public byte[] encode() {
        return EventCodec.encode(event, stamp);
    }
}
