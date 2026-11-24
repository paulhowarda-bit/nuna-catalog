package nuna.datagen;

import java.time.Duration;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.Map;
import nuna.datagen.kit.Kit;
import nuna.datagen.live.IcebergLake;
import nuna.datagen.live.KafkaTopics;
import nuna.datagen.live.Pacer;
import nuna.datagen.live.Publisher;
import nuna.datagen.live.Route;
import nuna.datagen.live.SingleStore;
import nuna.datagen.live.SingleStoreTables;
import nuna.datagen.live.SinkWatch;
import nuna.datagen.world.Feed;
import nuna.datagen.world.MarketCalendar;
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
 *   <li>{@code sql <dir>}: writes brokerage-feeds.sql and brokerage-feeds-pipelines.sql into {@code dir}
 *       ({@link SingleStoreTables}).</li>
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

    private Main() {
    }

    public static void main(String[] args) throws Exception {
        switch (args.length == 0 ? "" : args[0]) {
            case "bootstrap" -> bootstrap();
            case "run" -> run();
            case "sql" -> sql(Path.of(args.length > 1 ? args[1] : "."));
            default -> {
                System.err.println("usage: Main bootstrap | run | sql <dir>");
                System.exit(2);
            }
        }
    }

    static void sql(Path dir) throws Exception {
        Kit kit = Kit.load();
        Files.writeString(dir.resolve("brokerage-feeds.sql"), SingleStoreTables.tables(kit));
        Files.writeString(dir.resolve("brokerage-feeds-pipelines.sql"), SingleStoreTables.pipelines());
    }

    static void bootstrap() throws Exception {
        Route route = Route.of(env("BROKERAGE_ROUTE", "both"));
        String bootstrap = route.kafka() ? env("KAFKA_BOOTSTRAP", null) : null;
        if (route.kafka()) {
            KafkaTopics.create(bootstrap, CONTROL_TOPIC);
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
            Pacer pacer = new Pacer();
            // What this world has sent each way: through Kafka, each topic's records since the world began; through
            // SingleStore, each table's rows. The way that is off drained into the lake before it went off (demo.sh),
            // so the lake's tables count it.
            Map<Feed, Long> viaKafka = new EnumMap<>(Feed.class);
            Map<Feed, Long> viaSingleStore = new EnumMap<>(Feed.class);
            if (route.kafka()) {
                Runtime.getRuntime().addShutdownHook(new Thread(topics::close));
                KafkaTopics.create(bootstrap, CONTROL_TOPIC);
                SinkWatch.start(env("CONNECT_URL", "http://connect:8083"), SINK);
                Map<Feed, Long> ends = KafkaTopics.endOffsets(bootstrap);
                Map<Feed, Long> start = lake.startOffsets(ends);
                ends.forEach((feed, end) -> {
                    long sent = end - start.get(feed);
                    if (sent < 0) {
                        throw new IllegalStateException("topic " + feed.topic + " holds fewer records than when the world"
                            + " began: Kafka lost them, so the world cannot resume; demo/demo.sh wipe starts a new one");
                    }
                    viaKafka.put(feed, sent);
                });
            } else {
                for (Feed f : Feed.values()) {
                    viaKafka.put(f, lake.records(f.lakeTable(Feed.Path.KAFKA)));
                }
            }
            if (route.singlestore()) {
                viaSingleStore.putAll(store.counts());
            } else {
                for (Feed f : Feed.values()) {
                    if (f != Feed.ORDERS) {
                        viaSingleStore.put(f, lake.records(f.lakeTable(Feed.Path.SINGLESTORE)));
                    }
                }
            }
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

    static IcebergLake lake() {
        return IcebergLake.open(env("NUNA_CATALOG_URI", "http://nuna-catalog:8181"), env("NUNA_WAREHOUSE", "lakehouse"),
            env("NUNA_TOKEN", null), Duration.ofMillis(Long.parseLong(env("BROKERAGE_COMMIT_INTERVAL_MS", "15000"))));
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
