package nuna.changefeed;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.errors.ConnectException;
import org.apache.kafka.connect.errors.RetriableException;
import org.apache.kafka.connect.source.SourceRecord;
import org.apache.kafka.connect.source.SourceTask;

/**
 * Runs one table's change feed, or one for each table {@code singlestore.tables} lists: one task, so one extractor
 * in SingleStore's memory, whatever the number of tables (backlog 238). Each {@link #poll} returns a receipt for
 * every Iceberg commit it made, which the pipeline loads into SingleStore. Each feed's position is kept in Iceberg;
 * the pipeline's offset is only the last receipt SingleStore stored, from which a restarted task sends again the
 * receipts of a batch SingleStore failed. With several tables the offset holds each table's last receipt, under the
 * table's name, so it never depends on how SingleStore keeps a task's source partitions apart.
 */
public final class ChangeFeedTask extends SourceTask {

    /** A commit to the Iceberg copy, as loaded into SingleStore. */
    static final Schema RECEIPT = SchemaBuilder.struct().name("nuna.changefeed.Receipt")
            .field("snapshot_id", Schema.INT64_SCHEMA)
            .field("source_table", Schema.STRING_SCHEMA)
            .field("iceberg_table", Schema.STRING_SCHEMA)
            .field("kind", Schema.STRING_SCHEMA)
            .field("rows_written", Schema.INT64_SCHEMA)
            .field("keys_deleted", Schema.INT64_SCHEMA)
            .field("offsets", Schema.STRING_SCHEMA)
            .field("gap_after", Schema.OPTIONAL_STRING_SCHEMA)
            .field("committed_at", Schema.INT64_SCHEMA)
            .build();
    static final String TOPIC = "nuna.changefeed";
    /** poll() must return promptly: SingleStore waits on every task's poll before it writes a batch. */
    static final long POLL_WAIT_MS = 200;
    /** The tables of a pipeline that copies several, each into an Iceberg table of the same name. */
    static final String TABLES = "singlestore.tables";
    /** The offset's key for a pipeline of one table. */
    static final String SNAPSHOT_ID = "snapshot_id";

    /** One table's feed, and the key its last receipt has in the offset. */
    record Feed(String table, String offsetKey, Function<Long, List<Map<String, Object>>> engine) {}

    private Map<String, String> partition;
    private List<Feed> feeds = List.of();
    /** Each feed's last receipt, by its offset key: what the next record's offset says. */
    private Map<String, Object> receipted = new LinkedHashMap<>();
    /** A feed's failure, thrown at the next poll when the poll it happened in had other feeds' receipts to return. */
    private RuntimeException failed;

    @Override
    public String version() {
        return ChangeFeedConnector.VERSION;
    }

    @Override
    public void start(Map<String, String> props) {
        List<Map<String, String>> configs = perTable(props);
        boolean many = props.containsKey(TABLES);
        Map<String, String> partition = Map.of("source", props.getOrDefault("singlestore.database", "") + "."
                + String.join(",", configs.stream().map(c -> c.getOrDefault("singlestore.table", "")).toList()));
        Map<String, Object> stored = context.offsetStorageReader().offset(partition);
        Map<String, Object> receipted = new LinkedHashMap<>();
        List<Feed> feeds = new ArrayList<>();
        try {
            for (Map<String, String> config : configs) {
                String table = config.getOrDefault("singlestore.table", "");
                String key = many ? table : SNAPSHOT_ID;
                Long through = receiptedThrough(stored, key);
                if (through != null) {
                    receipted.put(key, through);
                }
                feeds.add(new Feed(table, key, engine(config, through)));
            }
        } catch (RuntimeException e) {
            close(feeds);
            throw e;
        }
        started(partition, feeds, receipted);
    }

    /** The task's state once its feeds have started. */
    void started(Map<String, String> partition, List<Feed> feeds, Map<String, Object> receipted) {
        this.partition = partition;
        this.feeds = List.copyOf(feeds);
        this.receipted = new LinkedHashMap<>(receipted);
        this.failed = null;
    }

    @SuppressWarnings("unchecked")
    private static Engine engine(Map<String, String> config, Long receiptedThrough) {
        try {
            return new Engine(Isolation.within(() -> (Function<Long, List<Map<String, Object>>>)
                    Class.forName(Isolation.ENGINE, true, Isolation.loader()).getConstructor(Map.class, Long.class)
                            .newInstance(config, receiptedThrough)));
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw new ConnectException("starting the change feed of " + config.get("singlestore.table") + ": " + e.getCause(), e.getCause());
        } catch (Exception e) {
            throw new ConnectException("starting the change feed of " + config.get("singlestore.table") + ": " + e, e);
        }
    }

    /** The engine, called with the feed's class loader as the thread's context loader. */
    private record Engine(Function<Long, List<Map<String, Object>>> feed) implements Function<Long, List<Map<String, Object>>>, AutoCloseable {
        @Override
        public List<Map<String, Object>> apply(Long waitMs) {
            try {
                return Isolation.within(() -> feed.apply(waitMs));
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new ConnectException(e);
            }
        }

        @Override
        public void close() throws Exception {
            if (feed instanceof AutoCloseable closeable) {
                Isolation.within(() -> {
                    closeable.close();
                    return null;
                });
            }
        }
    }

    /**
     * The engine's configuration for each table: the pipeline's own, or with {@code singlestore.tables} one per table
     * listed (separated by commas or spaces), each copying its table into an Iceberg table of the same name.
     */
    static List<Map<String, String>> perTable(Map<String, String> props) {
        if (!props.containsKey(TABLES)) {
            return List.of(props);
        }
        List<String> tables = Arrays.stream(props.get(TABLES).split("[,\\s]+")).filter(t -> !t.isEmpty()).toList();
        if (tables.isEmpty()) {
            throw new ConnectException(TABLES + " lists no table");
        }
        List<Map<String, String>> configs = new ArrayList<>(tables.size());
        for (String table : tables) {
            Map<String, String> config = new HashMap<>(props);
            config.remove(TABLES);
            config.put("singlestore.table", table);
            config.put("iceberg.table", table);
            configs.add(Map.copyOf(config));
        }
        return configs;
    }

    /** The snapshot id of the stored offset; a converter may hand it back as any kind of number, or as text. */
    static Long receiptedThrough(Map<String, Object> offset) {
        return receiptedThrough(offset, SNAPSHOT_ID);
    }

    /** The snapshot id stored under {@code key}. */
    static Long receiptedThrough(Map<String, Object> offset, String key) {
        Object id = offset == null ? null : offset.get(key);
        if (id == null) {
            return null;
        }
        return id instanceof Number n ? n.longValue() : Long.parseLong(id.toString());
    }

    @Override
    public List<SourceRecord> poll() {
        if (failed != null) {
            RuntimeException e = failed;
            failed = null;
            throw new RetriableException(e.getMessage(), e);
        }
        long waitMs = Math.max(1, POLL_WAIT_MS / feeds.size());
        // Never null: SingleStore 10.9 fails a batch whose poll() returns null.
        List<SourceRecord> records = new ArrayList<>();
        for (Feed feed : feeds) {
            List<Map<String, Object>> receipts;
            try {
                receipts = feed.engine().apply(waitMs);
            } catch (ConnectException e) {
                throw e;
            } catch (RuntimeException e) {
                // The feed has dropped what it held and starts again from Iceberg on its next poll; the others go on.
                if (failed == null) {
                    failed = new RuntimeException("change feed of " + feed.table() + ": " + e, e);
                }
                continue;
            }
            for (Map<String, Object> receipt : receipts) {
                Struct value = new Struct(RECEIPT);
                receipt.forEach(value::put);
                receipted.put(feed.offsetKey(), receipt.get("snapshot_id"));
                records.add(new SourceRecord(partition, Map.copyOf(receipted), TOPIC, RECEIPT, value));
            }
        }
        if (failed != null && records.isEmpty()) {
            RuntimeException e = failed;
            failed = null;
            throw new RetriableException(e.getMessage(), e);
        }
        return records;
    }

    @Override
    public void stop() {
        close(feeds);
    }

    private static void close(List<Feed> feeds) {
        ConnectException failure = null;
        for (Feed feed : feeds) {
            if (feed.engine() instanceof AutoCloseable closeable) {
                try {
                    closeable.close();
                } catch (Exception e) {
                    if (failure == null) {
                        failure = new ConnectException(e);
                    } else {
                        failure.addSuppressed(e);
                    }
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }
}
