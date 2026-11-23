package nuna.catalog.audit.archive;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/**
 * The archive check (D23; backlog 125): each chain of the audit archive verified up to the head the archive consumer
 * anchored outside the bucket, a run at a time. Each run reads only the segments written since the last head it
 * verified, from that head: a segment is locked once written (COMPLIANCE, D23), so what was verified stays verified,
 * and the run's records, linked to that head and ending at the anchor, verify the whole chain as a read from genesis
 * would. A run that finds a chain broken keeps its last verified head, so the break is found again on every run.
 *
 * <p>The heads verified are kept in {@link Heads}, outside the archive, so every server that checks shares them and a
 * restart does not read the whole archive again.
 */
public final class ArchiveCheck {

    /** Where the check keeps what it verified, and where it finds the archive consumer's anchors. */
    public interface Heads {

        /** The audit topic's partitions: one chain each. */
        List<Integer> partitions();

        /** The head the archive consumer recorded with each partition's committed offset, where it has one. */
        Map<Integer, Anchor> anchors();

        /** What earlier runs verified, by partition. */
        Map<Integer, Checked> checked();

        /** Keeps what this run verified. */
        void record(Map<Integer, Checked> checked);
    }

    /**
     * The archive consumer's anchor for a partition.
     *
     * @param nextOffset the first topic offset not yet archived
     * @param head       the chain's head after the last record archived
     */
    public record Anchor(long nextOffset, ChainHead head) {
    }

    /**
     * How far a chain was verified.
     *
     * @param head       the last record verified
     * @param nextOffset the first topic offset not verified: every record before it is
     * @param segmentKey the last segment verified, or null when none was
     * @param at         when it was verified to there
     */
    public record Checked(ChainHead head, long nextOffset, String segmentKey, Instant at) {

        public Checked {
            Objects.requireNonNull(head, "head");
            Objects.requireNonNull(at, "at");
        }

        /** {@code 1:<sequence>:<hash>:<epoch ms>:<segment key or empty>}, as a committed offset's metadata. */
        public String metadata() {
            return "1:" + head.sequence() + ":" + head.hash() + ":" + at.toEpochMilli() + ":"
                    + (segmentKey == null ? "" : segmentKey);
        }

        /** What {@link #metadata} wrote, with the offset it was committed at; empty for anything else. */
        public static Optional<Checked> parse(long nextOffset, String metadata) {
            if (metadata == null) {
                return Optional.empty();
            }
            String[] parts = metadata.split(":", 5);
            if (parts.length != 5 || !parts[0].equals("1")) {
                return Optional.empty();
            }
            try {
                return Optional.of(new Checked(new ChainHead(Long.parseLong(parts[1]), parts[2]), nextOffset,
                        parts[4].isEmpty() ? null : parts[4], Instant.ofEpochMilli(Long.parseLong(parts[3]))));
            } catch (NumberFormatException e) {
                return Optional.empty();
            }
        }
    }

    /**
     * One chain's outcome.
     *
     * @param verified what is verified now; empty while nothing of the chain ever was
     * @param records  the records this run verified
     * @param broken   what is wrong with the chain past {@code verified}, when it is broken
     * @param failed   why the chain could not be checked, when the store or a read failed
     */
    public record ChainResult(String chain, int partition, Optional<Checked> verified, long records,
                              Optional<String> broken, Optional<String> failed) {
    }

    /** A run's outcome: every chain whole up to its anchor when {@link #intact}. */
    public record Result(Instant startedAt, Instant endedAt, List<ChainResult> chains) {

        public boolean intact() {
            return chains.stream().allMatch(c -> c.broken().isEmpty() && c.failed().isEmpty());
        }
    }

    private final ObjectLockArchive archive;
    private final String topic;
    private final Heads heads;
    private final Clock clock;

    public ArchiveCheck(ObjectLockArchive archive, String topic, Heads heads, Clock clock) {
        this.archive = archive;
        this.topic = topic;
        this.heads = heads;
        this.clock = clock;
    }

    /** Checks every chain once. */
    public Result run() {
        Instant started = clock.instant();
        // The anchors first: the archive consumer writes a segment before it anchors it, so every anchored segment is
        // listed below.
        Map<Integer, Anchor> anchors = heads.anchors();
        Map<Integer, Checked> before = heads.checked();
        Map<Integer, Checked> after = new TreeMap<>();
        List<ChainResult> results = new ArrayList<>();
        for (int partition : heads.partitions().stream().sorted().toList()) {
            String chain = ArchiveConsumer.chain(topic, partition);
            Optional<Checked> was = Optional.ofNullable(before.get(partition));
            try {
                ChainResult result = check(chain, partition, was, Optional.ofNullable(anchors.get(partition)));
                result.verified().filter(c -> result.broken().isEmpty()).ifPresent(c -> after.put(partition, c));
                results.add(result);
            } catch (RuntimeException e) {
                results.add(new ChainResult(chain, partition, was, 0, Optional.empty(),
                        Optional.of(e.getClass().getSimpleName() + ": " + e.getMessage())));
            }
        }
        if (!after.isEmpty()) {
            heads.record(after);
        }
        return new Result(started, clock.instant(), List.copyOf(results));
    }

    private ChainResult check(String chain, int partition, Optional<Checked> was, Optional<Anchor> anchor) {
        Instant now = clock.instant();
        ChainHead from = was.map(Checked::head).orElse(ChainHead.GENESIS);
        if (anchor.isEmpty()) {
            return new ChainResult(chain, partition, was, 0, Optional.empty(), Optional.empty());
        }
        ChainHead to = anchor.get().head();
        if (to.sequence() <= from.sequence()) {
            if (to.sequence() == from.sequence() && !to.equals(from)) {
                return broken(chain, partition, was, "the archive consumer anchored " + to + " where the chain was"
                        + " verified to " + from);
            }
            // Nothing new anchored: verified as far as before, and looked at now.
            return new ChainResult(chain, partition, was.map(c -> new Checked(c.head(), c.nextOffset(),
                    c.segmentKey(), now)), 0, Optional.empty(), Optional.empty());
        }
        List<ObjectLockArchive.Segment> run = new ArrayList<>();
        for (ObjectLockArchive.Segment segment : archive.segmentsAfter(chain, was.map(Checked::segmentKey).orElse(null))) {
            if (segment.end().sequence() > to.sequence()) {
                break;
            }
            run.add(segment);
        }
        if (run.isEmpty() || run.getLast().end().sequence() != to.sequence()) {
            return broken(chain, partition, was, "the archive consumer anchored record " + to.sequence()
                    + ", but no segment of the chain ends there; the segments after " + from.sequence() + " end at "
                    + (run.isEmpty() ? "none" : String.valueOf(run.getLast().end().sequence())));
        }
        if (!run.getFirst().start().equals(from)) {
            return broken(chain, partition, was, "segment " + run.getFirst().key() + " starts from "
                    + run.getFirst().start() + ", but the chain was verified to " + from);
        }
        Verification verification = archive.verify(run, from);
        if (!(verification instanceof Verification.Intact)) {
            return broken(chain, partition, was, "after record " + from.sequence() + ": " + verification);
        }
        if (!run.getLast().end().equals(to)) {
            return broken(chain, partition, was, "the chain ends at " + run.getLast().end()
                    + ", but the archive consumer anchored " + to);
        }
        return new ChainResult(chain, partition, Optional.of(new Checked(to, run.getLast().nextOffset(),
                run.getLast().key(), now)), to.sequence() - from.sequence(), Optional.empty(), Optional.empty());
    }

    private static ChainResult broken(String chain, int partition, Optional<Checked> was, String why) {
        return new ChainResult(chain, partition, was, 0, Optional.of(why), Optional.empty());
    }
}
