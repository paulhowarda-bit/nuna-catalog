package nuna.datagen;

import java.time.Duration;
import java.time.LocalDate;
import java.util.Map;
import nuna.datagen.kit.Kit;
import nuna.datagen.live.IcebergLake;
import nuna.datagen.live.KafkaTopics;
import nuna.datagen.live.Pacer;
import nuna.datagen.live.Publisher;
import nuna.datagen.live.SinkWatch;
import nuna.datagen.live.SingleStoreOrders;
import nuna.datagen.world.Feed;
import nuna.datagen.world.MarketCalendar;
import nuna.datagen.world.World;

/**
 * The brokerage world's two commands, configured from the environment (demo/k8s/brokerage.yaml).
 *
 * <ul>
 *   <li>{@code bootstrap}: makes the topics, namespaces, tables and views, and fixes the world's settings in the
 *       {@code brokerage} namespace's properties. It makes only what is missing.</li>
 *   <li>{@code run}: plays the world day after day, for ever, on the real clock (D48, Paul, 2026-11-23): what has
 *       already happened (the catch-up days, today's earlier hours) as fast as the sink takes it, then each event at
 *       its own time of day in New York, quiet overnight, over weekends and on holidays. A restart resumes where the
 *       last run stopped. A watch restarts the sink's task whenever it has failed ({@link SinkWatch}).</li>
 * </ul>
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
            default -> {
                System.err.println("usage: Main bootstrap | run");
                System.exit(2);
            }
        }
    }

    static void bootstrap() throws Exception {
        String bootstrap = env("KAFKA_BOOTSTRAP", null);
        KafkaTopics.create(bootstrap, CONTROL_TOPIC);
        MarketCalendar calendar = new MarketCalendar();
        LocalDate today = LocalDate.now(World.NEW_YORK);
        LocalDate start = today;
        for (int i = Integer.parseInt(env("BROKERAGE_CATCH_UP_DAYS", "3")); i > 0; i--) {
            start = calendar.previous(start);
        }
        World.Settings settings = World.Settings.of(Long.parseLong(env("BROKERAGE_SEED", "20261122")), start,
            Integer.parseInt(env("BROKERAGE_ACCOUNTS", "1000")));
        try (IcebergLake lake = lake()) {
            lake.bootstrap(Kit.load(), settings, today, KafkaTopics.endOffsets(bootstrap));
            System.out.println("the world: " + lake.settings() + ", live from " + lake.pacedFrom());
        }
    }

    static void run() throws Exception {
        String bootstrap = env("KAFKA_BOOTSTRAP", null);
        Kit kit = Kit.load();
        try (IcebergLake lake = lake();
             KafkaTopics topics = new KafkaTopics(bootstrap);
             SingleStoreOrders orders = new SingleStoreOrders(env("SINGLESTORE_URL", null),
                 env("SINGLESTORE_USER", "root"), env("SINGLESTORE_PASSWORD", null))) {
            Runtime.getRuntime().addShutdownHook(new Thread(topics::close));
            World.Settings settings = lake.settings();
            Pacer pacer = new Pacer();
            SinkWatch.start(env("CONNECT_URL", "http://connect:8083"), SINK);
            // What this world has sent: each topic's records since the world began.
            Map<Feed, Long> offsets = KafkaTopics.endOffsets(bootstrap);
            Map<Feed, Long> start = lake.startOffsets();
            offsets.replaceAll((feed, end) -> {
                long sent = end - start.getOrDefault(feed, 0L);
                if (sent < 0) {
                    throw new IllegalStateException("topic " + feed.source + " holds fewer records than when the world"
                        + " began: Kafka lost them, so the world cannot resume; demo/demo.sh wipe and up start a new one");
                }
                return sent;
            });
            System.out.println("the world: " + settings + ", live from " + lake.pacedFrom() + " on the real clock;"
                + " already sent " + offsets);
            Publisher publisher = new Publisher(topics, orders, lake, pacer::await, offsets);
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
