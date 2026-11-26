package nuna.catalog.audit.archive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import nuna.catalog.audit.CatalogEvent;
import nuna.catalog.audit.SampleEvents;
import nuna.catalog.tests.s3.TestS3;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ObjectLockMode;
import software.amazon.awssdk.services.s3.model.ObjectLockRetentionMode;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * The archive of D23 on the run's S3 store ({@link TestS3}) with Object Lock, fed from the log (backlog 262):
 * segments and checkpoints locked for at least seven years, written once, chained across segments, and read back
 * intact by the check. Segments are locked in COMPLIANCE mode, as the server always locks them, except on
 * real AWS: there a COMPLIANCE lock would leave objects and a bucket nobody can delete for seven years, so the
 * test locks in GOVERNANCE mode and the run deletes them at the end.
 */
@Tag("heavy")
class LockedArchiveTest {

    private static TestS3 store;
    private static S3Client s3;
    private static ObjectLockMode mode;

    @BeforeAll
    static void start() {
        store = TestS3.get();
        s3 = store.client();
        mode = store.kind == TestS3.Kind.AWS ? ObjectLockMode.GOVERNANCE : ObjectLockMode.COMPLIANCE;
    }

    /** The store itself is shared by the run and removed when the JVM exits. */
    @AfterAll
    static void stop() {
        s3.close();
    }

    private static String newBucket(boolean objectLock) {
        return store.bucket("archive-" + UUID.randomUUID().toString().substring(0, 8), objectLock);
    }

    private static ObjectLockArchive archive(String bucket) {
        return new ObjectLockArchive(s3, bucket, "audit", ObjectLockArchive.MINIMUM_RETENTION, Clock.systemUTC(),
                mode);
    }

    private static List<CatalogEvent> events(int from, int count) {
        List<CatalogEvent> events = new ArrayList<>();
        for (int i = from; i < from + count; i++) {
            events.add(SampleEvents.event(SampleEvents.oneOfEachType().get(i % SampleEvents.oneOfEachType().size()), i));
        }
        return events;
    }

    /** One segment of {@code events} chained from {@code start}. */
    private static ObjectLockArchive.Segment write(ObjectLockArchive archive, String chain, ChainHead start,
                                                   List<CatalogEvent> events) throws IOException {
        ByteArrayOutputStream records = new ByteArrayOutputStream();
        ChainHead end = new ArchiveWriter(records, start).append(events);
        return archive.write(chain, start, records.toByteArray(), end);
    }

    @Test
    @DisplayName("a bucket without Object Lock is refused, and so is a retention under seven years")
    void refusesUnlockedBucketAndShortRetention() {
        assertThatThrownBy(() -> archive(newBucket(false)).checkBucket())
                .isInstanceOf(IllegalStateException.class);
        archive(newBucket(true)).checkBucket();
        assertThatThrownBy(() -> new ObjectLockArchive(s3, "b", "audit",
                ObjectLockArchive.MINIMUM_RETENTION.minusDays(1), Clock.systemUTC()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("seven-year floor");
    }

    @Test
    @DisplayName("a segment is locked in COMPLIANCE mode for at least seven years: it can be neither deleted nor replaced"
            + " (GOVERNANCE on AWS: not deleted without the bypass)")
    void segmentsAreLocked() throws IOException {
        String bucket = newBucket(true);
        ObjectLockArchive archive = archive(bucket);
        Instant before = Instant.now();
        ObjectLockArchive.Segment segment = write(archive, "audit-0", ChainHead.GENESIS, events(1, 3));

        var retention = s3.getObjectRetention(b -> b.bucket(bucket).key(segment.key())
                .versionId(segment.versionId())).retention();
        assertThat(retention.mode()).isEqualTo(ObjectLockRetentionMode.fromValue(mode.toString()));
        assertThat(retention.retainUntilDate()).isAfterOrEqualTo(before.plus(Duration.ofDays(7 * 365)));

        assertThatThrownBy(() -> s3.deleteObject(b -> b.bucket(bucket).key(segment.key())
                .versionId(segment.versionId()))).isInstanceOf(S3Exception.class);
        if (mode == ObjectLockMode.COMPLIANCE) {
            assertThatThrownBy(() -> s3.deleteObject(b -> b.bucket(bucket).key(segment.key())
                    .versionId(segment.versionId()).bypassGovernanceRetention(true))).isInstanceOf(S3Exception.class);
        }
        assertThatThrownBy(() -> write(archive, "audit-0", ChainHead.GENESIS, events(9, 1)))
                .as("a second writer of the same segment is refused").isInstanceOf(S3Exception.class);
        assertThat(archive.segments("audit-0")).containsExactly(segment);
    }

    @Test
    @DisplayName("the chain runs across segments; a delete marker hides nothing; a lost tail shows against the anchor")
    void chainAcrossSegments() throws IOException {
        String bucket = newBucket(true);
        ObjectLockArchive archive = archive(bucket);
        ObjectLockArchive.Segment first = write(archive, "audit-1", ChainHead.GENESIS, events(1, 3));
        ObjectLockArchive.Segment second = write(archive, "audit-1", first.end(), events(4, 2));

        assertThat(archive.chains()).containsExactly("audit-1");
        assertThat(archive.verify("audit-1", Optional.empty()))
                .isEqualTo(new Verification.Intact(5, second.end()));
        assertThat(archive.events("audit-1", Optional.of(second.end()))).containsExactlyElementsOf(events(1, 5));

        s3.deleteObject(b -> b.bucket(bucket).key(second.key()));
        assertThat(archive.segments("audit-1")).as("listed by version, past the delete marker")
                .containsExactly(first, second);

        assertThat(archive.verify("audit-1", Optional.of(new ChainHead(7, "f".repeat(64)))))
                .isInstanceOfSatisfying(Verification.Broken.class,
                        b -> assertThat(b.defect()).isEqualTo(Verification.Defect.HEAD_MISMATCH));
    }

    @Test
    @DisplayName("the writer's segments and checkpoints are locked, a checkpoint is never replaced, and the check reads"
            + " the runs intact up to their end")
    void writerLocksSegmentsAndCheckpoints() {
        String bucket = newBucket(true);
        ObjectLockArchive archive = archive(bucket);
        ArchiveSink sink = new ArchiveSink(archive, 3, Duration.ofMinutes(1), Duration.ofMinutes(5), 1000,
                Clock.systemUTC());
        UUID run = UUID.randomUUID();
        List<byte[]> bodies = new ArrayList<>();
        for (long n = 1; n <= 7; n++) {
            bodies.add(ArchiveCheckTest.logged(run, n));
        }
        bodies.add(ArchiveCheckTest.ended(run, 8));
        assertThat(sink.offer(bodies).archived()).isEqualTo(8);
        ArchiveSink.Round round = sink.finish();
        assertThat(round.problems()).isEmpty();
        ObjectLockArchive.Stored checkpoint = round.checkpoint().orElseThrow();

        var retention = s3.getObjectRetention(b -> b.bucket(bucket).key(checkpoint.key())
                .versionId(checkpoint.versionId())).retention();
        assertThat(retention.mode()).isEqualTo(ObjectLockRetentionMode.fromValue(mode.toString()));
        assertThatThrownBy(() -> archive.writeCheckpoint(
                Checkpoint.parse(archive.read(checkpoint.key(), checkpoint.versionId())).at(), "{}".getBytes()))
                .as("a second checkpoint at the same key is refused").isInstanceOf(S3Exception.class);
        assertThat(archive.segments(run.toString())).hasSize(3);

        ArchiveCheck.Result result = new ArchiveCheck(archive, Clock.systemUTC()).run();
        assertThat(result.intact()).isTrue();
        assertThat(result.checkpoints().problems()).isEmpty();
        assertThat(result.chains()).singleElement().satisfies(c -> {
            assertThat(c.records()).isEqualTo(8);
            assertThat(c.missing()).isEmpty();
            assertThat(c.verified().ended()).isTrue();
        });
    }
}
