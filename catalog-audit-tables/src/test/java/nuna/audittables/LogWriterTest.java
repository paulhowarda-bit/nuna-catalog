package nuna.audittables;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import nuna.catalog.audit.Actor;
import nuna.catalog.audit.CatalogEvent;
import nuna.catalog.audit.ObjectRead;
import nuna.catalog.audit.PointerState;
import nuna.catalog.audit.Securable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.iceberg.Table;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.data.IcebergGenerics;
import org.apache.iceberg.data.Record;
import org.apache.iceberg.exceptions.CommitStateUnknownException;
import org.apache.iceberg.io.CloseableIterable;
import org.junit.jupiter.api.Test;

/**
 * The audit table writer's source {@code otlp} (D23, amended 2026-11-23), against Iceberg's in-memory catalog: records
 * from the log are written once each, deduplicated by CloudEvent id across repeats, a commit whose answer was lost and
 * a restart; their rows are in no topic partition and their numbers rise and never repeat; a failed commit loses
 * nothing; the writer's own records are left out.
 */
class LogWriterTest {

    private static final String WRITER = "nuna-audit-tables";
    private static final Namespace NAMESPACE = Namespace.of("audit");
    private static final TableIdentifier EVENTS = TableIdentifier.of(NAMESPACE, EventsTable.NAME);
    private static final Duration INTERVAL = Duration.ofMinutes(1);
    private static final Duration WINDOW = Duration.ofMinutes(15);
    private static final URI SOURCE = URI.create("urn:nuna:deployment:test");
    private static final Securable ORDERS = new Securable(Securable.Kind.TABLE, UUID.randomUUID(), "lake",
            List.of("sales", "orders"));
    private static final PointerState V1 = new PointerState("s3://lake/orders/metadata/00001.json", 1, "a".repeat(64));
    private static final AtomicInteger REQUESTS = new AtomicInteger();

    private final FaultyCatalog catalog = new FaultyCatalog();
    private final MovableClock clock = new MovableClock();

    private LogWriter writer(int maxRows) {
        return new LogWriter(() -> EventsTable.open(catalog, NAMESPACE, LogWriter.SOURCE), WRITER, INTERVAL, maxRows,
                WINDOW, clock);
    }

    private CatalogEvent read(String principal) {
        return CatalogEvent.create(SOURCE, clock.instant(), new ObjectRead(ORDERS, "main", V1,
                new Actor(principal, null, null, "req-" + REQUESTS.incrementAndGet())));
    }

    private List<CatalogEvent> reads(int n) {
        List<CatalogEvent> events = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            events.add(read("reader"));
        }
        return events;
    }

    private record Row(String eventId, int partition, long offset) {
    }

    private List<Row> rows() {
        Table table = catalog.loadTable(EVENTS);
        try (CloseableIterable<Record> records = IcebergGenerics.read(table).build()) {
            List<Row> all = new ArrayList<>();
            records.forEach(r -> all.add(new Row((String) r.getField("event_id"), (Integer) r.getField("topic_partition"),
                    (Long) r.getField("topic_offset"))));
            return all;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static List<String> ids(List<CatalogEvent> events) {
        return events.stream().map(e -> e.id().toString()).toList();
    }

    @Test
    void writesEachRecordOnceInNoTopicPartitionWithRisingNumbers() {
        LogWriter writer = writer(1_000);
        List<CatalogEvent> first = reads(3);

        assertThat(writer.offer(first)).isEqualTo(new LogWriter.Taken(3, 0, 0, 0));
        assertThat(writer.offer(List.of(first.get(1)))).as("the exporter sent it again")
                .isEqualTo(new LogWriter.Taken(0, 1, 0, 0));
        writer.commitIfDue();
        assertThat(rows()).as("not due before the interval").isEmpty();

        clock.advance(INTERVAL);
        writer.commitIfDue();

        List<Row> rows = rows();
        assertThat(rows).extracting(Row::eventId).containsExactlyInAnyOrderElementsOf(ids(first));
        assertThat(rows).extracting(Row::partition).containsOnly(LogWriter.PARTITION);
        assertThat(rows.stream().map(Row::offset).distinct()).hasSize(3);
        long highest = rows.stream().mapToLong(Row::offset).max().orElseThrow();
        assertThat(catalog.loadTable(EVENTS).currentSnapshot().summary())
                .containsEntry(EventsTable.OFFSETS + LogWriter.SOURCE, LogWriter.PARTITION + ":" + (highest + 1));
    }

    @Test
    void aRestartedWriterLeavesOutARecentRecordItWroteAndNumbersAboveIt() {
        LogWriter first = writer(1_000);
        List<CatalogEvent> written = reads(2);
        first.offer(written);
        first.commit();
        long highest = rows().stream().mapToLong(Row::offset).max().orElseThrow();

        LogWriter restarted = writer(1_000);
        restarted.commit();
        CatalogEvent fresh = read("reader");
        assertThat(restarted.offer(List.of(written.get(0), fresh))).isEqualTo(new LogWriter.Taken(1, 1, 0, 0));
        restarted.commit();

        assertThat(rows()).extracting(Row::eventId).containsExactlyInAnyOrder(written.get(0).id().toString(),
                written.get(1).id().toString(), fresh.id().toString());
        assertThat(rows()).filteredOn(row -> row.eventId().equals(fresh.id().toString())).singleElement()
                .satisfies(row -> assertThat(row.offset()).isGreaterThan(highest));
    }

    @Test
    void aCommitWhoseAnswerWasLostIsNotWrittenAgain() {
        LogWriter writer = writer(1_000);
        writer.commit();
        List<CatalogEvent> events = reads(3);
        writer.offer(events);
        catalog.failNextRows(FaultyCatalog.Fault.ANSWER_LOST);

        assertThatThrownBy(writer::commit).isInstanceOf(CommitStateUnknownException.class);
        writer.offer(List.of(events.get(2)));
        writer.commit();

        assertThat(rows()).extracting(Row::eventId).containsExactlyInAnyOrderElementsOf(ids(events));
        assertThat(writer.waiting()).isZero();
    }

    @Test
    void aCommitThatFailsKeepsItsRowsForTheNext() {
        LogWriter writer = writer(1_000);
        writer.commit();
        List<CatalogEvent> events = reads(2);
        writer.offer(events);
        catalog.failNextRows(FaultyCatalog.Fault.LOST);

        assertThatThrownBy(writer::commit).isInstanceOf(IllegalStateException.class);
        assertThat(writer.waiting()).isEqualTo(2);
        writer.commit();

        assertThat(rows()).extracting(Row::eventId).containsExactlyInAnyOrderElementsOf(ids(events));
    }

    @Test
    void leavesOutTheRecordsOfItsOwnRequests() {
        LogWriter writer = writer(1_000);
        CatalogEvent own = read(WRITER);
        CatalogEvent other = read("reader");

        assertThat(writer.offer(List.of(own, other))).isEqualTo(new LogWriter.Taken(1, 0, 1, 0));
        writer.commit();

        assertThat(rows()).extracting(Row::eventId).containsExactly(other.id().toString());
    }

    @Test
    void writesToATableTheTopicFilledBefore() {
        EventsTable fromTopic = EventsTable.open(catalog, NAMESPACE, "nuna.catalog.audit");
        fromTopic.reload();
        CatalogEvent earlier = read("reader");
        fromTopic.append(List.of(nuna.catalog.audit.AuditRows.of(earlier, 0, 41)), java.util.Map.of(0, 42L));

        LogWriter writer = writer(1_000);
        CatalogEvent later = read("reader");
        writer.offer(List.of(later));
        writer.commit();

        assertThat(rows()).extracting(Row::partition).containsExactlyInAnyOrder(0, LogWriter.PARTITION);
    }

    @Test
    void refusesPastTwiceMaxRowsWaiting() {
        LogWriter writer = writer(2);
        catalog.failNextRows(FaultyCatalog.Fault.LOST);
        writer.offer(reads(4));

        assertThatThrownBy(() -> writer.offer(reads(1))).isInstanceOf(LogWriter.Busy.class);
        assertThatThrownBy(writer::commitIfDue).isInstanceOf(IllegalStateException.class);
        writer.commitIfDue();
        assertThat(writer.offer(reads(1)).written()).isEqualTo(1);
    }
}
