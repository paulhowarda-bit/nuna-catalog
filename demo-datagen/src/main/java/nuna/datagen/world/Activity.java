package nuna.datagen.world;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;

/**
 * When customers act (D48, amended 2026-11-27): at every hour of every calendar day, holidays included, on a retail
 * broker's curve by hour and weekday. A weekday is busy from the open to the evening and busiest around 09:30 and from
 * 19:00 to 21:00, when people are home; a weekend or holiday starts late and is busy all afternoon and evening; 03:00
 * is a trickle. The curve is measured in business days of the world as it was before (D48, amended 2026-11-23): a week
 * holds five, a weekend day 1.1 times a weekday, so a week's deposits, logins and changes are what five business days
 * held, spread over seven.
 */
final class Activity {

    /** Relative weight of each hour, New York time, on a business day and on a weekend day or holiday. */
    private static final double[] WEEKDAY = {1.5, 0.9, 0.6, 0.4, 0.5, 0.9, 2, 4, 6, 9, 8, 6.5, 7, 6, 6, 7, 6, 5.5, 6,
        7, 7.5, 7, 5, 3};
    private static final double[] WEEKEND = {2, 1.2, 0.8, 0.5, 0.4, 0.5, 0.9, 2, 3.5, 5, 6.5, 7, 7, 6.5, 6.5, 6.5, 6.5,
        6.5, 7, 8, 8.5, 8, 6, 3.5};
    /** A weekday's and a weekend day's share of a week of five business days. */
    static final double WEEKDAY_SHARE = 5 / 7.2, WEEKEND_SHARE = WEEKDAY_SHARE * 1.1;
    private static final double WEEKDAY_SUM = sum(WEEKDAY), WEEKEND_SUM = sum(WEEKEND);

    private Activity() {
    }

    private static double sum(double[] w) {
        double s = 0;
        for (double v : w) {
            s += v;
        }
        return s;
    }

    /**
     * The customer activity of a stretch of time within one hour, in business days: what a business day held before,
     * times this.
     */
    static double customer(MarketCalendar calendar, Instant from, Instant to) {
        ZonedDateTime z = from.atZone(World.NEW_YORK);
        LocalDate date = z.toLocalDate();
        boolean quiet = date.getDayOfWeek() == DayOfWeek.SATURDAY || date.getDayOfWeek() == DayOfWeek.SUNDAY
            || calendar.holiday(date) != null;
        double[] w = quiet ? WEEKEND : WEEKDAY;
        double share = (quiet ? WEEKEND_SHARE : WEEKDAY_SHARE) * w[z.getHour()] / (quiet ? WEEKEND_SUM : WEEKDAY_SUM);
        return share * java.time.Duration.between(from, to).toSeconds() / 3600.0;
    }

    /**
     * The trading of a stretch of time within one session of a trade date, in trade dates: the session's share of a
     * trade date's orders, spread evenly over its hours; 0 outside trading.
     */
    static double trading(LocalDate tradeDate, Instant from, Instant to) {
        Session s = Session.at(tradeDate, from);
        if (s == null) {
            return 0;
        }
        return s.orders / s.hours(tradeDate) * java.time.Duration.between(from, to).toSeconds() / 3600.0;
    }
}
