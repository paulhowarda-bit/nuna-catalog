package fidice.changefeed;

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
 * pipeline loads into SingleStore; the feed's position is kept in Iceberg, not in the pipeline's offsets.
 */
public final class ChangeFeedTask extends SourceTask {

    /** A commit to the Iceberg copy, as loaded into SingleStore. */
    static final Schema RECEIPT = SchemaBuilder.struct().name("fidice.changefeed.Receipt")
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
    static final String TOPIC = "fidice.changefeed";
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
        try {
            feed = Isolation.within(() -> (Function<Long, List<Map<String, Object>>>)
                    Class.forName(Isolation.ENGINE, true, Isolation.loader()).getConstructor(Map.class).newInstance(props));
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw new ConnectException("starting the change feed: " + e.getCause(), e.getCause());
        } catch (Exception e) {
            throw new ConnectException("starting the change feed: " + e, e);
        }
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
