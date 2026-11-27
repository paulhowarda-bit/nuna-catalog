package nuna.datagen;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import nuna.datagen.kit.Kit;
import nuna.datagen.live.Custody;
import nuna.datagen.live.HttpRefs;
import nuna.datagen.live.IcebergLake;
import nuna.datagen.live.MonthEndWriter;
import org.apache.iceberg.rest.RESTCatalog;
import nuna.datagen.live.KafkaTopics;
import nuna.datagen.live.Pacer;
import nuna.datagen.live.Publisher;
import nuna.datagen.live.Route;
import nuna.datagen.live.SingleStore;
import nuna.datagen.live.SingleStoreTables;
import nuna.datagen.live.SinkWatch;
import nuna.datagen.live.Sources;
import nuna.datagen.world.Event;
import nuna.datagen.world.Feed;
import nuna.datagen.world.MarketCalendar;
import nuna.datagen.world.Sink;
import nuna.datagen.world.World;

/**
 * The brokerage world's commands, configured from the environment (demo/k8s/brokerage.yaml).
 *
 * <ul>
 *   <li>{@code bootstrap}: makes the topics, namespaces, tables and views, and fixes the world's settings in the
 *       {@code brokerage} namespace's properties. It makes only what is missing.</li>
 *   <li>{@code run}: plays the world day after day, for ever, on the real clock (D48, Paul, 2026-11-23): what has
 *       already happened (the catch-up days, today's earlier hours) as fast as the sink takes it, then each event at
 *       its own time of day in New York, quiet overnight, over weekends and on holidays. A restart resumes where the
 *       last run stopped. A watch restarts the sink's task whenever it has failed ({@link SinkWatch}).</li>
 *   <li>{@code drain kafka|singlestore}: waits until what went that way is all in the lake ({@link #drain}).</li>
 *   <li>{@code sql <dir>}: writes brokerage-feeds.sql and brokerage-feeds-pipelines.sql into {@code dir}
 *       ({@link SingleStoreTables}).</li>
 *   <li>{@code cdc}: brings the CDC leg's databases (CockroachDB {@code custody}, PostgreSQL {@code backoffice}, Oracle
 *       {@code treasury}) up to the lake's last business day ({@link #cdc}).</li>
 *   <li>{@code history <dir>}: writes the custody tables' history tables, one create-table request each, into
 *       {@code dir}.</li>
 * </ul>
 *
 * <p>{@code BROKERAGE_ROUTE} says which of Kafka and SingleStore are on ({@link Route}; D50, amended 2026-11-24): the
 * bootstrap makes the topics only when Kafka is, and the run writes only to those that are, counting what earlier runs
 * sent the other way from the lake.
 */
public final class Main {

    /** The sink connector's control topic (demo/brokerage/connector.json). */
    static final String CONTROL_TOPIC = "brokerage-lake-control";

    /** The sink connector's name (demo.sh registers demo/brokerage/connector.json under it). */
    static final String SINK = "brokerage-lake";

    /**
     * The sink of brokerage.party.beneficial_owners alone (demo/brokerage/owners-connector.json), and its control topic:
     * the table lands unclassified in a regulated namespace, which the catalog refuses (D27), and a sink stops every
     * table's commits at one refused table, so it has a sink of its own, which its watch restarts once a steward
     * classifies the table (item 152).
     */
    static final String OWNERS_SINK = "brokerage-lake-owners", OWNERS_CONTROL_TOPIC = "brokerage-owners-control";

    private Main() {
    }

    public static void main(String[] args) throws Exception {
        switch (args.length == 0 ? "" : args[0]) {
            case "bootstrap" -> bootstrap();
            case "run" -> run();
            case "sql" -> sql(Path.of(args.length > 1 ? args[1] : "."));
            case "drain" -> drain(args.length > 1 ? args[1] : "");
            case "cdc" -> cdc();
            case "history" -> history(Path.of(args.length > 1 ? args[1] : "."));
            default -> {
                System.err.println("usage: Main bootstrap | run | sql <dir> | drain kafka|singlestore | cdc"
                    + " | history <dir>");
                System.exit(2);
            }
        }
    }

    static void sql(Path dir) throws Exception {
        Kit kit = Kit.load();
        Files.writeString(dir.resolve("brokerage-feeds.sql"), SingleStoreTables.tables(kit));
        Files.writeString(dir.resolve("brokerage-feeds-pipelines.sql"), SingleStoreTables.pipelines());
    }

    static void history(Path dir) throws Exception {
        Files.createDirectories(dir);
        for (Custody.Table t : Custody.Table.values()) {
            Files.writeString(dir.resolve(t.table + ".json"), Custody.history(t));
        }
    }

    /**
     * Replays the world to the last business day the lake tagged and writes its books of record into the CDC leg's three
     * databases, each only what changed: the clearing books into CockroachDB {@code custody}, as user {@code clearing}
     * ({@code COCKROACH_URL}, {@code COCKROACH_USER}, {@code COCKROACH_PASSWORD}; {@link Custody}); the back office into
     * PostgreSQL {@code backoffice}, as its owner {@code backoffice} ({@code BACKOFFICE_*}); the bank payments into Oracle
     * {@code treasury}, as its schema's user ({@code TREASURY_*}; {@link Sources}). demo.sh runs it each time the leg
     * starts, while the generator is stopped. A database that does not load leaves its tables as they were; the others
     * still load, and the command exits 1.
     */
    static void cdc() throws Exception {
        Kit kit = Kit.load();
        try (IcebergLake lake = lake()) {
            LocalDate last = lake.lastEndOfDay(List.of(Feed.BALANCES_EOD.lakeTable(Feed.Path.KAFKA),
                Feed.BALANCES_EOD.lakeTable(Feed.Path.SINGLESTORE)));
            if (last == null) {
                System.out.println("cdc: no business day has ended in the lake yet, so the books are empty");
                return;
            }
            World world = new World(lake.settings(), kit);
            Custody.Book custody = new Custody.Book();
            Sources.Book sources = new Sources.Book();
            Sink both = new Sink() {
                @Override
                public void accept(Event e) {
                    custody.accept(e);
                    sources.accept(e);
                }

                @Override
                public void endOfDay(LocalDate day) {
                    custody.endOfDay(day);
                    sources.endOfDay(day);
                }
            };
            while (!world.day().isAfter(last)) {
                world.runDay(both);
            }
            List<String> failed = new ArrayList<>();
            try (Custody db = Custody.open(env("COCKROACH_URL", "jdbc:postgresql://cockroach:26257/custody?sslmode=disable"),
                     env("COCKROACH_USER", "clearing"), env("COCKROACH_PASSWORD", null))) {
                System.out.println(db.load(custody));
            } catch (SQLException e) {
                failed.add("custody (" + e.getMessage() + ")");
            }
            load(Sources.Db.BACKOFFICE, "BACKOFFICE", "jdbc:postgresql://backoffice:5432/backoffice", "backoffice",
                sources, failed);
            load(Sources.Db.TREASURY, "TREASURY", "jdbc:oracle:thin:@//oracle:1521/FREEPDB1", "treasury", sources,
                failed);
            if (!failed.isEmpty()) {
                System.out.println("cdc: not loaded, so their tables keep what they held: " + failed);
                System.exit(1);
            }
        }
    }

    private static void load(Sources.Db db, String prefix, String url, String user, Sources.Book book,
                             List<String> failed) {
        try (Sources source = Sources.open(db, env(prefix + "_URL", url), env(prefix + "_USER", user),
                 env(prefix + "_PASSWORD", null))) {
            System.out.println(db.name().toLowerCase() + " after " + book.last() + ": " + source.load(book));
        } catch (SQLException e) {
            failed.add(db.name().toLowerCase() + " (" + e.getMessage() + ")");
        }
    }

    static void bootstrap() throws Exception {
        Route route = Route.of(env("BROKERAGE_ROUTE", "both"));
        String bootstrap = route.kafka() ? env("KAFKA_BOOTSTRAP", null) : null;
        if (route.kafka()) {
            KafkaTopics.create(bootstrap, CONTROL_TOPIC, OWNERS_CONTROL_TOPIC);
        }
        MarketCalendar calendar = new MarketCalendar();
        LocalDate today = LocalDate.now(World.NEW_YORK);
        LocalDate start = today;
        for (int i = Integer.parseInt(env("BROKERAGE_CATCH_UP_DAYS", "3")); i > 0; i--) {
            start = calendar.previous(start);
        }
        World.Settings settings = World.Settings.of(Long.parseLong(env("BROKERAGE_SEED", "20261122")), start,
            Integer.parseInt(env("BROKERAGE_ACCOUNTS", "1000")));
        try (IcebergLake lake = lake()) {
            lake.bootstrap(Kit.load(), settings, today, route.kafka() ? KafkaTopics.endOffsets(bootstrap) : Map.of());
            System.out.println("the world: " + lake.settings() + ", live from " + lake.pacedFrom());
        }
    }

    static void run() throws Exception {
        Route route = Route.of(env("BROKERAGE_ROUTE", "both"));
        String bootstrap = route.kafka() ? env("KAFKA_BOOTSTRAP", null) : null;
        Kit kit = Kit.load();
        try (IcebergLake lake = lake();
             KafkaTopics topics = route.kafka() ? new KafkaTopics(bootstrap) : null;
             SingleStore store = route.singlestore() ? new SingleStore(env("SINGLESTORE_URL", null),
                 env("SINGLESTORE_USER", "root"), env("SINGLESTORE_PASSWORD", null), kit) : null) {
            World.Settings settings = lake.settings();
            lake.withCloses(closes());
            Pacer pacer = new Pacer();
            // What this world has sent each way: through Kafka, each topic's records since the world began; through
            // SingleStore, each table's rows. The way that is off drained into the lake before it went off (demo.sh),
            // so the lake's tables count it.
            Map<Feed, Long> viaKafka = new EnumMap<>(Feed.class);
            Map<Feed, Long> viaSingleStore = new EnumMap<>(Feed.class);
            if (route.kafka()) {
                Runtime.getRuntime().addShutdownHook(new Thread(topics::close));
                KafkaTopics.create(bootstrap, CONTROL_TOPIC, OWNERS_CONTROL_TOPIC);
                String connect = env("CONNECT_URL", "http://connect:8083");
                SinkWatch.start(connect, SINK);
                SinkWatch.start(connect, OWNERS_SINK, SinkWatch.whenClassified(env("NUNA_CATALOG_URI",
                    "http://nuna-catalog:8181"), env("NUNA_WAREHOUSE", "lakehouse"), env("NUNA_TOKEN", null),
                    Feed.BENEFICIAL_OWNERS.lakeTable(Feed.Path.KAFKA)));
                Map<Feed, Long> ends = KafkaTopics.endOffsets(bootstrap);
                Map<Feed, Long> start = lake.startOffsets(ends);
                ends.forEach((feed, end) -> {
                    long sent = end - start.get(feed);
                    if (sent < 0) {
                        throw new IllegalStateException("topic " + feed.topic + " holds fewer records than when the world"
                            + " began: Kafka lost them, so the world cannot resume; demo/demo.sh wipe and up start a new one");
                    }
                    viaKafka.put(feed, sent);
                });
            } else {
                for (Feed f : Feed.numbered()) {
                    viaKafka.put(f, lake.records(f.lakeTable(Feed.Path.KAFKA)));
                }
            }
            if (route.singlestore()) {
                viaSingleStore.putAll(store.counts());
            } else {
                for (Feed f : Feed.numbered()) {
                    viaSingleStore.put(f, lake.records(f.lakeTable(Feed.Path.SINGLESTORE)));
                }
            }
            // The reconciliation views read every lake table each feed has; a change feed makes its copy as it first
            // runs, which on a new lake is after the bootstrap made the views.
            lake.views();
            System.out.println("the world: " + settings + ", live from " + lake.pacedFrom() + " on the real clock, route "
                + route + "; already sent through Kafka " + viaKafka + ", through SingleStore " + viaSingleStore);
            Publisher publisher = new Publisher(route, topics, store, store, lake, pacer::await, viaKafka,
                viaSingleStore);
            World world = new World(settings, kit);
            while (true) {
                long before = publisher.sent();
                LocalDate day = world.runDay(publisher);
                System.out.println(day + ": " + (publisher.caughtUp()
                    ? (publisher.sent() - before) + " events sent" : "replayed (sent before a restart)")
                    + (world.unexpectedRejections() > 0 ? "; WARN " + world.unexpectedRejections()
                    + " unexpected rejections so far" : ""));
            }
        }
    }

    /**
     * Waits until what the world sent one way has all reached the lake, with the generator stopped: through Kafka, each
     * topic's records since the world began against its sink table's; through SingleStore, each feed table's rows
     * against its change feed's copy. demo.sh runs it before it turns that way off, since the next run counts what
     * went that way from the lake. Exits 1 if the lake has not caught up within {@code BROKERAGE_DRAIN_SECONDS}.
     */
    static void drain(String way) throws Exception {
        Feed.Path path = switch (way) {
            case "kafka" -> Feed.Path.KAFKA;
            case "singlestore" -> Feed.Path.SINGLESTORE;
            default -> throw new IllegalArgumentException("drain takes kafka or singlestore, not " + way);
        };
        Map<Feed, Long> sent = new EnumMap<>(Feed.class);
        try (IcebergLake lake = lake()) {
            if (path == Feed.Path.KAFKA) {
                String bootstrap = env("KAFKA_BOOTSTRAP", null);
                Map<Feed, Long> ends = KafkaTopics.endOffsets(bootstrap);
                Map<Feed, Long> start = lake.startOffsets(ends);
                ends.forEach((feed, end) -> sent.put(feed, end - start.get(feed)));
            } else {
                try (SingleStore store = new SingleStore(env("SINGLESTORE_URL", null), env("SINGLESTORE_USER", "root"),
                         env("SINGLESTORE_PASSWORD", null), Kit.load())) {
                    sent.putAll(store.counts());
                }
            }
            long deadline = System.nanoTime() + Duration.ofSeconds(Long.parseLong(env("BROKERAGE_DRAIN_SECONDS",
                "180"))).toNanos();
            while (true) {
                Map<String, String> behind = new java.util.TreeMap<>();
                sent.forEach((feed, n) -> {
                    long landed = lake.records(feed.lakeTable(path));
                    if (landed < n) {
                        behind.put(feed.lakeTable(path), landed + " of " + n);
                    }
                });
                if (behind.isEmpty()) {
                    System.out.println("everything sent through " + way + " is in the lake: " + sent);
                    return;
                }
                if (System.nanoTime() > deadline) {
                    System.out.println("the lake has not caught up with what went through " + way + ": " + behind);
                    System.exit(1);
                }
                Thread.sleep(3000);
            }
        }
    }

    static IcebergLake lake() {
        return IcebergLake.open(env("NUNA_CATALOG_URI", "http://nuna-catalog:8181"), env("NUNA_WAREHOUSE", "lakehouse"),
            env("NUNA_TOKEN", null), Duration.ofMillis(Long.parseLong(env("BROKERAGE_COMMIT_INTERVAL_MS", "15000"))));
    }

    /**
     * The month-end close's writer (item 153): a catalog that writes data files with the credentials the catalog vends,
     * and the catalog's refs for the tag and the restatement branch.
     */
    static MonthEndWriter closes() {
        String uri = env("NUNA_CATALOG_URI", "http://nuna-catalog:8181");
        String warehouse = env("NUNA_WAREHOUSE", "lakehouse");
        String token = env("NUNA_TOKEN", null);
        Map<String, String> properties = Map.of("uri", uri, "token", token,
            "header.X-Iceberg-Access-Delegation", "vended-credentials");
        RESTCatalog catalog = new RESTCatalog();
        Map<String, String> main = new java.util.HashMap<>(properties);
        main.put("warehouse", warehouse);
        catalog.initialize("brokerage-close", main);
        return new MonthEndWriter(catalog, new HttpRefs(uri, warehouse, token, properties));
    }

    static String env(String name, String fallback) {
        String v = System.getenv(name);
        if (v == null || v.isBlank()) {
            if (fallback == null) {
                throw new IllegalStateException("environment variable " + name + " is not set");
            }
            return fallback;
        }
        return v;
    }
}
