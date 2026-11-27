package nuna.datagen.live;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import nuna.datagen.kit.Kit;
import nuna.datagen.world.Event;
import nuna.datagen.world.Feed;
import nuna.datagen.world.World;
import org.junit.jupiter.api.Test;

class PublisherTest {

    private static final Kit KIT = Kit.load();
    private static final World.Settings SETTINGS = World.Settings.of(11, LocalDate.of(2026, 10, 26), 150);
    private static final int DAYS = 4;

    /** Topics that keep each feed's records in order, and can stop the run after a number of sends. */
    static final class Topics implements Publisher.Topics {
        final Map<Feed, List<String>> records = new EnumMap<>(Feed.class);
        long crashAfter = Long.MAX_VALUE;
        long sends;

        @Override
        public void send(Event event) {
            if (sends++ == crashAfter) {
                throw new Crash();
            }
            records.computeIfAbsent(event.feed(), f -> new ArrayList<>()).add(Encoding.json(event));
        }

        @Override
        public void flush() {
        }

        Map<Feed, Long> endOffsets() {
            Map<Feed, Long> offsets = new EnumMap<>(Feed.class);
            records.forEach((f, list) -> offsets.put(f, (long) list.size()));
            return offsets;
        }
    }

    static final class Crash extends RuntimeException {
    }

    static final class Orders implements Publisher.Orders {
        final Map<Object, Map<String, Object>> rows = new HashMap<>();

        @Override
        public void upsert(Map<String, Object> row) {
            rows.put(row.get("order_id"), row);
        }
    }

    /** SingleStore's tables of the feeds but the orders, each row by its seq. */
    static final class Rows implements Publisher.Rows {
        final Map<Feed, TreeMap<Long, String>> rows = new EnumMap<>(Feed.class);

        @Override
        public void insert(Feed feed, long seq, Map<String, Object> row) {
            String before = rows.computeIfAbsent(feed, f -> new TreeMap<>())
                .put(seq, Encoding.json(new Event(Instant.EPOCH, feed, row)));
            assertThat(before).as(feed + " seq " + seq + " inserted twice").isNull();
        }

        Map<Feed, Long> counts() {
            Map<Feed, Long> counts = new EnumMap<>(Feed.class);
            rows.forEach((f, r) -> counts.put(f, (long) r.size()));
            return counts;
        }
    }

    static final class Lake implements Publisher.Lake {
        final Map<LocalDate, Map<String, Long>> tagged = new LinkedHashMap<>();

        @Override
        public void ensureLocateId() {
        }

        @Override
        public void endOfDay(LocalDate day, Map<String, Long> totals) {
            tagged.put(day, totals);
        }
    }

    private static Publisher both(Topics topics, Orders orders, Rows rows, Lake lake, Map<Feed, Long> viaKafka) {
        return new Publisher(Route.BOTH, topics, orders, rows, lake, at -> {}, viaKafka, rows.counts());
    }

    private static void play(Publisher p, int days) {
        World w = new World(SETTINGS, KIT);
        for (int i = 0; i < days; i++) {
            w.runDay(p);
        }
    }

    @Test
    void aRestartCarriesEachTopicOnFromItsLastRecordAndRewritesNoOrderBackwards() {
        Topics whole = new Topics();
        Orders wholeOrders = new Orders();
        Rows wholeRows = new Rows();
        Lake wholeLake = new Lake();
        play(both(whole, wholeOrders, wholeRows, wholeLake, Map.of()), DAYS);

        // The first run stops partway through the third day, and the last records it sent to the journal-lines topic
        // were never acknowledged, so the topic does not hold them.
        long total = whole.records.values().stream().mapToLong(List::size).sum();
        Topics topics = new Topics();
        topics.crashAfter = total * 5 / 8;
        Orders orders = new Orders();
        Rows rows = new Rows();
        Lake lake = new Lake();
        assertThatThrownBy(() -> play(both(topics, orders, rows, lake, Map.of()), DAYS))
            .isInstanceOf(Crash.class);
        List<String> lines = topics.records.get(Feed.JOURNAL_LINES);
        lines.subList(lines.size() - 3, lines.size()).clear();
        assertThat(lake.tagged).hasSizeLessThan(DAYS);
        int taggedBefore = lake.tagged.size();

        topics.crashAfter = Long.MAX_VALUE;
        Publisher resumed = both(topics, orders, rows, lake, topics.endOffsets());
        assertThat(resumed.caughtUp()).isFalse();
        play(resumed, DAYS);

        assertThat(resumed.caughtUp()).isTrue();
        assertThat(topics.records).isEqualTo(whole.records);
        // The order management system's feeds carried on in SingleStore from their last row, each row written once.
        assertThat(rows.rows).isEqualTo(wholeRows.rows).containsKeys(Feed.ORDER_EVENTS, Feed.ROUTES, Feed.EXECUTIONS);
        assertThat(orders.rows).isEqualTo(wholeOrders.rows);
        // The days the first run finished are not tagged again; the rest are, with the totals of an unbroken run.
        assertThat(lake.tagged).isEqualTo(wholeLake.tagged);
        assertThat(taggedBefore).isPositive();
    }

    @Test
    void aRunTagsEachDayTheTablesThatChangedThatDay() {
        Topics topics = new Topics();
        Lake lake = new Lake();
        play(both(topics, new Orders(), new Rows(), lake, Map.of()), 2);
        List<Map<String, Long>> days = new ArrayList<>(lake.tagged.values());
        // Reference data goes out on the first day only; the orders are SingleStore's and its change feed's, never
        // tagged, but their events, routes and executions are numbered, so tagged, in brokerage.trading.
        assertThat(days.get(0)).containsKeys(table(Feed.CALENDAR), table(Feed.SECURITIES), table(Feed.ACCOUNTS),
            table(Feed.JOURNAL_LINES), "brokerage.trading.order_events", "brokerage.trading.executions",
            table(Feed.STOCK_RECORD), table(Feed.CNS_OBLIGATIONS)).doesNotContainKeys("brokerage.trading.orders");
        assertThat(days.get(1)).doesNotContainKeys(table(Feed.CALENDAR), table(Feed.SECURITIES), table(Feed.ACCOUNTS))
            .containsKeys(table(Feed.JOURNAL_LINES), table(Feed.BALANCES_EOD), table(Feed.BARS_5M));
        assertThat(days.get(1).get(table(Feed.JOURNAL_LINES)))
            .isEqualTo((long) topics.records.get(Feed.JOURNAL_LINES).size());
    }

    private static String table(Feed f) {
        return f.lakeTable(Feed.Path.KAFKA);
    }

    /** The records an unbroken run on route both sends, by feed, each feed's SingleStore rows, and its orders. */
    private static Topics unbroken(Orders orders, Rows rows) {
        Topics whole = new Topics();
        play(both(whole, orders, rows, new Lake(), Map.of()), DAYS);
        return whole;
    }

    /** A feed's records as an unbroken run on route both sends them: through its topic, or into its SingleStore table. */
    private static List<String> expected(Feed f, Topics whole, Rows wholeRows) {
        return f.path == Feed.Path.KAFKA ? whole.records.get(f) : new ArrayList<>(wholeRows.rows.get(f).values());
    }

    @Test
    void withSingleStoreOffTheOrderManagementSystemsFeedsGoToTheirTopicsAndTheOrdersNowhere() {
        Orders wholeOrders = new Orders();
        Rows wholeRows = new Rows();
        Topics whole = unbroken(wholeOrders, wholeRows);
        Topics topics = new Topics();
        Lake lake = new Lake();
        play(new Publisher(Route.KAFKA, topics, null, null, lake, at -> {}, Map.of(), Map.of()), DAYS);

        assertThat(topics.records).doesNotContainKey(Feed.ORDERS);
        for (Feed f : Feed.numbered()) {
            assertThat(topics.records.get(f)).as(f.name()).isEqualTo(expected(f, whole, wholeRows));
        }
        List<String> events = topics.records.get(Feed.ORDER_EVENTS);
        assertThat(events).hasSizeGreaterThan(wholeOrders.rows.size())
            .allSatisfy(e -> assertThat(e).endsWith("\"lake_table\":\"brokerage.kafka.order_events\"}"));
        long last = lake.tagged.values().stream().reduce((a, b) -> b).orElseThrow().get("brokerage.kafka.order_events");
        assertThat(last).isEqualTo(events.size());
        assertThat(lake.tagged.values()).allSatisfy(day -> assertThat(day).doesNotContainKey("brokerage.trading.orders"));
    }

    @Test
    void withKafkaOffEachFeedGoesToItsSingleStoreTableNumberedAndTheRouteCanChangeBetweenRuns() {
        Orders wholeOrders = new Orders();
        Rows wholeRows = new Rows();
        Topics whole = unbroken(wholeOrders, wholeRows);
        long total = whole.records.values().stream().mapToLong(List::size).sum();

        // Through both until partway through the second day; then Kafka is switched off, its records drained into the
        // lake (so the lake counts them), and the world resumes through SingleStore alone. SingleStore's tables keep
        // their rows throughout.
        Topics topics = new Topics();
        topics.crashAfter = total * 3 / 8;
        Orders orders = new Orders();
        Rows rows = new Rows();
        Lake lake = new Lake();
        assertThatThrownBy(() -> play(both(topics, orders, rows, lake, Map.of()), DAYS)).isInstanceOf(Crash.class);
        Map<Feed, Long> drained = topics.endOffsets();
        Publisher viaSingleStore = new Publisher(Route.SINGLESTORE, null, orders, rows, lake, at -> {}, drained,
            rows.counts());
        play(viaSingleStore, 3);
        assertThat(viaSingleStore.caughtUp()).isTrue();

        // Then Kafka comes back, the SingleStore tables drain into their copies, and the world carries on through both.
        Publisher back = new Publisher(Route.BOTH, topics, orders, rows, lake, at -> {}, topics.endOffsets(),
            rows.counts());
        topics.crashAfter = Long.MAX_VALUE;
        play(back, DAYS);

        // Each feed's records, the ones through Kafka followed by the ones through SingleStore in seq order and then
        // the ones through Kafka again, are an unbroken run's.
        for (Feed f : Feed.numbered()) {
            List<String> before = topics.records.getOrDefault(f, List.of());
            long first = drained.getOrDefault(f, 0L);
            List<String> joined = new ArrayList<>(before.subList(0, (int) first));
            TreeMap<Long, String> diverted = rows.rows.getOrDefault(f, new TreeMap<>());
            if (!diverted.isEmpty()) {
                assertThat(diverted.firstKey()).as(f + " first seq").isEqualTo(first + 1);
                assertThat(diverted.lastKey()).as(f + " last seq").isEqualTo(first + diverted.size());
            }
            joined.addAll(diverted.values());
            joined.addAll(before.subList((int) first, before.size()));
            assertThat(joined).as(f.name()).isEqualTo(expected(f, whole, wholeRows));
        }
        assertThat(orders.rows).isEqualTo(wholeOrders.rows);
        // The days through SingleStore tagged the copies, with each copy's rows.
        assertThat(lake.tagged.values()).anySatisfy(day -> assertThat(day)
            .containsEntry("brokerage.singlestore.journal_lines", (long) rows.rows.get(Feed.JOURNAL_LINES).size()));
    }

    @Test
    void recordsCarryDecimalsAsStringsDatesAsIsoInstantsAsMillisAndTheirTable() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("amount", new BigDecimal("1250.10"));
        row.put("business_date", LocalDate.of(2026, 11, 20));
        row.put("event_time", Instant.parse("2026-11-20T14:30:00.123Z"));
        // In order: Map.of's iteration order changes from one JVM run to the next.
        Map<String, Object> lot = new LinkedHashMap<>();
        lot.put("lot", 7L);
        lot.put("shares", new BigDecimal("1.50000"));
        row.put("lots", List.of(lot));
        row.put("security_id", null);
        assertThat(Encoding.json(new Event(Instant.EPOCH, Feed.FINANCIAL_TRANSACTIONS, row))).isEqualTo(
            "{\"amount\":\"1250.10\",\"business_date\":\"2026-11-20\",\"event_time\":"
                + Instant.parse("2026-11-20T14:30:00.123Z").toEpochMilli() + ","
                + "\"lots\":[{\"lot\":7,\"shares\":\"1.50000\"}],\"security_id\":null,"
                + "\"lake_table\":\"brokerage.ledger.financial_transactions\"}");
    }
}
