package fidice.changefeed.engine;

import fidice.changefeed.engine.ObserveReader.End;
import fidice.changefeed.engine.ObserveReader.Event;
import java.io.Closeable;
import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.apache.iceberg.CatalogUtil;
import org.apache.iceberg.catalog.Catalog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One SingleStore table's change feed into its Iceberg copy. The pipeline's task calls {@link #apply} (poll) once
 * per batch; each call reads what the change feed has sent, commits to Iceberg when the commit interval has passed,
 * and returns a receipt for each commit, which the pipeline loads into SingleStore.
 *
 * <p>Only JDK types cross this class's surface: the connector loads it in its own class loader, apart from the
 * libraries SingleStore's extractor already has.
 *
 * <p>Where the feed resumes is kept in Iceberg, not in the pipeline: every commit records its offsets, so after any
 * failure (a lost commit, a killed extractor, a dropped connection) the feed drops what it held and starts again
 * from the offsets of the table's current snapshot. When SingleStore no longer has its log from there, the feed
 * takes a new snapshot and records the gap.
 */
public final class ChangeFeed implements Function<Long, List<Map<String, Object>>>, AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(ChangeFeed.class);
    /** A feed nobody polls is closed, so that it stops holding an OBSERVE (which blocks ALTER and DROP on the table). */
    static final long IDLE_CLOSE_MS = TimeUnit.MINUTES.toMillis(5);
    static final long GUARD_EVERY_MS = TimeUnit.MINUTES.toMillis(1);
    /** Events taken in per poll, so a poll returns while the change feed is busy. */
    static final int EVENTS_PER_POLL = 50_000;
    /** Keys a batch may hold before it is committed without waiting for the interval. */
    static final int MAX_KEYS = 200_000;

    private final FeedConfig config;
    private final ScheduledExecutorService watchdog;
    private volatile long lastPoll = System.currentTimeMillis();

    private Catalog catalog;
    private SourceTable source;
    private IcebergCopy copy;
    private ObserveReader reader;
    private ChangeBatch batch;
    /** Set while the feed is taking a snapshot. */
    private Snapshotting snapshotting;
    private long lastCommit;
    private long lastGuard;
    /** Receipts of commits SingleStore has not stored, sent before anything else. */
    private List<Map<String, Object>> owed;

    /** The partitions' snapshots, written as they arrive and committed once every partition has sent its own. */
    private static final class Snapshotting {
        final IcebergCopy.SnapshotWriter writer;
        final String[] offsets;
        final boolean[] open;
        final String[] gapAfter;
        int done;

        Snapshotting(IcebergCopy.SnapshotWriter writer, int partitions, String[] gapAfter) {
            this.writer = writer;
            this.offsets = new String[partitions];
            this.open = new boolean[partitions];
            this.gapAfter = gapAfter;
        }
    }

    /**
     * {@code receiptedThrough} is the snapshot of the last receipt SingleStore stored (the pipeline's offset), or
     * null if it has stored none. A batch SingleStore failed to write lost its receipts, though their commits are in
     * Iceberg; SingleStore then starts the task again from its stored offset, and the feed sends those receipts again.
     */
    public ChangeFeed(Map<String, String> props, Long receiptedThrough) {
        config = FeedConfig.from(props);
        watchdog = Executors.newSingleThreadScheduledExecutor(r -> Thread.ofPlatform().daemon().name("changefeed-watchdog").unstarted(r));
        watchdog.scheduleWithFixedDelay(this::closeIfIdle, 30, 30, TimeUnit.SECONDS);
        open(null);
        owed = copy.receiptsAfter(receiptedThrough).stream().map(this::receipt).toList();
        if (!owed.isEmpty()) {
            LOG.info("{} -> {}: sending again the receipts of {} commits after {}", config.source(), config.icebergTable(),
                    owed.size(), receiptedThrough);
        }
    }

    /** Takes in what arrived within {@code waitMs}, commits if due, and returns the receipts of any commits. */
    @Override
    public synchronized List<Map<String, Object>> apply(Long waitMs) {
        lastPoll = System.currentTimeMillis();
        if (reader == null) {
            open(null);
        }
        try {
            List<Map<String, Object>> receipts = poll(waitMs);
            owed = List.of();
            return receipts;
        } catch (RuntimeException e) {
            // Whatever was held is read again from the offsets in Iceberg.
            LOG.warn("change feed {} -> {} failed; it restarts from Iceberg's offsets on the next poll", config.source(),
                    config.icebergTable(), e);
            closeState();
            throw e;
        }
    }

    private List<Map<String, Object>> poll(long waitMs) {
        List<Map<String, Object>> receipts = new ArrayList<>(owed);
        long now = System.currentTimeMillis();
        if (now - lastGuard >= GUARD_EVERY_MS) {
            guard();
        }
        long deadline = now + waitMs;
        for (int taken = 0; taken < EVENTS_PER_POLL; taken++) {
            Object next;
            try {
                next = reader.next(Math.max(0, deadline - System.currentTimeMillis()));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            if (next == null) {
                break;
            }
            if (next instanceof End end) {
                ended(end);
                return receipts;
            }
            Map<String, Object> receipt = take((Event) next);
            if (receipt != null) {
                receipts.add(receipt);
            }
            if (snapshotting == null && batch.size() >= MAX_KEYS) {
                break;
            }
        }
        if (snapshotting == null && !batch.isEmpty()
                && (System.currentTimeMillis() - lastCommit >= config.commitIntervalMs() || batch.size() >= MAX_KEYS)) {
            receipts.add(receipt(copy.commit(batch)));
            batch.clear();
            lastCommit = System.currentTimeMillis();
        }
        return receipts;
    }

    /** Takes in one event; returns the snapshot's receipt when it is the last partition's {@code CommitSnapshot}. */
    private Map<String, Object> take(Event event) {
        int p = event.partition();
        switch (event.type()) {
            case BEGIN_SNAPSHOT -> {
                if (snapshotting == null) {
                    throw new IllegalStateException("SingleStore sent a snapshot after offsets the feed resumed from");
                }
                snapshotting.open[p] = true;
            }
            case COMMIT_SNAPSHOT -> {
                snapshotting.open[p] = false;
                snapshotting.offsets[p] = event.offset();
                batch.startAt(p, event.offset());
                if (++snapshotting.done == snapshotting.offsets.length) {
                    IcebergCopy.Receipt receipt = snapshotting.writer.commit(snapshotting.offsets, snapshotting.gapAfter);
                    snapshotting = null;
                    lastCommit = System.currentTimeMillis();
                    LOG.info("{} -> {}: snapshot of {} rows committed", config.source(), config.icebergTable(), receipt.rows());
                    return receipt(receipt);
                }
            }
            case INSERT -> {
                if (snapshotting != null && snapshotting.open[p]) {
                    snapshotting.writer.write(event.values());
                } else {
                    batch.accept(event);
                }
            }
            default -> batch.accept(event);
        }
        return null;
    }

    /** The change feed stopped. A stale offset means a new snapshot; anything else fails this poll. */
    private void ended(End end) {
        if (!end.stale()) {
            throw new IllegalStateException("change feed of " + config.source() + " stopped", end.error());
        }
        String[] gapAfter = copy.offsets();
        LOG.warn("{}: SingleStore no longer has its change feed after offsets {}; taking a new snapshot of {}",
                config.icebergTable(), Arrays.toString(gapAfter), config.source());
        closeState();
        open(gapAfter);
    }

    private void open(String[] gapAfter) {
        try (Connection c = config.connect()) {
            source = SourceTable.read(c, config.database(), config.table());
            LoopGuard.check(c, config);
            lastGuard = System.currentTimeMillis();
        } catch (SQLException e) {
            throw new IllegalStateException("reading " + config.source() + " from SingleStore", e);
        }
        catalog = CatalogUtil.buildIcebergCatalog("changefeed", config.catalogProperties(), null);
        copy = IcebergCopy.open(catalog, config, source);
        String[] from = gapAfter != null ? new String[source.partitions()] : copy.offsets();
        batch = new ChangeBatch(source, from);
        snapshotting = Arrays.stream(from).allMatch(Objects::isNull)
                ? new Snapshotting(copy.snapshotWriter(), source.partitions(), gapAfter)
                : null;
        lastCommit = System.currentTimeMillis();
        reader = new ObserveReader(config, source, from);
        LOG.info("{} -> {}: {}", config.source(), config.icebergTable(),
                snapshotting != null ? "taking a snapshot" : "resuming after offsets " + Arrays.toString(from));
    }

    private void guard() {
        try (Connection c = config.connect()) {
            LoopGuard.check(c, config);
        } catch (SQLException e) {
            throw new IllegalStateException("checking SingleStore's pipelines", e);
        }
        lastGuard = System.currentTimeMillis();
    }

    private Map<String, Object> receipt(IcebergCopy.Receipt r) {
        Map<String, Object> receipt = new LinkedHashMap<>();
        receipt.put("snapshot_id", r.snapshotId());
        receipt.put("source_table", config.source());
        receipt.put("iceberg_table", LoopGuard.name(config.icebergTable()));
        receipt.put("kind", r.kind());
        receipt.put("rows_written", r.rows());
        receipt.put("keys_deleted", r.deletedKeys());
        receipt.put("offsets", r.offsets());
        receipt.put("gap_after", r.gapAfter());
        receipt.put("committed_at", r.committedAt());
        return receipt;
    }

    private synchronized void closeIfIdle() {
        if (reader != null && System.currentTimeMillis() - lastPoll > IDLE_CLOSE_MS) {
            LOG.warn("{} -> {}: not polled for {} ms, closing its change feed", config.source(), config.icebergTable(),
                    IDLE_CLOSE_MS);
            closeState();
        }
    }

    private void closeState() {
        if (reader != null) {
            reader.stop();
        }
        if (snapshotting != null) {
            snapshotting.writer.abort();
        }
        if (catalog instanceof Closeable closeable) {
            try {
                closeable.close();
            } catch (IOException e) {
                LOG.debug("closing the catalog", e);
            }
        }
        reader = null;
        snapshotting = null;
        batch = null;
        copy = null;
        catalog = null;
    }

    @Override
    public synchronized void close() {
        watchdog.shutdownNow();
        closeState();
    }
}
