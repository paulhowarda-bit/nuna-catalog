package nuna.catalog.audit.archive;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * One of the archive writer's checkpoints (D23, amended 2026-11-25; backlog 262): the heads of the chains it wrote
 * since the checkpoint before, locked in the bucket as the segments are. The writer writes one after every round of
 * segments and every {@code interval} when it wrote none, so a writer that stops shows as a late checkpoint, and a chain
 * that falls short of a head a checkpoint gave it shows to the archive check. Each names the checkpoint before it and
 * that checkpoint's SHA-256, so the checkpoints are a chain of their own.
 *
 * @param writer    the writer's own run: a new one each time the writer starts
 * @param at        when it was written
 * @param interval  how often the writer writes one when idle; the check calls a checkpoint late past twice this
 * @param retention how long the writer locks what it writes
 * @param previous  the checkpoint before it; empty for the archive's first
 * @param chains    the head each chain written since the checkpoint before has now, and its last segment
 */
public record Checkpoint(String writer, Instant at, Duration interval, Duration retention, Optional<Link> previous,
                         List<Anchor> chains) {

    static final int VERSION = 1;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** A checkpoint's key and the SHA-256 of its bytes, in lower-case hex. */
    public record Link(String key, String sha256) {

        public Link {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(sha256, "sha256");
        }
    }

    /** A chain's head as a checkpoint gives it, and the key of the segment that ends there. */
    public record Anchor(String chain, ChainHead head, String segment) {

        public Anchor {
            Objects.requireNonNull(chain, "chain");
            Objects.requireNonNull(head, "head");
            Objects.requireNonNull(segment, "segment");
        }
    }

    public Checkpoint {
        Objects.requireNonNull(writer, "writer");
        Objects.requireNonNull(at, "at");
        Objects.requireNonNull(interval, "interval");
        Objects.requireNonNull(retention, "retention");
        Objects.requireNonNull(previous, "previous");
        chains = List.copyOf(chains);
    }

    /** As it is stored: compact JSON. */
    public byte[] toJson() {
        ObjectNode node = JSON.createObjectNode();
        node.put("version", VERSION);
        node.put("writer", writer);
        node.put("at", at.toString());
        node.put("interval", interval.toString());
        node.put("retention", retention.toString());
        if (previous.isPresent()) {
            ObjectNode link = node.putObject("previous");
            link.put("key", previous.get().key());
            link.put("sha256", previous.get().sha256());
        } else {
            node.putNull("previous");
        }
        ArrayNode anchors = node.putArray("chains");
        for (Anchor anchor : chains) {
            ObjectNode each = anchors.addObject();
            each.put("chain", anchor.chain());
            each.put("sequence", anchor.head().sequence());
            each.put("hash", anchor.head().hash());
            each.put("segment", anchor.segment());
        }
        return JSON.writeValueAsBytes(node);
    }

    /** @throws IllegalArgumentException if the bytes are not a checkpoint {@link #toJson} would write */
    public static Checkpoint parse(byte[] json) {
        try {
            JsonNode node = JSON.readTree(json);
            if (node == null || !node.isObject() || node.path("version").asInt(0) != VERSION) {
                throw new IllegalArgumentException("not a version " + VERSION + " checkpoint");
            }
            JsonNode link = node.path("previous");
            Optional<Link> previous = link.isObject()
                    ? Optional.of(new Link(text(link, "key"), text(link, "sha256"))) : Optional.empty();
            List<Anchor> chains = new ArrayList<>();
            for (JsonNode each : node.path("chains")) {
                if (!each.path("sequence").canConvertToLong()) {
                    throw new IllegalArgumentException("a checkpoint's chain has no sequence");
                }
                chains.add(new Anchor(text(each, "chain"), new ChainHead(each.path("sequence").asLong(),
                        text(each, "hash")), text(each, "segment")));
            }
            return new Checkpoint(text(node, "writer"), Instant.parse(text(node, "at")),
                    Duration.parse(text(node, "interval")), Duration.parse(text(node, "retention")), previous, chains);
        } catch (JacksonException | DateTimeParseException e) {
            throw new IllegalArgumentException("not a checkpoint: " + e.getMessage(), e);
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isString()) {
            throw new IllegalArgumentException("a checkpoint has no " + field);
        }
        return value.asString();
    }

    /** The lower-case hex SHA-256 of {@code bytes}: how a checkpoint names the one before it. */
    public static String sha256(byte[] bytes) {
        try {
            return Chain.HEX.formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            // Every Java platform must provide SHA-256 (MessageDigest's contract).
            throw new IllegalStateException(e);
        }
    }
}
