package nuna.changefeed;

import java.util.ArrayList;
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
 * Runs one table's change feed. Each {@link #poll} returns a receipt for every Iceberg commit it made, which the
 * pipeline loads into SingleStore. The feed's position is kept in Iceberg; the pipeline's offset is only the last
 * receipt SingleStore stored, from which a restarted task sends again the receipts of a batch SingleStore failed.
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

    private Function<Long, List<Map<String, Object>>> feed;
    private Map<String, String> partition;

    @Override
    public String version() {
        return ChangeFeedConnector.VERSION;
    }

    @Override
    @SuppressWarnings("unchecked")
    public void start(Map<String, String> props) {
        partition = Map.of("source", props.getOrDefault("singlestore.database", "") + "." + props.getOrDefault("singlestore.table", ""));
        Long receiptedThrough = receiptedThrough(context.offsetStorageReader().offset(partition));
        try {
            feed = Isolation.within(() -> (Function<Long, List<Map<String, Object>>>)
                    Class.forName(Isolation.ENGINE, true, Isolation.loader()).getConstructor(Map.class, Long.class)
                            .newInstance(props, receiptedThrough));
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw new ConnectException("starting the change feed: " + e.getCause(), e.getCause());
        } catch (Exception e) {
            throw new ConnectException("starting the change feed: " + e, e);
        }
    }

    /** The snapshot id of the stored offset; a converter may hand it back as any kind of number, or as text. */
    static Long receiptedThrough(Map<String, Object> offset) {
        Object id = offset == null ? null : offset.get("snapshot_id");
        if (id == null) {
            return null;
        }
        return id instanceof Number n ? n.longValue() : Long.parseLong(id.toString());
    }

    @Override
    public List<SourceRecord> poll() {
        List<Map<String, Object>> receipts;
        try {
            receipts = Isolation.within(() -> feed.apply(POLL_WAIT_MS));
        } catch (RuntimeException e) {
            // The feed has dropped what it held and starts again from Iceberg on the next poll.
            throw new RetriableException("change feed: " + e, e);
        } catch (Exception e) {
            throw new ConnectException(e);
        }
        // Never null: SingleStore 10.9 fails a batch whose poll() returns null.
        List<SourceRecord> records = new ArrayList<>(receipts.size());
        for (Map<String, Object> receipt : receipts) {
            Struct value = new Struct(RECEIPT);
            receipt.forEach(value::put);
            records.add(new SourceRecord(partition, Map.of("snapshot_id", receipt.get("snapshot_id")), TOPIC, RECEIPT, value));
        }
        return records;
    }

    @Override
    public void stop() {
        if (feed instanceof AutoCloseable closeable) {
            try {
                Isolation.within(() -> {
                    closeable.close();
                    return null;
                });
            } catch (Exception e) {
                throw new ConnectException(e);
            }
        }
    }
}
