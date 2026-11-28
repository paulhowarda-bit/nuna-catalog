package nuna.datagen.world;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import nuna.datagen.kit.Kit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The streams (item 154) at the demo's default scale, 1,000 accounts and 300 securities: the market's quotes through
 * the regular session and, thinner and wider, the others (item 321), and the digital channels' sessions and click
 * events at every hour, at the rates the design gives and in the shape their tables hold (LakeSchemasTest checks every
 * value fits its column).
 */
class StreamsTest {

    private static final int DAYS = 3;
    private static final Map<Feed, List<Event>> sent = new EnumMap<>(Feed.class);
    private static final List<Feed> order = new ArrayList<>();
    private static double expectedQuotes;
    /** The customer activity the days played held, in business days ({@link Activity}). */
    private static double activity;

    @BeforeAll
    static void play() {
        World w = new World(World.Settings.of(20261122, LocalDate.of(2026, 11, 2), 1000), Kit.load());
        expectedQuotes = new Quotes(w.market(), new Rng(1), (at, feed, row) -> { }).expectedPerDay();
        Sink sink = new Sink() {
            @Override
            public void accept(Event e) {
                if (e.feed().streaming()) {
                    sent.computeIfAbsent(e.feed(), k -> new ArrayList<>()).add(e);
                    order.add(e.feed());
                }
            }
        };
        MarketCalendar calendar = w.calendar();
        for (int i = 0; i < DAYS; i++) {
            LocalDate d = w.day();
            Instant from = calendar.previous(d).atTime(World.DAY_ENDS).atZone(World.NEW_YORK).toInstant();
            Instant to = d.atTime(World.DAY_ENDS).atZone(World.NEW_YORK).toInstant();
            for (Instant t = from; t.isBefore(to); t = t.plusSeconds(World.SLOT_SECONDS)) {
                activity += Activity.customer(calendar, t, t.plusSeconds(World.SLOT_SECONDS));
            }
            w.runDay(sink);
        }
    }

    @Test
    void quotesComeThroughTheSessionAtAboutThirtySevenThousandADayEachStraddlingItsPrice() {
        List<Event> quotes = sent.get(Feed.QUOTES);
        assertThat(expectedQuotes).isBetween(35_000.0, 40_000.0);
        List<Event> regular = quotes.stream().filter(e -> Session.at((LocalDate) e.row().get("business_date"),
            e.at()) == Session.REGULAR).toList();
        assertThat(regular.size() / (double) DAYS).isBetween(expectedQuotes * 0.97, expectedQuotes * 1.03);
        // Outside the regular session (item 321): the most popular securities only, far fewer quotes, and wider.
        int thin = Market.thinCount(300);
        assertThat(quotes.size() - regular.size()).isBetween(regular.size() / 20, regular.size() / 3);
        Map<Session, BigDecimal> widest = new EnumMap<>(Session.class);
        long id = 0;
        Instant last = Instant.MIN;
        Set<Object> quoted = new HashSet<>();
        for (Event e : quotes) {
            Map<String, Object> q = e.row();
            assertThat((Long) q.get("quote_id")).isEqualTo(++id);
            assertThat(e.at()).isEqualTo(q.get("quoted_at")).isAfterOrEqualTo(last);
            last = e.at();
            Session session = Session.at((LocalDate) q.get("business_date"), e.at());
            assertThat(session).as("quoted in a session of its trade date: " + q).isNotNull();
            if (session.thin()) {
                assertThat((Long) q.get("security_id")).isLessThanOrEqualTo(100000L + thin);
            }
            BigDecimal bid = (BigDecimal) q.get("bid_price"), ask = (BigDecimal) q.get("ask_price");
            assertThat(bid).isPositive().isLessThan(ask);
            BigDecimal relative = ask.subtract(bid).divide(bid, 6, java.math.RoundingMode.HALF_UP);
            widest.merge(session, relative, BigDecimal::max);
            assertThat(ask.subtract(bid)).as("the spread of " + q).isLessThan(bid.multiply(new BigDecimal("0.01")
                .multiply(BigDecimal.valueOf(session.spread))).max(new BigDecimal("0.05")));
            assertThat((Long) q.get("bid_size") % 100).isZero();
            assertThat((Long) q.get("ask_size") % 100).isZero();
            quoted.add(q.get("security_id"));
        }
        assertThat(quoted).as("every security is quoted").hasSize(300);
        assertThat(widest).containsOnlyKeys(Session.values());
        assertThat(widest.get(Session.OVERNIGHT)).isGreaterThan(widest.get(Session.REGULAR));
    }

    @Test
    void aBusinessDaysActivityHoldsTwoToFiveHundredSessionsAndEachSessionsEventsGoOutBeforeItsRow() {
        List<Event> sessions = sent.get(Feed.SESSIONS), clicks = sent.get(Feed.CLICK_EVENTS);
        assertThat(sessions.size() / activity).isBetween(200.0, 500.0);
        assertThat(clicks.size() / (double) sessions.size()).as("events a session").isBetween(5.0, 20.0);
        Map<Long, List<Map<String, Object>>> bySession = new HashMap<>();
        long id = 0;
        for (Event e : clicks) {
            Map<String, Object> c = e.row();
            assertThat((Long) c.get("event_id")).isEqualTo(++id);
            assertThat(e.at()).isEqualTo(c.get("event_time"));
            bySession.computeIfAbsent((Long) c.get("session_id"), k -> new ArrayList<>()).add(c);
        }
        Set<Long> ended = new HashSet<>();
        int visitors = 0, logouts = 0;
        for (Event e : sessions) {
            Map<String, Object> s = e.row();
            Long session = (Long) s.get("session_id");
            List<Map<String, Object>> events = bySession.get(session);
            assertThat(events).as("session " + session + "'s events").isNotNull().hasSize((Integer) s.get("events"));
            assertThat(e.at()).isEqualTo(s.get("ended_at")).isEqualTo(events.getLast().get("event_time"));
            assertThat(events.getFirst().get("event_time")).isEqualTo(s.get("started_at"));
            long views = events.stream().filter(c -> List.of("page_view", "quote_view").contains(c.get("event_type")))
                .count();
            assertThat((Integer) s.get("page_views")).isEqualTo((int) views);
            if (Boolean.TRUE.equals(s.get("logged_in"))) {
                assertThat(s.get("brokerage_account")).isNotNull();
                assertThat(events.getFirst().get("event_type")).isEqualTo("login");
                assertThat(events).allMatch(c -> s.get("brokerage_account").equals(c.get("brokerage_account")));
            } else {
                visitors++;
                assertThat(s.get("brokerage_account")).isNull();
                assertThat(events).allMatch(c -> c.get("brokerage_account") == null && "page_view".equals(c.get("event_type")));
            }
            if ("logout".equals(s.get("ended_by"))) {
                logouts++;
                assertThat(events.getLast().get("event_type")).isEqualTo("logout");
            }
            ended.add(session);
        }
        // Every session with events ends, but those still open as the last day's stretch ended: the next day ends them.
        assertThat(bySession.keySet()).containsAll(ended);
        Instant last = sent.get(Feed.CLICK_EVENTS).getLast().at();
        bySession.forEach((session, events) -> {
            if (!ended.contains(session)) {
                assertThat((Instant) events.getFirst().get("event_time")).as("session " + session + " still open")
                    .isAfter(last.minusSeconds(3 * 3600));
            }
        });
        assertThat(visitors / (double) sessions.size()).as("visitors").isBetween(0.05, 0.15);
        assertThat(logouts).isPositive();
        // A session's row goes out after its last event: no event of a session follows its row in the stream.
        Set<Long> closed = new HashSet<>();
        int s = 0, c = 0;
        for (Feed f : order) {
            if (f == Feed.SESSIONS) {
                closed.add((Long) sessions.get(s++).row().get("session_id"));
            } else if (f == Feed.CLICK_EVENTS) {
                assertThat(closed).doesNotContain((Long) clicks.get(c++).row().get("session_id"));
            }
        }
    }

    @Test
    void activeTradersOpenSessionsFarMoreOftenThanDormantAccounts() {
        World w = new World(World.Settings.of(20261122, LocalDate.of(2026, 11, 2), 1000), Kit.load());
        Map<Long, String> persona = new HashMap<>();
        w.accounts().forEach(a -> persona.put(a.id(), a.persona()));
        Map<String, Integer> sessions = new HashMap<>(), accounts = new HashMap<>();
        persona.values().forEach(p -> accounts.merge(p, 1, Integer::sum));
        for (Event e : sent.get(Feed.SESSIONS)) {
            Object account = e.row().get("brokerage_account");
            if (account != null) {
                sessions.merge(persona.get(account), 1, Integer::sum);
            }
        }
        double active = sessions.get("active_trader") / (double) accounts.get("active_trader");
        double dormant = sessions.getOrDefault("dormant", 0) / (double) accounts.get("dormant");
        assertThat(active).isGreaterThan(5 * dormant);
    }
}
