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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Consumer;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ChecksumAlgorithm;
import software.amazon.awssdk.services.s3.model.GetObjectLockConfigurationResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ObjectLockEnabled;
import software.amazon.awssdk.services.s3.model.ObjectLockMode;
import software.amazon.awssdk.services.s3.model.ObjectVersion;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * The audit archive (D23): hash-chained segments in an S3 bucket with Object Lock, each written once
 * in COMPLIANCE mode and kept at least seven years (nuna's policy, D23), longer where configured (CMP-1). No
 * one, root included, can shorten the retention or delete a locked version before it lapses.
 *
 * <p>Each chain (one per audit-topic partition) is a run of segments under
 * {@code <prefix>/<chain>/}, named by the sequence number of their first record. A segment's user
 * metadata records the chain heads it starts from and ends at and the next topic offset to archive,
 * so a restarted consumer resumes exactly where the last segment ended. Segments are listed by
 * version, so a delete marker cannot hide one.
 */
public final class ObjectLockArchive {

    /** Seven years, with their leap days: the floor on retention, nuna's policy (D23; NYDFS 500.6 asks three). */
    public static final Duration MINIMUM_RETENTION = Duration.ofDays(7 * 365 + 2);

    static final String START_SEQUENCE = "chain-start-sequence";
    static final String START_HASH = "chain-start-hash";
    static final String END_SEQUENCE = "chain-end-sequence";
    static final String END_HASH = "chain-end-hash";
    static final String NEXT_OFFSET = "next-offset";

    /**
     * How many bytes of a chain a read holds at once besides the record it is checking: one buffer of the stream from
     * the store. A chain is read in one pass, segment by segment, never whole: it can outgrow any heap (D23).
     */
    static final int READ_BUFFER = 64 * 1024;

    /**
     * One written segment.
     *
     * @param key        its object key
     * @param versionId  its object version
     * @param start      the head its first record links to
     * @param end        the head after its last record
     * @param nextOffset the first topic offset not in this or any earlier segment of the chain
     */
    public record Segment(String key, String versionId, ChainHead start, ChainHead end, long nextOffset) {
    }

    private final S3Client s3;
    private final String bucket;
    private final String prefix;
    private final Duration retention;
    private final Clock clock;
    private final ObjectLockMode mode;
    private final int readBuffer;

    /**
     * @param prefix    the key prefix every chain lives under, without a trailing slash
     * @param retention how long each segment is locked; at least {@link #MINIMUM_RETENTION}
     */
    public ObjectLockArchive(S3Client s3, String bucket, String prefix, Duration retention, Clock clock) {
        this(s3, bucket, prefix, retention, clock, ObjectLockMode.COMPLIANCE);
    }

    /**
     * For tests on real AWS only: GOVERNANCE mode, so the test can delete what it wrote. Never reachable from
     * the server, which always locks in COMPLIANCE mode (D23).
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
     * Writes one segment, locked in COMPLIANCE mode until now plus the retention. Refuses to replace an
     * existing segment.
     *
     * @param records the chained records, as {@link ArchiveWriter} wrote them from {@code start} to {@code end}
     */
    public Segment write(String chain, ChainHead start, byte[] records, ChainHead end, long nextOffset) {
        if (end.sequence() <= start.sequence()) {
            throw new IllegalArgumentException("a segment holds at least one record");
        }
        String key = key(chain, start.sequence() + 1);
        Map<String, String> metadata = Map.of(
                START_SEQUENCE, Long.toString(start.sequence()), START_HASH, start.hash(),
                END_SEQUENCE, Long.toString(end.sequence()), END_HASH, end.hash(),
                NEXT_OFFSET, Long.toString(nextOffset));
        String version = s3.putObject(b -> b.bucket(bucket).key(key)
                        .ifNoneMatch("*")
                        .contentType("application/x-ndjson")
                        .metadata(metadata)
                        .checksumAlgorithm(ChecksumAlgorithm.SHA256)
                        .objectLockMode(mode)
                        .objectLockRetainUntilDate(clock.instant().plus(retention)),
                RequestBody.fromBytes(records)).versionId();
        return new Segment(key, version, start, end, nextOffset);
    }

    /** The chains in the archive. */
    public List<String> chains() {
        List<String> chains = new ArrayList<>();
        s3.listObjectsV2Paginator(b -> b.bucket(bucket).prefix(prefix + "/").delimiter("/"))
                .commonPrefixes()
                .forEach(p -> chains.add(p.prefix().substring(prefix.length() + 1, p.prefix().length() - 1)));
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
        for (ObjectVersion version : firstVersions(chain, afterKey, null).values()) {
            segments.add(segment(version.key(), version.versionId()));
        }
        return segments;
    }

    /**
     * Checks a run of consecutive segments on its own: its records link to one another from {@code start}, the head
     * the first segment starts from, and end at the last segment's end. After a part of the chain already checked,
     * that is as good as checking the whole chain again, since a locked segment cannot change (D23).
     */
    public Verification verify(List<Segment> run, ChainHead start) {
        try (InputStream records = new SegmentStream(run)) {
            return ArchiveVerifier.verify(new BufferedInputStream(records, readBuffer), start,
                    run.isEmpty() ? start : run.getLast().end(), body -> { });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Where a record of the audit topic lies in the archive, for the audit feed (backlog 125).
     *
     * @param segment  the segment holding it
     * @param sequence its sequence number in the chain
     * @param hash     the hash it stores
     * @param previous the hash of the record before it: the segment's start head for its first record
     * @param next     the hash of the record after it; empty while it is the chain's last
     * @param holds    whether its stored hash is the hash of the one before it and its content
     */
    public record Placement(Segment segment, long sequence, String hash, String previous, Optional<String> next,
                           boolean holds) {
    }

    /**
     * The place of the record at {@code offset} of the chain's partition, whose event id is {@code eventId}; empty
     * when the archive does not hold it yet, or never will (it precedes the archive). The audit topic is written
     * without transactions, so its offsets run without gaps and a record's sequence is its offset less the chain's
     * first offset, plus one. The segment that sequence falls in is searched for the record by its event id.
     *
     * @param segmentEvents the most records a segment holds, so the listing starts at most that far back
     */
    public Optional<Placement> place(String chain, long offset, String eventId, int segmentEvents) {
        Map.Entry<String, ObjectVersion> firstEntry = firstVersions(chain, null, key(chain, 1)).firstEntry();
        if (firstEntry == null || !firstEntry.getKey().equals(key(chain, 1))) {
            return Optional.empty();
        }
        Segment first = segment(firstEntry.getKey(), firstEntry.getValue().versionId());
        long firstOffset = first.nextOffset() - (first.end().sequence() - first.start().sequence());
        long sequence = offset - firstOffset + 1;
        if (sequence < 1) {
            return Optional.empty();
        }
        Map.Entry<String, ObjectVersion> holder = firstVersions(chain,
                key(chain, Math.max(0, sequence - segmentEvents)), key(chain, sequence)).lastEntry();
        if (holder == null) {
            holder = firstVersions(chain, null, key(chain, sequence)).lastEntry();
        }
        Segment segment = segment(holder.getKey(), holder.getValue().versionId());
        byte[] id = ("\"id\":\"" + eventId + "\"").getBytes(StandardCharsets.UTF_8);
        try (InputStream in = new BufferedInputStream(fetch(segment), readBuffer)) {
            byte[] previous = Chain.HEX.parseHex(segment.start().hash());
            byte[] line;
            while ((line = ArchiveVerifier.nextLine(in)) != null) {
                if (line.length < Chain.HASH_HEX_LENGTH + 2) {
                    return Optional.empty();
                }
                String stored = new String(line, 0, Chain.HASH_HEX_LENGTH, StandardCharsets.US_ASCII);
                byte[] body = Arrays.copyOfRange(line, Chain.HASH_HEX_LENGTH + 1,
                        line.length - (line[line.length - 1] == '\n' ? 1 : 0));
                if (contains(body, id)) {
                    boolean holds = Chain.HEX.formatHex(Chain.link(previous, body)).equals(stored);
                    byte[] after = ArchiveVerifier.nextLine(in);
                    Optional<String> next = after != null && after.length >= Chain.HASH_HEX_LENGTH
                            ? Optional.of(new String(after, 0, Chain.HASH_HEX_LENGTH, StandardCharsets.US_ASCII))
                            : firstHashAfter(chain, segment);
                    return Optional.of(new Placement(segment, sequence(body), stored, Chain.HEX.formatHex(previous),
                            next, holds));
                }
                previous = Chain.HEX.parseHex(stored);
            }
            return Optional.empty();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The hash of the first record of the segment after {@code segment}, if there is one yet. */
    private Optional<String> firstHashAfter(String chain, Segment segment) throws IOException {
        Map.Entry<String, ObjectVersion> next = firstVersions(chain, segment.key(), null).firstEntry();
        if (next == null) {
            return Optional.empty();
        }
        try (InputStream in = s3.getObject(r -> r.bucket(bucket).key(next.getKey())
                .versionId(next.getValue().versionId()))) {
            byte[] hash = in.readNBytes(Chain.HASH_HEX_LENGTH);
            return hash.length == Chain.HASH_HEX_LENGTH
                    ? Optional.of(new String(hash, StandardCharsets.US_ASCII)) : Optional.empty();
        }
    }

    private InputStream fetch(Segment segment) {
        return s3.getObject(r -> r.bucket(bucket).key(segment.key()).versionId(segment.versionId()));
    }

    private static long sequence(byte[] body) {
        int end = 0;
        while (body[end] != ' ') {
            end++;
        }
        return Long.parseLong(new String(body, 0, end, StandardCharsets.US_ASCII));
    }

    private static boolean contains(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= haystack.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }

    /**
     * The first version of each of the chain's segment keys after {@code afterKey} (from the first when null) up to
     * {@code upTo} inclusive (to the last when null), by key. The listing stops once past {@code upTo}.
     */
    private TreeMap<String, ObjectVersion> firstVersions(String chain, String afterKey, String upTo) {
        TreeMap<String, ObjectVersion> firstVersions = new TreeMap<>();
        for (ObjectVersion v : s3.listObjectVersionsPaginator(b -> {
            b.bucket(bucket).prefix(prefix + "/" + chain + "/");
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

    /** The chain's last segment, if it has any. */
    public Optional<Segment> last(String chain) {
        List<Segment> segments = segments(chain);
        return segments.isEmpty() ? Optional.empty() : Optional.of(segments.getLast());
    }

    /**
     * Checks the whole chain from genesis: every record links to the one before it, across segments,
     * and the chain ends at {@code anchor}, a head kept outside the archive, or else at the last
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
                    ChainHead.GENESIS, expected(segments, anchor), verified);
            // Fetch the segments after a break too, as the read that fetched every segment before checking any did:
            // a segment the store cannot serve still fails the read as unreadable before the break fails it as broken.
            records.transferTo(OutputStream.nullOutputStream());
            return verification;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The event a verified record's body ({@code <sequence> SP <event JSON>}) holds. */
    private static CatalogEvent event(byte[] body) {
        int space = 0;
        while (body[space] != ' ') {
            space++;
        }
        return EventCodec.decode(Arrays.copyOfRange(body, space + 1, body.length));
    }

    private static ChainHead expected(List<Segment> segments, Optional<ChainHead> anchor) {
        return anchor.orElse(segments.isEmpty() ? ChainHead.GENESIS : segments.getLast().end());
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

    private Segment segment(String key, String versionId) {
        HeadObjectResponse head = s3.headObject(b -> b.bucket(bucket).key(key).versionId(versionId));
        Map<String, String> metadata = head.metadata();
        return new Segment(key, versionId,
                new ChainHead(Long.parseLong(metadata.get(START_SEQUENCE)), metadata.get(START_HASH)),
                new ChainHead(Long.parseLong(metadata.get(END_SEQUENCE)), metadata.get(END_HASH)),
                Long.parseLong(metadata.get(NEXT_OFFSET)));
    }

    private String key(String chain, long firstSequence) {
        if (chain.isEmpty() || chain.contains("/")) {
            throw new IllegalArgumentException("a chain name is non-empty and has no slash: " + chain);
        }
        return prefix + "/" + chain + "/" + String.format("%019d", firstSequence) + ".log";
    }
}
