package fidice.catalog.server.governance;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.TimeoutException;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.utils.Utils;
import org.springframework.stereotype.Component;

/**
 * The most recent audit records about one securable, read back from the audit topic (D23). Every record is
 * keyed by its subject, the securable's id, and the publisher leaves partitioning to Kafka's default, so all
 * of one securable's records sit in one partition, in the order they were acknowledged. This reads that
 * partition backwards from its end, at most {@link #SCAN_LIMIT} offsets, and keeps the records whose key is
 * the subject.
 *
 * <p>It is a bounded look at recent history, not a search: the topic's retention and the window bound what
 * it can see, and the result says which offsets it covered. Searching the whole trail is the SIEM's job and,
 * from v1.x, the queryable audit tables' (D23).
 */
@Component
class RecentAudit {

    /** The most offsets one read scans; on a busy partition that is the last few minutes, not days. */
    static final int SCAN_LIMIT = 20_000;
    private static final int CHUNK = 2_000;
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    /**
     * The offsets a read covered in the subject's partition.
     *
     * @param earliest the partition's earliest retained offset
     * @param from     the first offset scanned
     * @param to       the partition's end when the read began; nothing at or after it was scanned
     */
    record Window(String topic, int partition, long earliest, long from, long to) {

        /** Whether the scan reached the earliest record the topic still holds. */
        boolean complete() {
            return from <= earliest;
        }
    }

    /** The records found, newest first, as the audit topic holds them (CloudEvents JSON), and the window. */
    record Result(List<byte[]> records, Window window) {
    }

    private final AuditSettings settings;

    RecentAudit(AuditSettings settings) {
        this.settings = settings;
    }

    boolean configured() {
        return settings.kafkaConfigured();
    }

    /**
     * Up to {@code limit} records whose subject is {@code subject}, newest first.
     *
     * @throws TimeoutException     if Kafka does not answer in time
     * @throws IllegalStateException if the audit topic does not exist
     */
    Result recent(String subject, int limit) {
        try (KafkaConsumer<byte[], byte[]> consumer = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, settings.kafkaBootstrapServers(),
                ConsumerConfig.CLIENT_ID_CONFIG, "fidice-audit-reader",
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false",
                ConsumerConfig.ALLOW_AUTO_CREATE_TOPICS_CONFIG, "false",
                ConsumerConfig.MAX_POLL_RECORDS_CONFIG, Integer.toString(CHUNK)),
                new ByteArrayDeserializer(), new ByteArrayDeserializer())) {
            String topic = settings.auditTopic();
            List<PartitionInfo> partitions = consumer.partitionsFor(topic, TIMEOUT);
            if (partitions == null || partitions.isEmpty()) {
                throw new IllegalStateException("audit topic " + topic + " does not exist");
            }
            byte[] key = subject.getBytes(StandardCharsets.UTF_8);
            // Kafka's default partitioner for a keyed record, which KafkaEventPublisher uses.
            TopicPartition partition = new TopicPartition(topic, Utils.toPositive(Utils.murmur2(key)) % partitions.size());
            consumer.assign(List.of(partition));
            long earliest = consumer.beginningOffsets(List.of(partition), TIMEOUT).get(partition);
            long end = consumer.endOffsets(List.of(partition), TIMEOUT).get(partition);
            long floor = Math.max(earliest, end - SCAN_LIMIT);
            long deadline = System.nanoTime() + TIMEOUT.toNanos();
            List<byte[]> newestFirst = new ArrayList<>();
            long hi = end;
            while (hi > floor && newestFirst.size() < limit) {
                long lo = Math.max(floor, hi - CHUNK);
                consumer.seek(partition, lo);
                List<byte[]> chunk = new ArrayList<>();
                while (consumer.position(partition, TIMEOUT) < hi) {
                    if (System.nanoTime() > deadline) {
                        throw new TimeoutException("reading audit partition " + partition + " took longer than " + TIMEOUT);
                    }
                    for (ConsumerRecord<byte[], byte[]> record : consumer.poll(Duration.ofMillis(200)).records(partition)) {
                        if (record.offset() >= hi) {
                            break;
                        }
                        if (Arrays.equals(record.key(), key)) {
                            chunk.add(record.value());
                        }
                    }
                }
                Collections.reverse(chunk);
                newestFirst.addAll(chunk);
                hi = lo;
            }
            return new Result(List.copyOf(newestFirst.subList(0, Math.min(limit, newestFirst.size()))),
                    new Window(topic, partition.partition(), earliest, hi, end));
        }
    }
}
