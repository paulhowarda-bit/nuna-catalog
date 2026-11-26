package nuna.audittables;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import nuna.catalog.audit.Actor;
import nuna.catalog.audit.CatalogEvent;
import nuna.catalog.audit.LogStamp;
import nuna.catalog.audit.ObjectRead;
import nuna.catalog.audit.PointerState;
import nuna.catalog.audit.Securable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.iceberg.Table;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.data.IcebergGenerics;
import org.apache.iceberg.data.Record;
import org.apache.iceberg.exceptions.CommitStateUnknownException;
import org.apache.iceberg.exceptions.ForbiddenException;
import org.apache.iceberg.exceptions.NoSuchTableException;
import org.apache.iceberg.io.CloseableIterable;
import org.junit.jupiter.api.Test;

/**
 * The audit table writer's source {@code otlp} (D23, amended 2026-11-23), against Iceberg's in-memory catalog: records
 * from the log are written once each, deduplicated by CloudEvent id across repeats, a commit whose answer was lost and
 * a restart; their rows are in no topic partition and their numbers rise and never repeat; a failed commit loses
 * nothing; the writer's own records are left out; a table dropped under the writer is made again, a refusal never.
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
    /** How many times a writer's opener was called. */
    private final AtomicInteger opened = new AtomicInteger();

    private LogWriter writer(int maxRows) {
        return new LogWriter(() -> {
            opened.incrementAndGet();
            return EventsTable.open(catalog, NAMESPACE, LogWriter.SOURCE);
        }, WRITER, INTERVAL, maxRows, WINDOW, clock);
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

    /** The records as a server that stamped none logged them. */
    private static LogWriter.Taken offer(LogWriter writer, List<CatalogEvent> events) {
        return writer.offer(events.stream().map(e -> new LogWriter.Logged(e, Optional.empty())).toList());
    }

    private static List<String> ids(List<CatalogEvent> events) {
        return events.stream().map(e -> e.id().toString()).toList();
    }

    @Test
    void writesEachRecordOnceInNoTopicPartitionWithRisingNumbers() {
        LogWriter writer = writer(1_000);
        List<CatalogEvent> first = reads(3);

        assertThat(offer(writer, first)).isEqualTo(new LogWriter.Taken(3, 0, 0, 0));
        assertThat(offer(writer, List.of(first.get(1)))).as("the exporter sent it again")
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
    void eachRowKeepsTheRunAndNumberItWasLoggedWithAndARecordLoggedTwiceKeepsItsFirst() {
        // Backlog 272: the stamp places the row in its run's archive chain.
        LogWriter writer = writer(1_000);
        UUID run = UUID.fromString("a0000000-0000-4000-8000-000000000272");
        CatalogEvent a = read("reader");
        CatalogEvent b = read("reader");
        CatalogEvent unstamped = read("reader");

        assertThat(writer.offer(List.of(new LogWriter.Logged(a, Optional.of(new LogStamp(run, 1))),
                new LogWriter.Logged(b, Optional.of(new LogStamp(run, 2))),
                new LogWriter.Logged(a, Optional.of(new LogStamp(run, 3))),
                new LogWriter.Logged(unstamped, Optional.empty())))).as("the relay logged a twice")
                .isEqualTo(new LogWriter.Taken(3, 1, 0, 0));
        writer.commit();

        Table table = catalog.loadTable(EVENTS);
        java.util.Map<String, String> stamps = new java.util.HashMap<>();
        try (CloseableIterable<Record> records = IcebergGenerics.read(table).build()) {
            records.forEach(r -> stamps.put((String) r.getField("event_id"), r.getField("log_run") + ":"
                    + r.getField("log_seq")));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        assertThat(stamps).containsOnly(java.util.Map.entry(a.id().toString(), run + ":1"),
                java.util.Map.entry(b.id().toString(), run + ":2"),
                java.util.Map.entry(unstamped.id().toString(), "null:null"));
    }

    @Test
    void aRestartedWriterLeavesOutARecentRecordItWroteAndNumbersAboveIt() {
        LogWriter first = writer(1_000);
        List<CatalogEvent> written = reads(2);
        offer(first, written);
        first.commit();
        long highest = rows().stream().mapToLong(Row::offset).max().orElseThrow();

        LogWriter restarted = writer(1_000);
        restarted.commit();
        CatalogEvent fresh = read("reader");
        assertThat(offer(restarted, List.of(written.get(0), fresh))).isEqualTo(new LogWriter.Taken(1, 1, 0, 0));
        restarted.commit();

        assertThat(rows()).extracting(Row::eventId).containsExactlyInAnyOrder(written.get(0).id().toString(),
                written.get(1).id().toString(), fresh.id().toString());
        assertThat(rows()).filteredOn(row -> row.eventId().equals(fresh.id().toString())).singleElement()
                .satisfies(row -> assertThat(row.offset()).isGreaterThan(highest));
    }

    @Test
    void aCopyArrivingAnHourAfterTheFirstIsLeftOutAndALateFirstCopyIsWritten() {
        // Backlog 263: a collector's persistent queue re-sends its batches after a crash, or drains a backlog, long
        // after the ids taken are forgotten in memory.
        LogWriter writer = writer(1_000);
        CatalogEvent event = read("reader");
        offer(writer, List.of(event));
        writer.commit();

        clock.advance(Duration.ofHours(1));
        CatalogEvent late = read("reader");
        clock.advance(Duration.ofHours(1));
        assertThat(offer(writer, List.of(event, late))).as("both forgotten in memory")
                .isEqualTo(new LogWriter.Taken(2, 0, 0, 0));
        writer.commit();

        assertThat(rows()).extracting(Row::eventId).containsExactlyInAnyOrder(event.id().toString(),
                late.id().toString());
        assertThat(writer.waiting()).isZero();
    }

    @Test
    void aLateCopyArrivingAfterARestartIsLeftOut() {
        LogWriter first = writer(1_000);
        CatalogEvent event = read("reader");
        offer(first, List.of(event));
        first.commit();

        clock.advance(Duration.ofHours(1));
        LogWriter restarted = writer(1_000);
        restarted.commit();
        offer(restarted, List.of(event));
        restarted.commit();

        assertThat(rows()).extracting(Row::eventId).containsExactly(event.id().toString());
        assertThat(restarted.waiting()).isZero();
    }

    @Test
    void aCommitWhoseAnswerWasLostIsNotWrittenAgain() {
        LogWriter writer = writer(1_000);
        writer.commit();
        List<CatalogEvent> events = reads(3);
        offer(writer, events);
        catalog.failNextRows(FaultyCatalog.Fault.ANSWER_LOST);

        assertThatThrownBy(writer::commit).isInstanceOf(CommitStateUnknownException.class);
        offer(writer, List.of(events.get(2)));
        writer.commit();

        assertThat(rows()).extracting(Row::eventId).containsExactlyInAnyOrderElementsOf(ids(events));
        assertThat(writer.waiting()).isZero();
    }

    @Test
    void aCommitThatFailsKeepsItsRowsForTheNext() {
        LogWriter writer = writer(1_000);
        writer.commit();
        List<CatalogEvent> events = reads(2);
        offer(writer, events);
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

        assertThat(offer(writer, List.of(own, other))).isEqualTo(new LogWriter.Taken(1, 0, 1, 0));
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
        offer(writer, List.of(later));
        writer.commit();

        assertThat(rows()).extracting(Row::partition).containsExactlyInAnyOrder(0, LogWriter.PARTITION);
    }

    @Test
    void refusesPastTwiceMaxRowsWaiting() {
        LogWriter writer = writer(2);
        catalog.failNextRows(FaultyCatalog.Fault.LOST);
        offer(writer, reads(4));

        assertThatThrownBy(() -> offer(writer, reads(1))).isInstanceOf(LogWriter.Busy.class);
        assertThatThrownBy(writer::commitIfDue).isInstanceOf(IllegalStateException.class);
        writer.commitIfDue();
        assertThat(offer(writer, reads(1)).written()).isEqualTo(1);
    }

    @Test
    void aTableDroppedBetweenCommitsIsMadeAgainWithTheRecordsItHeld() {
        // Backlog 284: the catalog's database new under a running writer, or the table dropped.
        LogWriter writer = writer(1_000);
        List<CatalogEvent> before = reads(2);
        offer(writer, before);
        writer.commit();
        UUID dropped = catalog.loadTable(EVENTS).uuid();
        catalog.dropTable(EVENTS, false);

        List<CatalogEvent> held = reads(3);
        offer(writer, held);
        assertThatThrownBy(writer::commit).isInstanceOf(NoSuchTableException.class);
        assertThat(writer.waiting()).isEqualTo(3);
        writer.commitIfDue();
        clock.advance(INTERVAL);
        writer.commitIfDue();

        assertThat(catalog.loadTable(EVENTS).uuid()).as("a new table").isNotEqualTo(dropped);
        assertThat(rows()).as("the rows written before stay in the dropped table").extracting(Row::eventId)
                .containsExactlyInAnyOrderElementsOf(ids(held));
        assertThat(writer.waiting()).isZero();
        assertThat(opened).hasValue(2);

        List<CatalogEvent> after = reads(1);
        offer(writer, after);
        writer.commit();
        assertThat(rows()).hasSize(4);
    }

    @Test
    void aRefusalIsThrownAndNeverMakesTheTableAgain() {
        // Backlog 284: a 403 is a revoked grant, which a new table would hide.
        LogWriter writer = writer(1_000);
        List<CatalogEvent> before = reads(1);
        offer(writer, before);
        writer.commit();
        UUID table = catalog.loadTable(EVENTS).uuid();
        catalog.refuse(true);

        List<CatalogEvent> held = reads(2);
        offer(writer, held);
        assertThatThrownBy(writer::commit).isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(writer::commit).as("read again, still refused").isInstanceOf(ForbiddenException.class);
        assertThat(opened).as("the table is not opened again").hasValue(1);
        assertThat(writer.waiting()).isEqualTo(2);

        catalog.refuse(false);
        writer.commit();
        assertThat(catalog.loadTable(EVENTS).uuid()).isEqualTo(table);
        assertThat(rows()).extracting(Row::eventId).containsExactlyInAnyOrderElementsOf(
                java.util.stream.Stream.concat(before.stream(), held.stream()).map(e -> e.id().toString()).toList());
        assertThat(opened).hasValue(1);
    }
}
