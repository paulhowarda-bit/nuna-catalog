package nuna.changefeed.engine;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Streams {@code OBSERVE} for one table on its own thread, into a bounded queue the task drains. A full queue
 * blocks the thread, which stops reading the result set: SingleStore holds the rest of its log meanwhile.
 *
 * <p>What SingleStore 10.9.0 sends, found by running it: per partition, {@code BeginSnapshot}, the snapshot's rows
 * as {@code Insert}s, {@code CommitSnapshot}; then transactions, each {@code BeginTransaction}, row events,
 * {@code CommitTransaction}. An update carries the whole new row, a delete only the key. Partitions interleave.
 * With {@code BEGIN AT} one offset per partition, the stream starts after those offsets and sends no snapshot. An
 * offset older than the oldest snapshot SingleStore keeps fails with error 2851.
 */
final class ObserveReader {

    private static final Logger LOG = LoggerFactory.getLogger(ObserveReader.class);
    /** SingleStore's answer to an offset it no longer has: "the offset is older than the oldest snapshot". */
    static final int STALE_OFFSET = 2851;

    enum Type { BEGIN_SNAPSHOT, COMMIT_SNAPSHOT, BEGIN_TRANSACTION, COMMIT_TRANSACTION, INSERT, UPDATE, DELETE }

    /** A change-feed row: its type, partition and offset, and for row events the column values in table order. */
    record Event(Type type, int partition, String offset, Object[] values) {}

    /** Put on the queue last when the stream ends: why, and whether it was a stale offset. */
    record End(SQLException error, boolean stale) {}

    private final FeedConfig config;
    private final SourceTable table;
    private final BlockingQueue<Object> queue = new ArrayBlockingQueue<>(10_000);
    private final Thread thread;
    private volatile Statement statement;
    private volatile boolean stopping;

    /** Starts reading after the given offsets (one per partition), or from a snapshot when they are all null. */
    ObserveReader(FeedConfig config, SourceTable table, String[] from) {
        this.config = config;
        this.table = table;
        this.thread = Thread.ofPlatform().daemon().name("observe-" + table.database() + "." + table.table())
                .start(() -> run(observeSql(table, from)));
    }

    static String observeSql(SourceTable table, String[] from) {
        String sql = "OBSERVE * FROM `" + table.database() + "`.`" + table.table() + "`";
        boolean any = false;
        List<String> parts = new ArrayList<>();
        for (String offset : from) {
            any |= offset != null;
            parts.add(offset == null ? "NULL" : "'" + offset + "'");
        }
        return any ? sql + " BEGIN AT (" + String.join(",", parts) + ")" : sql;
    }

    /** The next event or {@link End}, or null if none arrives within the wait. */
    Object next(long waitMs) throws InterruptedException {
        return queue.poll(waitMs, TimeUnit.MILLISECONDS);
    }

    void stop() {
        stopping = true;
        try {
            Statement s = statement;
            if (s != null) {
                s.cancel();
            }
        } catch (SQLException e) {
            LOG.debug("cancelling OBSERVE", e);
        }
        thread.interrupt();
    }

    private void run(String sql) {
        SQLException error = null;
        try (Connection c = config.connect(); Statement s = c.createStatement()) {
            statement = s;
            // One row per fetch: the stream never ends, so a larger fetch would wait for rows to fill it.
            s.setFetchSize(1);
            LOG.info("{}", sql);
            try (ResultSet rs = s.executeQuery(sql)) {
                int[] positions = new int[table.columns().size()];
                for (int i = 0; i < positions.length; i++) {
                    positions[i] = rs.findColumn(table.columns().get(i).name());
                }
                while (rs.next()) {
                    queue.put(event(rs, positions));
                }
            }
        } catch (SQLException e) {
            error = e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (!stopping) {
            boolean stale = error != null && error.getErrorCode() == STALE_OFFSET;
            if (error == null) {
                error = new SQLException("OBSERVE ended");
            }
            try {
                queue.put(new End(error, stale));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private Event event(ResultSet rs, int[] positions) throws SQLException {
        Type type = switch (rs.getString("Type")) {
            case "BeginSnapshot" -> Type.BEGIN_SNAPSHOT;
            case "CommitSnapshot" -> Type.COMMIT_SNAPSHOT;
            case "BeginTransaction" -> Type.BEGIN_TRANSACTION;
            case "CommitTransaction" -> Type.COMMIT_TRANSACTION;
            case "Insert" -> Type.INSERT;
            case "Update" -> Type.UPDATE;
            case "Delete" -> Type.DELETE;
            default -> throw new SQLException("unknown change-feed event type " + rs.getString("Type"));
        };
        Object[] values = null;
        if (type == Type.INSERT || type == Type.UPDATE || type == Type.DELETE) {
            values = new Object[positions.length];
            for (int i = 0; i < positions.length; i++) {
                values[i] = table.columns().get(i).reader().read(rs, positions[i]);
            }
        }
        return new Event(type, rs.getInt("PartitionId"), HexFormat.of().formatHex(rs.getBytes("Offset")), values);
    }
}
