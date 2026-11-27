package nuna.datagen.live;

import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import nuna.datagen.world.Event;
import nuna.datagen.world.Feed;
import nuna.datagen.world.LakeSchemas;
import nuna.datagen.world.MonthEnd;
import nuna.datagen.world.Sink;

/**
 * Sends the world's events the way its route takes each feed ({@link Route}): through Kafka, to the feed's topic;
 * through SingleStore, each numbered feed to its own table, each row numbered by its place in the feed, and the orders
 * upserted into {@code brokerage.orders}. At each end of day it has the lake tag every table that changed that day.
 *
 * <p>A restart resumes where the last run stopped, whichever way each record went. The world is replayed from its first
 * day, and each feed's first records are skipped: as many as went through Kafka (its topic's records, or the lake's
 * table when Kafka is off) plus as many as went through SingleStore (its table's rows, or its copy's when SingleStore is
 * off). A feed's records always go out in order, so those it has sent are always its first; a feed new to the world
 * has sent none, and sends its records from the world's first day. Orders are skipped until every feed has caught up:
 * each order before the last record any feed holds was written before that record was sent. Orders after it are
 * written again, which an upsert makes harmless. With SingleStore off they go nowhere: their every change is an order
 * event, which goes through Kafka.
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

    /** SingleStore's table of each other feed. */
    public interface Rows {
        /** @param seq the record's place in its feed, from 1 */
        void insert(Feed feed, long seq, Map<String, Object> row);
    }

    /** The Iceberg side. */
    public interface Lake {
        /** Adds financial_transactions' locate_id column if it is missing, and waits until the sink writes with it. */
        void ensureLocateId();

        /** Tags each table {@code eod-<day>} once it holds its total, where it is not tagged already. */
        void endOfDay(LocalDate day, Map<String, Long> totals);

        /** Writes a month-end close and tags it (item 153), unless it is done already. */
        default void monthEnd(MonthEnd close) {}

        /** Restates a closed month on a branch merged into main (item 153), unless it is done already. */
        default void restated(MonthEnd.Restatement restatement) {}
    }

    /** The wall clock the events wait for. */
    public interface Clock {
        void await(Instant businessTime) throws InterruptedException;
    }

    private final Route route;
    private final Topics topics;
    private final Orders orders;
    private final Rows rows;
    private final Lake lake;
    private final Clock clock;
    private final Map<Feed, Long> alreadySent = new EnumMap<>(Feed.class);
    private final Map<Feed, Long> seen = new EnumMap<>(Feed.class);
    /** Each lake table's records once every record sent so far has landed, by table. */
    private final Map<String, Long> totals = new LinkedHashMap<>();
    private final Set<String> changedToday = new LinkedHashSet<>();
    private int behind;
    private boolean locateId;
    private long sent;

    /**
     * @param topics         the Kafka side, or null on route {@code singlestore}
     * @param orders         SingleStore's orders, or null on route {@code kafka}
     * @param rows           SingleStore's other tables, or null on route {@code kafka}
     * @param viaKafka       the records each numbered feed sent through Kafka in earlier runs
     * @param viaSingleStore the records each numbered feed sent through SingleStore in earlier runs
     */
    public Publisher(Route route, Topics topics, Orders orders, Rows rows, Lake lake, Clock clock,
            Map<Feed, Long> viaKafka, Map<Feed, Long> viaSingleStore) {
        this.route = route;
        this.topics = topics;
        this.orders = orders;
        this.rows = rows;
        this.lake = lake;
        this.clock = clock;
        for (Feed f : Feed.numbered()) {
            long kafka = viaKafka.getOrDefault(f, 0L);
            totals.put(f.lakeTable(Feed.Path.KAFKA), kafka);
            long singlestore = viaSingleStore.getOrDefault(f, 0L);
            totals.put(f.lakeTable(Feed.Path.SINGLESTORE), singlestore);
            if (kafka + singlestore > 0) {
                alreadySent.put(f, kafka + singlestore);
                behind++;
            }
        }
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
        Feed.Path via = route.pathOf(feed);
        if (feed == Feed.ORDERS) {
            if (behind > 0 || via != Feed.Path.SINGLESTORE) {
                return;
            }
            pace(e);
            // The change feed's copy holds each order once, at its latest state: not a count to tag by.
            orders.upsert(e.row());
            sent++;
            return;
        }
        String table = feed.lakeTable(via);
        long n = seen.merge(feed, 1L, Long::sum);
        Long before = alreadySent.get(feed);
        if (before != null && n <= before) {
            // Sent by an earlier run, one way or the other: whichever of its tables holds records changed today.
            for (Feed.Path p : Feed.Path.values()) {
                if (totals.get(feed.lakeTable(p)) > 0) {
                    changedToday.add(feed.lakeTable(p));
                }
            }
            if (n == before) {
                behind--;
            }
            return;
        }
        pace(e);
        if (via == Feed.Path.KAFKA) {
            if (feed == Feed.FINANCIAL_TRANSACTIONS && !locateId && e.row().get(LakeSchemas.LOCATE_ID) != null) {
                lake.ensureLocateId();
                locateId = true;
            }
            topics.send(e);
        } else {
            rows.insert(feed, n, e.row());
        }
        totals.merge(table, 1L, Long::sum);
        changedToday.add(table);
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
            if (topics != null) {
                topics.flush();
            }
            Map<String, Long> today = new LinkedHashMap<>();
            for (String t : changedToday) {
                today.put(t, totals.get(t));
            }
            lake.endOfDay(day, today);
        }
        changedToday.clear();
    }

    @Override
    public void monthEnd(MonthEnd close) {
        lake.monthEnd(close);
    }

    @Override
    public void restated(MonthEnd.Restatement restatement) {
        try {
            clock.await(restatement.at());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", interrupted);
        }
        lake.restated(restatement);
    }
}
