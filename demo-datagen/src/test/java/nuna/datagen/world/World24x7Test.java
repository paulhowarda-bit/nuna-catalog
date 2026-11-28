package nuna.datagen.world;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import nuna.datagen.kit.Kit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The world runs 24x7 (item 321; D48, amended 2026-11-27): customers at every hour of every day, holidays included;
 * trading on Nasdaq's 24x5 sessions, none in the weekend gap, the 20:00 pause or on a holiday; the books on business
 * days. Played over Thanksgiving week 2026: from Friday 2026-11-20's 20:30 to Monday 2026-11-30's, two weekends and
 * the holiday on Thursday 2026-11-26.
 */
class World24x7Test {

    private static final Kit KIT = Kit.load();
    private static final LocalDate THANKSGIVING = LocalDate.of(2026, 11, 26);
    private static final List<Event> events = new ArrayList<>();
    private static MarketCalendar calendar;

    @BeforeAll
    static void play() {
        World w = new World(World.Settings.of(321, LocalDate.of(2026, 11, 23), 600), KIT);
        calendar = w.calendar();
        Sink sink = events::add;
        for (int i = 0; i < 5; i++) {
            w.runDay(sink);
        }
        assertThat(w.day()).isEqualTo(LocalDate.of(2026, 12, 1));
        assertThat(w.unexpectedRejections()).isZero();
    }

    private static ZonedDateTime ny(Instant t) {
        return t.atZone(World.NEW_YORK);
    }

    private static boolean booksClosed(LocalDate d) {
        return !calendar.isBusinessDay(d);
    }

    @Test
    void everyHourOfTheWeekHasTheCustomersEvents() {
        Set<Instant> hours = new HashSet<>();
        for (Event e : events) {
            if (e.feed() == Feed.CLICK_EVENTS) {
                hours.add(e.at().truncatedTo(ChronoUnit.HOURS));
            }
        }
        Instant first = LocalDate.of(2026, 11, 20).atTime(21, 0).atZone(World.NEW_YORK).toInstant();
        Instant last = LocalDate.of(2026, 11, 30).atTime(20, 0).atZone(World.NEW_YORK).toInstant();
        int checked = 0;
        for (Instant h = first; h.isBefore(last); h = h.plus(1, ChronoUnit.HOURS)) {
            assertThat(hours).as("click events in the hour from " + ny(h)).contains(h);
            checked++;
        }
        assertThat(checked).isEqualTo(9 * 24 + 23);
    }

    @Test
    void customersAreBusiestInTheEveningAndQuietAtThree() {
        Map<Integer, Integer> byHour = new HashMap<>();
        for (Event e : events) {
            if (e.feed() == Feed.SESSIONS) {
                byHour.merge(ny((Instant) e.row().get("started_at")).getHour(), 1, Integer::sum);
            }
        }
        assertThat(byHour.getOrDefault(20, 0)).isGreaterThan(4 * byHour.getOrDefault(3, 0));
    }

    @Test
    void customersServiceTheirAccountsAndAskForMoneyOnWeekendsAndTheHoliday() {
        Map<LocalDate, Integer> requests = new HashMap<>(), servicing = new HashMap<>(), sessions = new HashMap<>();
        for (Event e : events) {
            LocalDate d = ny(e.at()).toLocalDate();
            if (!booksClosed(d)) {
                continue;
            }
            switch (e.feed()) {
                case PAYMENTS -> {
                    if ("requested".equals(e.row().get("status"))) {
                        requests.merge(d, 1, Integer::sum);
                    }
                }
                case PARTIES, SUITABILITY_PROFILES, TRUSTED_CONTACTS, COMPLAINTS -> servicing.merge(d, 1, Integer::sum);
                case SESSIONS -> sessions.merge(d, 1, Integer::sum);
                default -> { }
            }
        }
        List<LocalDate> closed = List.of(LocalDate.of(2026, 11, 21), LocalDate.of(2026, 11, 22), THANKSGIVING,
            LocalDate.of(2026, 11, 28), LocalDate.of(2026, 11, 29));
        assertThat(requests.keySet()).as("money requests on the days the books are closed").containsAll(closed);
        assertThat(sessions.keySet()).containsAll(closed);
        assertThat(servicing.values().stream().mapToInt(Integer::intValue).sum())
            .as("changes to parties, suitability, trusted contacts and complaints on closed days").isGreaterThan(5);
    }

    @Test
    void nothingTradesInTheWeekendGapThePauseOrOnTheHoliday() {
        Set<String> sessions = new HashSet<>();
        int executions = 0;
        for (Event e : events) {
            if (e.feed() != Feed.EXECUTIONS) {
                continue;
            }
            executions++;
            Instant at = (Instant) e.row().get("executed_at");
            ZonedDateTime t = ny(at);
            LocalDate tradeDate = (LocalDate) e.row().get("trade_date");
            assertThat(calendar.isBusinessDay(tradeDate)).as("trade date " + tradeDate).isTrue();
            assertThat(t.getHour()).as("no trade in the 20:00 pause: " + t).isNotEqualTo(20);
            DayOfWeek dow = t.getDayOfWeek();
            boolean gap = dow == DayOfWeek.SATURDAY || dow == DayOfWeek.FRIDAY && t.getHour() >= 20
                || dow == DayOfWeek.SUNDAY && t.getHour() < 21;
            assertThat(gap).as("no trade in the weekend gap: " + t).isFalse();
            boolean holiday = t.toLocalDate().equals(THANKSGIVING) && t.getHour() < 21;
            assertThat(holiday).as("no trade on Thanksgiving before Friday's overnight session: " + t).isFalse();
            Session s = Session.at(tradeDate, at);
            assertThat(s).as(t + " within trade date " + tradeDate).isNotNull();
            assertThat(e.row().get("session")).isEqualTo(s.code);
            sessions.add(s.code);
        }
        assertThat(executions).isGreaterThan(1000);
        assertThat(sessions).containsExactlyInAnyOrder("overnight", "pre_market", "regular", "post_market");
    }

    @Test
    void aTradeAfterNineInTheEveningTakesTheNextTradingDaysTradeDateAndSettlesTPlusOne() {
        int evening = 0;
        for (Event e : events) {
            if (e.feed() != Feed.EXECUTIONS) {
                continue;
            }
            ZonedDateTime t = ny((Instant) e.row().get("executed_at"));
            LocalDate tradeDate = (LocalDate) e.row().get("trade_date");
            if (!t.toLocalTime().isBefore(LocalTime.of(21, 0))) {
                evening++;
                assertThat(tradeDate).as(t.toString()).isEqualTo(calendar.next(t.toLocalDate()));
            } else {
                assertThat(tradeDate).as(t.toString()).isEqualTo(t.toLocalDate());
            }
            assertThat(e.row().get("settlement_date")).isEqualTo(calendar.settlement(tradeDate));
        }
        assertThat(evening).isPositive();
    }

    @Test
    void ordersOutsideTheRegularSessionAreLimitOrders() {
        int off = 0;
        for (Event e : events) {
            if (e.feed() == Feed.ORDERS && !"regular".equals(e.row().get("session"))) {
                off++;
                assertThat(e.row().get("order_type")).as(e.row().toString()).isEqualTo("limit");
                assertThat(e.row().get("side")).isIn("buy", "sell");
            }
        }
        assertThat(off).isPositive();
    }

    @Test
    void aMoneyRequestWhileTheBooksAreClosedIsBookedTheNextBusinessDayWithItsDate() {
        Map<String, Event> asked = new HashMap<>();
        int booked = 0;
        for (Event e : events) {
            if (e.feed() != Feed.PAYMENTS) {
                continue;
            }
            String id = (String) e.row().get("payment_id");
            if ("requested".equals(e.row().get("status"))) {
                LocalDate d = ny(e.at()).toLocalDate();
                assertThat(e.row().get("financial_transaction_id")).isNull();
                assertThat(e.row().get("business_date")).as("a request's business date is the next the books keep")
                    .isEqualTo(calendar.isBusinessDay(d) && ny(e.at()).toLocalTime().isBefore(LocalTime.of(20, 30))
                        ? d : calendar.next(d));
                asked.put(id, e);
                continue;
            }
            Event request = asked.remove(id);
            if (request == null) {
                continue;
            }
            LocalDate on = ny(request.at()).toLocalDate();
            if (!booksClosed(on)) {
                continue;
            }
            booked++;
            assertThat(e.row().get("business_date")).as(e.row().toString()).isEqualTo(calendar.next(on));
            assertThat(ny(e.at()).toLocalDate()).isEqualTo(calendar.next(on));
            assertThat(ny(e.at()).toLocalTime()).isAfterOrEqualTo(World.MONEY_OPENS);
            assertThat(e.row().get("version")).isEqualTo(2);
            if (!"rejected".equals(e.row().get("status"))) {
                assertThat(e.row().get("financial_transaction_id")).isNotNull();
            }
        }
        assertThat(booked).as("weekend and holiday requests booked").isGreaterThan(10);
    }
}
