package nuna.datagen.live;

import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import nuna.datagen.world.Event;
import nuna.datagen.world.Feed;
import nuna.datagen.world.LakeSchemas;
import nuna.datagen.world.Sink;

/**
 * Sends the world's events where they go: each Kafka feed's to its topic, the orders to SingleStore. At each end of day
 * it has the lake tag every table that changed that day.
 *
 * <p>A restart resumes where the last run stopped. The world is replayed from its first day; the records already in
 * each topic (its end offset, since each topic has one partition and the producer is idempotent) are skipped, feed by
 * feed, so each topic carries on from its last record. Orders are skipped until every topic has caught up: each order
 * before the last record any topic holds was written to SingleStore before that record was sent. Orders after it are
 * written again, which an upsert makes harmless.
 */
public final class Publisher implements Sink {

    /** The Kafka side. */
    public interface Topics {
        void send(Event event);

        /** Returns once every record sent has been acknowledged. */
        void flush();
    }

    /** SingleStore's orders table. */
    public interface Orders {
        void upsert(Map<String, Object> row);
    }

    /** The Iceberg side. */
    public interface Lake {
        /** Adds financial_transactions' locate_id column if it is missing, and waits until the sink writes with it. */
        void ensureLocateId();

        /** Tags each table {@code eod-<day>} once it holds its feed's total, where it is not tagged already. */
        void endOfDay(LocalDate day, Map<Feed, Long> totals);
    }

    /** The wall clock the events wait for. */
    public interface Clock {
        void await(Instant businessTime) throws InterruptedException;
    }

    private final Topics topics;
    private final Orders orders;
    private final Lake lake;
    private final Clock clock;
    private final Map<Feed, Long> alreadySent;
    private final Map<Feed, Long> seen = new EnumMap<>(Feed.class);
    private final Set<Feed> changedToday = EnumSet.noneOf(Feed.class);
    private int behind;
    private boolean locateId;
    private long sent;

    /** @param alreadySent the records each topic holds from an earlier run */
    public Publisher(Topics topics, Orders orders, Lake lake, Clock clock, Map<Feed, Long> alreadySent) {
        this.topics = topics;
        this.orders = orders;
        this.lake = lake;
        this.clock = clock;
        this.alreadySent = new EnumMap<>(Feed.class);
        alreadySent.forEach((feed, n) -> {
            if (n > 0) {
                this.alreadySent.put(feed, n);
                behind++;
            }
        });
    }

    /** Whether the replay has passed every record an earlier run sent. */
    public boolean caughtUp() {
        return behind == 0;
    }

    /** Events sent or written by this run. */
    public long sent() {
        return sent;
    }

    @Override
    public void accept(Event e) {
        Feed feed = e.feed();
        if (feed.path == Feed.Path.KAFKA) {
            changedToday.add(feed);
            long n = seen.merge(feed, 1L, Long::sum);
            Long before = alreadySent.get(feed);
            if (before != null && n <= before) {
                if (n == before) {
                    behind--;
                }
                return;
            }
            if (feed == Feed.FINANCIAL_TRANSACTIONS && !locateId && e.row().get(LakeSchemas.LOCATE_ID) != null) {
                lake.ensureLocateId();
                locateId = true;
            }
            pace(e);
            topics.send(e);
        } else {
            if (behind > 0) {
                return;
            }
            pace(e);
            orders.upsert(e.row());
        }
        sent++;
    }

    private void pace(Event e) {
        try {
            clock.await(e.at());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", interrupted);
        }
    }

    @Override
    public void endOfDay(LocalDate day) {
        if (behind == 0) {
            topics.flush();
            Map<Feed, Long> totals = new EnumMap<>(Feed.class);
            for (Feed f : changedToday) {
                totals.put(f, seen.get(f));
            }
            lake.endOfDay(day, totals);
        }
        changedToday.clear();
    }
}
