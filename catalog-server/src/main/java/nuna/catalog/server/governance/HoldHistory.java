package nuna.catalog.server.governance;

import nuna.catalog.audit.CatalogEvent;
import nuna.catalog.audit.EventCodec;
import nuna.catalog.audit.GovernanceChange;
import nuna.catalog.audit.HoldChange;
import nuna.catalog.audit.Securable;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.TimeoutException;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * The holds released and DR freezes lifted in one catalog, read back from the audit topic (D23, D45, item 128). A
 * released hold and a lifted freeze leave the store, so their history is in the audit records of their changes: a
 * hold's placing and release ({@link HoldChange}), a freeze's setting and removal ({@link GovernanceChange} of
 * {@code dr-freeze}). Those records are keyed by their target, so a catalog's are spread over every partition; this
 * reads the last {@value #SCAN_LIMIT} offsets of each, as {@link RecentAudit} reads one, and says which offsets it
 * covered. It is a bounded look at recent history, not a search: the whole trail is the SIEM's and the queryable
 * audit tables' (D23).
 */
@Component
class HoldHistory {

    /** The most offsets read from each partition, as {@link RecentAudit} reads its one. */
    static final int SCAN_LIMIT = RecentAudit.SCAN_LIMIT;
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final String FREEZE = "dr-freeze";
    private static final String HOLD_TYPE = "\"" + nuna.catalog.audit.EventType.HOLD_CHANGE.typeName() + "\"";
    private static final String GOVERNANCE_TYPE = "\"" + nuna.catalog.audit.EventType.GOVERNANCE_CHANGE.typeName()
            + "\"";

    /**
     * A hold released or a freeze lifted.
     *
     * @param kind     {@code hold} or {@code freeze}
     * @param holdId   the hold's id; null for a freeze
     * @param on       what it was on, as the records name it
     * @param reason   the reason it was placed with; null for a freeze set before the records read
     * @param placedBy who placed it; null when it was placed before the records read
     * @param placedAt when; null when it was placed before the records read
     */
    record Lifted(String kind, UUID holdId, Securable on, String reason, String placedBy, Instant placedAt,
                  String liftedBy, Instant liftedAt) {
    }

    /** The offsets read in one partition: from {@code from} up to, not including, {@code to}. */
    record Partition(int partition, long earliest, long from, long to) {

        /** Whether the read reached the earliest record the partition still holds. */
        boolean complete() {
            return from <= earliest;
        }
    }

    /**
     * The lifts found, the most recent first, and the offsets read.
     *
     * @param since when the topic received the oldest record read in the partition whose read reached least far
     *              back; every lift the topic received since then is listed. Null when every read was complete.
     */
    record Result(List<Lifted> lifted, List<Partition> partitions, Instant since) {

        boolean complete() {
            return partitions.stream().allMatch(Partition::complete);
        }
    }

    private final AuditSettings settings;
    private final Supplier<Consumer<byte[], byte[]>> consumers;

    @Autowired
    HoldHistory(AuditSettings settings) {
        this(settings, () -> new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, settings.kafkaBootstrapServers(),
                ConsumerConfig.CLIENT_ID_CONFIG, "nuna-hold-history",
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false",
                ConsumerConfig.ALLOW_AUTO_CREATE_TOPICS_CONFIG, "false",
                ConsumerConfig.MAX_POLL_RECORDS_CONFIG, "2000"),
                new ByteArrayDeserializer(), new ByteArrayDeserializer()));
    }

    HoldHistory(AuditSettings settings, Supplier<Consumer<byte[], byte[]>> consumers) {
        this.settings = settings;
        this.consumers = consumers;
    }

    boolean configured() {
        return settings.kafkaConfigured();
    }

    /**
     * The holds released and freezes lifted in the catalog named {@code catalog}, as the records name it.
     *
     * @throws TimeoutException      if Kafka does not answer in time
     * @throws IllegalStateException if the audit topic does not exist
     */
    Result lifted(String catalog) {
        try (Consumer<byte[], byte[]> consumer = consumers.get()) {
            String topic = settings.auditTopic();
            List<PartitionInfo> infos = consumer.partitionsFor(topic, TIMEOUT);
            if (infos == null || infos.isEmpty()) {
                throw new IllegalStateException("audit topic " + topic + " does not exist");
            }
            List<TopicPartition> partitions = infos.stream().map(info -> new TopicPartition(topic, info.partition()))
                    .sorted(Comparator.comparingInt(TopicPartition::partition)).toList();
            consumer.assign(partitions);
            Map<TopicPartition, Long> earliest = consumer.beginningOffsets(partitions, TIMEOUT);
            Map<TopicPartition, Long> end = consumer.endOffsets(partitions, TIMEOUT);
            Set<TopicPartition> open = new HashSet<>();
            List<Partition> read = new ArrayList<>();
            for (TopicPartition partition : partitions) {
                long from = Math.max(earliest.get(partition), end.get(partition) - SCAN_LIMIT);
                read.add(new Partition(partition.partition(), earliest.get(partition), from, end.get(partition)));
                if (from < end.get(partition)) {
                    consumer.seek(partition, from);
                    open.add(partition);
                }
            }
            Map<Integer, Instant> oldest = new HashMap<>();
            List<CatalogEvent> found = new ArrayList<>();
            long deadline = System.nanoTime() + TIMEOUT.toNanos();
            while (!open.isEmpty()) {
                if (System.nanoTime() > deadline) {
                    throw new TimeoutException("reading the audit topic " + topic + " took longer than " + TIMEOUT);
                }
                for (ConsumerRecord<byte[], byte[]> record : consumer.poll(Duration.ofMillis(200))) {
                    if (record.offset() >= end.get(new TopicPartition(record.topic(), record.partition()))) {
                        continue;
                    }
                    oldest.putIfAbsent(record.partition(), Instant.ofEpochMilli(record.timestamp()));
                    CatalogEvent event = change(record.value(), catalog);
                    if (event != null) {
                        found.add(event);
                    }
                }
                open.removeIf(partition -> consumer.position(partition, TIMEOUT) >= end.get(partition));
            }
            Instant since = read.stream().filter(partition -> !partition.complete())
                    .map(partition -> oldest.get(partition.partition())).max(Comparator.naturalOrder()).orElse(null);
            return new Result(lifts(found), List.copyOf(read), since);
        }
    }

    /** The record, when it is a hold change or a DR freeze change in the catalog; null for any other. */
    private static CatalogEvent change(byte[] value, String catalog) {
        String json = new String(value, StandardCharsets.UTF_8);
        if (!json.contains(HOLD_TYPE) && !json.contains(GOVERNANCE_TYPE)) {
            return null;
        }
        CatalogEvent event = EventCodec.decode(value);
        return switch (event.data()) {
            case HoldChange hold when hold.target().catalog().equals(catalog) -> event;
            case GovernanceChange change when change.attribute().equals(FREEZE)
                    && change.target().catalog().equals(catalog) -> event;
            default -> null;
        };
    }

    /**
     * Pairs each release or removal with the placing or setting before it, by when each happened: a hold by its id, a
     * freeze by its target, whose latest setting before the removal gives its reason, as the store keeps only the
     * latest. The most recent lift first.
     */
    static List<Lifted> lifts(List<CatalogEvent> changes) {
        Map<UUID, CatalogEvent> placed = new HashMap<>();
        Map<UUID, CatalogEvent> frozen = new HashMap<>();
        List<Lifted> lifted = new ArrayList<>();
        for (CatalogEvent event : changes.stream().sorted(Comparator.comparing(CatalogEvent::time)).toList()) {
            switch (event.data()) {
                case HoldChange hold when hold.action() == HoldChange.Action.PLACED -> placed.put(hold.holdId(), event);
                case HoldChange hold -> {
                    CatalogEvent at = placed.remove(hold.holdId());
                    lifted.add(new Lifted("hold", hold.holdId(), hold.target(), hold.reason(),
                            at == null ? null : ((HoldChange) at.data()).actor().principal(),
                            at == null ? null : at.time(), hold.actor().principal(), event.time()));
                }
                case GovernanceChange change when change.action() == GovernanceChange.Action.SET ->
                        frozen.put(change.target().id(), event);
                case GovernanceChange change -> {
                    CatalogEvent at = frozen.remove(change.target().id());
                    GovernanceChange set = at == null ? null : (GovernanceChange) at.data();
                    lifted.add(new Lifted("freeze", null, change.target(), set == null ? null : set.value(),
                            set == null ? null : set.actor().principal(), at == null ? null : at.time(),
                            change.actor().principal(), event.time()));
                }
                default -> throw new IllegalArgumentException("not a hold or freeze change: " + event.type());
            }
        }
        return lifted.reversed();
    }
}
