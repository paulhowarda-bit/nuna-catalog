package nuna.catalog.audit.archive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import nuna.catalog.audit.CatalogEvent;
import nuna.catalog.audit.SampleEvents;
import nuna.catalog.audit.publish.KafkaBroker;
import nuna.catalog.audit.publish.KafkaEventPublisher;
import nuna.catalog.audit.publish.PublisherSettings;
import nuna.catalog.tests.s3.TestS3;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
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
 * The archive of D23 on the run's S3 store ({@link TestS3}) with Object Lock, fed from Kafka: segments locked
 * for at least five years, written once, chained across segments, and archived exactly once per partition
 * across consumer restarts. Segments are locked in COMPLIANCE mode, as the server always locks them, except on
 * real AWS: there a COMPLIANCE lock would leave objects and a bucket nobody can delete for five years, so the
 * test locks in GOVERNANCE mode and the run deletes them at the end.
 */
@Tag("heavy")
class LockedArchiveTest {

    private static TestS3 store;
    private static S3Client s3;
    private static KafkaBroker broker;
    private static ObjectLockMode mode;

    @BeforeAll
    static void start() {
        store = TestS3.get();
        s3 = store.client();
        mode = store.kind == TestS3.Kind.AWS ? ObjectLockMode.GOVERNANCE : ObjectLockMode.COMPLIANCE;
        broker = new KafkaBroker();
    }

    /** The store itself is shared by the run and removed when the JVM exits. */
    @AfterAll
    static void stop() {
        broker.close();
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
                                                   List<CatalogEvent> events, long nextOffset) throws IOException {
        ByteArrayOutputStream records = new ByteArrayOutputStream();
        ChainHead end = new ArchiveWriter(records, start).append(events);
        return archive.write(chain, start, records.toByteArray(), end, nextOffset);
    }

    @Test
    @DisplayName("a bucket without Object Lock is refused, and so is a retention under five years")
    void refusesUnlockedBucketAndShortRetention() {
        assertThatThrownBy(() -> archive(newBucket(false)).checkBucket())
                .isInstanceOf(IllegalStateException.class);
        archive(newBucket(true)).checkBucket();
        assertThatThrownBy(() -> new ObjectLockArchive(s3, "b", "audit",
                ObjectLockArchive.MINIMUM_RETENTION.minusDays(1), Clock.systemUTC()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("five-year floor");
    }

    @Test
    @DisplayName("a segment is locked in COMPLIANCE mode for at least five years: it can be neither deleted nor replaced"
            + " (GOVERNANCE on AWS: not deleted without the bypass)")
    void segmentsAreLocked() throws IOException {
        String bucket = newBucket(true);
        ObjectLockArchive archive = archive(bucket);
        Instant before = Instant.now();
        ObjectLockArchive.Segment segment = write(archive, "audit-0", ChainHead.GENESIS, events(1, 3), 3);

        var retention = s3.getObjectRetention(b -> b.bucket(bucket).key(segment.key())
                .versionId(segment.versionId())).retention();
        assertThat(retention.mode()).isEqualTo(ObjectLockRetentionMode.fromValue(mode.toString()));
        assertThat(retention.retainUntilDate()).isAfterOrEqualTo(before.plus(Duration.ofDays(5 * 365)));

        assertThatThrownBy(() -> s3.deleteObject(b -> b.bucket(bucket).key(segment.key())
                .versionId(segment.versionId()))).isInstanceOf(S3Exception.class);
        if (mode == ObjectLockMode.COMPLIANCE) {
            assertThatThrownBy(() -> s3.deleteObject(b -> b.bucket(bucket).key(segment.key())
                    .versionId(segment.versionId()).bypassGovernanceRetention(true))).isInstanceOf(S3Exception.class);
        }
        assertThatThrownBy(() -> write(archive, "audit-0", ChainHead.GENESIS, events(9, 1), 1))
                .as("a second writer of the same segment is refused").isInstanceOf(S3Exception.class);
        assertThat(archive.segments("audit-0")).containsExactly(segment);
    }

    @Test
    @DisplayName("the chain runs across segments; a delete marker hides nothing; a lost tail shows against the anchor")
    void chainAcrossSegments() throws IOException {
        String bucket = newBucket(true);
        ObjectLockArchive archive = archive(bucket);
        ObjectLockArchive.Segment first = write(archive, "audit-1", ChainHead.GENESIS, events(1, 3), 3);
        ObjectLockArchive.Segment second = write(archive, "audit-1", first.end(), events(4, 2), 5);

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
    @DisplayName("the consumer archives each partition once, in order, across restarts, and anchors the head in its committed offset")
    void consumerArchivesExactlyOnce() {
        String bucket = newBucket(true);
        ObjectLockArchive archive = archive(bucket);
        String topic = broker.newTopic(2);
        String group = "archive-" + UUID.randomUUID();
        List<CatalogEvent> first = events(1, 7);
        List<CatalogEvent> second = events(8, 5);

        try (KafkaEventPublisher publisher = new KafkaEventPublisher(new PublisherSettings(broker.direct(), topic,
                Duration.ofSeconds(10), Duration.ofSeconds(5), Map.of()))) {
            first.forEach(publisher::publish);
            consumeAll(archive, topic, group, first.size());
            second.forEach(publisher::publish);
            consumeAll(archive, topic, group, first.size() + second.size());
        }

        List<CatalogEvent> archived = new ArrayList<>();
        try (Admin admin = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, broker.direct()))) {
            Map<TopicPartition, OffsetAndMetadata> committed = admin.listConsumerGroupOffsets(group)
                    .partitionsToOffsetAndMetadata().get();
            for (int partition = 0; partition < 2; partition++) {
                String chain = ArchiveConsumer.chain(topic, partition);
                Optional<ChainHead> anchor = ArchiveConsumer.anchor(committed.get(new TopicPartition(topic, partition)));
                assertThat(anchor).isPresent();
                assertThat(archive.verify(chain, anchor)).isInstanceOf(Verification.Intact.class);
                archived.addAll(archive.events(chain, anchor));
            }
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        List<CatalogEvent> published = new ArrayList<>(first);
        published.addAll(second);
        assertThat(archived).as("every event once").containsExactlyInAnyOrderElementsOf(published);
        for (CatalogEvent event : published) {
            if (event.subject() == null) {
                continue;
            }
            List<CatalogEvent> sameSubject = archived.stream().filter(e -> java.util.Objects.equals(e.subject(),
                    event.subject())).toList();
            assertThat(sameSubject).as("one subject keeps its order")
                    .isEqualTo(published.stream().filter(e -> java.util.Objects.equals(e.subject(), event.subject()))
                            .toList());
        }
    }

    /** Runs a fresh consumer until the archive holds {@code total} events, then closes it, as a restart would. */
    private static void consumeAll(ObjectLockArchive archive, String topic, String group, int total) {
        Map<String, Object> properties = new HashMap<>();
        properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, broker.direct());
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, group);
        try (ArchiveConsumer consumer = new ArchiveConsumer(properties, topic, archive, 3, Duration.ofMillis(200),
                Clock.systemUTC())) {
            long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
            while (archivedCount(archive, topic) < total) {
                assertThat(System.nanoTime()).as("archived within 60 s").isLessThan(deadline);
                consumer.pollOnce(Duration.ofMillis(200));
            }
        }
    }

    private static long archivedCount(ObjectLockArchive archive, String topic) {
        long count = 0;
        for (int partition = 0; partition < 2; partition++) {
            count += archive.last(ArchiveConsumer.chain(topic, partition)).map(s -> s.end().sequence()).orElse(0L);
        }
        return count;
    }
}
