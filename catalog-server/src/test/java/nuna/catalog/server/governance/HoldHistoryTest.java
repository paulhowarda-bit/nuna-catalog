package nuna.catalog.server.governance;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import nuna.catalog.audit.Actor;
import nuna.catalog.audit.CatalogEvent;
import nuna.catalog.audit.EventCodec;
import nuna.catalog.audit.EventData;
import nuna.catalog.audit.GovernanceChange;
import nuna.catalog.audit.HoldChange;
import nuna.catalog.audit.ObjectRead;
import nuna.catalog.audit.PointerState;
import nuna.catalog.audit.Securable;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.common.record.TimestampType;
import org.junit.jupiter.api.Test;

/**
 * The holds released and freezes lifted in a catalog, read back from the audit topic (item 128): every partition's
 * last {@link HoldHistory#SCAN_LIMIT} offsets, only the catalog's hold and DR freeze changes, each release or removal
 * paired with its placing or setting, and a window that says how far back the read reached. Kafka's own
 * {@link MockConsumer} stands in for the topic, so this needs only Java.
 */
class HoldHistoryTest {

    static final String TOPIC = "nuna.catalog.audit";
    static final Instant T0 = Instant.parse("2026-11-20T08:00:00Z");
    static final Securable STAFF = new Securable(Securable.Kind.TABLE, UUID.randomUUID(), "gate",
            List.of("hr", "staff"));
    static final Securable HR = new Securable(Securable.Kind.NAMESPACE, UUID.randomUUID(), "gate", List.of("hr"));
    static final Securable ELSEWHERE = new Securable(Securable.Kind.TABLE, UUID.randomUUID(), "other",
            List.of("hr", "staff"));

    private final Map<Integer, Long> ends = new HashMap<>(Map.of(0, 0L, 1, 0L));
    private final Map<Integer, Long> beginnings = new HashMap<>(Map.of(0, 0L, 1, 0L));
    /** The records sent, which the consumer is given once the history assigns it the partitions. */
    private final List<ConsumerRecord<byte[], byte[]>> topic = new ArrayList<>();
    private final MockConsumer<byte[], byte[]> consumer = new MockConsumer<>("earliest") {
        @Override
        public synchronized void assign(Collection<TopicPartition> partitions) {
            super.assign(partitions);
            topic.forEach(this::addRecord);
        }
    };

    @Test
    void eachReleaseAndRemovalIsPairedWithItsPlacingTheMostRecentLiftFirst() {
        UUID hold = UUID.randomUUID();
        send(0, minutes(0), new HoldChange(HoldChange.Action.PLACED, hold, STAFF, "matter 9", actor("stella")));
        send(1, minutes(1), new GovernanceChange(GovernanceChange.Action.SET, HR, "dr-freeze", "drill 1",
                "management-api", actor("oscar")));
        send(1, minutes(2), new GovernanceChange(GovernanceChange.Action.SET, HR, "dr-freeze", "drill 1, extended",
                "management-api", actor("olga")));
        send(0, minutes(3), read(STAFF));
        send(1, minutes(4), new GovernanceChange(GovernanceChange.Action.REMOVED, HR, "dr-freeze", null, null,
                actor("oscar")));
        send(0, minutes(5), new HoldChange(HoldChange.Action.RELEASED, hold, STAFF, "matter 9", actor("sam")));

        HoldHistory.Result result = history().lifted("gate");

        assertThat(result.lifted()).containsExactly(
                new HoldHistory.Lifted("hold", hold, STAFF, "matter 9", "stella", minutes(0), "sam", minutes(5)),
                new HoldHistory.Lifted("freeze", null, HR, "drill 1, extended", "olga", minutes(2), "oscar",
                        minutes(4)));
        assertThat(result.complete()).isTrue();
        assertThat(result.since()).isNull();
        assertThat(result.partitions()).containsExactly(new HoldHistory.Partition(0, 0, 0, 3),
                new HoldHistory.Partition(1, 0, 0, 3));
    }

    @Test
    void onlyTheCatalogsHoldsAndFreezesAreRead() {
        UUID hold = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        send(0, minutes(0), new HoldChange(HoldChange.Action.PLACED, other, ELSEWHERE, "theirs", actor("stella")));
        send(0, minutes(1), new HoldChange(HoldChange.Action.RELEASED, other, ELSEWHERE, "theirs", actor("stella")));
        send(1, minutes(2), new GovernanceChange(GovernanceChange.Action.SET, HR, "classification", "internal",
                "collibra", actor("stella")));
        send(1, minutes(3), new GovernanceChange(GovernanceChange.Action.REMOVED, HR, "classification", null, null,
                actor("stella")));
        send(0, minutes(4), new HoldChange(HoldChange.Action.PLACED, hold, STAFF, "ours", actor("stella")));

        assertThat(history().lifted("gate").lifted()).as("a standing hold is the store's to list").isEmpty();
    }

    @Test
    void aReadThatStopsShortSaysSinceWhenAndLeavesOutWhatWasPlacedBefore() {
        UUID hold = UUID.randomUUID();
        UUID older = UUID.randomUUID();
        long from = 5;
        long end = from + HoldHistory.SCAN_LIMIT;
        beginnings.put(0, 0L);
        send(0, 2, minutes(0), new HoldChange(HoldChange.Action.PLACED, hold, STAFF, "matter 9", actor("stella")));
        send(0, 3, minutes(1), new HoldChange(HoldChange.Action.RELEASED, older, STAFF, "matter 1", actor("sam")));
        send(0, from, minutes(2), read(STAFF));
        send(0, from + 10, minutes(3), new HoldChange(HoldChange.Action.RELEASED, hold, STAFF, "matter 9",
                actor("sam")));
        send(0, end - 1, minutes(4), read(STAFF));
        send(1, 0, minutes(0), read(HR));

        HoldHistory.Result result = history().lifted("gate");

        assertThat(result.lifted()).as("the release read, its placing not; the earlier release not read at all")
                .containsExactly(new HoldHistory.Lifted("hold", hold, STAFF, "matter 9", null, null, "sam",
                        minutes(3)));
        assertThat(result.partitions()).containsExactly(new HoldHistory.Partition(0, 0, from, end),
                new HoldHistory.Partition(1, 0, 0, 1));
        assertThat(result.complete()).isFalse();
        assertThat(result.since()).as("when the topic took the first record read").isEqualTo(minutes(2));
    }

    @Test
    void aFreezeSetBeforeTheReadIsListedWithoutItsReason() {
        send(1, minutes(1), new GovernanceChange(GovernanceChange.Action.REMOVED, HR, "dr-freeze", null, null,
                actor("oscar")));

        assertThat(history().lifted("gate").lifted()).containsExactly(
                new HoldHistory.Lifted("freeze", null, HR, null, null, null, "oscar", minutes(1)));
    }

    // ---- the topic ----

    HoldHistory history() {
        Node node = new Node(0, "localhost", 9092);
        consumer.updatePartitions(TOPIC, List.of(
                new PartitionInfo(TOPIC, 0, node, new Node[] {node}, new Node[] {node}),
                new PartitionInfo(TOPIC, 1, node, new Node[] {node}, new Node[] {node})));
        Map<TopicPartition, Long> first = new HashMap<>();
        Map<TopicPartition, Long> last = new HashMap<>();
        beginnings.forEach((p, offset) -> first.put(new TopicPartition(TOPIC, p), offset));
        ends.forEach((p, offset) -> last.put(new TopicPartition(TOPIC, p), offset));
        consumer.updateBeginningOffsets(first);
        consumer.updateEndOffsets(last);
        return new HoldHistory(settings("localhost:9092"), () -> consumer);
    }

    static AuditSettings settings(String bootstrap) {
        return new AuditSettings(bootstrap, TOPIC, "nuna.catalog.changes", Duration.ofSeconds(5),
                Duration.ofSeconds(2), Duration.ofSeconds(10), Duration.ofMillis(250), Duration.ofSeconds(30), 500,
                new AuditSettings.Archive(null, null, "audit", Duration.ofDays(1827), 10000, Duration.ofMinutes(5),
                        "nuna-audit-archive"));
    }

    /** Appends the record at the partition's end, stamped by the topic when the event happened. */
    void send(int partition, Instant at, EventData data) {
        send(partition, ends.get(partition), at, data);
    }

    void send(int partition, long offset, Instant at, EventData data) {
        byte[] value = EventCodec.encode(CatalogEvent.create(URI.create("urn:nuna:test"), at, data));
        topic.add(new ConsumerRecord<>(TOPIC, partition, offset, at.toEpochMilli(),
                TimestampType.CREATE_TIME, -1, value.length, null, value, new RecordHeaders(), Optional.empty()));
        ends.put(partition, Math.max(ends.get(partition), offset + 1));
    }

    static ObjectRead read(Securable on) {
        return new ObjectRead(on, "main", new PointerState("s3://gate/m.json", 1, "sha"), actor("ann"));
    }

    static Actor actor(String principal) {
        return new Actor(principal, null, "console", "request-" + UUID.randomUUID());
    }

    static Instant minutes(long minutes) {
        return T0.plus(Duration.ofMinutes(minutes));
    }
}
