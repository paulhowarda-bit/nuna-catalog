package nuna.catalog.audit.archive;

import nuna.catalog.audit.CatalogEvent;
import nuna.catalog.audit.EventCodec;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Consumer;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ChecksumAlgorithm;
import software.amazon.awssdk.services.s3.model.DeleteMarkerEntry;
import software.amazon.awssdk.services.s3.model.GetObjectLockConfigurationResponse;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.ObjectLockEnabled;
import software.amazon.awssdk.services.s3.model.ObjectLockMode;
import software.amazon.awssdk.services.s3.model.ObjectVersion;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * The audit archive (D23, amended 2026-11-25): the records of the log {@code nuna.audit} in an S3 bucket with Object
 * Lock, each object written once in COMPLIANCE mode and kept at least seven years (nuna's policy, D23), longer where
 * configured (CMP-1). No one, root included, can shorten the retention or delete a locked version before it lapses.
 *
 * <p>Under the archive's prefix:
 * <ul>
 * <li>{@code runs/<chain>/<first sequence>.log}: the segments of one hash chain. A chain holds one catalog server run's
 * records ({@link nuna.catalog.audit.LogStamp}), named by the run, or the records of servers that stamped none,
 * {@code unstamped-<writer>}. A segment's user metadata records the chain heads it starts from and ends at.</li>
 * <li>{@code checkpoints/<day>/<time>.json}: the archive writer's checkpoints ({@link Checkpoint}), locked like the
 * segments, each naming the one before it: the heads of the chains it wrote, outside the chains themselves.</li>
 * <li>{@code check/state.json}: how far the archive check has verified ({@link ArchiveCheck}), unlocked and
 * overwritten by each run; losing it costs one read of the archive from the start.</li>
 * </ul>
 *
 * <p>Objects are listed by version, so a delete marker hides nothing, and read by their first version, so a later
 * version written over a delete marker replaces nothing; both are reported ({@link #strays}).
 */
public final class ObjectLockArchive {

    /** Seven years, with their leap days: the floor on retention, nuna's policy (D23; NYDFS 500.6 asks three). */
    public static final Duration MINIMUM_RETENTION = Duration.ofDays(7 * 365 + 2);

    static final String START_SEQUENCE = "chain-start-sequence";
    static final String START_HASH = "chain-start-hash";
    static final String END_SEQUENCE = "chain-end-sequence";
    static final String END_HASH = "chain-end-hash";

    static final String RUNS = "runs";
    static final String CHECKPOINTS = "checkpoints";
    static final String STATE = "check/state.json";

    /**
     * Checkpoint keys sort as they were written: a folder per UTC day, then the time to the millisecond, so the newest
     * is found without listing every checkpoint since the archive began.
     */
    private static final DateTimeFormatter CHECKPOINT_TIME = DateTimeFormatter.ofPattern("yyyyMMdd/HHmmss.SSS'Z'")
            .withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter CHECKPOINT_DAY = DateTimeFormatter.ofPattern("yyyyMMdd")
            .withZone(ZoneOffset.UTC);
    /** How many days back from today the newest checkpoint is looked for, a day at a time, before every one is listed. */
    private static final int CHECKPOINT_DAYS_BACK = 31;

    /**
     * How many bytes of a chain a read holds at once besides the record it is checking: one buffer of the stream from
     * the store. A chain is read in one pass, segment by segment, never whole: it can outgrow any heap (D23).
     */
    static final int READ_BUFFER = 64 * 1024;

    /**
     * One written segment.
     *
     * @param key       its object key
     * @param versionId its object version: the first written under the key
     * @param start     the head its first record links to
     * @param end       the head after its last record
     * @param written   when the store took it
     */
    public record Segment(String key, String versionId, ChainHead start, ChainHead end, Instant written) {
    }

    /**
     * A version under the archive's prefix that is not the first version of its key: a delete marker, or a later
     * version written after one. Neither can hide or replace a locked first version, but neither should exist.
     *
     * @param deleteMarker whether it is a delete marker rather than a later version
     */
    public record Stray(String key, String versionId, boolean deleteMarker, Instant at) {
    }

    /**
     * One object read by its first version.
     *
     * @param key       its object key
     * @param versionId its first version
     * @param written   when the store took it
     */
    public record Stored(String key, String versionId, Instant written) {
    }

    private final S3Client s3;
    private final String bucket;
    private final String prefix;
    private final Duration retention;
    private final Clock clock;
    private final ObjectLockMode mode;
    private final int readBuffer;

    /**
     * @param prefix    the key prefix the archive lives under, without a trailing slash
     * @param retention how long each segment and checkpoint is locked; at least {@link #MINIMUM_RETENTION}
     */
    public ObjectLockArchive(S3Client s3, String bucket, String prefix, Duration retention, Clock clock) {
        this(s3, bucket, prefix, retention, clock, ObjectLockMode.COMPLIANCE);
    }

    /**
     * For tests on real AWS only: GOVERNANCE mode, so the test can delete what it wrote. Never reachable from
     * the writer, which always locks in COMPLIANCE mode (D23).
     */
    ObjectLockArchive(S3Client s3, String bucket, String prefix, Duration retention, Clock clock, ObjectLockMode mode) {
        this(s3, bucket, prefix, retention, clock, mode, READ_BUFFER);
    }

    /** For tests: {@code readBuffer} in place of {@link #READ_BUFFER}, so a small archive is larger than a read holds. */
    ObjectLockArchive(S3Client s3, String bucket, String prefix, Duration retention, Clock clock, ObjectLockMode mode,
                      int readBuffer) {
        this.mode = Objects.requireNonNull(mode, "mode");
        this.readBuffer = readBuffer;
        this.s3 = Objects.requireNonNull(s3, "s3");
        this.bucket = Objects.requireNonNull(bucket, "bucket");
        this.prefix = Objects.requireNonNull(prefix, "prefix");
        this.retention = Objects.requireNonNull(retention, "retention");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (retention.compareTo(MINIMUM_RETENTION) < 0) {
            throw new IllegalArgumentException("archive retention " + retention + " is below the seven-year floor "
                    + MINIMUM_RETENTION);
        }
        if (prefix.isEmpty() || prefix.endsWith("/")) {
            throw new IllegalArgumentException("the archive prefix is non-empty and has no trailing slash: " + prefix);
        }
    }

    /** How long each object is locked. */
    public Duration retention() {
        return retention;
    }

    /** @throws IllegalStateException unless the bucket has Object Lock enabled, which only bucket creation can turn on */
    public void checkBucket() {
        GetObjectLockConfigurationResponse lock;
        try {
            lock = s3.getObjectLockConfiguration(b -> b.bucket(bucket));
        } catch (S3Exception e) {
            throw new IllegalStateException("archive bucket " + bucket + " has no Object Lock configuration: "
                    + e.getMessage(), e);
        }
        if (lock.objectLockConfiguration() == null
                || lock.objectLockConfiguration().objectLockEnabled() != ObjectLockEnabled.ENABLED) {
            throw new IllegalStateException("archive bucket " + bucket + " does not have Object Lock enabled");
        }
    }

    /**
     * Writes one segment, locked in COMPLIANCE mode until now plus the retention. Refuses to replace an existing
     * segment: an S3Exception with status 412 when the key holds one ({@link #segmentAt} tells whether it is this one).
     *
     * @param records the chained records, as {@link ArchiveWriter} wrote them from {@code start} to {@code end}
     */
    public Segment write(String chain, ChainHead start, byte[] records, ChainHead end) {
        if (end.sequence() <= start.sequence()) {
            throw new IllegalArgumentException("a segment holds at least one record");
        }
        String key = key(chain, start.sequence() + 1);
        Map<String, String> metadata = Map.of(
                START_SEQUENCE, Long.toString(start.sequence()), START_HASH, start.hash(),
                END_SEQUENCE, Long.toString(end.sequence()), END_HASH, end.hash());
        Instant now = clock.instant();
        String version = locked(key, "application/x-ndjson", metadata, records, now);
        return new Segment(key, version, start, end, takenAt(key, version));
    }

    /** The segment whose first record is {@code firstSequence}, if one is stored. */
    public Optional<Segment> segmentAt(String chain, long firstSequence) {
        String key = key(chain, firstSequence);
        Map.Entry<String, ObjectVersion> found = firstVersions(chainPrefix(chain), null, key).lastEntry();
        return found == null || !found.getKey().equals(key) ? Optional.empty()
                : Optional.of(segment(found.getValue()));
    }

    private String locked(String key, String contentType, Map<String, String> metadata, byte[] body, Instant now) {
        return s3.putObject(b -> b.bucket(bucket).key(key)
                        .ifNoneMatch("*")
                        .contentType(contentType)
                        .metadata(metadata)
                        .checksumAlgorithm(ChecksumAlgorithm.SHA256)
                        .objectLockMode(mode)
                        .objectLockRetainUntilDate(now.plus(retention)),
                RequestBody.fromBytes(body)).versionId();
    }

    /** The chains in the archive, by name. */
    public List<String> chains() {
        List<String> chains = new ArrayList<>();
        String runs = prefix + "/" + RUNS + "/";
        s3.listObjectsV2Paginator(b -> b.bucket(bucket).prefix(runs).delimiter("/"))
                .commonPrefixes()
                .forEach(p -> chains.add(p.prefix().substring(runs.length(), p.prefix().length() - 1)));
        return chains;
    }

    /** Every segment of the chain, first to last, including any a delete marker hides. */
    public List<Segment> segments(String chain) {
        return segmentsAfter(chain, null);
    }

    /**
     * The chain's segments after the one keyed {@code afterKey}, first to last, including any a delete marker hides;
     * every segment when {@code afterKey} is null. The archive check reads only what was written since it last looked.
     */
    public List<Segment> segmentsAfter(String chain, String afterKey) {
        List<Segment> segments = new ArrayList<>();
        for (ObjectVersion version : firstVersions(chainPrefix(chain), afterKey, null).values()) {
            segments.add(segment(version));
        }
        return segments;
    }

    /** The chain's last segment, if it has any. */
    public Optional<Segment> last(String chain) {
        List<Segment> segments = segments(chain);
        return segments.isEmpty() ? Optional.empty() : Optional.of(segments.getLast());
    }

    /** The delete markers and later versions under the chain's keys. */
    public List<Stray> strays(String chain) {
        return strays(chainPrefix(chain), null);
    }

    /** The delete markers and later versions under the checkpoints' keys after {@code afterKey}, or all of them. */
    public List<Stray> checkpointStrays(String afterKey) {
        return strays(prefix + "/" + CHECKPOINTS + "/", afterKey);
    }

    /**
     * Writes a checkpoint, locked as the segments are, under a key that sorts by {@code at}.
     *
     * @return where it went
     */
    public Stored writeCheckpoint(Instant at, byte[] json) {
        String key = prefix + "/" + CHECKPOINTS + "/" + CHECKPOINT_TIME.format(at) + ".json";
        String version = locked(key, "application/json", Map.of(), json, clock.instant());
        return new Stored(key, version, takenAt(key, version));
    }

    /**
     * When the store took {@code version} of {@code key}, as a listing of versions gives it, so what a write returns
     * equals what a later listing finds: this process's clock is not the store's, and a HEAD answers to the second.
     */
    private Instant takenAt(String key, String version) {
        for (ObjectVersion v : s3.listObjectVersionsPaginator(b -> b.bucket(bucket).prefix(key)).versions()) {
            if (v.key().equals(key) && v.versionId().equals(version)) {
                return v.lastModified();
            }
        }
        throw new IllegalStateException("the store lists no version " + version + " of " + key + " just written");
    }

    /** The checkpoints after the one keyed {@code afterKey}, oldest first; every checkpoint when it is null. */
    public List<Stored> checkpointsAfter(String afterKey) {
        List<Stored> found = new ArrayList<>();
        firstVersions(prefix + "/" + CHECKPOINTS + "/", afterKey, null).values()
                .forEach(v -> found.add(new Stored(v.key(), v.versionId(), v.lastModified())));
        return found;
    }

    /** The newest checkpoint, if there is one: today's folder first, then a day further back at a time. */
    public Optional<Stored> lastCheckpoint() {
        Instant today = clock.instant();
        for (int back = 0; back <= CHECKPOINT_DAYS_BACK; back++) {
            String day = prefix + "/" + CHECKPOINTS + "/" + CHECKPOINT_DAY.format(today.minus(Duration.ofDays(back)))
                    + "/";
            Map.Entry<String, ObjectVersion> last = firstVersions(day, null, null).lastEntry();
            if (last != null) {
                return Optional.of(new Stored(last.getKey(), last.getValue().versionId(),
                        last.getValue().lastModified()));
            }
        }
        List<Stored> all = checkpointsAfter(null);
        return all.isEmpty() ? Optional.empty() : Optional.of(all.getLast());
    }

    /** An object's bytes, as {@code version} of {@code key} holds them. */
    public byte[] read(String key, String version) {
        return s3.getObjectAsBytes(b -> b.bucket(bucket).key(key).versionId(version)).asByteArray();
    }

    /**
     * Where a logged record lies in its run's chain, for the audit feed (backlog 272).
     *
     * @param segment  the segment holding it
     * @param position its place in the chain, from 1: the chain's own count, which repeats and losses set apart from
     *                 the record's number in its run
     * @param hash     the hash it stores
     * @param previous the hash of the record before it: the segment's start head for its first record
     * @param next     the hash of the record after it; empty while it is the chain's last
     * @param holds    whether its stored hash is the hash of the one before it and its content
     */
    public record Placement(Segment segment, long position, String hash, String previous, Optional<String> next,
                            boolean holds) {
    }

    /**
     * The place of record {@code number} of the run whose chain is {@code chain}, with the event id {@code eventId};
     * empty when no segment searched holds it. A run's records reach its chain in about the order they were numbered,
     * so the segment its number falls in is found by the numbers of the segments' first records, a binary search
     * reading one record of each segment it tries, and searched first; then the {@code reach} segments after it and
     * before it, nearest first. A record that arrived more than {@code reach} segments away from its number's place
     * is not found: {@link Integer#MAX_VALUE} searches the whole chain.
     */
    public Optional<Placement> place(String chain, long number, String eventId, int reach) {
        List<ObjectVersion> segments = new ArrayList<>(firstVersions(chainPrefix(chain), null, null).values());
        if (segments.isEmpty()) {
            return Optional.empty();
        }
        // The last segment whose first record's number is at or below the one wanted.
        int low = 0;
        int high = segments.size() - 1;
        while (low < high) {
            int middle = (low + high + 1) >>> 1;
            if (firstNumber(segments.get(middle)) <= number) {
                low = middle;
            } else {
                high = middle - 1;
            }
        }
        byte[] stamp = ("\"" + EventCodec.SEQUENCE + "\":\"" + number + "\"")
                .getBytes(StandardCharsets.UTF_8);
        byte[] id = ("\"id\":\"" + eventId + "\"").getBytes(StandardCharsets.UTF_8);
        for (int step = 0; step <= Math.min(reach, segments.size()); step++) {
            for (int at : step == 0 ? new int[] {low} : new int[] {low + step, low - step}) {
                if (at < 0 || at >= segments.size()) {
                    continue;
                }
                Optional<Placement> found = placeIn(chain, segment(segments.get(at)), stamp, id);
                if (found.isPresent()) {
                    return found;
                }
            }
        }
        return Optional.empty();
    }

    /** The number its run gave the segment's first record; {@link Long#MIN_VALUE} for one that carries none. */
    private long firstNumber(ObjectVersion version) {
        try (ResponseInputStream<GetObjectResponse> in = s3.getObject(r -> r.bucket(bucket).key(version.key())
                .versionId(version.versionId()))) {
            byte[] line = ArchiveVerifier.nextLine(new BufferedInputStream(in, 8 * 1024));
            in.abort();
            return line == null ? Long.MIN_VALUE : number(line);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The {@code nunaseq} a record line carries, or {@link Long#MIN_VALUE}. */
    private static long number(byte[] line) {
        byte[] key = ("\"" + EventCodec.SEQUENCE + "\":\"").getBytes(StandardCharsets.UTF_8);
        int at = indexOf(line, key);
        if (at < 0) {
            return Long.MIN_VALUE;
        }
        long number = 0;
        int digits = 0;
        for (int i = at + key.length; i < line.length && line[i] >= '0' && line[i] <= '9' && digits < 18; i++) {
            number = number * 10 + (line[i] - '0');
            digits++;
        }
        return digits == 0 ? Long.MIN_VALUE : number;
    }

    /** The record in {@code segment} whose line holds both {@code stamp} and {@code id}, read in one pass. */
    private Optional<Placement> placeIn(String chain, Segment segment, byte[] stamp, byte[] id) {
        try (InputStream in = new BufferedInputStream(s3.getObject(r -> r.bucket(bucket).key(segment.key())
                .versionId(segment.versionId())), readBuffer)) {
            byte[] previous = Chain.HEX.parseHex(segment.start().hash());
            byte[] line;
            while ((line = ArchiveVerifier.nextLine(in)) != null) {
                if (line.length < Chain.HASH_HEX_LENGTH + 2 || line[Chain.HASH_HEX_LENGTH] != ' ') {
                    return Optional.empty();
                }
                String stored = new String(line, 0, Chain.HASH_HEX_LENGTH, StandardCharsets.US_ASCII);
                if (indexOf(line, stamp) >= 0 && indexOf(line, id) >= 0) {
                    byte[] body = Arrays.copyOfRange(line, Chain.HASH_HEX_LENGTH + 1,
                            line.length - (line[line.length - 1] == '\n' ? 1 : 0));
                    boolean holds = Chain.HEX.formatHex(Chain.link(previous, body)).equals(stored);
                    byte[] after = ArchiveVerifier.nextLine(in);
                    Optional<String> next = after != null && after.length >= Chain.HASH_HEX_LENGTH
                            ? Optional.of(new String(after, 0, Chain.HASH_HEX_LENGTH, StandardCharsets.US_ASCII))
                            : firstHashAfter(chain, segment);
                    return Optional.of(new Placement(segment, position(body), stored, Chain.HEX.formatHex(previous),
                            next, holds));
                }
                try {
                    previous = Chain.HEX.parseHex(stored);
                } catch (IllegalArgumentException e) {
                    return Optional.empty();
                }
            }
            return Optional.empty();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The hash of the first record of the segment after {@code segment}, if there is one yet. */
    private Optional<String> firstHashAfter(String chain, Segment segment) throws IOException {
        Map.Entry<String, ObjectVersion> next = firstVersions(chainPrefix(chain), segment.key(), null).firstEntry();
        if (next == null) {
            return Optional.empty();
        }
        try (ResponseInputStream<GetObjectResponse> in = s3.getObject(r -> r.bucket(bucket).key(next.getKey())
                .versionId(next.getValue().versionId()))) {
            byte[] hash = in.readNBytes(Chain.HASH_HEX_LENGTH);
            in.abort();
            return hash.length == Chain.HASH_HEX_LENGTH
                    ? Optional.of(new String(hash, StandardCharsets.US_ASCII)) : Optional.empty();
        }
    }

    /** A record body's place in its chain, the number before its first space. */
    private static long position(byte[] body) {
        int end = 0;
        while (body[end] != ' ') {
            end++;
        }
        return Long.parseLong(new String(body, 0, end, StandardCharsets.US_ASCII));
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= haystack.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    /** What the archive check last kept, if anything. */
    public Optional<byte[]> readCheckState() {
        try {
            return Optional.of(s3.getObjectAsBytes(b -> b.bucket(bucket).key(prefix + "/" + STATE)).asByteArray());
        } catch (NoSuchKeyException e) {
            return Optional.empty();
        }
    }

    /** Keeps what the archive check verified, over what it kept before; unlocked, so the next run can replace it. */
    public void writeCheckState(byte[] json) {
        s3.putObject(b -> b.bucket(bucket).key(prefix + "/" + STATE).contentType("application/json")
                .checksumAlgorithm(ChecksumAlgorithm.SHA256), RequestBody.fromBytes(json));
    }

    /**
     * Checks a run of consecutive segments on its own: its records link to one another from {@code start}, the head
     * the first segment starts from, and end at the last segment's end. After a part of the chain already checked,
     * that is as good as checking the whole chain again, since a locked segment cannot change (D23).
     */
    public Verification verify(List<Segment> run, ChainHead start) {
        return verify(run, start, body -> { });
    }

    /**
     * As {@link #verify(List, ChainHead)}, handing {@code verified} each record's body, {@code <sequence> SP <event
     * JSON>}, once its hash checks.
     */
    public Verification verify(List<Segment> run, ChainHead start, Consumer<byte[]> verified) {
        try (InputStream records = new SegmentStream(run)) {
            return ArchiveVerifier.verify(new BufferedInputStream(records, readBuffer), start,
                    run.isEmpty() ? start : run.getLast().end(), verified);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Checks the whole chain from genesis: every record links to the one before it, across segments,
     * and the chain ends at {@code anchor}, a head kept outside the chain, or else at the last
     * segment's recorded end.
     */
    public Verification verify(String chain, Optional<ChainHead> anchor) {
        return read(chain, anchor, body -> { });
    }

    /**
     * The chain's events, in order, once it verifies as {@link #verify} does.
     *
     * @throws IllegalStateException if it is broken
     */
    public List<CatalogEvent> events(String chain, Optional<ChainHead> anchor) {
        List<CatalogEvent> events = new ArrayList<>();
        forEachEvent(chain, anchor, events::add);
        return events;
    }

    /**
     * Hands {@code each} the chain's events in order, as {@link #verify} reads and checks it: each event once its
     * record's hash checks. A chain broken further on has handed over every event before the break by the time this
     * throws, so the caller acts on none of them until it returns. An event that cannot be decoded, or that
     * {@code each} refuses, fails the read only once the chain is known to verify, as it did when the chain was read
     * whole and checked before any event was decoded.
     *
     * @throws IllegalStateException if the chain is broken
     */
    void forEachEvent(String chain, Optional<ChainHead> anchor, Consumer<CatalogEvent> each) {
        List<RuntimeException> refused = new ArrayList<>(1);
        Verification verification = read(chain, anchor, body -> {
            if (refused.isEmpty()) {
                try {
                    each.accept(event(body));
                } catch (RuntimeException e) {
                    refused.add(e);
                }
            }
        });
        if (!(verification instanceof Verification.Intact)) {
            throw new IllegalStateException("archive chain " + chain + " is broken: " + verification);
        }
        if (!refused.isEmpty()) {
            throw refused.getFirst();
        }
    }

    /**
     * Checks the chain from genesis in one pass, segment by segment, holding at most {@code readBuffer} bytes of it
     * besides the record being checked, and hands {@code verified} each record's body once its hash checks.
     */
    private Verification read(String chain, Optional<ChainHead> anchor, Consumer<byte[]> verified) {
        List<Segment> segments = segments(chain);
        try (InputStream records = new SegmentStream(segments)) {
            Verification verification = ArchiveVerifier.verify(new BufferedInputStream(records, readBuffer),
                    ChainHead.GENESIS, anchor.orElse(segments.isEmpty() ? ChainHead.GENESIS : segments.getLast().end()),
                    verified);
            // Fetch the segments after a break too, as the read that fetched every segment before checking any did:
            // a segment the store cannot serve still fails the read as unreadable before the break fails it as broken.
            records.transferTo(OutputStream.nullOutputStream());
            return verification;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The record JSON of a verified record's body, {@code <sequence> SP <event JSON>}. */
    static byte[] json(byte[] body) {
        int space = 0;
        while (body[space] != ' ') {
            space++;
        }
        return Arrays.copyOfRange(body, space + 1, body.length);
    }

    private static CatalogEvent event(byte[] body) {
        return EventCodec.decode(json(body));
    }

    /**
     * The first version of each key under {@code under} after {@code afterKey} (from the first when null) up to
     * {@code upTo} inclusive (to the last when null), by key. The listing stops once past {@code upTo}.
     */
    private TreeMap<String, ObjectVersion> firstVersions(String under, String afterKey, String upTo) {
        TreeMap<String, ObjectVersion> firstVersions = new TreeMap<>();
        for (ObjectVersion v : s3.listObjectVersionsPaginator(b -> {
            b.bucket(bucket).prefix(under);
            if (afterKey != null) {
                b.keyMarker(afterKey);
            }
        }).versions()) {
            if (upTo != null && v.key().compareTo(upTo) > 0) {
                break;
            }
            if (afterKey == null || v.key().compareTo(afterKey) > 0) {
                firstVersions.merge(v.key(), v, (a, b) -> a.lastModified().isBefore(b.lastModified()) ? a : b);
            }
        }
        return firstVersions;
    }

    /** Every delete marker under {@code under}, and every version of a key but its first. */
    private List<Stray> strays(String under, String afterKey) {
        List<Stray> strays = new ArrayList<>();
        Map<String, ObjectVersion> first = new TreeMap<>();
        List<ObjectVersion> later = new ArrayList<>();
        var pages = s3.listObjectVersionsPaginator(b -> {
            b.bucket(bucket).prefix(under);
            if (afterKey != null) {
                b.keyMarker(afterKey);
            }
        });
        pages.forEach(page -> {
            for (ObjectVersion v : page.versions()) {
                ObjectVersion was = first.get(v.key());
                if (was == null) {
                    first.put(v.key(), v);
                } else if (v.lastModified().isBefore(was.lastModified())) {
                    first.put(v.key(), v);
                    later.add(was);
                } else {
                    later.add(v);
                }
            }
            for (DeleteMarkerEntry marker : page.deleteMarkers()) {
                strays.add(new Stray(marker.key(), marker.versionId(), true, marker.lastModified()));
            }
        });
        later.forEach(v -> strays.add(new Stray(v.key(), v.versionId(), false, v.lastModified())));
        strays.sort(java.util.Comparator.comparing(Stray::key).thenComparing(Stray::at));
        return strays;
    }

    /** A chain's segments as one stream, each fetched only once the one before it has been read to its end. */
    private final class SegmentStream extends InputStream {

        private final Iterator<Segment> next;
        private InputStream current = InputStream.nullInputStream();

        SegmentStream(List<Segment> segments) {
            this.next = segments.iterator();
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            return read(one, 0, 1) == -1 ? -1 : one[0] & 0xff;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n;
            while ((n = current.read(b, off, len)) == -1 && next.hasNext()) {
                current.close();
                current = InputStream.nullInputStream();
                Segment segment = next.next();
                current = s3.getObject(r -> r.bucket(bucket).key(segment.key()).versionId(segment.versionId()));
            }
            return n;
        }

        @Override
        public void close() throws IOException {
            current.close();
        }
    }

    private Segment segment(ObjectVersion version) {
        HeadObjectResponse head = s3.headObject(b -> b.bucket(bucket).key(version.key())
                .versionId(version.versionId()));
        Map<String, String> metadata = head.metadata();
        return new Segment(version.key(), version.versionId(),
                new ChainHead(Long.parseLong(metadata.get(START_SEQUENCE)), metadata.get(START_HASH)),
                new ChainHead(Long.parseLong(metadata.get(END_SEQUENCE)), metadata.get(END_HASH)),
                version.lastModified());
    }

    private String chainPrefix(String chain) {
        if (chain.isEmpty() || chain.contains("/")) {
            throw new IllegalArgumentException("a chain name is non-empty and has no slash: " + chain);
        }
        return prefix + "/" + RUNS + "/" + chain + "/";
    }

    private String key(String chain, long firstSequence) {
        return chainPrefix(chain) + String.format("%019d", firstSequence) + ".log";
    }
}
