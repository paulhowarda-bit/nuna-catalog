package nuna.datagen.live;

import java.io.Closeable;
import java.io.IOException;
import java.time.Duration;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import nuna.datagen.kit.Kit;
import nuna.datagen.world.Feed;
import nuna.datagen.world.LakeSchemas;
import nuna.datagen.world.World;
import org.apache.iceberg.Schema;
import org.apache.iceberg.Snapshot;
import org.apache.iceberg.Table;
import org.apache.iceberg.TableProperties;
import org.apache.iceberg.catalog.Catalog;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.catalog.SupportsNamespaces;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.catalog.ViewCatalog;
import org.apache.iceberg.inmemory.InMemoryFileIO;
import org.apache.iceberg.rest.RESTCatalog;
import org.apache.iceberg.types.Types;

/**
 * The world's side of the catalog: it makes the namespaces, tables and views the sink writes into, adds the locate_id
 * column when the world first sells short, and tags each table at the end of each day. It only ever changes metadata,
 * so it reads and writes no files (an in-memory FileIO stands in) and asks for no credentials.
 */
public final class IcebergLake implements Publisher.Lake, AutoCloseable {

    public static final Namespace ROOT = Namespace.of("brokerage");
    static final List<Namespace> NAMESPACES = List.of(ROOT, Namespace.of("brokerage", "reference"),
        Namespace.of("brokerage", "markets"), Namespace.of("brokerage", "party"), Namespace.of("brokerage", "ledger"));
    static final Namespace LEDGER = Namespace.of("brokerage", "ledger");

    /**
     * What the bootstrap fixes for every run: the world's settings, the first day the clock is paced, and the records
     * each topic held when the world began (a lake made again beside a Kafka that kept its topics starts a new world).
     */
    static final String SEED = "world.seed", START = "world.start", ACCOUNTS = "world.accounts",
        SECURITIES = "world.securities", PACED_FROM = "world.paced-from", OFFSET = "world.offset.";

    /** Trino's name for the warehouse's catalog (demo/k8s/trino.yaml), which the views' SQL names. */
    static final String TRINO_CATALOG = "lakehouse";

    /** The catalog's three faces; one object, a RESTCatalog in the demo. */
    private final Catalog catalog;
    private final SupportsNamespaces namespaces;
    private final ViewCatalog views;
    private final Closeable closer;
    private final Duration commitInterval;
    private final Duration patience;

    /**
     * @param catalog  a catalog with namespaces and views, as RESTCatalog and InMemoryCatalog are
     * @param patience how long a day's end waits for the sink before it leaves a table untagged
     */
    IcebergLake(Catalog catalog, Duration commitInterval, Duration patience) {
        this.catalog = catalog;
        this.namespaces = (SupportsNamespaces) catalog;
        this.views = (ViewCatalog) catalog;
        this.closer = (Closeable) catalog;
        this.commitInterval = commitInterval;
        this.patience = patience;
    }

    public static IcebergLake open(String uri, String warehouse, String token, Duration commitInterval) {
        RESTCatalog catalog = new RESTCatalog();
        catalog.initialize("brokerage", Map.of("uri", uri, "warehouse", warehouse, "token", token,
            "io-impl", InMemoryFileIO.class.getName()));
        Duration patience = commitInterval.multipliedBy(8);
        return new IcebergLake(catalog, commitInterval,
            patience.compareTo(Duration.ofMinutes(2)) > 0 ? patience : Duration.ofMinutes(2));
    }

    // ---------------------------------------------------------------- bootstrap

    /**
     * Makes whatever is missing. The settings are kept from the first bootstrap; a later one leaves them be.
     *
     * @param offsets the records each Kafka feed's topic holds now, before the world sends any
     */
    public void bootstrap(Kit kit, World.Settings settings, LocalDate pacedFrom, Map<Feed, Long> offsets) {
        for (Namespace ns : NAMESPACES) {
            if (!namespaces.namespaceExists(ns)) {
                namespaces.createNamespace(ns);
                System.out.println("created namespace " + ns);
            }
        }
        // The settings go on brokerage whoever made it: SingleStore's change feed makes it, with no properties, when it
        // first makes brokerage.trading, and in demo.sh up the feeds start before the bootstrap.
        if (!namespaces.loadNamespaceMetadata(ROOT).containsKey(SEED)) {
            Map<String, String> properties = new LinkedHashMap<>();
            properties.put(SEED, String.valueOf(settings.seed()));
            properties.put(START, settings.start().toString());
            properties.put(ACCOUNTS, String.valueOf(settings.accounts()));
            properties.put(SECURITIES, String.valueOf(settings.securities()));
            properties.put(PACED_FROM, pacedFrom.toString());
            offsets.forEach((feed, n) -> properties.put(OFFSET + feed.source, String.valueOf(n)));
            namespaces.setProperties(ROOT, properties);
            System.out.println("fixed the world's settings on namespace " + ROOT);
        }
        for (Feed f : Feed.values()) {
            if (f.path != Feed.Path.KAFKA) {
                continue;
            }
            TableIdentifier id = id(f);
            if (!catalog.tableExists(id)) {
                Schema schema = LakeSchemas.schema(f, kit);
                catalog.buildTable(id, schema).withPartitionSpec(LakeSchemas.spec(f, schema))
                    .withProperty(TableProperties.FORMAT_VERSION, "2")
                    .create();
                System.out.println("created table " + id);
            }
        }
        view("buying_power", "Each brokerage account's buying power at each day's end: the trade clock's net equity"
            + " (4201).", 4201);
        view("running_collected_balance", "Each brokerage account's running collected balance at each day's end: the"
            + " withdraw clock's net equity (5201).", 5201);
    }

    private void view(String name, String comment, int definition) {
        TableIdentifier id = TableIdentifier.of(LEDGER, name);
        if (views.viewExists(id)) {
            return;
        }
        Schema schema = new Schema(
            Types.NestedField.optional(1, "business_date", Types.DateType.get()),
            Types.NestedField.optional(2, "brokerage_account", Types.LongType.get()),
            Types.NestedField.optional(3, name, Types.DecimalType.of(18, 2)));
        views.buildView(id).withSchema(schema).withDefaultCatalog(TRINO_CATALOG).withDefaultNamespace(LEDGER)
            .withQuery("trino", "SELECT business_date, brokerage_account, CAST(balance AS decimal(18, 2)) AS " + name
                + " FROM " + TRINO_CATALOG + ".\"brokerage.ledger\".balances_eod WHERE definition_id = " + definition)
            .withProperty("comment", comment)
            .create();
        System.out.println("created view " + id);
    }

    /** The settings the bootstrap fixed. */
    public World.Settings settings() {
        Map<String, String> p = rootProperties();
        return new World.Settings(Long.parseLong(p.get(SEED)), LocalDate.parse(p.get(START)),
            Integer.parseInt(p.get(ACCOUNTS)), Integer.parseInt(p.get(SECURITIES)));
    }

    public LocalDate pacedFrom() {
        return LocalDate.parse(rootProperties().get(PACED_FROM));
    }

    /** The records each Kafka feed's topic held when the world began. */
    public Map<Feed, Long> startOffsets() {
        Map<String, String> p = rootProperties();
        Map<Feed, Long> offsets = new EnumMap<>(Feed.class);
        for (Feed f : Feed.values()) {
            if (f.path == Feed.Path.KAFKA) {
                offsets.put(f, Long.parseLong(p.getOrDefault(OFFSET + f.source, "0")));
            }
        }
        return offsets;
    }

    private Map<String, String> rootProperties() {
        Map<String, String> p = namespaces.loadNamespaceMetadata(ROOT);
        if (!p.containsKey(SEED)) {
            throw new IllegalStateException("namespace brokerage has no world settings: run the bootstrap first");
        }
        return p;
    }

    // ---------------------------------------------------------------- run

    @Override
    public void ensureLocateId() {
        Table t = catalog.loadTable(id(Feed.FINANCIAL_TRANSACTIONS));
        if (t.schema().findField(LakeSchemas.LOCATE_ID) != null) {
            return;
        }
        t.updateSchema().addColumn(LakeSchemas.LOCATE_ID, Types.StringType.get(),
            "The locate a short sale was accepted under (template 60).").commit();
        System.out.println("added column locate_id to " + t.name() + "; waiting two commit intervals for the sink");
        try {
            // The sink loads a table when it opens a writer, and opens new writers after each commit.
            Thread.sleep(commitInterval.multipliedBy(2).toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void endOfDay(LocalDate day, Map<Feed, Long> totals) {
        String tag = "eod-" + day;
        long deadline = System.nanoTime() + patience.toNanos();
        Map<Feed, Long> waiting = new LinkedHashMap<>(totals);
        while (!waiting.isEmpty()) {
            for (var it = waiting.entrySet().iterator(); it.hasNext(); ) {
                var e = it.next();
                Table t = catalog.loadTable(id(e.getKey()));
                if (t.refs().containsKey(tag)) {
                    it.remove();
                    continue;
                }
                Snapshot s = t.currentSnapshot();
                long records = s == null ? 0 : Long.parseLong(s.summary().getOrDefault("total-records", "0"));
                if (records == e.getValue()) {
                    t.manageSnapshots().createTag(tag, s.snapshotId()).commit();
                    it.remove();
                } else if (records > e.getValue()) {
                    System.out.println("WARN " + t.name() + " holds " + records + " records, more than the "
                        + e.getValue() + " sent; not tagged " + tag);
                    it.remove();
                }
            }
            if (waiting.isEmpty()) {
                break;
            }
            if (System.nanoTime() > deadline) {
                System.out.println("WARN gave up waiting for the sink; not tagged " + tag + ": " + waiting.keySet());
                break;
            }
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        System.out.println("end of " + day + ": tagged " + (totals.size() - waiting.size()) + " of " + totals.size()
            + " tables " + tag);
    }

    static TableIdentifier id(Feed f) {
        return TableIdentifier.of(Namespace.of(f.namespace.split("\\.")), f.table);
    }

    @Override
    public void close() throws IOException {
        closer.close();
    }
}
