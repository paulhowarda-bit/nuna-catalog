package nuna.changefeed.engine;

import static nuna.changefeed.engine.Fixtures.ORDERS;
import static nuna.changefeed.engine.Fixtures.config;
import static nuna.changefeed.engine.Fixtures.delete;
import static nuna.changefeed.engine.Fixtures.insert;
import static nuna.changefeed.engine.Fixtures.orders;
import static nuna.changefeed.engine.Fixtures.row;
import static nuna.changefeed.engine.Fixtures.rows;
import static nuna.changefeed.engine.Fixtures.transaction;
import static nuna.changefeed.engine.Fixtures.update;
import static nuna.changefeed.engine.Fixtures.values;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.iceberg.CatalogProperties;
import org.apache.iceberg.DataFile;
import org.apache.iceberg.DataOperations;
import org.apache.iceberg.FileFormat;
import org.apache.iceberg.FileScanTask;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.RewriteFiles;
import org.apache.iceberg.Schema;
import org.apache.iceberg.Snapshot;
import org.apache.iceberg.Table;
import org.apache.iceberg.data.GenericFileWriterFactory;
import org.apache.iceberg.data.GenericRecord;
import org.apache.iceberg.data.IcebergGenerics;
import org.apache.iceberg.data.Record;
import org.apache.iceberg.inmemory.InMemoryCatalog;
import org.apache.iceberg.io.CloseableIterable;
import org.apache.iceberg.io.DataWriter;
import org.apache.iceberg.io.OutputFileFactory;
import org.apache.iceberg.types.Types;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class IcebergCopyTest {

    private InMemoryCatalog catalog;

    @BeforeEach
    void catalog() {
        catalog = new InMemoryCatalog();
        catalog.initialize("test", Map.of(CatalogProperties.WAREHOUSE_LOCATION, "mem://" + UUID.randomUUID()));
    }

    @Test
    void createsTheCopyFromTheSingleStoreTable() {
        IcebergCopy.open(catalog, config("orders", "bucket(order_id, 4)"), orders(2));

        Table table = catalog.loadTable(ORDERS);
        assertThat(table.schema().columns()).extracting(Types.NestedField::name)
                .containsExactly("order_id", "symbol", "qty", "price", "status");
        assertThat(table.schema().identifierFieldNames()).containsExactly("order_id");
        assertThat(table.schema().findField("price").type()).isEqualTo(Types.DecimalType.of(18, 6));
        assertThat(table.schema().findField("price").isOptional()).isTrue();
        assertThat(table.schema().findField("symbol").isRequired()).isTrue();
        assertThat(table.spec().fields()).singleElement().satisfies(f -> assertThat(f.transform().toString()).isEqualTo("bucket[4]"));
        assertThat(table.properties()).containsEntry(IcebergCopy.SOURCE, "ops.orders");
        assertThat(table.currentSnapshot()).isNull();
    }

    @Test
    void theCopyEndsWithSingleStoresRows() {
        SourceTable source = orders(2);
        IcebergCopy copy = IcebergCopy.open(catalog, config("orders", "bucket(order_id, 4)"), source);
        IcebergCopy.SnapshotWriter snapshot = copy.snapshotWriter();
        snapshot.write(row(1, "SAP", 100, "182.100000", "NEW"));
        snapshot.write(row(2, "SIE", 50, "171.000000", "NEW"));
        snapshot.write(row(3, "ALV", 10, null, "NEW"));
        IcebergCopy.Receipt first = snapshot.commit(new String[] {"a0", "b0"}, null);
        assertThat(first.kind()).isEqualTo("snapshot");
        assertThat(first.rows()).isEqualTo(3);

        ChangeBatch batch = new ChangeBatch(source, new String[] {"a0", "b0"}, false);
        transaction(batch, 0, "a1", update(0, row(1, "SAP", 100, "182.100000", "FILLED")), delete(0, 2));
        // Inserted and then changed within the batch: one row, nothing earlier to remove.
        transaction(batch, 1, "b1", insert(1, row(4, "DTE", 2000, "23.100000", "NEW")));
        transaction(batch, 1, "b2", update(1, row(4, "DTE", 20, "23.100000", "NEW")));
        // Inserted and deleted within the batch: nothing at all.
        transaction(batch, 0, "a2", insert(0, row(5, "BAS", 300, "47.200000", "NEW")), delete(0, 5));
        IcebergCopy.Receipt second = copy.commit(batch);
        batch.clear();

        assertThat(second.kind()).isEqualTo("changes");
        assertThat(second.rows()).isEqualTo(2);
        assertThat(second.deletedKeys()).isEqualTo(1);
        assertThat(second.offsets()).isEqualTo("a2,b2");
        Table table = catalog.loadTable(ORDERS);
        assertThat(rows(table)).containsExactly(
                Map.entry(1L, values("SAP", 100, "182.100000", "FILLED")),
                Map.entry(3L, values("ALV", 10, null, "NEW")),
                Map.entry(4L, values("DTE", 20, "23.100000", "NEW")));

        // A key written by an earlier commit and changed in a later one.
        transaction(batch, 1, "b3", update(1, row(4, "DTE", 25, "23.200000", "PARTIAL")));
        transaction(batch, 0, "a3", delete(0, 3));
        copy.commit(batch);

        assertThat(rows(table)).containsExactly(
                Map.entry(1L, values("SAP", 100, "182.100000", "FILLED")),
                Map.entry(4L, values("DTE", 25, "23.200000", "PARTIAL")));
        assertThat(copy.offsets()).containsExactly("a3", "b3");
    }

    @Test
    void theOffsetsAreReadBackFromTheTable() {
        SourceTable source = orders(2);
        IcebergCopy copy = IcebergCopy.open(catalog, config("orders", ""), source);
        assertThat(copy.offsets()).containsExactly(null, null);

        ChangeBatch batch = new ChangeBatch(source, new String[] {"a0", "b0"}, false);
        transaction(batch, 1, "b7", insert(1, row(9, "SAP", 1, "1.000000", "NEW")));
        copy.commit(batch);

        Snapshot current = catalog.loadTable(ORDERS).currentSnapshot();
        assertThat(current.summary()).containsEntry(IcebergCopy.OFFSETS, "a0,b7").containsEntry(IcebergCopy.KIND, "changes")
                .containsKey(IcebergCopy.COMMIT_ID);
        assertThat(catalog.loadTable(ORDERS).properties()).containsEntry(IcebergCopy.OFFSETS, "a0,b7");
        assertThat(IcebergCopy.open(catalog, config("orders", ""), source).offsets()).containsExactly("a0", "b7");
    }

    @Test
    void theFeedResumesPastMaintenancesReplaceSnapshots() {
        SourceTable source = orders(2);
        IcebergCopy copy = IcebergCopy.open(catalog, config("orders", ""), source);
        ChangeBatch batch = new ChangeBatch(source, new String[] {"a0", "b0"}, false);
        transaction(batch, 1, "b7", insert(1, row(1, "SAP", 1, "1.000000", "NEW")));
        copy.commit(batch);
        batch.clear();
        transaction(batch, 0, "a3", insert(0, row(2, "SIE", 2, "2.000000", "NEW")));
        copy.commit(batch);
        batch.clear();

        Table table = catalog.loadTable(ORDERS);
        compact(table);
        table.rewriteManifests().clusterBy(file -> "all").commit();
        assertThat(table.currentSnapshot().operation()).isEqualTo(DataOperations.REPLACE);
        assertThat(table.currentSnapshot().summary()).doesNotContainKey(IcebergCopy.OFFSETS);

        IcebergCopy reopened = IcebergCopy.open(catalog, config("orders", ""), source);
        assertThat(reopened.offsets()).containsExactly("a3", "b7");
        transaction(batch, 0, "a4", update(0, row(2, "SIE", 3, "2.000000", "FILLED")));
        reopened.commit(batch);
        assertThat(rows(table)).containsExactly(
                Map.entry(1L, values("SAP", 1, "1.000000", "NEW")),
                Map.entry(2L, values("SIE", 3, "2.000000", "FILLED")));
        assertThat(IcebergCopy.open(catalog, config("orders", ""), source).offsets()).containsExactly("a4", "b7");
    }

    @Test
    void theFeedResumesFromThePropertyOnceExpiryRemovesItsCommits() {
        SourceTable source = orders(2);
        IcebergCopy copy = IcebergCopy.open(catalog, config("orders", ""), source);
        ChangeBatch batch = new ChangeBatch(source, new String[] {"a0", "b0"}, false);
        transaction(batch, 0, "a3", insert(0, row(1, "SAP", 1, "1.000000", "NEW")));
        transaction(batch, 1, "b7", insert(1, row(2, "SIE", 2, "2.000000", "NEW")));
        copy.commit(batch);
        batch.clear();

        Table table = catalog.loadTable(ORDERS);
        compact(table);
        expireAllButCurrent(table);
        assertThat(table.snapshots()).allSatisfy(s -> {
            assertThat(s.operation()).isEqualTo(DataOperations.REPLACE);
            assertThat(s.summary()).doesNotContainKey(IcebergCopy.OFFSETS);
        });

        IcebergCopy reopened = IcebergCopy.open(catalog, config("orders", ""), source);
        assertThat(reopened.offsets()).containsExactly("a3", "b7");
        transaction(batch, 1, "b8", delete(1, 2));
        reopened.commit(batch);
        assertThat(rows(table)).containsExactly(Map.entry(1L, values("SAP", 1, "1.000000", "NEW")));
    }

    @Test
    void aForeignWriteBeneathMaintenancesSnapshotsStillStopsTheFeed() {
        SourceTable source = orders(1);
        IcebergCopy copy = IcebergCopy.open(catalog, config("orders", ""), source);
        ChangeBatch batch = new ChangeBatch(source, new String[] {"a0"}, false);
        transaction(batch, 0, "a1", insert(0, row(1, "SAP", 1, "1.000000", "NEW")));
        copy.commit(batch);

        Table table = catalog.loadTable(ORDERS);
        Record foreign = GenericRecord.create(table.schema());
        foreign.set(0, 9L);
        foreign.set(1, "BAS");
        foreign.set(2, 1);
        foreign.set(4, "NEW");
        table.newAppend().appendFile(dataFile(table, List.of(foreign))).commit();
        long appended = table.currentSnapshot().snapshotId();
        compact(table);

        assertThatThrownBy(() -> IcebergCopy.open(catalog, config("orders", ""), source).offsets())
                .hasMessageContaining("snapshot " + appended + " (append) was not committed by the change feed");
    }

    @Test
    void aCopyWrittenBeforeThePropertyWhoseCommitsExpiredStopsTheFeed() {
        SourceTable source = orders(1);
        IcebergCopy copy = IcebergCopy.open(catalog, config("orders", ""), source);
        ChangeBatch batch = new ChangeBatch(source, new String[] {"a0"}, false);
        transaction(batch, 0, "a1", insert(0, row(1, "SAP", 1, "1.000000", "NEW")));
        copy.commit(batch);

        Table table = catalog.loadTable(ORDERS);
        table.updateProperties().remove(IcebergCopy.OFFSETS).commit();
        compact(table);
        expireAllButCurrent(table);

        assertThatThrownBy(() -> IcebergCopy.open(catalog, config("orders", ""), source).offsets())
                .hasMessageContaining("keeps no snapshot the change feed committed")
                .hasMessageContaining("drop the copy");
    }

    /** Compaction as maintenance does it: every data file rewritten into one, a replace snapshot. Rows only, no deletes. */
    private static void compact(Table table) {
        table.refresh();
        RewriteFiles rewrite = table.newRewrite();
        try (CloseableIterable<FileScanTask> tasks = table.newScan().planFiles();
             CloseableIterable<Record> records = IcebergGenerics.read(table).build()) {
            tasks.forEach(task -> {
                assertThat(task.deletes()).isEmpty();
                rewrite.deleteFile(task.file());
            });
            rewrite.addFile(dataFile(table, records));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        rewrite.commit();
    }

    private static DataFile dataFile(Table table, Iterable<Record> records) {
        DataWriter<Record> writer = new GenericFileWriterFactory.Builder(table).dataFileFormat(FileFormat.PARQUET).build()
                .newDataWriter(OutputFileFactory.builderFor(table, 9, 9).format(FileFormat.PARQUET).build().newOutputFile(),
                        table.spec(), null);
        try (writer) {
            records.forEach(writer::write);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return writer.toDataFile();
    }

    /** Snapshot expiry keeping only the current snapshot, as the policy does once the others are old enough. */
    private static void expireAllButCurrent(Table table) {
        table.expireSnapshots().retainLast(1).expireOlderThan(table.currentSnapshot().timestampMillis() + 1).commit();
        assertThat(table.snapshots()).hasSize(1);
    }

    @Test
    void aSecondWriterFailsInsteadOfApplyingTheSameChangesAgain() {
        SourceTable source = orders(1);
        IcebergCopy first = IcebergCopy.open(catalog, config("orders", ""), source);
        IcebergCopy second = IcebergCopy.open(catalog, config("orders", ""), source);

        ChangeBatch batch = new ChangeBatch(source, new String[] {"a0"}, false);
        transaction(batch, 0, "a1", insert(0, row(1, "SAP", 1, "1.000000", "NEW")));
        first.commit(batch);

        assertThatThrownBy(() -> second.commit(batch)).hasMessageContaining("conflict");
        assertThat(rows(catalog.loadTable(ORDERS))).containsOnlyKeys(1L);
        assertThat(catalog.loadTable(ORDERS).snapshots()).hasSize(1);
    }

    @Test
    void aNewSnapshotReplacesTheCopyAndRecordsTheGap() {
        SourceTable source = orders(2);
        IcebergCopy copy = IcebergCopy.open(catalog, config("orders", "bucket(order_id, 2)"), source);
        ChangeBatch batch = new ChangeBatch(source, new String[] {"a0", "b0"}, false);
        transaction(batch, 0, "a1", insert(0, row(1, "SAP", 1, "1.000000", "NEW")), insert(0, row(2, "SIE", 2, "2.000000", "NEW")));
        copy.commit(batch);

        IcebergCopy.SnapshotWriter snapshot = copy.snapshotWriter();
        snapshot.write(row(2, "SIE", 3, "2.000000", "FILLED"));
        IcebergCopy.Receipt receipt = snapshot.commit(new String[] {"a9", "b9"}, new String[] {"a1", "b0"});

        assertThat(receipt.kind()).isEqualTo("resnapshot");
        assertThat(receipt.gapAfter()).isEqualTo("a1,b0");
        Table table = catalog.loadTable(ORDERS);
        assertThat(table.currentSnapshot().summary()).containsEntry(IcebergCopy.GAP_AFTER, "a1,b0")
                .containsEntry(IcebergCopy.KIND, "resnapshot").containsEntry(IcebergCopy.OFFSETS, "a9,b9");
        assertThat(table.properties()).containsEntry(IcebergCopy.OFFSETS, "a9,b9");
        assertThat(rows(table)).containsExactly(Map.entry(2L, values("SIE", 3, "2.000000", "FILLED")));
    }

    @Test
    void theReceiptsOfCommitsAfterAGivenOneAreReadBackAsTheyWereSent() {
        SourceTable source = orders(2);
        IcebergCopy copy = IcebergCopy.open(catalog, config("orders", ""), source);
        IcebergCopy.SnapshotWriter snapshot = copy.snapshotWriter();
        snapshot.write(row(1, "SAP", 1, "1.000000", "NEW"));
        snapshot.write(row(3, "ALV", 1, "1.000000", "NEW"));
        IcebergCopy.Receipt first = snapshot.commit(new String[] {"a0", "b0"}, null);
        ChangeBatch batch = new ChangeBatch(source, new String[] {"a0", "b0"}, false);
        transaction(batch, 0, "a1", update(0, row(1, "SAP", 2, "1.000000", "FILLED")), insert(0, row(2, "SIE", 2, "2.000000", "NEW")));
        transaction(batch, 1, "b1", delete(1, 3));
        IcebergCopy.Receipt second = copy.commit(batch);
        IcebergCopy.SnapshotWriter again = copy.snapshotWriter();
        again.write(row(2, "SIE", 3, "2.000000", "FILLED"));
        IcebergCopy.Receipt third = again.commit(new String[] {"a9", "b9"}, new String[] {"a1", "b1"});

        assertThat(copy.receiptsAfter(first.snapshotId())).containsExactly(second, third);
        assertThat(copy.receiptsAfter(third.snapshotId())).isEmpty();
        assertThat(copy.receiptsAfter(null)).containsExactly(first, second, third);
        // A snapshot no longer in the history (expired, or of another table): every commit still in it.
        assertThat(copy.receiptsAfter(42L)).containsExactly(first, second, third);
        assertThat(second.rows()).isEqualTo(2);
        assertThat(second.deletedKeys()).isEqualTo(1);
        assertThat(third.gapAfter()).isEqualTo("a1,b1");
    }

    @Test
    void refusesAPartitionOutsideThePrimaryKey() {
        assertThatThrownBy(() -> IcebergCopy.open(catalog, config("orders", "symbol"), orders(1)))
                .hasMessageContaining("partitioned on symbol, which is not in the primary key");
    }

    @Test
    void refusesATableCopiedFromAnotherSource() {
        IcebergCopy.open(catalog, config("orders", ""), orders(1));
        assertThatThrownBy(() -> IcebergCopy.open(catalog, config("other_orders", ""), orders(1)))
                .hasMessageContaining("is not the change-feed copy of ops.other_orders");
    }

    @Test
    void refusesATableNobodyMarkedAsACopy() {
        catalog.createNamespace(ORDERS.namespace());
        catalog.createTable(ORDERS, new Schema(Types.NestedField.required(1, "order_id", Types.LongType.get())));
        assertThatThrownBy(() -> IcebergCopy.open(catalog, config("orders", ""), orders(1)))
                .hasMessageContaining("is not the change-feed copy of ops.orders");
    }

    @Test
    void columnsAddedAtTheEndAreCarriedOver() {
        IcebergCopy.open(catalog, config("orders", ""), orders(1));
        SourceTable.Column venue = SourceTable.column("ops.orders.venue", "venue", "varchar", "varchar(4)", 0, 0, true);

        IcebergCopy.open(catalog, config("orders", ""), orders(1, venue));

        assertThat(catalog.loadTable(ORDERS).schema().findField("venue").type()).isEqualTo(Types.StringType.get());
    }

    @Test
    void otherSchemaChangesStopTheFeed() {
        IcebergCopy.open(catalog, config("orders", ""), orders(1));
        SourceTable.Column notNull = SourceTable.column("ops.orders.venue", "venue", "varchar", "varchar(4)", 0, 0, false);
        assertThatThrownBy(() -> IcebergCopy.open(catalog, config("orders", ""), orders(1, notNull)))
                .hasMessageContaining("was added NOT NULL");

        SourceTable retyped = new SourceTable("ops", "orders", List.of(
                SourceTable.column("ops.orders.order_id", "order_id", "bigint", "bigint(20)", 19, 0, false),
                SourceTable.column("ops.orders.symbol", "symbol", "varchar", "varchar(12)", 0, 0, false),
                SourceTable.column("ops.orders.qty", "qty", "bigint", "bigint(20)", 19, 0, false),
                SourceTable.column("ops.orders.price", "price", "decimal", "decimal(18,6)", 18, 6, true),
                SourceTable.column("ops.orders.status", "status", "varchar", "varchar(16)", 0, 0, false)),
                List.of("order_id"), 1);
        assertThatThrownBy(() -> IcebergCopy.open(catalog, config("orders", ""), retyped))
                .hasMessageContaining("column 3");
    }

    @Test
    void readsPartitionTransforms() {
        Schema schema = new Schema(
                Types.NestedField.required(1, "id", Types.LongType.get()),
                Types.NestedField.required(2, "ts", Types.TimestampType.withoutZone()),
                Types.NestedField.required(3, "symbol", Types.StringType.get()));

        PartitionSpec spec = IcebergCopy.partitionSpec(schema, "day(ts), bucket(id, 8), truncate(symbol,2)");

        assertThat(spec.fields()).extracting(f -> f.transform().toString()).containsExactly("day", "bucket[8]", "truncate[2]");
        assertThat(IcebergCopy.partitionSpec(schema, "symbol").fields()).singleElement()
                .satisfies(f -> assertThat(f.transform().isIdentity()).isTrue());
        assertThat(IcebergCopy.partitionSpec(schema, "").isUnpartitioned()).isTrue();
        assertThatThrownBy(() -> IcebergCopy.partitionSpec(schema, "week(ts)")).hasMessageContaining("unknown partition transform week");
    }
}
