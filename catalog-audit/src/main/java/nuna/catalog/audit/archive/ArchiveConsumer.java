package nuna.catalog.audit.archive;

import nuna.catalog.audit.CatalogEvent;
import nuna.catalog.audit.EventCodec;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRebalanceListener;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;

/**
 * Archives the audit topic (D23): each partition is one hash chain, written to
 * {@link ObjectLockArchive} in segments of at most {@code maxEvents} records or {@code maxAge} of
 * waiting. Each partition's position lives in its last segment, so after a crash, a restart or a
 * rebalance the chain resumes at the first offset not yet archived: no record is archived twice or
 * skipped. The consumer group's committed offset carries the chain head too, as an anchor outside
 * the archive. Single-threaded: call {@link #pollOnce} from one thread.
 */
public final class ArchiveConsumer implements AutoCloseable {

    private final Consumer<byte[], byte[]> consumer;
    private final String topic;
    private final ObjectLockArchive archive;
    private final int maxEvents;
    private final Duration maxAge;
    private final Clock clock;
    private final Map<TopicPartition, Pending> pending = new HashMap<>();

    /** One partition's open segment. */
    private static final class Pending {
        final ChainHead start;
        final ByteArrayOutputStream records = new ByteArrayOutputStream();
        final ArchiveWriter writer;
        int count;
        Instant openedAt;
        long nextOffset;

        Pending(ChainHead start, long nextOffset) {
            this.start = start;
            this.writer = new ArchiveWriter(records, start);
            this.nextOffset = nextOffset;
        }
    }

    /**
     * @param consumerProperties Kafka client settings, at least {@code bootstrap.servers} and {@code group.id};
     *                           offsets are committed by this class
     */
    public ArchiveConsumer(Map<String, Object> consumerProperties, String topic, ObjectLockArchive archive,
                           int maxEvents, Duration maxAge, Clock clock) {
        this.topic = Objects.requireNonNull(topic, "topic");
        this.archive = Objects.requireNonNull(archive, "archive");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.maxAge = Objects.requireNonNull(maxAge, "maxAge");
        if (maxEvents < 1) {
            throw new IllegalArgumentException("a segment holds at least one event, got " + maxEvents);
        }
        this.maxEvents = maxEvents;
        Map<String, Object> config = new HashMap<>(consumerProperties);
        config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        config.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed");
        this.consumer = new KafkaConsumer<>(config, new ByteArrayDeserializer(), new ByteArrayDeserializer());
        consumer.subscribe(List.of(topic), new ConsumerRebalanceListener() {
            @Override
            public void onPartitionsRevoked(Collection<TopicPartition> partitions) {
                partitions.forEach(ArchiveConsumer.this::flush);
                partitions.forEach(pending::remove);
            }

            @Override
            public void onPartitionsAssigned(Collection<TopicPartition> partitions) {
                partitions.forEach(ArchiveConsumer.this::resume);
            }
        });
    }

    /** The chain name of a partition. */
    public static String chain(String topic, int partition) {
        return topic + "-" + partition;
    }

    /**
     * Polls once, appends what arrived to each partition's open segment, and writes every segment
     * that is full or old enough.
     *
     * @throws IllegalStateException if a record is not a catalog event; it is not archived, and the
     *                               consumer must be closed and recreated once the cause is fixed
     */
    public void pollOnce(Duration timeout) {
        for (ConsumerRecord<byte[], byte[]> record : consumer.poll(timeout)) {
            TopicPartition partition = new TopicPartition(record.topic(), record.partition());
            Pending open = pending.get(partition);
            if (open == null || record.offset() < open.nextOffset) {
                continue;
            }
            CatalogEvent event;
            try {
                event = EventCodec.decode(record.value());
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException("audit record " + partition + "@" + record.offset()
                        + " is not a catalog event; archiving of the partition stops here", e);
            }
            try {
                open.writer.append(List.of(event));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            if (open.count++ == 0) {
                open.openedAt = clock.instant();
            }
            open.nextOffset = record.offset() + 1;
            if (open.count >= maxEvents) {
                flush(partition);
            }
        }
        Instant now = clock.instant();
        for (TopicPartition partition : List.copyOf(pending.keySet())) {
            Pending open = pending.get(partition);
            if (open.count > 0 && !open.openedAt.plus(maxAge).isAfter(now)) {
                flush(partition);
            }
        }
    }

    /** Writes every open segment that holds a record. */
    public void flushAll() {
        List.copyOf(pending.keySet()).forEach(this::flush);
    }

    /** Flushes what is open, then leaves the group. */
    @Override
    public void close() {
        try {
            flushAll();
        } finally {
            consumer.close();
        }
    }

    private void resume(TopicPartition partition) {
        Optional<ObjectLockArchive.Segment> last = archive.last(chain(partition.topic(), partition.partition()));
        if (last.isPresent()) {
            consumer.seek(partition, last.get().nextOffset());
            pending.put(partition, new Pending(last.get().end(), last.get().nextOffset()));
        } else {
            consumer.seekToBeginning(List.of(partition));
            pending.put(partition, new Pending(ChainHead.GENESIS, 0));
        }
    }

    private void flush(TopicPartition partition) {
        Pending open = pending.get(partition);
        if (open == null || open.count == 0) {
            return;
        }
        ChainHead end = open.writer.head();
        archive.write(chain(partition.topic(), partition.partition()), open.start, open.records.toByteArray(), end,
                open.nextOffset);
        pending.put(partition, new Pending(end, open.nextOffset));
        consumer.commitSync(Map.of(partition, new OffsetAndMetadata(open.nextOffset, anchor(end))));
    }

    /** The head as the committed offset's metadata carries it: {@code <sequence>:<hash>}. */
    static String anchor(ChainHead head) {
        return head.sequence() + ":" + head.hash();
    }

    /** The head a committed offset's metadata carries, if it carries one. */
    public static Optional<ChainHead> anchor(OffsetAndMetadata committed) {
        if (committed == null || committed.metadata() == null || !committed.metadata().contains(":")) {
            return Optional.empty();
        }
        String[] parts = committed.metadata().split(":", 2);
        return Optional.of(new ChainHead(Long.parseLong(parts[0]), parts[1]));
    }

    /** The topic this consumer archives. */
    public String topic() {
        return topic;
    }
}
