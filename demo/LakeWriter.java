import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.iceberg.DataFile;
import org.apache.iceberg.FileFormat;
import org.apache.iceberg.PartitionKey;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.Schema;
import org.apache.iceberg.SortOrder;
import org.apache.iceberg.Table;
import org.apache.iceberg.TableProperties;
import org.apache.iceberg.AppendFiles;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.data.GenericFileWriterFactory;
import org.apache.iceberg.data.GenericRecord;
import org.apache.iceberg.data.InternalRecordWrapper;
import org.apache.iceberg.data.Record;
import org.apache.iceberg.deletes.PositionDelete;
import org.apache.iceberg.deletes.PositionDeleteWriter;
import org.apache.iceberg.expressions.Expressions;
import org.apache.iceberg.io.FanoutDataWriter;
import org.apache.iceberg.io.OutputFileFactory;
import org.apache.iceberg.rest.RESTCatalog;
import org.apache.iceberg.types.Types;

/**
 * Another engine writing Iceberg, for the demo: the tables SingleStore's Iceberg pipelines load, written through the
 * catalog with the Iceberg Java library and vended credentials. Run by demo.sh in the {@code lake} container, with
 * the catalog token in NUNA_TOKEN.
 *
 * <pre>
 * LakeWriter seed   the sample tables, each with a history: appends, added columns, a row deleted, a changed
 *                   partition spec, a tag and a branch, v2 and v3 tables, and a view with two versions
 * LakeWriter rate   one more EURUSD rate, appended to markets.fx.rates
 * LakeWriter dev    the next day, 2026-11-11, on the catalog's branch dev (D33): two trades and two rates,
 *                   committed through the warehouse lakehouse@dev, so main has neither until dev is merged
 * LakeWriter positions markets.desks.positions, each desk's positions, for the row filters and column masks
 *                   (D20, backlog 115); written before the table is flagged, since then only a trusted engine
 *                   reaches its files
 * </pre>
 */
public class LakeWriter {

    static final Namespace XETRA = Namespace.of("markets", "equities", "eu", "xetra");
    static final Namespace FX = Namespace.of("markets", "fx");
    static final Namespace US = Namespace.of("markets", "equities", "us");
    static final Namespace REFERENCE = Namespace.of("reference");

    public static void main(String[] args) throws Exception {
        String mode = args.length == 0 ? "" : args[0];
        try (RESTCatalog catalog = new RESTCatalog()) {
            catalog.initialize("lake", Map.of(
                    "uri", "http://nuna-catalog:8181",
                    // An engine commits on a branch by naming it in the warehouse, catalog@branch (D33).
                    "warehouse", mode.equals("dev") ? "lakehouse@dev" : "lakehouse",
                    "token", System.getenv("NUNA_TOKEN"),
                    "header.X-Iceberg-Access-Delegation", "vended-credentials"));
            switch (mode) {
                case "seed" -> seed(catalog);
                case "rate" -> rate(catalog);
                case "dev" -> dev(catalog);
                case "positions" -> positions(catalog);
                default -> {
                    System.err.println("usage: LakeWriter seed | rate | dev | positions");
                    System.exit(2);
                }
            }
        }
    }

    static void seed(RESTCatalog catalog) {
        for (String ns : List.of("markets", "markets.equities", "markets.equities.eu", "markets.equities.eu.xetra",
                "markets.equities.us", "markets.fx", "reference")) {
            Namespace namespace = Namespace.of(ns.split("\\."));
            if (!catalog.namespaceExists(namespace)) {
                catalog.createNamespace(namespace);
            }
        }
        trades(catalog);
        rates(catalog);
        Table quotes = create(catalog, TableIdentifier.of(US, "quotes"), 3, new Schema(
                Types.NestedField.optional(1, "symbol", Types.StringType.get()),
                Types.NestedField.optional(2, "bid", Types.DoubleType.get()),
                Types.NestedField.optional(3, "ask", Types.DoubleType.get()),
                Types.NestedField.optional(4, "quoted_at", Types.TimestampType.withZone())), null);
        append(quotes, row(quotes, "AAPL", 227.10, 227.12, utc("2026-11-10T14:30:00")));

        Table countries = create(catalog, TableIdentifier.of(REFERENCE, "countries"), 2, new Schema(
                Types.NestedField.optional(1, "iso2", Types.StringType.get()),
                Types.NestedField.optional(2, "name", Types.StringType.get())), null);
        append(countries, row(countries, "DE", "Germany"), row(countries, "GB", "United Kingdom"), row(countries, "US", "United States"));

        // A view, replaced once, so it has two versions. Written in SingleStore's dialect, over the table it loads.
        Schema daily = new Schema(
                Types.NestedField.optional(1, "symbol", Types.StringType.get()),
                Types.NestedField.optional(2, "trade_date", Types.DateType.get()),
                Types.NestedField.optional(3, "volume", Types.LongType.get()));
        TableIdentifier view = TableIdentifier.of(Namespace.of("markets", "equities", "eu"), "xetra_daily");
        catalog.buildView(view).withSchema(daily).withDefaultNamespace(XETRA)
                .withQuery("singlestore", "SELECT symbol, DATE(trade_ts) AS trade_date, SUM(qty) AS volume "
                        + "FROM lake.xetra_trades GROUP BY symbol, DATE(trade_ts)")
                .create();
        Schema daily2 = new Schema(
                Types.NestedField.optional(1, "symbol", Types.StringType.get()),
                Types.NestedField.optional(2, "trade_date", Types.DateType.get()),
                Types.NestedField.optional(3, "volume", Types.LongType.get()),
                Types.NestedField.optional(4, "avg_price", Types.DecimalType.of(22, 10)));
        catalog.buildView(view).withSchema(daily2).withDefaultNamespace(XETRA)
                .withQuery("singlestore", "SELECT symbol, DATE(trade_ts) AS trade_date, SUM(qty) AS volume, "
                        + "AVG(price) AS avg_price FROM lake.xetra_trades GROUP BY symbol, DATE(trade_ts)")
                .replace();
        System.out.println("seeded the lake");
    }

    /** v2, partitioned by day and sorted by symbol: appends, two added columns (one a struct), a deleted row, a new spec. */
    static void trades(RESTCatalog catalog) {
        Table trades = create(catalog, TableIdentifier.of(XETRA, "trades"), 2, new Schema(
                Types.NestedField.optional(1, "trade_id", Types.LongType.get()),
                Types.NestedField.optional(2, "symbol", Types.StringType.get()),
                Types.NestedField.optional(3, "price", Types.DecimalType.of(18, 6)),
                Types.NestedField.optional(4, "qty", Types.LongType.get()),
                Types.NestedField.optional(5, "trade_ts", Types.TimestampType.withZone())), "day(trade_ts)");
        trades.replaceSortOrder().asc("symbol").commit();
        List<DataFile> first = append(trades,
                row(trades, 1L, "SAP", dec("182.140000"), 300L, utc("2026-11-09T09:00:01")),
                row(trades, 2L, "SIE", dec("171.020000"), 120L, utc("2026-11-09T09:00:04")),
                row(trades, 3L, "ALV", dec("268.500000"), 75L, utc("2026-11-09T09:01:12")));
        append(trades,
                row(trades, 4L, "SAP", dec("182.300000"), 500L, utc("2026-11-10T09:00:02")),
                row(trades, 5L, "DTE", dec("23.115000"), 2000L, utc("2026-11-10T09:00:09")));
        trades.updateSchema().addColumn("venue", Types.StringType.get(), "MIC of the venue").commit();
        append(trades, row(trades, 6L, "SAP", dec("182.410000"), 250L, utc("2026-11-10T09:05:00"), "XETR"));
        trades.updateSchema().addColumn("fees", Types.StructType.of(
                Types.NestedField.optional(8, "commission", Types.DecimalType.of(12, 4)),
                Types.NestedField.optional(9, "tax", Types.DecimalType.of(12, 4)))).commit();
        Record fees = GenericRecord.create(trades.schema().findField("fees").type().asStructType());
        fees.setField("commission", dec("1.2500"));
        fees.setField("tax", dec("0.1000"));
        append(trades, row(trades, 7L, "SIE", dec("171.500000"), 90L, utc("2026-11-10T09:06:30"), "XETR", fees));
        // Trade 3 is the third row of the first file (one file: the three trades share a day).
        deleteRow(trades, first.get(0), 2);
        trades.updateSpec().addField(Expressions.bucket("symbol", 8)).commit();
        Record fees8 = GenericRecord.create(trades.schema().findField("fees").type().asStructType());
        fees8.setField("commission", dec("0.9000"));
        fees8.setField("tax", dec("0.0500"));
        append(trades, row(trades, 8L, "ALV", dec("269.000000"), 40L, utc("2026-11-10T10:00:00"), "XETR", fees8));
        // A tag on the first day's close and a branch for a review, at the first and the current snapshot.
        trades.refresh();
        long firstSnapshot = trades.history().get(0).snapshotId();
        trades.manageSnapshots()
                .createTag("eod-2026-11-09", firstSnapshot)
                .createBranch("surveillance-review", trades.currentSnapshot().snapshotId())
                .commit();
    }

    /** v3, partitioned by month: two appends, an added column, a third append. */
    static void rates(RESTCatalog catalog) {
        Table rates = create(catalog, TableIdentifier.of(FX, "rates"), 3, new Schema(
                Types.NestedField.optional(1, "pair", Types.StringType.get()),
                Types.NestedField.optional(2, "rate", Types.DoubleType.get()),
                Types.NestedField.optional(3, "as_of", Types.TimestampType.withZone())), "month(as_of)");
        append(rates, row(rates, "EURUSD", 1.0841, utc("2026-11-09T16:00:00")), row(rates, "GBPUSD", 1.2712, utc("2026-11-09T16:00:00")));
        append(rates, row(rates, "EURUSD", 1.0855, utc("2026-11-10T16:00:00")), row(rates, "GBPUSD", 1.2699, utc("2026-11-10T16:00:00")));
        rates.updateSchema().addColumn("source", Types.StringType.get()).commit();
        append(rates, row(rates, "USDJPY", 151.42, utc("2026-11-10T16:00:00"), "ECB"));
    }

    static void rate(RESTCatalog catalog) {
        Table rates = catalog.loadTable(TableIdentifier.of(FX, "rates"));
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC).withNano(0);
        double rate = 1.08 + Math.round(Math.random() * 100) / 10_000.0;
        append(rates, row(rates, "EURUSD", rate, now, "demo"));
        System.out.println("appended EURUSD " + rate + " as of " + now + " to markets.fx.rates");
    }

    /**
     * The next day's trades and rates, staged on the branch dev for review: demo.sh makes the branch first, and a
     * merge releases both tables to main in one transaction.
     */
    static void dev(RESTCatalog catalog) {
        Table trades = catalog.loadTable(TableIdentifier.of(XETRA, "trades"));
        Types.StructType feeType = trades.schema().findField("fees").type().asStructType();
        Record fees9 = GenericRecord.create(feeType);
        fees9.setField("commission", dec("1.4000"));
        fees9.setField("tax", dec("0.1200"));
        Record fees10 = GenericRecord.create(feeType);
        fees10.setField("commission", dec("0.8000"));
        fees10.setField("tax", dec("0.0400"));
        append(trades,
                row(trades, 9L, "SAP", dec("183.020000"), 400L, utc("2026-11-11T09:00:03"), "XETR", fees9),
                row(trades, 10L, "ALV", dec("270.150000"), 60L, utc("2026-11-11T09:01:40"), "XETR", fees10));
        Table rates = catalog.loadTable(TableIdentifier.of(FX, "rates"));
        append(rates, row(rates, "EURUSD", 1.0862, utc("2026-11-11T16:00:00"), "ECB"),
                row(rates, "GBPUSD", 1.2731, utc("2026-11-11T16:00:00"), "ECB"));
        System.out.println("committed 2026-11-11 on lakehouse@dev: trades 9 and 10 to markets.equities.eu.xetra.trades,"
                + " EURUSD and GBPUSD to markets.fx.rates");
    }

    /** Two desks' positions, each row a trader's, for the row filters and column masks on it (demo.sh). */
    static void positions(RESTCatalog catalog) {
        Namespace desks = Namespace.of("markets", "desks");
        if (!catalog.namespaceExists(desks)) {
            catalog.createNamespace(desks);
        }
        TableIdentifier id = TableIdentifier.of(desks, "positions");
        if (catalog.tableExists(id)) {
            return;
        }
        Table positions = create(catalog, id, 2, new Schema(
                Types.NestedField.optional(1, "desk", Types.StringType.get()),
                Types.NestedField.optional(2, "trader", Types.StringType.get()),
                Types.NestedField.optional(3, "symbol", Types.StringType.get()),
                Types.NestedField.optional(4, "quantity", Types.LongType.get()),
                Types.NestedField.optional(5, "pnl", Types.DecimalType.of(14, 2))), null);
        append(positions,
                row(positions, "EQ", "Mia Novak", "SAP", 1200L, dec("18250.00")),
                row(positions, "EQ", "Mia Novak", "ASML", 300L, dec("-4100.50")),
                row(positions, "EQ", "Leo Brandt", "SIE", 800L, dec("9120.25")),
                row(positions, "FX", "Ida Moreau", "EURUSD", 5000000L, dec("31400.00")),
                row(positions, "FX", "Ida Moreau", "GBPUSD", 2000000L, dec("-12800.75")),
                row(positions, "RATES", "Tom Okafor", "BUND-10Y", 150L, dec("2210.00")));
    }

    static Table create(RESTCatalog catalog, TableIdentifier id, int formatVersion, Schema schema, String partition) {
        PartitionSpec.Builder spec = PartitionSpec.builderFor(schema);
        if (partition != null) {
            String column = partition.substring(partition.indexOf('(') + 1, partition.indexOf(')'));
            if (partition.startsWith("day(")) {
                spec.day(column);
            } else {
                spec.month(column);
            }
        }
        return catalog.buildTable(id, schema).withPartitionSpec(spec.build())
                .withProperty(TableProperties.FORMAT_VERSION, String.valueOf(formatVersion))
                .withSortOrder(SortOrder.unsorted())
                .create();
    }

    static Record row(Table table, Object... values) {
        Record record = GenericRecord.create(table.schema());
        for (int i = 0; i < values.length; i++) {
            record.set(i, values[i]);
        }
        return record;
    }

    /** Appends the rows as one commit: a file per partition. */
    static List<DataFile> append(Table table, Record... rows) {
        GenericFileWriterFactory writers = new GenericFileWriterFactory.Builder(table)
                .dataFileFormat(FileFormat.PARQUET).deleteFileFormat(FileFormat.PARQUET).build();
        OutputFileFactory files = OutputFileFactory.builderFor(table, 1, System.nanoTime()).format(FileFormat.PARQUET).build();
        FanoutDataWriter<Record> writer = new FanoutDataWriter<>(writers, files, table.io(), 128L << 20);
        PartitionKey key = new PartitionKey(table.spec(), table.schema());
        InternalRecordWrapper wrapper = new InternalRecordWrapper(table.schema().asStruct());
        for (Record row : rows) {
            key.partition(wrapper.wrap(row));
            writer.write(row, table.spec(), table.spec().isUnpartitioned() ? null : key.copy());
        }
        close(writer);
        List<DataFile> written = writer.result().dataFiles();
        AppendFiles append = table.newAppend();
        written.forEach(append::appendFile);
        append.commit();
        return new ArrayList<>(written);
    }

    /** Deletes one row by its position in a data file, as engines do for a row-level DELETE on a v2 table. */
    static void deleteRow(Table table, DataFile file, long position) {
        GenericFileWriterFactory writers = new GenericFileWriterFactory.Builder(table)
                .dataFileFormat(FileFormat.PARQUET).deleteFileFormat(FileFormat.PARQUET).build();
        OutputFileFactory files = OutputFileFactory.builderFor(table, 1, System.nanoTime()).format(FileFormat.PARQUET).build();
        PartitionSpec spec = table.specs().get(file.specId());
        PositionDeleteWriter<Record> writer = writers.newPositionDeleteWriter(files.newOutputFile(spec, file.partition()), spec, file.partition());
        PositionDelete<Record> delete = PositionDelete.create();
        writer.write(delete.set(file.location(), position));
        close(writer);
        table.newRowDelta().addDeletes(writer.toDeleteFile()).commit();
    }

    static OffsetDateTime utc(String localDateTime) {
        return OffsetDateTime.parse(localDateTime + "Z");
    }

    static BigDecimal dec(String value) {
        return new BigDecimal(value);
    }

    static void close(java.io.Closeable writer) {
        try {
            writer.close();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
