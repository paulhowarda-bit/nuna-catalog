package nuna.audittables;

import nuna.catalog.audit.AuditRow;
import nuna.catalog.audit.AuditRows;
import nuna.catalog.audit.CatalogEvent;
import nuna.catalog.audit.EventCodec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.function.Supplier;
import org.apache.iceberg.exceptions.NoSuchTableException;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRebalanceListener;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Writes every record of the audit topic to the audit table (D23, amended 2026-11-20), about a commit interval
 * behind. Rows wait in memory and are committed every {@code commitInterval}, or sooner once {@code maxRows} wait,
 * with each partition's next offset in the same Iceberg commit ({@link EventsTable}); the writer starts each partition
 * from the offset the table holds, so after a crash, a restart, a rebalance or a failed commit no record is written
 * twice or skipped. The relay is at least once (D23), so an {@code event_id} can still repeat at another offset.
 *
 * <p>When the catalog answers that the table does not exist (it was dropped, or the catalog's database is new), the
 * writer makes it again through its opener and, the new table holding no offsets, writes the topic into it from the
 * earliest record Kafka keeps (backlog 284). A refusal never does: it is thrown, and the writer retries.
 *
 * <p>The records of the writer's own requests are left out: its every commit makes records (its loads, credentials
 * and commits), which would otherwise make the next commit. They stay in the topic, the SIEM and the archive.
 *
 * <p>A record that is not a catalog event this writer can map stops it there: it is not written, and every later
 * attempt stops at it again until the writer is fixed. Single-threaded: call {@link #pollOnce} from one thread.
 */
final class TableWriter implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(TableWriter.class);

    private final Consumer<byte[], byte[]> consumer;
    private final String topic;
    private final Supplier<EventsTable> opener;
    private final String principal;
    private final Duration commitInterval;
    private final int maxRows;
    private final Clock clock;

    private EventsTable table;
    /** True until the table's offsets are read, and after any failure: they are read again before the next poll. */
    private boolean stale = true;
    /** Each partition's next offset as the table holds it. */
    private Map<Integer, Long> committed = Map.of();
    /** Each partition's next offset after the records read since, written or left out. */
    private final Map<Integer, Long> read = new HashMap<>();
    private final List<AuditRow> rows = new ArrayList<>();
    private Instant waitingSince;

    /**
     * @param consumer  a consumer in the writer's own group, with automatic commits off; this class subscribes it
     * @param opener    opens (or creates) the table; called on the first poll, after which a failure only reloads it
     * @param principal the writer's own principal id, as the catalog records it; its records are left out
     */
    TableWriter(Consumer<byte[], byte[]> consumer, String topic, Supplier<EventsTable> opener, String principal,
                Duration commitInterval, int maxRows, Clock clock) {
        this.consumer = Objects.requireNonNull(consumer, "consumer");
        this.topic = Objects.requireNonNull(topic, "topic");
        this.opener = Objects.requireNonNull(opener, "opener");
        this.principal = Objects.requireNonNull(principal, "principal");
        this.commitInterval = Objects.requireNonNull(commitInterval, "commitInterval");
        this.maxRows = maxRows;
        this.clock = Objects.requireNonNull(clock, "clock");
        consumer.subscribe(List.of(topic), new ConsumerRebalanceListener() {
            @Override
            public void onPartitionsRevoked(Collection<TopicPartition> partitions) {
                // Their rows are left for the next owner, which starts from the table's offsets.
                partitions.forEach(p -> read.remove(p.partition()));
                rows.removeIf(row -> partitions.contains(new TopicPartition(topic, row.topicPartition())));
                if (rows.isEmpty()) {
                    waitingSince = null;
                }
            }

            @Override
            public void onPartitionsAssigned(Collection<TopicPartition> partitions) {
                if (!stale) {
                    seek(partitions);
                }
            }
        });
    }

    /**
     * Polls once, takes what arrived into the waiting rows, and commits them when the interval has passed since the
     * first of them arrived, or {@code maxRows} wait. After a failure, the next call first reads the table's offsets
     * again and goes back to them.
     */
    void pollOnce(Duration timeout) {
        if (stale) {
            reload();
        }
        for (ConsumerRecord<byte[], byte[]> record : consumer.poll(timeout)) {
            AuditRow row;
            try {
                CatalogEvent event = EventCodec.decode(record.value());
                row = AuditRows.of(event, EventCodec.envelope(record.value()).stamp(), record.partition(), record.offset());
            } catch (IllegalArgumentException e) {
                stale = true;
                throw new IllegalStateException("audit record " + record.topic() + "-" + record.partition() + "@"
                        + record.offset() + " is not a catalog event this writer can map; the audit table stops here",
                        e);
            }
            read.put(record.partition(), record.offset() + 1);
            if (principal.equals(row.principal())) {
                continue;
            }
            if (rows.isEmpty()) {
                waitingSince = clock.instant();
            }
            rows.add(row);
        }
        if (!rows.isEmpty() && (rows.size() >= maxRows
                || !clock.instant().isBefore(waitingSince.plus(commitInterval)))) {
            commit();
        }
    }

    /** Commits the waiting rows now, with the offsets read so far. */
    void commit() {
        Map<Integer, Long> offsets = new TreeMap<>(committed);
        offsets.putAll(read);
        try {
            table.append(rows, offsets);
        } catch (RuntimeException e) {
            stale = true;
            throw e;
        }
        LOG.info("Wrote {} audit records to the audit table, up to offsets {}", rows.size(), offsets);
        committed = offsets;
        rows.clear();
        waitingSince = null;
        // The group's offsets show its lag to Kafka's tools; the writer never reads them back.
        Map<TopicPartition, OffsetAndMetadata> lag = new HashMap<>();
        read.forEach((partition, next) -> {
            TopicPartition p = new TopicPartition(topic, partition);
            if (consumer.assignment().contains(p)) {
                lag.put(p, new OffsetAndMetadata(next));
            }
        });
        read.clear();
        try {
            consumer.commitSync(lag);
        } catch (KafkaException e) {
            LOG.warn("Could not record the audit table writer's offsets in its consumer group: {}", e.toString());
        }
    }

    /** Drops what waits, reads the table's offsets and goes back to them in every partition this writer holds. */
    private void reload() {
        rows.clear();
        read.clear();
        waitingSince = null;
        if (table == null) {
            table = opener.get();
        }
        try {
            committed = table.reload();
        } catch (NoSuchTableException e) {
            // Only this answer makes the table again: a refusal is thrown, so a revoked grant shows (backlog 284).
            table = null;
            LOG.warn("The audit table is gone ({}); making it again and writing {} into it from the earliest record"
                    + " Kafka keeps. A dropped audit table should be undropped rather than replaced: rows older than"
                    + " the topic keeps are only in the dropped one (undrop needs the name free).", e.getMessage(),
                    topic);
            table = opener.get();
            committed = table.reload();
        }
        stale = false;
        seek(consumer.assignment());
    }

    private void seek(Collection<TopicPartition> partitions) {
        for (TopicPartition partition : partitions) {
            Long next = committed.get(partition.partition());
            if (next == null) {
                consumer.seekToBeginning(List.of(partition));
            } else {
                consumer.seek(partition, next);
            }
        }
    }

    /** Leaves the group; what waits is not written, and the next writer reads it again. */
    @Override
    public void close() {
        consumer.close();
    }
}
