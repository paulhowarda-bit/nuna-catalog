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

    static final class Lake implements Publisher.Lake {
        final Map<LocalDate, Map<Feed, Long>> tagged = new LinkedHashMap<>();

        @Override
        public void ensureLocateId() {
        }

        @Override
        public void endOfDay(LocalDate day, Map<Feed, Long> totals) {
            tagged.put(day, totals);
        }
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
        Lake wholeLake = new Lake();
        play(new Publisher(whole, wholeOrders, wholeLake, at -> {}, Map.of()), DAYS);

        // The first run stops partway through the third day, and the last records it sent to the journal-lines topic
        // were never acknowledged, so the topic does not hold them.
        long total = whole.records.values().stream().mapToLong(List::size).sum();
        Topics topics = new Topics();
        topics.crashAfter = total * 5 / 8;
        Orders orders = new Orders();
        Lake lake = new Lake();
        assertThatThrownBy(() -> play(new Publisher(topics, orders, lake, at -> {}, Map.of()), DAYS))
            .isInstanceOf(Crash.class);
        List<String> lines = topics.records.get(Feed.JOURNAL_LINES);
        lines.subList(lines.size() - 3, lines.size()).clear();
        assertThat(lake.tagged).hasSizeLessThan(DAYS);
        int taggedBefore = lake.tagged.size();

        topics.crashAfter = Long.MAX_VALUE;
        Publisher resumed = new Publisher(topics, orders, lake, at -> {}, topics.endOffsets());
        assertThat(resumed.caughtUp()).isFalse();
        play(resumed, DAYS);

        assertThat(resumed.caughtUp()).isTrue();
        assertThat(topics.records).isEqualTo(whole.records);
        assertThat(orders.rows).isEqualTo(wholeOrders.rows);
        // The days the first run finished are not tagged again; the rest are, with the totals of an unbroken run.
        assertThat(lake.tagged).isEqualTo(wholeLake.tagged);
        assertThat(taggedBefore).isPositive();
    }

    @Test
    void aRunTagsEachDayTheTablesThatChangedThatDay() {
        Topics topics = new Topics();
        Lake lake = new Lake();
        play(new Publisher(topics, new Orders(), lake, at -> {}, Map.of()), 2);
        List<Map<Feed, Long>> days = new ArrayList<>(lake.tagged.values());
        // Reference data goes out on the first day only; orders are SingleStore's and the feed's, never tagged here.
        assertThat(days.get(0)).containsKeys(Feed.CALENDAR, Feed.SECURITIES, Feed.ACCOUNTS, Feed.JOURNAL_LINES)
            .doesNotContainKey(Feed.ORDERS);
        assertThat(days.get(1)).doesNotContainKeys(Feed.CALENDAR, Feed.SECURITIES, Feed.ACCOUNTS)
            .containsKeys(Feed.JOURNAL_LINES, Feed.BALANCES_EOD, Feed.BARS_5M);
        assertThat(days.get(1).get(Feed.JOURNAL_LINES)).isEqualTo((long) topics.records.get(Feed.JOURNAL_LINES).size());
    }

    @Test
    void recordsCarryDecimalsAsStringsDatesAsIsoInstantsAsMillisAndTheirTable() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("amount", new BigDecimal("1250.10"));
        row.put("business_date", LocalDate.of(2026, 11, 20));
        row.put("event_time", Instant.parse("2026-11-20T14:30:00.123Z"));
        row.put("lots", List.of(Map.of("lot", 7L, "shares", new BigDecimal("1.50000"))));
        row.put("security_id", null);
        assertThat(Encoding.json(new Event(Instant.EPOCH, Feed.FINANCIAL_TRANSACTIONS, row))).isEqualTo(
            "{\"amount\":\"1250.10\",\"business_date\":\"2026-11-20\",\"event_time\":"
                + Instant.parse("2026-11-20T14:30:00.123Z").toEpochMilli() + ","
                + "\"lots\":[{\"lot\":7,\"shares\":\"1.50000\"}],\"security_id\":null,"
                + "\"lake_table\":\"brokerage.ledger.financial_transactions\"}");
    }
}
