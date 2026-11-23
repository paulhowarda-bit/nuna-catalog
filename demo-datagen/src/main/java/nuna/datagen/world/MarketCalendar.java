package nuna.datagen.world;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.Month;
import java.time.temporal.TemporalAdjusters;
import java.util.HashMap;
import java.util.Map;

/**
 * The NYSE calendar: weekdays less the exchange's full-day holidays, with Saturday holidays observed on Friday and
 * Sunday ones on Monday (New Year's Day falling on a Saturday is not observed). Settlement is T+1.
 */
public final class MarketCalendar {

    private final Map<Integer, Map<LocalDate, String>> holidaysByYear = new HashMap<>();

    public boolean isBusinessDay(LocalDate d) {
        return d.getDayOfWeek() != DayOfWeek.SATURDAY && d.getDayOfWeek() != DayOfWeek.SUNDAY && holiday(d) == null;
    }

    /** The holiday's name, or null. */
    public String holiday(LocalDate d) {
        return holidaysByYear.computeIfAbsent(d.getYear(), MarketCalendar::holidays).get(d);
    }

    public LocalDate next(LocalDate d) {
        LocalDate n = d.plusDays(1);
        while (!isBusinessDay(n)) {
            n = n.plusDays(1);
        }
        return n;
    }

    public LocalDate previous(LocalDate d) {
        LocalDate p = d.minusDays(1);
        while (!isBusinessDay(p)) {
            p = p.minusDays(1);
        }
        return p;
    }

    /** The settlement date of a trade on a business day: T+1. */
    public LocalDate settlement(LocalDate tradeDate) {
        return next(tradeDate);
    }

    /** Whether the business day is the last of its month. */
    public boolean isMonthEnd(LocalDate d) {
        return next(d).getMonth() != d.getMonth();
    }

    private static Map<LocalDate, String> holidays(int year) {
        Map<LocalDate, String> h = new HashMap<>();
        LocalDate newYear = LocalDate.of(year, 1, 1);
        if (newYear.getDayOfWeek() != DayOfWeek.SATURDAY) {
            h.put(observed(newYear), "New Year's Day");
        }
        h.put(nth(year, Month.JANUARY, DayOfWeek.MONDAY, 3), "Martin Luther King Jr. Day");
        h.put(nth(year, Month.FEBRUARY, DayOfWeek.MONDAY, 3), "Washington's Birthday");
        h.put(easter(year).minusDays(2), "Good Friday");
        h.put(LocalDate.of(year, 5, 1).with(TemporalAdjusters.lastInMonth(DayOfWeek.MONDAY)), "Memorial Day");
        h.put(observed(LocalDate.of(year, 6, 19)), "Juneteenth");
        h.put(observed(LocalDate.of(year, 7, 4)), "Independence Day");
        h.put(nth(year, Month.SEPTEMBER, DayOfWeek.MONDAY, 1), "Labor Day");
        h.put(nth(year, Month.NOVEMBER, DayOfWeek.THURSDAY, 4), "Thanksgiving Day");
        h.put(observed(LocalDate.of(year, 12, 25)), "Christmas Day");
        return h;
    }

    private static LocalDate observed(LocalDate d) {
        return switch (d.getDayOfWeek()) {
            case SATURDAY -> d.minusDays(1);
            case SUNDAY -> d.plusDays(1);
            default -> d;
        };
    }

    private static LocalDate nth(int year, Month month, DayOfWeek dow, int n) {
        return LocalDate.of(year, month, 1).with(TemporalAdjusters.dayOfWeekInMonth(n, dow));
    }

    /** Easter Sunday (the anonymous Gregorian algorithm). */
    static LocalDate easter(int y) {
        int a = y % 19, b = y / 100, c = y % 100, d = b / 4, e = b % 4, f = (b + 8) / 25, g = (b - f + 1) / 3;
        int h = (19 * a + b - d - g + 15) % 30, i = c / 4, k = c % 4, l = (32 + 2 * e + 2 * i - h - k) % 7;
        int m = (a + 11 * h + 22 * l) / 451;
        int month = (h + l - 7 * m + 114) / 31, day = (h + l - 7 * m + 114) % 31 + 1;
        return LocalDate.of(y, month, day);
    }
}
