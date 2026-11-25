package nuna.catalog.audit.archive;

import nuna.catalog.audit.EventCodec;
import nuna.catalog.audit.EventType;
import nuna.catalog.audit.LogStamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The archive check (D23, amended 2026-11-25; backlogs 125 and 262). Each run:
 * <ul>
 * <li>follows the writer's checkpoints written since the last it verified: each must name the one before it, by key and
 * SHA-256, and none may give a chain a head short of one an earlier checkpoint gave it; the newest is <em>late</em>
 * when it is older than twice the writer's interval, as when the writer has stopped;</li>
 * <li>verifies each chain up to the newest head a checkpoint gave it, reading only the segments written since the last
 * head it verified, from that head: a locked segment cannot change (COMPLIANCE, D23), so what was verified stays
 * verified, and the new records, linked to that head and ending at the checkpoint's, verify the whole chain as a read
 * from genesis would;</li>
 * <li>for a run's chain, follows the records' numbers ({@link LogStamp}): a number below the highest archived that is
 * not archived is <em>missing</em> once it has been missing for twice the writer's interval, which leaves late
 * arrivals time to land; a number archived twice is a repeat, which loses nothing; and a run with no run-end record
 * whose highest number has not moved for {@link #UNENDED_AFTER} is <em>unended</em>: it stopped without closing, a
 * crash, a kill or a lost node, and its last lines may be lost, or it is a server that has written no audit record
 * since;</li>
 * <li>reports a delete marker or a second version on any key of a chain it reads, or of a checkpoint.</li>
 * </ul>
 *
 * <p>A run that finds a chain broken keeps the chain's last verified head, so the break is found again on every run.
 * What it verified is kept in the bucket ({@link ObjectLockArchive#writeCheckState}), so every server that checks
 * shares it and a restart does not read the whole archive again. A chain whose run ended with every number archived and
 * verified is done, and not read again until a checkpoint gives it a newer head.
 */
public final class ArchiveCheck {

    /** How long a run's highest number may stand still, without its run-end record, before the run is unended. */
    public static final Duration UNENDED_AFTER = Duration.ofDays(1);

    /** The writer's interval assumed before any checkpoint says what it is. */
    static final Duration DEFAULT_INTERVAL = Duration.ofMinutes(5);

    private static final String RUN_END = EventType.RUN_END.typeName();
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** Run numbers {@code from} to {@code to}, both included. */
    public record Range(long from, long to) {
    }

    /**
     * How far a chain is verified, and what its records' numbers show.
     *
     * @param head       the last record verified
     * @param segment    the last segment verified, or null when none was
     * @param at         when it was last looked at
     * @param highest    the highest run number verified; 0 for a chain with no stamped record
     * @param through    every run number up to this one is verified
     * @param missing    the run numbers below {@code highest} not archived, each range with when it was first missed
     * @param repeats    records verified whose run number was verified before
     * @param ended      whether the run's run-end record is verified
     * @param advancedAt when the store took the segment that moved {@code highest}, or the head of a chain of
     *                   unstamped records
     * @param done       the run ended, every number up to its end is verified, and nothing is left to read
     */
    public record Verified(ChainHead head, String segment, Instant at, long highest, long through,
                           List<Missed> missing, long repeats, boolean ended, Instant advancedAt, boolean done) {

        public Verified {
            Objects.requireNonNull(head, "head");
            Objects.requireNonNull(at, "at");
            missing = List.copyOf(missing);
        }

        static Verified fresh(Instant now) {
            return new Verified(ChainHead.GENESIS, null, now, 0, 0, List.of(), 0, false, now, false);
        }
    }

    /** Run numbers missing since {@code since}. */
    public record Missed(long from, long to, Instant since) {
    }

    /**
     * One chain's outcome.
     *
     * @param chain    its name: a run, or {@code unstamped-<writer>}
     * @param verified how far it is verified now
     * @param records  the records this run verified
     * @param missing  the run numbers missing long enough to report
     * @param unended  whether the run stopped without its run-end record, as far as anyone can tell
     * @param problems what is wrong with it: a break, a head that does not match its checkpoint, a delete marker or a
     *                 second version, segments no checkpoint gives a head to
     * @param failed   why the chain could not be checked, when the store or a read failed
     */
    public record ChainResult(String chain, Verified verified, long records, List<Range> missing, boolean unended,
                              List<String> problems, Optional<String> failed) {

        /** Whether the chain is a server run's, rather than the records of servers that stamped none. */
        public boolean run() {
            return !chain.startsWith("unstamped-");
        }
    }

    /**
     * The checkpoints' outcome.
     *
     * @param latest   the newest checkpoint, if there is any
     * @param at       when the writer wrote it
     * @param interval the writer's interval as it gives it
     * @param late     whether it is older than twice that interval, or there is none
     * @param problems a checkpoint that does not follow the one before, gives a chain a shorter head than before, is
     *                 not a checkpoint, or has a delete marker or a second version
     */
    public record Checkpoints(Optional<String> latest, Optional<Instant> at, Duration interval, boolean late,
                              List<String> problems) {
    }

    /** A run's outcome. */
    public record Result(Instant startedAt, Instant endedAt, Checkpoints checkpoints, List<ChainResult> chains) {

        /** Nothing was changed, removed or hidden, and the writer's checkpoints are on time. */
        public boolean intact() {
            return !checkpoints.late() && checkpoints.problems().isEmpty()
                    && chains.stream().allMatch(c -> c.problems().isEmpty() && c.failed().isEmpty());
        }

        /** No run is missing a record or stopped without ending. */
        public boolean complete() {
            return chains.stream().allMatch(c -> c.missing().isEmpty() && !c.unended());
        }
    }

    /** What the check keeps between runs. */
    record State(Optional<Checkpoint.Link> checkpoint, Optional<Instant> checkpointAt, Duration interval,
                 Map<String, Anchored> anchors, Map<String, Verified> chains) {
    }

    /** The newest head the checkpoints gave a chain. */
    record Anchored(ChainHead head, String segment) {
    }

    private final ObjectLockArchive archive;
    private final Clock clock;

    public ArchiveCheck(ObjectLockArchive archive, Clock clock) {
        this.archive = archive;
        this.clock = clock;
    }

    /** How far each chain was verified, as the last run left it in the bucket. */
    public Map<String, Verified> verified() {
        return load().chains();
    }

    /** Checks the checkpoints and every chain once. */
    public Result run() {
        Instant started = clock.instant();
        State before = load();
        List<String> checkpointProblems = new ArrayList<>();
        Map<String, Anchored> anchors = new TreeMap<>(before.anchors());
        Optional<Checkpoint.Link> link = before.checkpoint();
        Optional<Instant> latestAt = before.checkpointAt();
        Duration interval = before.interval();
        try {
            for (ObjectLockArchive.Stray stray : archive.checkpointStrays(link.map(Checkpoint.Link::key).orElse(null))) {
                checkpointProblems.add(describe(stray));
            }
            for (ObjectLockArchive.Stored stored : archive.checkpointsAfter(link.map(Checkpoint.Link::key)
                    .orElse(null))) {
                byte[] bytes = archive.read(stored.key(), stored.versionId());
                Checkpoint checkpoint;
                try {
                    checkpoint = Checkpoint.parse(bytes);
                } catch (IllegalArgumentException e) {
                    checkpointProblems.add(stored.key() + " is not a checkpoint: " + e.getMessage());
                    link = Optional.of(new Checkpoint.Link(stored.key(), Checkpoint.sha256(bytes)));
                    continue;
                }
                if (!checkpoint.previous().equals(link)) {
                    checkpointProblems.add(stored.key() + " names " + checkpoint.previous().map(Checkpoint.Link::key)
                            .orElse("no checkpoint") + " as the one before it, but the one before it is "
                            + link.map(l -> l.key() + " (" + l.sha256() + ")").orElse("none")
                            + (link.isPresent() && checkpoint.previous().isPresent()
                            && link.get().key().equals(checkpoint.previous().get().key())
                            ? ", whose bytes have another hash" : ""));
                }
                for (Checkpoint.Anchor anchor : checkpoint.chains()) {
                    Anchored was = anchors.get(anchor.chain());
                    if (was != null && anchor.head().sequence() < was.head().sequence()) {
                        checkpointProblems.add(stored.key() + " gives chain " + anchor.chain() + " the head "
                                + anchor.head() + ", short of " + was.head() + " an earlier checkpoint gave it");
                    } else if (was != null && anchor.head().sequence() == was.head().sequence()
                            && !anchor.head().equals(was.head())) {
                        checkpointProblems.add(stored.key() + " gives chain " + anchor.chain() + " the head "
                                + anchor.head() + ", where an earlier checkpoint gave it " + was.head());
                    } else {
                        anchors.put(anchor.chain(), new Anchored(anchor.head(), anchor.segment()));
                    }
                }
                link = Optional.of(new Checkpoint.Link(stored.key(), Checkpoint.sha256(bytes)));
                latestAt = Optional.of(checkpoint.at());
                interval = checkpoint.interval();
            }
        } catch (RuntimeException e) {
            checkpointProblems.add("the checkpoints could not be read: " + e.getClass().getSimpleName() + ": "
                    + e.getMessage());
        }
        Instant now = clock.instant();
        boolean late = latestAt.isEmpty() || latestAt.get().plus(interval.multipliedBy(2)).isBefore(now);

        TreeSet<String> names = new TreeSet<>(before.chains().keySet());
        names.addAll(anchors.keySet());
        List<ChainResult> results = new ArrayList<>();
        try {
            names.addAll(archive.chains());
        } catch (RuntimeException e) {
            checkpointProblems.add("the chains could not be listed: " + e.getMessage());
        }
        Map<String, Verified> after = new TreeMap<>();
        for (String chain : names) {
            Verified was = before.chains().getOrDefault(chain, Verified.fresh(now));
            try {
                ChainResult result = check(chain, was, Optional.ofNullable(anchors.get(chain)), interval, now);
                after.put(chain, result.verified());
                results.add(result);
            } catch (RuntimeException e) {
                after.put(chain, was);
                results.add(new ChainResult(chain, was, 0, List.of(), false, List.of(),
                        Optional.of(e.getClass().getSimpleName() + ": " + e.getMessage())));
            }
        }
        try {
            archive.writeCheckState(toJson(new State(link, latestAt, interval, anchors, after)));
        } catch (RuntimeException e) {
            checkpointProblems.add("what this check verified could not be kept: " + e.getMessage());
        }
        Checkpoints checkpoints = new Checkpoints(link.map(Checkpoint.Link::key), latestAt, interval, late,
                List.copyOf(checkpointProblems));
        return new Result(started, clock.instant(), checkpoints, List.copyOf(results));
    }

    private ChainResult check(String chain, Verified was, Optional<Anchored> anchor, Duration interval, Instant now) {
        boolean ofARun = !chain.startsWith("unstamped-");
        if (was.done() && anchor.map(a -> a.head().sequence() <= was.head().sequence()).orElse(true)) {
            return new ChainResult(chain, was, 0, List.of(), false, List.of(), Optional.empty());
        }
        List<String> problems = new ArrayList<>();
        archive.strays(chain).forEach(s -> problems.add(describe(s)));
        List<ObjectLockArchive.Segment> segments = archive.segmentsAfter(chain, was.segment());
        long anchoredTo = anchor.map(a -> a.head().sequence()).orElse(0L);
        Instant stale = now.minus(interval.multipliedBy(2));
        segments.stream().filter(s -> s.end().sequence() > Math.max(anchoredTo, was.head().sequence())
                        && s.written().isBefore(stale)).findFirst()
                .ifPresent(s -> problems.add("segment " + s.key() + ", written " + s.written() + ", ends past record "
                        + anchoredTo + ", the furthest any checkpoint gives this chain: no checkpoint covers it"));

        Numbers numbers = new Numbers(was);
        Verified verified = was;
        long records = 0;
        if (anchor.isPresent() && anchoredTo > was.head().sequence()) {
            ChainHead to = anchor.get().head();
            List<ObjectLockArchive.Segment> run = new ArrayList<>();
            for (ObjectLockArchive.Segment segment : segments) {
                if (segment.end().sequence() > to.sequence()) {
                    break;
                }
                run.add(segment);
            }
            if (run.isEmpty() || run.getLast().end().sequence() != to.sequence()) {
                problems.add("a checkpoint gives this chain the head " + to + ", but no segment ends at record "
                        + to.sequence() + "; the segments after record " + was.head().sequence() + " end at "
                        + (run.isEmpty() ? "none" : String.valueOf(run.getLast().end().sequence())));
            } else if (!run.getFirst().start().equals(was.head())) {
                problems.add("segment " + run.getFirst().key() + " starts from " + run.getFirst().start()
                        + ", but the chain was verified to " + was.head());
            } else {
                Numbers reading = new Numbers(was);
                List<String> recordProblems = new ArrayList<>();
                Verification verification = archive.verify(run, was.head(),
                        body -> reading.read(chain, body, now, recordProblems));
                if (!(verification instanceof Verification.Intact intact)) {
                    problems.add("after record " + was.head().sequence() + ": " + verification);
                } else if (!run.getLast().end().equals(to)) {
                    problems.add("the chain ends at " + run.getLast().end() + ", but a checkpoint gives it " + to);
                } else {
                    problems.addAll(recordProblems);
                    if (reading.advanced) {
                        // When the store took the segment holding the newest number: as near as the archive tells
                        // when the run last wrote.
                        reading.advancedAt = run.getLast().written();
                    }
                    numbers = reading;
                    records = intact.records();
                    verified = new Verified(to, run.getLast().key(), now, 0, 0, List.of(), 0, false, now, false);
                }
            }
        }
        Verified read = numbers.into(verified, now);
        boolean left = segments.stream().anyMatch(s -> s.end().sequence() > read.head().sequence());
        boolean done = problems.isEmpty() && !left && anchor.isPresent()
                && read.head().equals(anchor.get().head())
                && (ofARun ? read.ended() && read.missing().isEmpty() && read.through() >= read.highest()
                : read.advancedAt().plus(UNENDED_AFTER).isBefore(now));
        Verified kept = new Verified(read.head(), read.segment(), read.at(), read.highest(), read.through(),
                read.missing(), read.repeats(), read.ended(), read.advancedAt(), done);
        List<Range> missing = kept.missing().stream().filter(m -> !m.since().isAfter(stale))
                .map(m -> new Range(m.from(), m.to())).toList();
        boolean unended = ofARun && !kept.ended() && kept.highest() > 0
                && kept.advancedAt().plus(UNENDED_AFTER).isBefore(now);
        return new ChainResult(chain, kept, records, missing, unended, List.copyOf(problems), Optional.empty());
    }

    /** A chain's run numbers as its records are read: what is archived, what is missing, what repeats. */
    private static final class Numbers {

        long highest;
        final TreeMap<Long, Missed> missing = new TreeMap<>();
        long repeats;
        boolean ended;
        Instant advancedAt;
        boolean advanced;

        Numbers(Verified was) {
            highest = was.highest();
            was.missing().forEach(m -> missing.put(m.from(), m));
            repeats = was.repeats();
            ended = was.ended();
            advancedAt = was.advancedAt();
        }

        void read(String chain, byte[] body, Instant now, List<String> problems) {
            boolean stamped = !chain.startsWith("unstamped-");
            EventCodec.Envelope envelope;
            try {
                envelope = EventCodec.envelope(ObjectLockArchive.json(body));
            } catch (IllegalArgumentException e) {
                problems.add("record " + sequence(body) + " is no CloudEvent: " + e.getMessage());
                return;
            }
            if (!stamped) {
                advanced = true;
                return;
            }
            Optional<LogStamp> stamp = envelope.stamp();
            if (stamp.isEmpty() || !stamp.get().run().toString().equals(chain)) {
                problems.add("record " + sequence(body) + " belongs to "
                        + stamp.map(s -> "run " + s.run()).orElse("no run") + ", not to run " + chain);
                return;
            }
            long n = stamp.get().sequence();
            if (n > highest) {
                if (n > highest + 1) {
                    missing.put(highest + 1, new Missed(highest + 1, n - 1, now));
                }
                highest = n;
                advanced = true;
            } else if (!fill(n)) {
                repeats++;
            }
            if (envelope.type().equals(RUN_END)) {
                ended = true;
            }
        }

        /** Takes {@code n} out of the missing ranges; false when it was not missing. */
        private boolean fill(long n) {
            Map.Entry<Long, Missed> holder = missing.floorEntry(n);
            if (holder == null || holder.getValue().to() < n) {
                return false;
            }
            Missed range = missing.remove(holder.getKey());
            if (range.from() < n) {
                missing.put(range.from(), new Missed(range.from(), n - 1, range.since()));
            }
            if (n < range.to()) {
                missing.put(n + 1, new Missed(n + 1, range.to(), range.since()));
            }
            return true;
        }

        Verified into(Verified head, Instant now) {
            long through = missing.isEmpty() ? highest : missing.firstKey() - 1;
            return new Verified(head.head(), head.segment(), now, highest, through, List.copyOf(missing.values()),
                    repeats, ended, advancedAt, false);
        }

        private static String sequence(byte[] body) {
            int end = 0;
            while (end < body.length && body[end] != ' ') {
                end++;
            }
            return new String(body, 0, end, java.nio.charset.StandardCharsets.US_ASCII);
        }
    }

    private static String describe(ObjectLockArchive.Stray stray) {
        return (stray.deleteMarker() ? "a delete marker on " : "a second version of ") + stray.key() + " ("
                + stray.versionId() + ", " + stray.at() + ")";
    }

    private State load() {
        Optional<byte[]> bytes = archive.readCheckState();
        if (bytes.isEmpty()) {
            return new State(Optional.empty(), Optional.empty(), DEFAULT_INTERVAL, Map.of(), Map.of());
        }
        try {
            return parse(bytes.get());
        } catch (IllegalArgumentException e) {
            // Only what earlier runs verified is lost: this run reads the archive from the start.
            return new State(Optional.empty(), Optional.empty(), DEFAULT_INTERVAL, Map.of(), Map.of());
        }
    }

    static byte[] toJson(State state) {
        ObjectNode node = JSON.createObjectNode();
        node.put("version", 1);
        if (state.checkpoint().isPresent()) {
            ObjectNode link = node.putObject("checkpoint");
            link.put("key", state.checkpoint().get().key());
            link.put("sha256", state.checkpoint().get().sha256());
        }
        state.checkpointAt().ifPresent(at -> node.put("checkpoint-at", at.toString()));
        node.put("interval", state.interval().toString());
        ArrayNode anchors = node.putArray("anchors");
        state.anchors().forEach((chain, anchored) -> {
            ObjectNode each = anchors.addObject();
            each.put("chain", chain);
            each.put("sequence", anchored.head().sequence());
            each.put("hash", anchored.head().hash());
            each.put("segment", anchored.segment());
        });
        ArrayNode chains = node.putArray("chains");
        state.chains().forEach((chain, v) -> {
            ObjectNode each = chains.addObject();
            each.put("chain", chain);
            each.put("sequence", v.head().sequence());
            each.put("hash", v.head().hash());
            each.put("segment", v.segment());
            each.put("at", v.at().toString());
            each.put("highest", v.highest());
            each.put("through", v.through());
            ArrayNode missing = each.putArray("missing");
            v.missing().forEach(m -> missing.addObject().put("from", m.from()).put("to", m.to())
                    .put("since", m.since().toString()));
            each.put("repeats", v.repeats());
            each.put("ended", v.ended());
            each.put("advanced-at", v.advancedAt().toString());
            each.put("done", v.done());
        });
        return JSON.writeValueAsBytes(node);
    }

    static State parse(byte[] json) {
        try {
            JsonNode node = JSON.readTree(json);
            if (node == null || node.path("version").asInt(0) != 1) {
                throw new IllegalArgumentException("not a version 1 check state");
            }
            JsonNode link = node.path("checkpoint");
            Optional<Checkpoint.Link> checkpoint = link.isObject()
                    ? Optional.of(new Checkpoint.Link(link.path("key").asString(), link.path("sha256").asString()))
                    : Optional.empty();
            Optional<Instant> checkpointAt = node.hasNonNull("checkpoint-at")
                    ? Optional.of(Instant.parse(node.path("checkpoint-at").asString())) : Optional.empty();
            Map<String, Anchored> anchors = new TreeMap<>();
            for (JsonNode each : node.path("anchors")) {
                anchors.put(each.path("chain").asString(), new Anchored(new ChainHead(each.path("sequence").asLong(),
                        each.path("hash").asString()), each.path("segment").asString()));
            }
            Map<String, Verified> chains = new TreeMap<>();
            for (JsonNode each : node.path("chains")) {
                List<Missed> missing = new ArrayList<>();
                for (JsonNode m : each.path("missing")) {
                    missing.add(new Missed(m.path("from").asLong(), m.path("to").asLong(),
                            Instant.parse(m.path("since").asString())));
                }
                chains.put(each.path("chain").asString(), new Verified(new ChainHead(each.path("sequence").asLong(),
                        each.path("hash").asString()), each.hasNonNull("segment") ? each.path("segment").asString()
                        : null, Instant.parse(each.path("at").asString()), each.path("highest").asLong(),
                        each.path("through").asLong(), missing, each.path("repeats").asLong(),
                        each.path("ended").asBoolean(), Instant.parse(each.path("advanced-at").asString()),
                        each.path("done").asBoolean()));
            }
            return new State(checkpoint, checkpointAt, Duration.parse(node.path("interval").asString()), anchors,
                    chains);
        } catch (JacksonException | DateTimeParseException | NullPointerException e) {
            throw new IllegalArgumentException("not a check state: " + e.getMessage(), e);
        }
    }
}
