package nuna.datagen.live;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import nuna.datagen.world.MonthEnd;
import org.apache.iceberg.BaseTransaction;
import org.apache.iceberg.DataFile;
import org.apache.iceberg.FileFormat;
import org.apache.iceberg.PartitionKey;
import org.apache.iceberg.Schema;
import org.apache.iceberg.Snapshot;
import org.apache.iceberg.Table;
import org.apache.iceberg.Transaction;
import org.apache.iceberg.catalog.Catalog;
import org.apache.iceberg.catalog.TableCommit;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.data.GenericRecord;
import org.apache.iceberg.data.Record;
import org.apache.iceberg.data.parquet.GenericParquetWriter;
import org.apache.iceberg.expressions.Expressions;
import org.apache.iceberg.io.DataWriter;
import org.apache.iceberg.io.OutputFile;
import org.apache.iceberg.parquet.Parquet;
import org.apache.iceberg.rest.RESTCatalog;
import org.apache.iceberg.types.Type;
import org.apache.iceberg.types.Types;

/**
 * Writes the month-end close (item 153): each close table's rows for the month as one Parquet file, every table in one
 * multi-table commit ({@code /transactions/commit}), each replacing its month's partition, then the catalog tag
 * {@code month-end-YYYY-MM} on what it wrote. A restatement writes the corrected statements and positions the same way on
 * a catalog branch, {@code restate-YYYY-MM}, and merges it into main; the tag keeps what the statements said.
 *
 * <p>A choice made here, Paul may overrule: the generator writes these itself with Iceberg's Java writer, not Spark. It
 * is idempotent, so a replay after a restart does nothing twice: a close whose tag exists is done, a commit whose
 * snapshot summary names its month ({@code brokerage.close}) is not made again, nor a restatement whose id
 * ({@code brokerage.restatement}) is in main's history.
 */
public final class MonthEndWriter {

    static final String CLOSE = "brokerage.close", RESTATEMENT = "brokerage.restatement";

    /** The catalog's references (docs/refs.md): catalog-wide branches and tags. */
    public interface Refs {
        boolean exists(String ref);

        void createTag(String name, String from, String description);

        void createBranch(String name, String from, String description);

        void delete(String name);

        /** Merges the branch into main. */
        void merge(String branch);

        /** The catalog as seen on a branch, for writes there. */
        RESTCatalog onBranch(String branch);
    }

    private final RESTCatalog catalog;
    private final Refs refs;

    public MonthEndWriter(RESTCatalog catalog, Refs refs) {
        this.catalog = catalog;
        this.refs = refs;
    }

    static String branch(MonthEnd.Restatement r) {
        return "restate-" + r.period();
    }

    /** Makes the close tables that are missing, each partitioned by its period. */
    public void bootstrap() {
        for (MonthEnd.Table t : MonthEnd.Table.values()) {
            TableIdentifier id = IcebergLake.id(t.lakeTable());
            if (!catalog.tableExists(id)) {
                Schema schema = MonthEnd.schema(t);
                catalog.buildTable(id, schema).withPartitionSpec(MonthEnd.spec(schema))
                    .withProperty("format-version", "2").create();
                System.out.println("created table " + id);
            }
        }
    }

    public void write(MonthEnd close) {
        String tag = close.tag();
        if (refs.exists(tag)) {
            System.out.println("month-end close " + close.period() + " is tagged " + tag + " already");
            return;
        }
        String marker = close.period().toString();
        if (!inHistory(catalog, MonthEnd.Table.STATEMENTS, CLOSE, marker)) {
            commit(catalog, close.period().toString(), close.rows(), CLOSE, marker);
        }
        refs.createTag(tag, "main", "The month-end close of " + close.period() + " as first published");
        System.out.println("closed " + close.period() + " in one commit; tagged " + tag);
    }

    public void restated(MonthEnd.Restatement r) {
        if (inHistory(catalog, MonthEnd.Table.STATEMENTS, RESTATEMENT, r.id())) {
            System.out.println("restatement " + r.id() + " is on main already");
            return;
        }
        String branch = branch(r);
        if (refs.exists(branch)) {
            refs.delete(branch);
        }
        refs.createBranch(branch, "main", "Restates " + r.period() + ": " + r.reason());
        commit(refs.onBranch(branch), r.period().toString(), r.rows(), RESTATEMENT, r.id());
        refs.merge(branch);
        System.out.println("restated " + r.period() + " on branch " + branch + " and merged it into main");
    }

    /** Whether a commit to the table's main history carries the marker. */
    static boolean inHistory(Catalog catalog, MonthEnd.Table t, String key, String value) {
        TableIdentifier id = IcebergLake.id(t.lakeTable());
        if (!catalog.tableExists(id)) {
            return false;
        }
        Table table = catalog.loadTable(id);
        for (Snapshot s = table.currentSnapshot(); s != null;
             s = s.parentId() == null ? null : table.snapshot(s.parentId())) {
            if (value.equals(s.summary().get(key))) {
                return true;
            }
        }
        return false;
    }

    /** One transaction per table with rows, each replacing the period's partition, committed together. */
    private static void commit(RESTCatalog catalog, String period, Map<MonthEnd.Table, List<Map<String, Object>>> rows,
                               String key, String value) {
        List<TableCommit> commits = new ArrayList<>();
        for (var e : rows.entrySet()) {
            if (e.getValue().isEmpty()) {
                continue;
            }
            TableIdentifier id = IcebergLake.id(e.getKey().lakeTable());
            Table table = catalog.loadTable(id);
            DataFile file = parquet(table, period, e.getValue());
            Transaction tx = table.newTransaction();
            tx.newOverwrite().overwriteByRowFilter(Expressions.equal("period", period)).addFile(file)
                .set(key, value).commit();
            BaseTransaction base = (BaseTransaction) tx;
            commits.add(TableCommit.create(id, base.startMetadata(), base.currentMetadata()));
        }
        catalog.commitTransaction(commits);
    }

    private static DataFile parquet(Table table, String period, List<Map<String, Object>> rows) {
        Schema schema = table.schema();
        PartitionKey key = new PartitionKey(table.spec(), schema);
        GenericRecord first = record(schema, rows.get(0));
        key.partition(first);
        OutputFile out = table.io().newOutputFile(table.locationProvider().newDataLocation(table.spec(), key,
            "close-" + period + "-" + UUID.randomUUID() + ".parquet"));
        try {
            DataWriter<Record> writer = Parquet.writeData(out).schema(schema).withSpec(table.spec())
                .withPartition(key).createWriterFunc(GenericParquetWriter::create).overwrite().build();
            try (writer) {
                for (Map<String, Object> row : rows) {
                    writer.write(record(schema, row));
                }
            }
            return writer.toDataFile();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static GenericRecord record(Schema schema, Map<String, Object> row) {
        GenericRecord r = GenericRecord.create(schema);
        for (Types.NestedField f : schema.columns()) {
            r.setField(f.name(), convert(f.type(), row.get(f.name())));
        }
        return r;
    }

    private static Object convert(Type type, Object v) {
        if (v == null) {
            return null;
        }
        return switch (type.typeId()) {
            case DECIMAL -> (v instanceof BigDecimal b ? b : new BigDecimal(v.toString()))
                .setScale(((Types.DecimalType) type).scale(), RoundingMode.HALF_UP);
            case LONG -> ((Number) v).longValue();
            case INTEGER -> ((Number) v).intValue();
            case DATE -> (LocalDate) v;
            case TIMESTAMP -> ((Instant) v).atOffset(ZoneOffset.UTC);
            case BOOLEAN -> (Boolean) v;
            default -> v.toString();
        };
    }
}
