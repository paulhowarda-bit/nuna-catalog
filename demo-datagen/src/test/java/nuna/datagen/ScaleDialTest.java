package nuna.datagen;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import nuna.datagen.kit.Kit;
import nuna.datagen.world.Event;
import nuna.datagen.world.Feed;
import nuna.datagen.world.Scale;
import nuna.datagen.world.Sink;
import nuna.datagen.world.World;
import org.junit.jupiter.api.Test;

/**
 * The scale dial (item 321; Paul, 2026-11-27): at scale n the firm grows toward n times its accounts through the
 * onboarding flows and its customers do n times as much; turned down, onboarding stops and the rates fall, and no
 * account goes. The history is kept, so a world replays the same.
 */
class ScaleDialTest {

    private static final Kit KIT = Kit.load();
    private static final LocalDate START = LocalDate.of(2026, 10, 5);
    private static final Instant FIRST = START.minusDays(3).atTime(20, 30).atZone(World.NEW_YORK).toInstant();

    /** What a world sent each business day, by feed. */
    private static final class Days implements Sink {
        final List<Map<Feed, Integer>> days = new ArrayList<>();
        Map<Feed, Integer> today = new EnumMap<>(Feed.class);
        final StringBuilder all = new StringBuilder();

        @Override
        public void accept(Event e) {
            today.merge(e.feed(), 1, Integer::sum);
            all.append(e.at()).append(e.feed()).append(e.row().hashCode()).append('\n');
        }

        @Override
        public void endOfDay(LocalDate d) {
            days.add(today);
            today = new EnumMap<>(Feed.class);
        }

        int on(int day, Feed f) {
            return days.get(day).getOrDefault(f, 0);
        }

        int accounts(int through) {
            int n = 0;
            for (int i = 0; i <= through; i++) {
                n += on(i, Feed.ACCOUNTS);
            }
            return n;
        }
    }

    private static Days play(Scale scale, int days, World[] out) {
        World w = new World(World.Settings.of(41, START, 150), KIT).withScale(scale);
        Days d = new Days();
        for (int i = 0; i < days; i++) {
            w.runDay(d);
        }
        assertThat(w.unexpectedRejections()).isZero();
        if (out != null) {
            out[0] = w;
        }
        return d;
    }

    @Test
    void theHistoryIsTheScaleFromEachInstantAndKeepsAsText() {
        Instant t1 = Instant.parse("2026-11-27T15:00:00Z"), t2 = Instant.parse("2026-11-28T02:00:00Z");
        Scale s = Scale.ONE.with(t1, 5).with(t2, 2);
        assertThat(s.at(t1.minusSeconds(1))).isEqualTo(1);
        assertThat(s.at(t1)).isEqualTo(5);
        assertThat(s.at(t2)).isEqualTo(2);
        assertThat(Scale.parse(s.toString())).isEqualTo(s);
        assertThat(Scale.parse(null)).isEqualTo(Scale.ONE).hasToString("");
        // A change at an instant replaces any change recorded from it on; setting the scale it has records nothing.
        assertThat(s.with(t1, 3).at(t2)).isEqualTo(3);
        assertThat(s.with(t2, 5)).isEqualTo(Scale.ONE.with(t1, 5));
        assertThatThrownBy(() -> s.with(t2, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> s.with(t2, Scale.MAX + 1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theGeneratorTakesTheDialFromTheNextWholeHour() {
        Instant now = Instant.parse("2026-11-27T15:42:10Z");
        Scale kept = Scale.ONE.with(Instant.parse("2026-11-27T01:00:00Z"), 4);
        assertThat(Main.scale(kept, k -> null, now)).isSameAs(kept);
        Scale s = Main.scale(kept, Map.of("BROKERAGE_SCALE", "10")::get, now);
        assertThat(s.at(Instant.parse("2026-11-27T15:59:59Z"))).isEqualTo(4);
        assertThat(s.at(Instant.parse("2026-11-27T16:00:00Z"))).isEqualTo(10);
        // The same setting again, as the pod restarts with it, changes nothing.
        assertThat(Main.scale(s, Map.of("BROKERAGE_SCALE", "10")::get, now.plusSeconds(7200))).isEqualTo(s);
    }

    @Test
    void atAHigherScaleTheCustomersDoProportionallyMoreAndTheFirmGrows() {
        World[] w = new World[1];
        Days one = play(Scale.ONE, 6, null);
        Days four = play(Scale.ONE.with(FIRST, 4), 6, w);
        for (Feed f : List.of(Feed.CLICK_EVENTS, Feed.SESSIONS, Feed.ORDERS, Feed.EXECUTIONS)) {
            double ratio = (double) four.on(5, f) / one.on(5, f);
            assertThat(ratio).as(f + " on the sixth day, scale 4 to scale 1").isBetween(2.7, 5.3);
        }
        // Payments more than that: each new customer's first deposit besides.
        assertThat((double) four.on(5, Feed.PAYMENTS) / one.on(5, Feed.PAYMENTS)).isGreaterThan(3.0);
        assertThat(one.accounts(5)).isEqualTo(150);
        // Onboarding a share of the gap each day: more accounts every day, toward 600 and never past it.
        for (int d = 1; d < 6; d++) {
            assertThat(four.accounts(d)).as("accounts after day " + (d + 1)).isGreaterThan(four.accounts(d - 1));
        }
        assertThat(four.accounts(5)).isBetween(300, 600);
        assertThat(w[0].accounts()).hasSize(four.accounts(5));
        balanced(w[0]);
    }

    @Test
    void turnedDownTheFirmStopsOnboardingKeepsItsAccountsAndItsBooksBalance() {
        Instant down = START.plusDays(1).atTime(12, 0).atZone(World.NEW_YORK).toInstant();
        World[] w = new World[1];
        Scale dial = Scale.ONE.with(FIRST, 5).with(down, 2);
        Days d = play(dial, 6, w);
        Days again = play(dial, 6, null);
        assertThat(again.all.toString()).as("the same history replays the same").isEqualTo(d.all.toString());
        int grown = d.accounts(2);
        assertThat(grown).isGreaterThan(150);
        // Above 2 x 150 already, the firm onboards no one more and deletes no one.
        if (grown >= 300) {
            assertThat(d.accounts(5)).isEqualTo(grown);
        }
        assertThat(w[0].accounts()).hasSize(d.accounts(5));
        assertThat(d.on(5, Feed.CLICK_EVENTS)).isLessThan(d.on(0, Feed.CLICK_EVENTS));
        balanced(w[0]);
    }

    /** Every brokerage account's books balance, each sub-ledger in its own measure. */
    private static void balanced(World w) {
        w.engine().snapshot().forEach((ba, balances) -> {
            Map<Integer, BigDecimal> perSubLedger = new TreeMap<>();
            balances.forEach((k, v) -> perSubLedger.merge(KIT.definition(k.definition()).root(), v, BigDecimal::add));
            perSubLedger.forEach((root, sum) -> assertThat(sum.signum()).as(ba + " sub-ledger " + root).isZero());
        });
    }
}
