package nuna.catalog.audit;

import java.net.URI;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Writes and reads events in the CloudEvents 1.0 JSON format, structured mode: one compact JSON
 * object holding the attributes and, under {@code data}, the payload as a JSON value. The same
 * bytes go to the outbox, to Kafka and to the archive.
 *
 * <p>Reading is strict: a payload must carry every field of its schema version and nothing else,
 * and the envelope must agree with it. The envelope may carry more attributes than these; the log's records carry
 * their {@link LogStamp} as two of them, {@value #RUN} and {@value #SEQUENCE}, which {@link #decode} leaves aside.
 */
public final class EventCodec {

    /** The Kafka {@code content-type} header value for an encoded event (structured mode). */
    public static final String MEDIA_TYPE = "application/cloudevents+json; charset=UTF-8";

    /** The extension attribute naming the server run that logged a record ({@link LogStamp#run}). */
    public static final String RUN = "nunarun";
    /** The extension attribute holding the record's number in its run ({@link LogStamp#sequence}), as a string. */
    public static final String SEQUENCE = "nunaseq";

    static final String SPEC_VERSION = "1.0";
    static final String DATA_CONTENT_TYPE = "application/json";

    static final JsonMapper MAPPER = JsonMapper.builder()
            .disable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .build();

    private EventCodec() {
    }

    /** UTF-8 JSON on one line. */
    public static byte[] encode(CatalogEvent event) {
        return MAPPER.writeValueAsBytes(envelope(event, null));
    }

    /** UTF-8 JSON on one line, carrying where the record was logged: the bytes the log {@code nuna.audit} holds. */
    public static byte[] encode(CatalogEvent event, LogStamp stamp) {
        return MAPPER.writeValueAsBytes(envelope(event, Objects.requireNonNull(stamp, "stamp")));
    }

    private static ObjectNode envelope(CatalogEvent event, LogStamp stamp) {
        ObjectNode envelope = MAPPER.createObjectNode();
        envelope.put("specversion", SPEC_VERSION);
        envelope.put("id", event.id().toString());
        envelope.put("source", event.source().toString());
        envelope.put("type", event.type().typeName());
        envelope.put("datacontenttype", DATA_CONTENT_TYPE);
        envelope.put("dataschema", event.type().dataSchema().toString());
        if (event.subject() != null) {
            envelope.put("subject", event.subject());
        }
        envelope.put("time", event.time().toString());
        if (event.subject() != null) {
            envelope.put("partitionkey", event.subject());
        }
        if (stamp != null) {
            envelope.put(RUN, stamp.run().toString());
            envelope.put(SEQUENCE, Long.toString(stamp.sequence()));
        }
        envelope.set("data", MAPPER.valueToTree(event.data()));
        return envelope;
    }

    /**
     * A logged record's type and stamp, read from its envelope alone: the archive keeps every record of the log as it
     * was written, including one of a type or payload version this codec does not know.
     *
     * @param type  the CloudEvents {@code type}
     * @param stamp where it was logged; empty for a record from a server that did not stamp its records
     */
    public record Envelope(String type, Optional<LogStamp> stamp) {
    }

    /** @throws IllegalArgumentException if the bytes are no CloudEvent, or carry half a stamp or a malformed one */
    public static Envelope envelope(byte[] json) {
        try {
            JsonNode envelope = MAPPER.readTree(json);
            if (envelope == null || !envelope.isObject()) {
                throw new IllegalArgumentException("not a JSON object");
            }
            String type = text(envelope, "type");
            boolean run = envelope.has(RUN);
            boolean sequence = envelope.has(SEQUENCE);
            if (!run && !sequence) {
                return new Envelope(type, Optional.empty());
            }
            if (run != sequence) {
                throw new IllegalArgumentException("a stamp carries both " + RUN + " and " + SEQUENCE);
            }
            String number = text(envelope, SEQUENCE);
            if (!number.matches("[1-9][0-9]{0,17}")) {
                throw new IllegalArgumentException(SEQUENCE + " must be a whole number from 1, not " + number);
            }
            return new Envelope(type, Optional.of(new LogStamp(UUID.fromString(text(envelope, RUN)),
                    Long.parseLong(number))));
        } catch (JacksonException e) {
            throw new IllegalArgumentException("not a catalog event: " + e.getMessage(), e);
        }
    }

    /** @throws IllegalArgumentException if the bytes are not an event this codec would write */
    public static CatalogEvent decode(byte[] json) {
        try {
            JsonNode envelope = MAPPER.readTree(json);
            if (!SPEC_VERSION.equals(text(envelope, "specversion"))) {
                throw new IllegalArgumentException("specversion must be " + SPEC_VERSION);
            }
            EventType type = EventType.fromTypeName(text(envelope, "type"));
            if (!type.dataSchema().toString().equals(text(envelope, "dataschema"))) {
                throw new IllegalArgumentException("unsupported data schema " + text(envelope, "dataschema")
                        + " for " + type.typeName());
            }
            if (!DATA_CONTENT_TYPE.equals(text(envelope, "datacontenttype"))) {
                throw new IllegalArgumentException("datacontenttype must be " + DATA_CONTENT_TYPE);
            }
            JsonNode data = envelope.get("data");
            if (data == null || !data.isObject()) {
                throw new IllegalArgumentException("data must be a JSON object");
            }
            CatalogEvent event = new CatalogEvent(UUID.fromString(text(envelope, "id")),
                    URI.create(text(envelope, "source")), Instant.parse(text(envelope, "time")),
                    MAPPER.treeToValue(data, type.dataClass()));
            requireAgrees(envelope, "subject", event.subject());
            requireAgrees(envelope, "partitionkey", event.subject());
            return event;
        } catch (JacksonException | DateTimeParseException e) {
            throw new IllegalArgumentException("not a catalog event: " + e.getMessage(), e);
        }
    }

    private static String text(JsonNode envelope, String attribute) {
        JsonNode value = envelope.get(attribute);
        if (value == null || !value.isString()) {
            throw new IllegalArgumentException(attribute + " is missing");
        }
        return value.asString();
    }

    private static void requireAgrees(JsonNode envelope, String attribute, String expected) {
        String actual = envelope.has(attribute) ? text(envelope, attribute) : null;
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new IllegalArgumentException(attribute + " " + actual + " does not match the data (" + expected + ")");
        }
    }
}
