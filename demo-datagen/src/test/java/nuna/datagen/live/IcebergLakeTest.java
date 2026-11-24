package nuna.datagen.live;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.LocalDate;
import java.util.Map;
import nuna.datagen.kit.Kit;
import nuna.datagen.world.Feed;
import nuna.datagen.world.LakeSchemas;
import nuna.datagen.world.World;
import org.apache.iceberg.DataFiles;
import org.apache.iceberg.FileFormat;
import org.apache.iceberg.Table;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.inmemory.InMemoryCatalog;
import org.apache.iceberg.view.SQLViewRepresentation;
import org.apache.iceberg.view.View;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The lake's side against Iceberg's in-memory catalog: what the bootstrap makes, the schema change, the day tags. */
class IcebergLakeTest {

    private static final Kit KIT = Kit.load();
    private static final World.Settings SETTINGS = World.Settings.of(9, LocalDate.of(2026, 11, 17), 1000);

    private InMemoryCatalog catalog;
    private IcebergLake lake;

    @BeforeEach
    void setUp() {
        catalog = new InMemoryCatalog();
        catalog.initialize("lake", Map.of());
        lake = new IcebergLake(catalog, Duration.ZERO, Duration.ofSeconds(1));
        lake.bootstrap(KIT, SETTINGS, LocalDate.of(2026, 11, 22), Map.of(Feed.JOURNAL_LINES, 40L));
    }

    private Table table(Feed f) {
        return catalog.loadTable(IcebergLake.id(f.lakeTable(Feed.Path.KAFKA)));
    }

    private static String name(Feed f) {
        return f.lakeTable(Feed.Path.KAFKA);
    }

    /** Stands in for a sink commit: a data file of n records. */
    private void commit(Feed f, long n) {
        Table t = table(f);
        DataFiles.Builder file = DataFiles.builder(t.spec()).withPath("mem://" + f + "/" + System.nanoTime() + ".parquet")
            .withFormat(FileFormat.PARQUET).withRecordCount(n).withFileSizeInBytes(100);
        if (t.spec().isPartitioned()) {
            file.withPartitionPath(t.spec().fields().getFirst().name() + "=2026-11-18");
        }
        t.newFastAppend().appendFile(file.build()).commit();
    }

    @Test
    void theBootstrapMakesEveryTableAndViewOnceAndFixesTheWorld() {
        for (Feed f : Feed.values()) {
            assertThat(catalog.tableExists(IcebergLake.id(f.lakeTable(Feed.Path.KAFKA)))).as(f.name()).isTrue();
        }
        // The orders' own table, for when SingleStore is off; SingleStore's change feeds make their copies.
        assertThat(table(Feed.ORDERS).name()).endsWith("brokerage.trading.order_events");
        assertThat(catalog.tableExists(IcebergLake.id("brokerage.trading.orders"))).isFalse();
        assertThat(table(Feed.JOURNAL_LINES).spec().fields()).singleElement()
            .satisfies(p -> assertThat(p.transform().toString()).isEqualTo("day"));
        assertThat(table(Feed.FINANCIAL_TRANSACTIONS).schema().findField(LakeSchemas.LOCATE_ID)).isNull();
        View view = catalog.loadView(TableIdentifier.of(Namespace.of("brokerage", "ledger"), "buying_power"));
        assertThat(((SQLViewRepresentation) view.sqlFor("trino")).sql())
            .isEqualTo("SELECT business_date, brokerage_account, CAST(balance AS decimal(18, 2)) AS buying_power"
                + " FROM lakehouse.\"brokerage.ledger\".balances_eod WHERE definition_id = 4201");
        assertThat(lake.settings()).isEqualTo(SETTINGS);
        assertThat(lake.pacedFrom()).isEqualTo(LocalDate.of(2026, 11, 22));
        assertThat(lake.startOffsets(Map.of())).containsEntry(Feed.JOURNAL_LINES, 40L)
            .containsEntry(Feed.CALENDAR, 0L).containsEntry(Feed.ORDERS, 0L);

        // A second bootstrap (demo.sh up on a running stack) changes nothing, the world's settings included.
        long snapshots = catalog.listTables(Namespace.of("brokerage", "ledger")).size();
        lake.bootstrap(KIT, World.Settings.of(1, LocalDate.of(2026, 1, 2), 5), LocalDate.of(2026, 1, 5), Map.of());
        assertThat(catalog.listTables(Namespace.of("brokerage", "ledger"))).hasSize((int) snapshots);
        assertThat(lake.settings()).isEqualTo(SETTINGS);
        assertThat(lake.startOffsets(Map.of())).containsEntry(Feed.JOURNAL_LINES, 40L);
    }

    @Test
    void aTopicTheWorldHasNotUsedStartsWhereItStandsTheFirstTimeTheWorldUsesIt() {
        // A world begun while Kafka was off records no topic; the first run through Kafka records them as they stand.
        InMemoryCatalog fresh = new InMemoryCatalog();
        fresh.initialize("lake", Map.of());
        IcebergLake other = new IcebergLake(fresh, Duration.ZERO, Duration.ofSeconds(1));
        other.bootstrap(KIT, SETTINGS, LocalDate.of(2026, 11, 22), Map.of());
        assertThat(other.startOffsets(Map.of(Feed.JOURNAL_LINES, 900L))).containsEntry(Feed.JOURNAL_LINES, 900L);
        assertThat(other.startOffsets(Map.of(Feed.JOURNAL_LINES, 950L))).containsEntry(Feed.JOURNAL_LINES, 900L)
            .containsEntry(Feed.ORDERS, 0L);
        assertThat(other.records("brokerage.ledger.journal_lines")).isZero();
        assertThat(other.records("brokerage.singlestore.journal_lines")).isZero();
    }

    @Test
    void theSettingsGoOnABrokerageNamespaceSomethingElseMade() {
        // SingleStore's change feed makes brokerage, with no properties, when it first makes brokerage.trading.
        InMemoryCatalog fresh = new InMemoryCatalog();
        fresh.initialize("lake", Map.of());
        fresh.createNamespace(Namespace.of("brokerage"));
        fresh.createNamespace(Namespace.of("brokerage", "trading"));
        IcebergLake other = new IcebergLake(fresh, Duration.ZERO, Duration.ofSeconds(1));
        other.bootstrap(KIT, SETTINGS, LocalDate.of(2026, 11, 22), Map.of(Feed.JOURNAL_LINES, 40L));
        assertThat(other.settings()).isEqualTo(SETTINGS);
        assertThat(other.pacedFrom()).isEqualTo(LocalDate.of(2026, 11, 22));
        assertThat(other.startOffsets(Map.of())).containsEntry(Feed.JOURNAL_LINES, 40L);
    }

    @Test
    void locateIdIsAddedOnce() {
        lake.ensureLocateId();
        lake.ensureLocateId();
        assertThat(table(Feed.FINANCIAL_TRANSACTIONS).schema().columns())
            .filteredOn(c -> c.name().equals(LakeSchemas.LOCATE_ID)).singleElement()
            .satisfies(c -> assertThat(c.type().typeId().toString()).isEqualTo("STRING"));
    }

    @Test
    void aDaysEndTagsEachTableThatHoldsItsRowsAndLeavesTheRestUntagged() {
        commit(Feed.JOURNAL_LINES, 10);
        commit(Feed.JOURNAL_LINES, 5);
        commit(Feed.BALANCES_EOD, 7);
        commit(Feed.CALENDAR, 3);
        commit(Feed.BARS_5M, 4);
        // journal_lines and calendar hold what was sent; balances_eod is one record short; bars_5m holds more.
        lake.endOfDay(LocalDate.of(2026, 11, 18), Map.of(name(Feed.JOURNAL_LINES), 15L, name(Feed.BALANCES_EOD), 8L,
            name(Feed.CALENDAR), 3L, name(Feed.BARS_5M), 3L));
        assertThat(lake.records(name(Feed.JOURNAL_LINES))).isEqualTo(15);
        assertThat(table(Feed.JOURNAL_LINES).refs()).containsKey("eod-2026-11-18");
        assertThat(table(Feed.JOURNAL_LINES).snapshot("eod-2026-11-18").snapshotId())
            .isEqualTo(table(Feed.JOURNAL_LINES).currentSnapshot().snapshotId());
        assertThat(table(Feed.CALENDAR).refs()).containsKey("eod-2026-11-18");
        assertThat(table(Feed.BALANCES_EOD).refs()).doesNotContainKey("eod-2026-11-18");
        assertThat(table(Feed.BARS_5M).refs()).doesNotContainKey("eod-2026-11-18");

        // Once tagged, a table is left alone: a resumed run's day's end does not wait on it again.
        commit(Feed.JOURNAL_LINES, 1);
        lake.endOfDay(LocalDate.of(2026, 11, 18), Map.of(name(Feed.JOURNAL_LINES), 15L));
        assertThat(table(Feed.JOURNAL_LINES).snapshot("eod-2026-11-18").snapshotId())
            .isNotEqualTo(table(Feed.JOURNAL_LINES).currentSnapshot().snapshotId());
    }
}
