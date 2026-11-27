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
import nuna.datagen.world.MonthEnd;
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
import org.apache.iceberg.exceptions.ForbiddenException;
import org.apache.iceberg.inmemory.InMemoryFileIO;
import org.apache.iceberg.rest.RESTCatalog;
import org.apache.iceberg.types.Types;

/**
 * The world's side of the catalog: it makes the namespaces, tables and views the sink writes into, adds the locate_id
 * column when the world first sells short, tags each table at the end of each day, and keeps the reconciliation views
 * ({@link Recon}) and the current-state views ({@link Current}) reading every lake table each feed has. It only ever changes metadata, so it reads and writes no files
 * (an in-memory FileIO stands in) and asks for no credentials.
 */
public final class IcebergLake implements Publisher.Lake, AutoCloseable {

    public static final Namespace ROOT = Namespace.of("brokerage");
    static final List<Namespace> NAMESPACES = List.of(ROOT, Namespace.of("brokerage", "reference"),
        Namespace.of("brokerage", "markets"), Namespace.of("brokerage", "party"), Namespace.of("brokerage", "ledger"),
        Namespace.of("brokerage", "trading"), Namespace.of("brokerage", "clearing"), Recon.NAMESPACE,
        Namespace.of("brokerage", "kafka"), Namespace.of("brokerage", "money_movement"),
        Namespace.of("brokerage", "compliance"), Namespace.of("brokerage", "margin"),
        Namespace.of("brokerage", "tax"), Namespace.of("brokerage", "client_reporting"),
        Namespace.of("brokerage", "regulatory"), Namespace.of("brokerage", "asset_servicing"), Finance.NAMESPACE);
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
    /** Writes the month-end closes and restatements (item 153); none where the lake only changes metadata. */
    private MonthEndWriter closes;

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
     * @param offsets the records each feed's topic holds now, before the world sends any; none while Kafka is off, and
     *                the first run through Kafka records them ({@link #startOffsets})
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
            offsets.forEach((feed, n) -> properties.put(OFFSET + feed.topic, String.valueOf(n)));
            namespaces.setProperties(ROOT, properties);
            System.out.println("fixed the world's settings on namespace " + ROOT);
        }
        // The tables the sink writes; SingleStore's change feeds make their own copies.
        for (Feed f : Feed.numbered()) {
            TableIdentifier id = id(f.lakeTable(Feed.Path.KAFKA));
            if (!catalog.tableExists(id)) {
                Schema schema = LakeSchemas.schema(f, kit);
                catalog.buildTable(id, schema).withPartitionSpec(LakeSchemas.spec(f, schema))
                    .withProperty(TableProperties.FORMAT_VERSION, "2")
                    .create();
                System.out.println("created table " + id);
            }
        }
        // The month-end close's tables (item 153), which the generator writes itself.
        for (MonthEnd.Table t : MonthEnd.Table.values()) {
            TableIdentifier id = id(t.lakeTable());
            if (!catalog.tableExists(id)) {
                Schema schema = MonthEnd.schema(t);
                catalog.buildTable(id, schema).withPartitionSpec(MonthEnd.spec(schema))
                    .withProperty(TableProperties.FORMAT_VERSION, "2").create();
                System.out.println("created table " + id);
            }
        }
        view("buying_power", "Each brokerage account's buying power at each day's end: the trade clock's net equity"
            + " (4201).", 4201);
        view("running_collected_balance", "Each brokerage account's running collected balance at each day's end: the"
            + " withdraw clock's net equity (5201).", 5201);
        views();
    }

    /**
     * Makes or remakes the reconciliation views ({@link Recon}) and the back office's current-state views
     * ({@link Current}) over the lake tables that exist now: a change feed makes its copy only as it first runs, so a
     * view made before reads the other way's table alone. Run at the bootstrap, as a run starts and at each day's end; a
     * view already reading those tables is left as it is.
     */
    public void views() {
        // The treasury's histories are the CDC leg's (item 153): the finance views, and the ties that read them, wait
        // for it to make them. Its namespace is not the world's, so a refusal there counts as not there yet.
        for (Finance.View v : Finance.views(TRINO_CATALOG, this::readable)) {
            derived(Finance.NAMESPACE, v.name(), v.comment(), v.schema(), v.sql());
        }
        for (Recon.View v : Recon.views(TRINO_CATALOG, this::readable)) {
            derived(Recon.NAMESPACE, v.name(), v.comment(), v.schema(), v.sql());
        }
        for (Current.View v : Current.views(TRINO_CATALOG, t -> catalog.tableExists(id(t)))) {
            derived(v.namespace(), v.name(), v.comment(), v.schema(), v.sql());
        }
    }

    private boolean readable(String table) {
        try {
            return catalog.tableExists(id(table));
        } catch (ForbiddenException e) {
            System.out.println("WARN the catalog refuses " + table + ", so no view reads it yet: " + e.getMessage());
            return false;
        }
    }

    private void derived(Namespace ns, String name, String comment, Schema schema, String sql) {
        TableIdentifier id = TableIdentifier.of(ns, name);
        boolean exists = views.viewExists(id);
        if (exists && sql.equals(views.loadView(id).sqlFor("trino").sql())) {
            return;
        }
        views.buildView(id).withSchema(schema).withDefaultCatalog(TRINO_CATALOG).withDefaultNamespace(ns)
            .withQuery("trino", sql).withProperty("comment", comment).createOrReplace();
        System.out.println((exists ? "remade view " : "created view ") + id);
    }

    /** The last business day whose end is tagged ({@code eod-<day>}) on any of the given lake tables, or null. */
    public LocalDate lastEndOfDay(List<String> tables) {
        LocalDate last = null;
        for (String table : tables) {
            if (!catalog.tableExists(id(table))) {
                continue;
            }
            for (String ref : catalog.loadTable(id(table)).refs().keySet()) {
                if (ref.startsWith("eod-")) {
                    LocalDate day = LocalDate.parse(ref.substring(4));
                    if (last == null || day.isAfter(last)) {
                        last = day;
                    }
                }
            }
        }
        return last;
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

    /**
     * The records each feed's topic held when the world began. A topic this world has not recorded (the world began
     * while Kafka was off, or before its feed was the world's) has sent it nothing yet, so its records now, {@code ends},
     * are recorded as its start.
     */
    public Map<Feed, Long> startOffsets(Map<Feed, Long> ends) {
        Map<String, String> p = rootProperties();
        Map<Feed, Long> offsets = new EnumMap<>(Feed.class);
        Map<String, String> missing = new LinkedHashMap<>();
        for (Feed f : Feed.numbered()) {
            String recorded = p.get(OFFSET + f.topic);
            if (recorded == null) {
                long now = ends.getOrDefault(f, 0L);
                missing.put(OFFSET + f.topic, String.valueOf(now));
                offsets.put(f, now);
            } else {
                offsets.put(f, Long.parseLong(recorded));
            }
        }
        if (!missing.isEmpty()) {
            namespaces.setProperties(ROOT, missing);
            System.out.println("recorded where these topics stood when this world first used them: " + missing);
        }
        return offsets;
    }

    /**
     * The records a lake table holds, 0 if it does not exist yet (a change feed makes its copy as it first starts), or if
     * the catalog refuses it: a table in a regulated namespace with no classification of its own is closed to everyone
     * (D27), as {@code brokerage.party.beneficial_owners} is until a steward classifies it (item 152).
     */
    public long records(String table) {
        TableIdentifier id = id(table);
        if (!catalog.tableExists(id)) {
            return 0;
        }
        try {
            Snapshot s = catalog.loadTable(id).currentSnapshot();
            return s == null ? 0 : Long.parseLong(s.summary().getOrDefault("total-records", "0"));
        } catch (ForbiddenException e) {
            System.out.println("WARN the catalog refuses " + table + ", so its records count as 0: " + e.getMessage());
            return 0;
        }
    }

    private Map<String, String> rootProperties() {
        Map<String, String> p = namespaces.loadNamespaceMetadata(ROOT);
        if (!p.containsKey(SEED)) {
            throw new IllegalStateException("namespace brokerage has no world settings: run the bootstrap first");
        }
        return p;
    }

    // ---------------------------------------------------------------- run

    /** Writes the month-end closes with this writer, whose catalog reads and writes data files. */
    public IcebergLake withCloses(MonthEndWriter closes) {
        this.closes = closes;
        return this;
    }

    @Override
    public void monthEnd(MonthEnd close) {
        if (closes == null) {
            System.out.println("WARN no writer for the month-end close of " + close.period());
            return;
        }
        closes.write(close);
        views();
    }

    @Override
    public void restated(MonthEnd.Restatement restatement) {
        if (closes != null) {
            closes.restated(restatement);
        }
    }

    @Override
    public void ensureLocateId() {
        Table t = catalog.loadTable(id(Feed.FINANCIAL_TRANSACTIONS.lakeTable(Feed.Path.KAFKA)));
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
    public void endOfDay(LocalDate day, Map<String, Long> totals) {
        String tag = "eod-" + day;
        long deadline = System.nanoTime() + patience.toNanos();
        Map<String, Long> waiting = new LinkedHashMap<>(totals);
        while (!waiting.isEmpty()) {
            for (var it = waiting.entrySet().iterator(); it.hasNext(); ) {
                var e = it.next();
                if (!catalog.tableExists(id(e.getKey()))) {
                    continue;
                }
                Table t;
                try {
                    t = catalog.loadTable(id(e.getKey()));
                } catch (ForbiddenException refused) {
                    // Closed until it is classified (D27); its sink waits too, so its rows are late, not lost.
                    System.out.println("WARN the catalog refuses " + e.getKey() + "; not tagged " + tag + ": "
                        + refused.getMessage());
                    it.remove();
                    continue;
                }
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
                System.out.println("WARN gave up waiting for the sink or a change feed; not tagged " + tag + ": "
                    + waiting.keySet());
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
        try {
            views();
        } catch (RuntimeException e) {
            System.out.println("WARN the reconciliation and current-state views were not remade: " + e);
        }
    }

    /** A lake table's identifier from its dotted name: the last part is the table, the rest its namespace. */
    static TableIdentifier id(String table) {
        int dot = table.lastIndexOf('.');
        return TableIdentifier.of(Namespace.of(table.substring(0, dot).split("\\.")), table.substring(dot + 1));
    }

    @Override
    public void close() throws IOException {
        closer.close();
    }
}
