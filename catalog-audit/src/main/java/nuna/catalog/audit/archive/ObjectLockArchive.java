package nuna.catalog.audit.archive;

import nuna.catalog.audit.CatalogEvent;
import nuna.catalog.audit.EventCodec;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
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
 * in COMPLIANCE mode and kept at least five years (NYDFS 500.6), longer where configured (CMP-1). No
 * one, root included, can shorten the retention or delete a locked version before it lapses.
 *
 * <p>Each chain (one per audit-topic partition) is a run of segments under
 * {@code <prefix>/<chain>/}, named by the sequence number of their first record. A segment's user
 * metadata records the chain heads it starts from and ends at and the next topic offset to archive,
 * so a restarted consumer resumes exactly where the last segment ended. Segments are listed by
 * version, so a delete marker cannot hide one.
 */
public final class ObjectLockArchive {

    /** Five years, with their leap days: the floor on retention (D23, NYDFS 500.6). */
    public static final Duration MINIMUM_RETENTION = Duration.ofDays(5 * 365 + 2);

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
            throw new IllegalArgumentException("archive retention " + retention + " is below the five-year floor "
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
        TreeMap<String, ObjectVersion> firstVersions = new TreeMap<>();
        s3.listObjectVersionsPaginator(b -> b.bucket(bucket).prefix(prefix + "/" + chain + "/"))
                .versions()
                .forEach(v -> firstVersions.merge(v.key(), v,
                        (a, b) -> a.lastModified().isBefore(b.lastModified()) ? a : b));
        List<Segment> segments = new ArrayList<>();
        for (ObjectVersion version : firstVersions.values()) {
            segments.add(segment(version.key(), version.versionId()));
        }
        return segments;
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
