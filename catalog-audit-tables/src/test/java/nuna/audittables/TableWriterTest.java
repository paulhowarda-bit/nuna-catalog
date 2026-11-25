package nuna.audittables;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import nuna.catalog.audit.Actor;
import nuna.catalog.audit.AuditRow;
import nuna.catalog.audit.AuditRows;
import nuna.catalog.audit.CatalogEvent;
import nuna.catalog.audit.Commit;
import nuna.catalog.audit.CredentialIssue;
import nuna.catalog.audit.EventCodec;
import nuna.catalog.audit.ObjectRead;
import nuna.catalog.audit.PointerState;
import nuna.catalog.audit.Securable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.iceberg.DataFiles;
import org.apache.iceberg.FileFormat;
import org.apache.iceberg.HasTableOperations;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.Schema;
import org.apache.iceberg.Snapshot;
import org.apache.iceberg.Table;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.data.IcebergGenerics;
import org.apache.iceberg.data.Record;
import org.apache.iceberg.exceptions.CommitStateUnknownException;
import org.apache.iceberg.exceptions.ValidationException;
import org.apache.iceberg.io.CloseableIterable;
import org.apache.iceberg.types.Types;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;

/**
 * The audit table writer (D23, amended 2026-11-20) against Kafka's own fake consumer and Iceberg's in-memory catalog:
 * it writes every record once with its offset, resumes after the last offset the table holds, writes no record twice
 * and skips none when a commit fails or a second writer races it, and leaves out the records of its own requests.
 */
class TableWriterTest {

    private static final String TOPIC = "nuna.catalog.audit";
    private static final String WRITER = "nuna-audit-tables";
    private static final Namespace NAMESPACE = Namespace.of("audit");
    private static final TableIdentifier EVENTS = TableIdentifier.of(NAMESPACE, EventsTable.NAME);
    private static final Duration INTERVAL = Duration.ofMinutes(1);
    private static final URI SOURCE = URI.create("urn:nuna:deployment:test");
    private static final Securable ORDERS = new Securable(Securable.Kind.TABLE,
            UUID.fromString("7f0c2a52-9d0e-4a8b-9c61-2b1e6f0d4a11"), "lake", List.of("sales", "orders"));
    private static final PointerState V1 = new PointerState("s3://lake/orders/metadata/00001.json", 1, "a".repeat(64));
    private static final PointerState V2 = new PointerState("s3://lake/orders/metadata/00002.json", 2, "b".repeat(64));
    private static final AtomicInteger REQUESTS = new AtomicInteger();

    private final FaultyCatalog catalog = new FaultyCatalog();
    private final MovableClock clock = new MovableClock();
    /** The audit topic as a log, per partition, so a new consumer can be handed it again. */
    private final Map<Integer, List<ConsumerRecord<byte[], byte[]>>> log = new HashMap<>(Map.of(0, new ArrayList<>(),
            1, new ArrayList<>()));
    private final List<Running> running = new ArrayList<>();

    /** A writer and the fake consumer it reads through. */
    private record Running(TableWriter writer, MockConsumer<byte[], byte[]> consumer) {

        void poll() {
            writer.pollOnce(Duration.ZERO);
        }
    }

    // ---- the topic ----

    private static CatalogEvent read(String principal) {
        return CatalogEvent.create(SOURCE, Instant.parse("2026-11-20T08:59:00.123456Z"), new ObjectRead(ORDERS, "main",
                V1, new Actor(principal, null, null, "req-" + REQUESTS.incrementAndGet())));
    }

    private static CatalogEvent commit(String principal) {
        return CatalogEvent.create(SOURCE, Instant.parse("2026-11-19T23:59:59Z"), new Commit(UUID.randomUUID(), ORDERS,
                "main", V1, V2, "[]", new Actor(principal, null, null, "req-" + REQUESTS.incrementAndGet())));
    }

    private static CatalogEvent credentials(String principal) {
        return CatalogEvent.create(SOURCE, Instant.parse("2026-11-20T08:59:01Z"), new CredentialIssue(ORDERS, "s3",
                "s3://lake/orders/", CredentialIssue.Access.READ_WRITE, "nuna-" + principal,
                Instant.parse("2026-11-20T09:29:01Z"), new Actor(principal, null, null,
                "req-" + REQUESTS.incrementAndGet())));
    }

    /** Appends a record to the topic and hands it to every running consumer. */
    private ConsumerRecord<byte[], byte[]> send(int partition, byte[] value) {
        List<ConsumerRecord<byte[], byte[]>> records = log.get(partition);
        ConsumerRecord<byte[], byte[]> record = new ConsumerRecord<>(TOPIC, partition, records.size(), null, value);
        records.add(record);
        running.forEach(r -> r.consumer().addRecord(record));
        return record;
    }

    private CatalogEvent send(int partition, CatalogEvent event) {
        send(partition, EventCodec.encode(event));
        return event;
    }

    /**
     * Hands a writer's consumer the whole topic again, as Kafka would after the writer seeks: the fake consumer keeps
     * no log, and drops what lies before the place the writer seeks to.
     */
    private void redeliver(Running writer) {
        log.values().forEach(records -> records.forEach(writer.consumer()::addRecord));
    }

    // ---- writers ----

    private Running start(int maxRows) {
        MockConsumer<byte[], byte[]> consumer = new MockConsumer<>("earliest");
        TableWriter writer = new TableWriter(consumer, TOPIC, () -> EventsTable.open(catalog, NAMESPACE, TOPIC), WRITER,
                INTERVAL, maxRows, clock);
        List<TopicPartition> partitions = log.keySet().stream().map(p -> new TopicPartition(TOPIC, p)).toList();
        consumer.rebalance(partitions);
        Map<TopicPartition, Long> beginnings = new HashMap<>();
        partitions.forEach(p -> beginnings.put(p, 0L));
        consumer.updateBeginningOffsets(beginnings);
        Running started = new Running(writer, consumer);
        running.add(started);
        return started;
    }

    private Running start() {
        return start(1000);
    }

    /** Stops a writer as a crash would: what waits is never written. */
    private void kill(Running writer) {
        running.remove(writer);
    }

    // ---- the table ----

    private record Row(String eventId, int partition, long offset) {
    }

    private List<Record> records() {
        try (CloseableIterable<Record> records = IcebergGenerics.read(catalog.loadTable(EVENTS)).build()) {
            List<Record> all = new ArrayList<>();
            records.forEach(all::add);
            return all;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private List<Row> rows() {
        return records().stream().map(r -> new Row((String) r.getField("event_id"), (Integer) r.getField("topic_partition"),
                (Long) r.getField("topic_offset"))).toList();
    }

    private static Row row(int partition, long offset, CatalogEvent event) {
        return new Row(event.id().toString(), partition, offset);
    }

    private Snapshot current() {
        return catalog.loadTable(EVENTS).currentSnapshot();
    }

    // ---- tests ----

    @Test
    void createsTheTableItWrites() {
        start().poll();

        Table table = catalog.loadTable(EVENTS);
        assertThat(table.schema().columns()).extracting(Types.NestedField::name).containsExactly("event_id", "time",
                "type", "source", "principal", "end_user", "engine", "request_id", "catalog", "object_kind", "object_id",
                "object_path", "operation", "privilege", "reason", "data", "topic_partition", "topic_offset", "log_run",
                "log_seq");
        assertThat(table.schema().findType("time")).isEqualTo(Types.TimestampType.withZone());
        assertThat(table.schema().findType("object_path").toString()).isEqualTo("list<string>");
        assertThat(table.spec().fields()).singleElement().satisfies(field -> {
            assertThat(field.transform().toString()).isEqualTo("day");
            assertThat(table.schema().findColumnName(field.sourceId())).isEqualTo("time");
        });
        assertThat(((HasTableOperations) table).operations().current().formatVersion()).isEqualTo(2);
        assertThat(table.currentSnapshot()).isNull();
    }

    @Test
    void writesEveryRecordOnceWithItsPlaceInTheTopic() {
        Running writer = start();
        CatalogEvent a = send(0, read("alice"));
        CatalogEvent b = send(0, commit("alice"));
        CatalogEvent c = send(1, read("bob"));
        CatalogEvent d = send(0, credentials("bob"));
        writer.poll();
        assertThat(current()).as("rows wait for the commit interval").isNull();

        clock.advance(INTERVAL);
        writer.poll();

        assertThat(rows()).containsExactlyInAnyOrder(row(0, 0, a), row(0, 1, b), row(1, 0, c), row(0, 2, d));
        assertThat(current().summary()).containsEntry(EventsTable.OFFSETS + TOPIC, "0:3,1:1")
                .containsEntry(EventsTable.ROWS, "4");
        Record written = records().stream().filter(r -> r.getField("event_id").equals(b.id().toString())).findFirst()
                .orElseThrow();
        AuditRow expected = AuditRows.of(b, 0, 1);
        assertThat(written.getField("time")).isEqualTo(OffsetDateTime.parse("2026-11-19T23:59:59Z"));
        assertThat(written.getField("type")).isEqualTo("nuna.catalog.commit");
        assertThat(written.getField("source")).isEqualTo(SOURCE.toString());
        assertThat(written.getField("principal")).isEqualTo("alice");
        assertThat(written.getField("end_user")).isNull();
        assertThat(written.getField("request_id")).isEqualTo(expected.requestId());
        assertThat(written.getField("catalog")).isEqualTo("lake");
        assertThat(written.getField("object_kind")).isEqualTo("TABLE");
        assertThat(written.getField("object_id")).isEqualTo(ORDERS.id().toString());
        assertThat(written.getField("object_path")).isEqualTo(List.of("sales", "orders"));
        assertThat(written.getField("data")).isEqualTo(expected.data());
    }

    @Test
    void keepsTheTimeToTheMicrosecond() {
        Running writer = start();
        CatalogEvent a = send(0, read("alice"));
        writer.poll();
        clock.advance(INTERVAL);
        writer.poll();

        assertThat(records()).singleElement().satisfies(r -> assertThat(r.getField("time"))
                .isEqualTo(OffsetDateTime.parse("2026-11-20T08:59:00.123456Z")));
        assertThat(rows()).containsExactly(row(0, 0, a));
    }

    @Test
    void commitsAfterTheIntervalOrOnceTheBatchIsFull() {
        Running writer = start(3);
        send(0, read("alice"));
        writer.poll();
        clock.advance(INTERVAL.minusSeconds(1));
        writer.poll();
        assertThat(current()).isNull();
        clock.advance(Duration.ofSeconds(1));
        writer.poll();
        assertThat(rows()).hasSize(1);

        send(0, read("alice"));
        send(1, read("bob"));
        writer.poll();
        assertThat(rows()).hasSize(1);
        send(1, read("carol"));
        writer.poll();
        assertThat(rows()).as("three rows make a full batch").hasSize(4);
    }

    @Test
    void resumesAfterItsLastCommittedOffsetAndWritesNoneTwice() {
        Running first = start();
        List<Row> expected = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            expected.add(row(0, i, send(0, read("alice"))));
        }
        first.poll();
        clock.advance(INTERVAL);
        first.poll();
        for (int i = 5; i < 8; i++) {
            expected.add(row(0, i, send(0, read("alice"))));
        }
        expected.add(row(1, 0, send(1, read("bob"))));
        first.poll();
        kill(first);

        Running second = start();
        redeliver(second);
        second.poll();
        clock.advance(INTERVAL);
        second.poll();

        assertThat(rows()).containsExactlyInAnyOrderElementsOf(expected);
        assertThat(current().summary()).containsEntry(EventsTable.OFFSETS + TOPIC, "0:8,1:1");
    }

    @Test
    void aCommitThatNeverLandsLeavesNoGapAndNoRepeat() {
        Running writer = start();
        List<Row> expected = new ArrayList<>();
        expected.add(row(0, 0, send(0, read("alice"))));
        expected.add(row(0, 1, send(0, read("bob"))));
        expected.add(row(1, 0, send(1, read("carol"))));
        writer.poll();
        clock.advance(INTERVAL);
        catalog.failNextRows(FaultyCatalog.Fault.LOST);
        assertThatThrownBy(writer::poll).hasMessageContaining("injected");
        assertThat(current()).as("nothing landed").isNull();

        // Kafka hands the records again from the offsets the writer goes back to.
        redeliver(writer);
        writer.poll();
        clock.advance(INTERVAL);
        writer.poll();

        assertThat(catalog.faults()).isEqualTo(1);
        assertThat(rows()).containsExactlyInAnyOrderElementsOf(expected);
        assertThat(current().summary()).containsEntry(EventsTable.OFFSETS + TOPIC, "0:2,1:1");
    }

    @Test
    void aCommitWhoseAnswerIsLostIsNotWrittenAgain() {
        Running writer = start();
        List<Row> expected = new ArrayList<>();
        expected.add(row(0, 0, send(0, read("alice"))));
        expected.add(row(0, 1, send(0, read("bob"))));
        writer.poll();
        clock.advance(INTERVAL);
        catalog.failNextRows(FaultyCatalog.Fault.ANSWER_LOST);
        assertThatThrownBy(writer::poll).isInstanceOf(CommitStateUnknownException.class);
        assertThat(rows()).as("the commit landed").hasSize(2);

        redeliver(writer);
        writer.poll();
        expected.add(row(0, 2, send(0, read("carol"))));
        writer.poll();
        clock.advance(INTERVAL);
        writer.poll();

        assertThat(rows()).containsExactlyInAnyOrderElementsOf(expected);
    }

    @Test
    void leavesOutTheRecordsOfItsOwnRequests() {
        Running writer = start();
        send(0, read(WRITER));
        send(0, credentials(WRITER));
        CatalogEvent alice = send(0, read("alice"));
        send(0, commit(WRITER));
        writer.poll();
        clock.advance(INTERVAL);
        writer.poll();

        assertThat(rows()).containsExactly(row(0, 2, alice));
        assertThat(current().summary()).as("its own records are read past, not written")
                .containsEntry(EventsTable.OFFSETS + TOPIC, "0:4");

        // The records its commit made bring no commit of their own.
        long snapshot = current().snapshotId();
        send(0, read(WRITER));
        send(0, commit(WRITER));
        writer.poll();
        clock.advance(INTERVAL);
        writer.poll();
        assertThat(current().snapshotId()).isEqualTo(snapshot);
        assertThat(rows()).hasSize(1);
    }

    @Test
    void aSecondWriterCannotWriteTheSameRecordsAgain() {
        List<Row> expected = new ArrayList<>();
        expected.add(row(0, 0, send(0, read("alice"))));
        expected.add(row(0, 1, send(0, read("bob"))));
        Running first = start();
        Running second = start();
        redeliver(first);
        redeliver(second);
        first.poll();
        second.poll();

        clock.advance(INTERVAL);
        first.poll();
        assertThatThrownBy(second::poll).isInstanceOf(ValidationException.class);

        redeliver(second);
        second.poll();
        clock.advance(INTERVAL);
        second.poll();
        assertThat(rows()).containsExactlyInAnyOrderElementsOf(expected);
    }

    @Test
    void rowsOfARevokedPartitionAreLeftForItsNextOwner() {
        Running writer = start();
        List<Row> expected = new ArrayList<>();
        expected.add(row(0, 0, send(0, read("alice"))));
        expected.add(row(1, 0, send(1, read("bob"))));
        expected.add(row(1, 1, send(1, read("carol"))));
        writer.poll();

        writer.consumer().rebalance(List.of(new TopicPartition(TOPIC, 0)));
        clock.advance(INTERVAL);
        expected.add(row(0, 1, send(0, read("dave"))));
        writer.poll();
        assertThat(current().summary()).containsEntry(EventsTable.OFFSETS + TOPIC, "0:2");
        assertThat(rows()).extracting(Row::partition).containsOnly(0);

        writer.consumer().rebalance(List.of(new TopicPartition(TOPIC, 0), new TopicPartition(TOPIC, 1)));
        redeliver(writer);
        writer.poll();
        clock.advance(INTERVAL);
        writer.poll();
        assertThat(rows()).containsExactlyInAnyOrderElementsOf(expected);
    }

    @Test
    void aRecordItCannotMapStopsItThere() {
        Running writer = start();
        send(0, read("alice"));
        send(0, "{\"specversion\":\"1.0\",\"type\":\"nuna.catalog.unknown\"}".getBytes(UTF_8));
        send(0, read("bob"));
        assertThatThrownBy(writer::poll).hasMessageContaining(TOPIC + "-0@1");

        clock.advance(INTERVAL);
        redeliver(writer);
        assertThatThrownBy(writer::poll).as("it stops at the same record again").hasMessageContaining(TOPIC + "-0@1");
        assertThat(current()).as("nothing after it is written, and nothing skipped").isNull();
    }

    @Test
    void refusesATableWithOtherColumns() {
        catalog.createNamespace(NAMESPACE);
        catalog.createTable(EVENTS, new Schema(Types.NestedField.required(1, "event_id", Types.LongType.get())));

        assertThatThrownBy(start()::poll).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not the audit table");
    }

    @Test
    void refusesToGuessWhereToStartWhenItsOffsetsAreGone() {
        start().poll();
        Table table = catalog.loadTable(EVENTS);
        PartitionSpec spec = table.spec();
        table.newAppend().appendFile(DataFiles.builder(spec).withPath("mem://elsewhere/data/f.parquet")
                .withFormat(FileFormat.PARQUET).withFileSizeInBytes(10).withRecordCount(1)
                .withPartitionPath("time_day=2026-11-20").build()).commit();

        assertThatThrownBy(start()::poll).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("snapshot expiry");
    }
}
