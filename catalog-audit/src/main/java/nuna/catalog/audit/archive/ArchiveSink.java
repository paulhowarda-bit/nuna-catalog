package nuna.catalog.audit.archive;

import nuna.catalog.audit.EventCodec;
import nuna.catalog.audit.EventType;
import nuna.catalog.audit.LogStamp;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeSet;
import java.util.UUID;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * Archives the records of the log {@code nuna.audit} as they arrive over the log path (D23, amended 2026-11-25;
 * backlog 262): the audit table writer hands it the body of every audit record its OTLP receiver takes, before any
 * of its own filters, and it keeps each byte for byte in {@link ObjectLockArchive}.
 *
 * <p>Each server run's records form one hash chain, named by the run ({@link LogStamp}); records that carry no stamp,
 * from a server older than the stamp, go to one chain of this writer's own, {@code unstamped-<writer>}. A chain is
 * written in segments of at most {@code segmentEvents} records or {@code segmentAge} of waiting, and a run's segment
 * as soon as its run-end record arrives. A record whose run and number this writer has already taken, from an exporter
 * that sent its batch again, is left out; one taken before the writer restarted is archived again, which the archive
 * check counts as a repeat. A chain already in the bucket is continued from its last segment.
 *
 * <p>After every round of segments, and every {@code checkpointInterval} when there were none, it writes a
 * {@link Checkpoint}: the heads the chains have now, linked to the checkpoint before. Records are held in memory
 * until their segment is written, so a writer that dies loses what waits, and that shows: as numbers missing from a
 * run whose later records arrive, or as a run with no run-end record. Past {@code maxWaiting} records waiting,
 * {@link #offer} refuses and the exporter retries.
 *
 * <p>{@link #offer} may be called from many threads; {@link #tick} and {@link #finish} from one.
 */
public final class ArchiveSink {

    /**
     * What one offer did with its records.
     *
     * @param archived records now waiting for their segment
     * @param repeated records whose run and number this writer already took, left out
     * @param refused  bodies that are no CloudEvent, carry a malformed stamp or break a line: nothing the catalog logs
     */
    public record Taken(int archived, int repeated, int refused) {
    }

    /** Too many records wait already: the exporter should send this batch again later. */
    public static final class Busy extends RuntimeException {

        Busy(int waiting) {
            super(waiting + " audit records wait for the archive");
        }
    }

    /**
     * What one round of writing did.
     *
     * @param segments   segments written
     * @param checkpoint the checkpoint written, if one was
     * @param problems   what could not be written, to be tried again next round
     */
    public record Round(int segments, Optional<ObjectLockArchive.Stored> checkpoint, List<String> problems) {
    }

    /** How long a chain with nothing waiting stays in memory after its last record, at the least. */
    static final Duration IDLE = Duration.ofMinutes(10);

    private static final String RUN_END = EventType.RUN_END.typeName();

    private final ObjectLockArchive archive;
    private final int segmentEvents;
    private final Duration segmentAge;
    private final Duration checkpointInterval;
    private final int maxWaiting;
    private final Clock clock;
    private final String writer = UUID.randomUUID().toString();
    private final String unstamped = "unstamped-" + writer;

    private final Object lock = new Object();
    // Guarded by lock.
    private final Map<String, Chain> chains = new HashMap<>();
    private int waiting;

    // The writing thread's own.
    private Optional<Checkpoint.Link> previous;
    private boolean previousKnown;
    /** The time of the last checkpoint, this writer's or, once read, its predecessor's: each is keyed after it. */
    private Instant lastCheckpoint = Instant.MIN;

    /** One segment closed for records and waiting to be written. */
    private record Sealed(ChainHead start, byte[] records, ChainHead end, int count) {
    }

    /** One chain as this writer holds it. */
    private final class Chain {

        final String name;
        ByteArrayOutputStream open = new ByteArrayOutputStream();
        ChainHead openStart;
        ArchiveWriter openWriter;
        int openCount;
        Instant openedAt;
        final Deque<Sealed> sealed = new ArrayDeque<>();
        ChainHead written;
        String writtenSegment;
        boolean anchorDue;
        long through;
        final TreeSet<Long> above = new TreeSet<>();
        Instant lastActivity;

        Chain(String name, ChainHead head, String segment) {
            this.name = name;
            this.openStart = head;
            this.openWriter = new ArchiveWriter(open, head);
            this.written = head;
            this.writtenSegment = segment;
            this.lastActivity = clock.instant();
        }

        /** False when the run and number were taken already. */
        boolean firstSight(LogStamp stamp) {
            long n = stamp.sequence();
            if (n <= through || above.contains(n)) {
                return false;
            }
            above.add(n);
            while (!above.isEmpty() && above.first() == through + 1) {
                through = above.pollFirst();
            }
            return true;
        }

        void append(byte[] body) {
            try {
                openWriter.appendLogged(List.of(body));
            } catch (IOException e) {
                // A ByteArrayOutputStream does not fail.
                throw new UncheckedIOException(e);
            }
            if (openCount++ == 0) {
                openedAt = clock.instant();
            }
            lastActivity = clock.instant();
        }

        void seal() {
            if (openCount == 0) {
                return;
            }
            ChainHead end = openWriter.head();
            sealed.addLast(new Sealed(openStart, open.toByteArray(), end, openCount));
            open = new ByteArrayOutputStream();
            openStart = end;
            openWriter = new ArchiveWriter(open, end);
            openCount = 0;
            openedAt = null;
        }
    }

    /**
     * @param segmentEvents      the most records in one segment
     * @param segmentAge         the longest a record waits before its segment is written
     * @param checkpointInterval how often a checkpoint is written when no segment was
     * @param maxWaiting         the most records waiting before {@link #offer} refuses
     */
    public ArchiveSink(ObjectLockArchive archive, int segmentEvents, Duration segmentAge, Duration checkpointInterval,
                       int maxWaiting, Clock clock) {
        this.archive = Objects.requireNonNull(archive, "archive");
        this.segmentAge = Objects.requireNonNull(segmentAge, "segmentAge");
        this.checkpointInterval = Objects.requireNonNull(checkpointInterval, "checkpointInterval");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (segmentEvents < 1 || maxWaiting < segmentEvents) {
            throw new IllegalArgumentException("a segment holds at least one record, and at least a segment's records"
                    + " may wait: " + segmentEvents + ", " + maxWaiting);
        }
        if (segmentAge.isNegative() || segmentAge.isZero() || checkpointInterval.isNegative()
                || checkpointInterval.isZero()) {
            throw new IllegalArgumentException("the segment age and the checkpoint interval are positive");
        }
        this.segmentEvents = segmentEvents;
        this.maxWaiting = maxWaiting;
    }

    /** This writer's own run, as its checkpoints name it. */
    public String writer() {
        return writer;
    }

    /**
     * Takes the bodies of one request's audit records, in order. A chain this writer does not hold yet is continued
     * from its last segment in the bucket, which this reads first.
     *
     * @throws Busy if more than {@code maxWaiting} records would wait; none of them is taken
     */
    public Taken offer(List<byte[]> bodies) {
        int refused = 0;
        Map<String, List<Logged>> byChain = new LinkedHashMap<>();
        for (byte[] body : bodies) {
            EventCodec.Envelope envelope;
            try {
                if (!ArchiveWriter.fitsOnALine(body)) {
                    throw new IllegalArgumentException("not one line");
                }
                envelope = EventCodec.envelope(body);
            } catch (IllegalArgumentException e) {
                refused++;
                continue;
            }
            String chain = envelope.stamp().map(s -> s.run().toString()).orElse(unstamped);
            byChain.computeIfAbsent(chain, c -> new ArrayList<>()).add(new Logged(body, envelope));
        }
        List<String> unknown;
        synchronized (lock) {
            unknown = byChain.keySet().stream().filter(c -> !chains.containsKey(c)).toList();
        }
        Map<String, Chain> resumed = new HashMap<>();
        for (String chain : unknown) {
            Optional<ObjectLockArchive.Segment> last = chain.equals(unstamped) ? Optional.empty() : archive.last(chain);
            resumed.put(chain, new Chain(chain, last.map(ObjectLockArchive.Segment::end).orElse(ChainHead.GENESIS),
                    last.map(ObjectLockArchive.Segment::key).orElse(null)));
        }
        int archived = 0;
        int repeated = 0;
        synchronized (lock) {
            int offered = byChain.values().stream().mapToInt(List::size).sum();
            if (offered > 0 && waiting + offered > maxWaiting) {
                throw new Busy(waiting);
            }
            resumed.forEach(chains::putIfAbsent);
            for (Map.Entry<String, List<Logged>> entry : byChain.entrySet()) {
                Chain chain = chains.get(entry.getKey());
                for (Logged logged : entry.getValue()) {
                    if (logged.envelope().stamp().isPresent() && !chain.firstSight(logged.envelope().stamp().get())) {
                        repeated++;
                        continue;
                    }
                    chain.append(logged.body());
                    waiting++;
                    archived++;
                    if (chain.openCount >= segmentEvents || logged.envelope().type().equals(RUN_END)) {
                        chain.seal();
                    }
                }
            }
        }
        return new Taken(archived, repeated, refused);
    }

    private record Logged(byte[] body, EventCodec.Envelope envelope) {
    }

    /**
     * Writes every segment that is full, old enough or closed by its run's end, each chain's in order, then a
     * checkpoint when it wrote any or the interval has passed since the last. What fails is tried again next round.
     */
    public Round tick() {
        return round(false);
    }

    private Round round(boolean everything) {
        Instant now = clock.instant();
        List<Chain> due = new ArrayList<>();
        synchronized (lock) {
            for (Chain chain : chains.values()) {
                if (chain.openCount > 0 && (everything || !chain.openedAt.plus(segmentAge).isAfter(now))) {
                    chain.seal();
                }
                if (!chain.sealed.isEmpty()) {
                    due.add(chain);
                }
            }
        }
        List<String> problems = new ArrayList<>();
        int segments = 0;
        for (Chain chain : due) {
            segments += writeSealed(chain, problems);
        }
        Optional<ObjectLockArchive.Stored> checkpoint = Optional.empty();
        boolean anchorsDue;
        synchronized (lock) {
            anchorsDue = chains.values().stream().anyMatch(c -> c.anchorDue);
        }
        if (anchorsDue || everything || !lastCheckpoint.plus(checkpointInterval).isAfter(now)) {
            try {
                checkpoint = Optional.of(checkpoint(now));
            } catch (RuntimeException e) {
                problems.add("checkpoint: " + e);
            }
        }
        synchronized (lock) {
            chains.values().removeIf(c -> c.openCount == 0 && c.sealed.isEmpty() && !c.anchorDue
                    && c.lastActivity.plus(IDLE.compareTo(segmentAge.multipliedBy(2)) > 0 ? IDLE
                    : segmentAge.multipliedBy(2)).isBefore(now));
        }
        return new Round(segments, checkpoint, List.copyOf(problems));
    }

    private int writeSealed(Chain chain, List<String> problems) {
        int written = 0;
        while (true) {
            Sealed next;
            synchronized (lock) {
                next = chain.sealed.peekFirst();
            }
            if (next == null) {
                return written;
            }
            ObjectLockArchive.Segment segment;
            try {
                segment = archive.write(chain.name, next.start(), next.records(), next.end());
            } catch (S3Exception e) {
                Optional<ObjectLockArchive.Segment> there = e.statusCode() == 412
                        ? archive.segmentAt(chain.name, next.start().sequence() + 1) : Optional.empty();
                if (there.isPresent() && there.get().start().equals(next.start()) && there.get().end().equals(next.end())) {
                    // Written before, its answer lost: the same records from the same head.
                    segment = there.get();
                } else {
                    problems.add("chain " + chain.name + ": segment from record " + (next.start().sequence() + 1)
                            + " not written" + (there.isPresent() ? ", its key holds another segment: is a second"
                            + " archive writer running?" : ": " + e.getMessage()));
                    return written;
                }
            } catch (RuntimeException e) {
                problems.add("chain " + chain.name + ": segment from record " + (next.start().sequence() + 1)
                        + " not written: " + e);
                return written;
            }
            synchronized (lock) {
                chain.sealed.pollFirst();
                chain.written = segment.end();
                chain.writtenSegment = segment.key();
                chain.anchorDue = true;
                waiting -= next.count();
            }
            written++;
        }
    }

    private ObjectLockArchive.Stored checkpoint(Instant now) {
        if (!previousKnown) {
            Optional<ObjectLockArchive.Stored> last = archive.lastCheckpoint();
            if (last.isPresent()) {
                byte[] bytes = archive.read(last.get().key(), last.get().versionId());
                previous = Optional.of(new Checkpoint.Link(last.get().key(), Checkpoint.sha256(bytes)));
                try {
                    lastCheckpoint = Checkpoint.parse(bytes).at();
                } catch (IllegalArgumentException e) {
                    // Not one this writer can read: linked all the same, and the check reports it.
                }
            } else {
                previous = Optional.empty();
            }
            previousKnown = true;
        }
        // Keyed to the millisecond and never at or before the last: two checkpoints in one millisecond, or a clock
        // behind its predecessor's, would otherwise share a key or sort out of order.
        Instant at = now.truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        if (!at.isAfter(lastCheckpoint)) {
            at = lastCheckpoint.truncatedTo(java.time.temporal.ChronoUnit.MILLIS).plusMillis(1);
        }
        List<Chain> anchored;
        List<Checkpoint.Anchor> anchors = new ArrayList<>();
        synchronized (lock) {
            anchored = chains.values().stream().filter(c -> c.anchorDue).sorted(
                    java.util.Comparator.comparing(c -> c.name)).toList();
            anchored.forEach(c -> anchors.add(new Checkpoint.Anchor(c.name, c.written, c.writtenSegment)));
        }
        byte[] json = new Checkpoint(writer, at, checkpointInterval, archive.retention(), previous, anchors).toJson();
        ObjectLockArchive.Stored stored = archive.writeCheckpoint(at, json);
        previous = Optional.of(new Checkpoint.Link(stored.key(), Checkpoint.sha256(json)));
        lastCheckpoint = at;
        synchronized (lock) {
            for (int i = 0; i < anchored.size(); i++) {
                Chain chain = anchored.get(i);
                // Anchored as far as this checkpoint says; a segment written since is due in the next.
                if (chain.written.equals(anchors.get(i).head())) {
                    chain.anchorDue = false;
                }
            }
        }
        return stored;
    }

    /** How many records wait for their segment. */
    public int waiting() {
        synchronized (lock) {
            return waiting;
        }
    }

    /**
     * Writes every waiting record, however young its segment, and a last checkpoint, as the writer stops: what fails is
     * in the round's problems, and is lost.
     */
    public Round finish() {
        return round(true);
    }
}
