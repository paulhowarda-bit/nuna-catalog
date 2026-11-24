package nuna.audittables;

import nuna.catalog.audit.AuditRow;
import nuna.catalog.audit.AuditRows;
import nuna.catalog.audit.CatalogEvent;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Writes the audit records the catalog logs, taken over OTLP ({@link OtlpLogsReceiver}), to the audit table (D23,
 * amended 2026-11-23), about a commit interval behind, as {@link TableWriter} writes the audit topic's.
 *
 * <p>The log has no offsets to resume from, and a record can arrive twice: the exporter retries a batch whose answer
 * it lost, and the catalog's outbox relay is at least once. So rows are deduplicated by CloudEvent id: an id seen in
 * the last {@code dedupWindow} (taken here, or in a row whose record time is that recent when the table is read) is
 * left out. Each row's {@code topic_partition} is {@value #PARTITION}, no topic's, and its {@code topic_offset} a
 * number this writer gives it: the current time in microseconds, or one more than the last it gave, whichever is
 * higher. The numbers rise, stay unique across restarts while the writer's clock does not go back, and keep rows apart
 * where the feed's reader orders and finds them by partition and offset. Each commit records the next one, under
 * {@value #SOURCE}, which a restart starts above.
 *
 * <p>Records are taken into memory and the exporter is answered at once; they are committed every
 * {@code commitInterval}, or sooner once {@code maxRows} wait. A commit that fails keeps them for the next; past twice
 * {@code maxRows} waiting, {@link #offer} refuses and the exporter retries. What waits is lost if the writer dies
 * before its commit; it is still in the catalog's log on stdout. A shutdown commits what waits first.
 *
 * <p>The records of the writer's own requests are left out, as {@link TableWriter} leaves them out. {@link #offer} may
 * be called from many threads; {@link #commitIfDue} and {@link #commit} from one.
 */
final class LogWriter {

    /** The source name the commits of rows from the log record their next number under. */
    static final String SOURCE = "otlp:nuna.audit";
    /** The {@code topic_partition} of every row from the log: no topic's. */
    static final int PARTITION = -1;

    private static final Logger LOG = LoggerFactory.getLogger(LogWriter.class);

    private final Supplier<EventsTable> opener;
    private final String principal;
    private final Duration commitInterval;
    private final int maxRows;
    private final Duration dedupWindow;
    private final Clock clock;

    // The committing thread's own.
    private EventsTable table;
    private boolean stale = true;

    // Guarded by this.
    private final List<AuditRow> rows = new ArrayList<>();
    /** The event ids seen, by when they were seen, oldest first. */
    private final LinkedHashMap<String, Instant> seen = new LinkedHashMap<>();
    private long last;
    private Instant waitingSince;

    /**
     * What one offer did with its records.
     *
     * @param written  rows that now wait for a commit
     * @param repeated records seen before, left out
     * @param own      records of the writer's own requests, left out
     * @param rejected records of a type this writer cannot map, left out; the log still holds them
     */
    record Taken(int written, int repeated, int own, int rejected) {
    }

    /** Twice {@code maxRows} wait already: the exporter should retry later. */
    static final class Busy extends RuntimeException {
        Busy(int waiting) {
            super(waiting + " audit records wait for the audit table; retry later");
        }
    }

    /**
     * @param opener      opens (or creates) the table; called on the first commit or reload
     * @param principal   the writer's own principal id, as the catalog records it; its records are left out
     * @param dedupWindow how long an event id is remembered, to leave out a record that arrives again
     */
    LogWriter(Supplier<EventsTable> opener, String principal, Duration commitInterval, int maxRows,
              Duration dedupWindow, Clock clock) {
        this.opener = Objects.requireNonNull(opener, "opener");
        this.principal = Objects.requireNonNull(principal, "principal");
        this.commitInterval = Objects.requireNonNull(commitInterval, "commitInterval");
        this.maxRows = maxRows;
        this.dedupWindow = Objects.requireNonNull(dedupWindow, "dedupWindow");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Takes records the catalog logged into the waiting rows, leaving out those seen before, the writer's own, and any
     * of a type this writer cannot map.
     *
     * @throws Busy if twice {@code maxRows} wait already; nothing is taken
     */
    synchronized Taken offer(List<CatalogEvent> events) {
        if (rows.size() >= 2 * maxRows) {
            throw new Busy(rows.size());
        }
        Instant now = clock.instant();
        forget(now);
        int written = 0;
        int repeated = 0;
        int own = 0;
        int rejected = 0;
        for (CatalogEvent event : events) {
            String id = event.id().toString();
            if (seen.containsKey(id)) {
                repeated++;
                continue;
            }
            long number = Math.max(last + 1, ChronoUnit.MICROS.between(Instant.EPOCH, now));
            AuditRow row;
            try {
                row = AuditRows.of(event, PARTITION, number);
            } catch (IllegalArgumentException e) {
                LOG.error("Audit record {} from the log is of a type this writer cannot map; it is not written to the"
                        + " audit table: {}", id, e.getMessage());
                rejected++;
                continue;
            }
            seen.put(id, now);
            if (principal.equals(row.principal())) {
                own++;
                continue;
            }
            last = number;
            if (rows.isEmpty()) {
                waitingSince = now;
            }
            rows.add(row);
            written++;
        }
        return new Taken(written, repeated, own, rejected);
    }

    private void forget(Instant now) {
        Instant before = now.minus(dedupWindow);
        for (Iterator<Map.Entry<String, Instant>> it = seen.entrySet().iterator(); it.hasNext(); ) {
            if (!it.next().getValue().isBefore(before)) {
                break;
            }
            it.remove();
        }
    }

    /**
     * Reads the table first if a commit failed or none was made yet, then commits the waiting rows when the interval
     * has passed since the first of them arrived, or {@code maxRows} wait.
     */
    void commitIfDue() {
        if (stale) {
            reload();
        }
        boolean due;
        synchronized (this) {
            due = !rows.isEmpty() && (rows.size() >= maxRows
                    || !clock.instant().isBefore(waitingSince.plus(commitInterval)));
        }
        if (due) {
            commit();
        }
    }

    /**
     * Reads the table again if a commit failed or none was made yet, then commits what waits, if anything does. A
     * failure keeps the rows for the next commit.
     */
    void commit() {
        if (stale) {
            reload();
        }
        List<AuditRow> batch;
        long next;
        synchronized (this) {
            if (rows.isEmpty()) {
                return;
            }
            batch = List.copyOf(rows);
            next = last + 1;
        }
        try {
            table.append(batch, Map.of(PARTITION, next));
        } catch (RuntimeException e) {
            stale = true;
            throw e;
        }
        LOG.info("Wrote {} audit records from the log to the audit table", batch.size());
        synchronized (this) {
            // Rows are only added at the end, and only this thread removes them.
            rows.subList(0, batch.size()).clear();
            waitingSince = rows.isEmpty() ? null : clock.instant();
        }
    }

    /**
     * Reads the table's last recorded number and the ids of its recent rows: what waits and is in the table already,
     * from a commit whose answer was lost, is dropped, and those ids count as seen.
     */
    private void reload() {
        if (table == null) {
            table = opener.get();
        }
        long recorded = table.reloadRecorded().map(offsets -> offsets.getOrDefault(PARTITION, 0L)).orElse(0L);
        Instant now = clock.instant();
        Set<String> written = table.eventIdsSince(now.minus(dedupWindow));
        synchronized (this) {
            int before = rows.size();
            rows.removeIf(row -> written.contains(row.eventId()));
            if (rows.size() < before) {
                LOG.info("{} waiting audit records are in the table already; they are not written again",
                        before - rows.size());
            }
            if (rows.isEmpty()) {
                waitingSince = null;
            }
            written.forEach(id -> seen.putIfAbsent(id, now));
            last = Math.max(last, recorded - 1);
        }
        stale = false;
    }

    /** How many rows wait for a commit. */
    synchronized int waiting() {
        return rows.size();
    }
}
