package nuna.datagen.world;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.IntStream;
import nuna.datagen.kit.Engine.BalanceKey;
import nuna.datagen.kit.Kit;
import org.junit.jupiter.api.Test;

class WorldTest {

    private static final Kit KIT = Kit.load();

    /** Counts what a world sends and hashes it in order. */
    static final class Recorder implements Sink {
        final Map<Feed, Integer> counts = new EnumMap<>(Feed.class);
        final Map<String, Integer> reasons = new HashMap<>();
        final Map<Long, String> lastOrderStatus = new HashMap<>();
        /** Financial transactions posted, by template. */
        final Map<Integer, Integer> templates = new TreeMap<>();
        final MessageDigest digest;
        World world;
        int days;

        Recorder() throws Exception {
            digest = MessageDigest.getInstance("SHA-256");
        }

        @Override
        public void accept(Event e) {
            // Every event has its time, which the live pacer waits for; an announcement made while the day is planned
            // had none on the first day, or the day before's (item 318).
            assertThat(e.at()).as(e.feed() + " " + e.row()).isNotNull();
            if (e.feed() == Feed.CORPORATE_ACTIONS) {
                assertThat(e.at().atZone(java.time.ZoneId.of("America/New_York")).toLocalDate())
                    .as("announced on its business date: " + e.row()).isEqualTo(e.row().get("business_date"));
            }
            counts.merge(e.feed(), 1, Integer::sum);
            digest.update((e.at() + " " + e.feed() + " " + e.row() + "\n").getBytes(StandardCharsets.UTF_8));
            if (e.feed() == Feed.POSTING_REJECTIONS) {
                reasons.merge((String) e.row().get("reason"), 1, Integer::sum);
            }
            if (e.feed() == Feed.FINANCIAL_TRANSACTIONS) {
                templates.merge((Integer) e.row().get("template_id"), 1, Integer::sum);
            }
            if (e.feed() == Feed.ORDERS) {
                lastOrderStatus.put((Long) e.row().get("order_id"), (String) e.row().get("status"));
            }
        }

        @Override
        public void endOfDay(LocalDate d) {
            days++;
            assertThat(lastOrderStatus.values()).as("no order open after the close of " + d)
                .allMatch(s -> s.equals("filled") || s.contains("expired") || s.equals("trade_busted"));
        }

        String hash() {
            return HexFormat.of().formatHex(digest.digest());
        }
    }

    private static Recorder play(long seed, int accounts, int days) throws Exception {
        World w = new World(World.Settings.of(seed, LocalDate.of(2026, 10, 1), accounts), KIT);
        Recorder r = new Recorder();
        r.world = w;
        for (int i = 0; i < days; i++) {
            w.runDay(r);
        }
        assertThat(w.unexpectedRejections()).isZero();
        return r;
    }

    @Test
    void theSameSeedGivesTheSameEventsAndAnotherSeedOthers() throws Exception {
        String a = play(7, 150, 4).hash(), b = play(7, 150, 4).hash(), c = play(8, 150, 4).hash();
        assertThat(a).isEqualTo(b).isNotEqualTo(c);
    }

    @Test
    void aThousandAccountMonthPostsWithNoHaltAndNoRejectionButTheSeededOne() throws Exception {
        Recorder r = play(20261001, 1000, 22);
        assertThat(r.days).isEqualTo(22);
        assertThat(r.reasons).isEqualTo(Map.of("BalanceBelowZero", 1));
        assertThat(r.counts.get(Feed.FINANCIAL_TRANSACTIONS)).isBetween(20_000, 60_000);
        assertThat(r.counts.get(Feed.JOURNAL_LINES)).isGreaterThan(5 * r.counts.get(Feed.FINANCIAL_TRANSACTIONS));
        assertThat(r.counts.get(Feed.BARS_5M)).isEqualTo(22 * Market.BARS * 300);
        assertThat(r.counts.get(Feed.PRICES_EOD)).isEqualTo(22 * 300);
        assertThat(r.counts.get(Feed.ACCOUNTS)).isEqualTo(1000);
        assertThat(r.counts.get(Feed.SECURITIES)).isEqualTo(300);
        assertThat(r.counts.get(Feed.BOOKING_SCHEMES)).isEqualTo(45);
        assertThat(r.counts).containsKeys(Feed.ORDERS, Feed.BALANCES_EOD, Feed.CALENDAR, Feed.CHART_OF_ACCOUNTS);
        // Item 155: every template of the demo kit's brokerage events posts in the month.
        assertThat(r.templates.keySet()).as("templates posted: " + r.templates)
            .containsAll(IntStream.rangeClosed(70, 91).boxed().toList());
        // And every brokerage account's books still balance, each sub-ledger in its own measure.
        r.world.engine().snapshot().forEach((ba, balances) -> {
            Map<Integer, BigDecimal> perSubLedger = new TreeMap<>();
            balances.forEach((BalanceKey k, BigDecimal v) ->
                perSubLedger.merge(KIT.definition(k.definition()).root(), v, BigDecimal::add));
            perSubLedger.forEach((root, sum) -> assertThat(sum.signum()).as(ba + " sub-ledger " + root).isZero());
        });
    }

    @Test
    void theCalendarKeepsTheExchangeHolidaysOf2026() {
        MarketCalendar c = new MarketCalendar();
        for (String d : List.of("2026-01-01", "2026-01-19", "2026-02-16", "2026-04-03", "2026-05-25", "2026-06-19",
            "2026-07-03", "2026-09-07", "2026-11-26", "2026-12-25")) {
            assertThat(c.isBusinessDay(LocalDate.parse(d))).as(d).isFalse();
        }
        assertThat(c.settlement(LocalDate.parse("2026-11-25"))).isEqualTo(LocalDate.parse("2026-11-27"));
        assertThat(c.isMonthEnd(LocalDate.parse("2026-10-30"))).isTrue();
    }

    @Test
    void checkDigitsFollowCusipAndIsinRulesAndIssuersSitInThe990To999Range() {
        // The algorithms on a published identifier (037833100, US0378331005); the world never emits it.
        assertThat(Identifiers.cusipCheck("03783310")).isZero();
        assertThat(Identifiers.isin("037833100")).isEqualTo("US0378331005");
        assertThat(Identifiers.cusip(0)).startsWith("990000");
        assertThat(Identifiers.cusip(9999)).startsWith("999999");
    }
}
