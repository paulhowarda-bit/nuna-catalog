package nuna.datagen.world;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;

/**
 * Nasdaq's 24x5 trading sessions (Equity Trader Alert 2026-46, effective Sunday, December 6, 2026; D48, amended
 * 2026-11-27): a trade date's trading runs from 21:00 New York time on the calendar day before it to 20:00 on the day
 * itself, so trading goes from 21:00 Sunday to 20:00 Friday with a pause from 20:00 to 21:00 each weekday. A trade from
 * 21:00 to midnight takes the next trading day's trade date. There is no trading over the weekend gap, nor in the
 * session that would open the evening before an exchange holiday: only a business day is a trade date, and its trading
 * starts the evening before it.
 */
enum Session {
    /** 21:00 the evening before to 04:00: thin, wide quotes, the most popular securities only. */
    OVERNIGHT("overnight", LocalTime.of(21, 0), LocalTime.of(4, 0), 0.04, 0.03, 6.0),
    /** 04:00 to 09:30. */
    PRE_MARKET("pre_market", LocalTime.of(4, 0), LocalTime.of(9, 30), 0.05, 0.08, 3.0),
    /** 09:30 to 16:00, the only session with market orders, short sales and every security. */
    REGULAR("regular", LocalTime.of(9, 30), LocalTime.of(16, 0), 0.85, 1.0, 1.0),
    /** 16:00 to 20:00. */
    POST_MARKET("post_market", LocalTime.of(16, 0), LocalTime.of(20, 0), 0.06, 0.10, 3.0);

    /** Its name in the order management system's rows. */
    final String code;
    final LocalTime start, end;
    /** Its share of a trade date's orders, its bars' volume against the regular session's, and its quotes' width. */
    final double orders, volume, spread;

    Session(String code, LocalTime start, LocalTime end, double orders, double volume, double spread) {
        this.code = code;
        this.start = start;
        this.end = end;
        this.orders = orders;
        this.volume = volume;
        this.spread = spread;
    }

    /** When the session begins for this trade date (the overnight session, the evening before). */
    Instant start(LocalDate tradeDate) {
        return (this == OVERNIGHT ? tradeDate.minusDays(1) : tradeDate).atTime(start).atZone(World.NEW_YORK).toInstant();
    }

    Instant end(LocalDate tradeDate) {
        return tradeDate.atTime(end).atZone(World.NEW_YORK).toInstant();
    }

    /** Its hours, for its share of orders per hour. */
    double hours(LocalDate tradeDate) {
        return java.time.Duration.between(start(tradeDate), end(tradeDate)).toSeconds() / 3600.0;
    }

    /** Whether only the market's most popular securities trade and quote in it: every session but the regular one. */
    boolean thin() {
        return this != REGULAR;
    }

    /** When a trade date's trading begins: 21:00 the calendar day before. */
    static Instant opens(LocalDate tradeDate) {
        return OVERNIGHT.start(tradeDate);
    }

    /** When it ends: 20:00 on the day. */
    static Instant closes(LocalDate tradeDate) {
        return POST_MARKET.end(tradeDate);
    }

    /** The session of a trade date at an instant, or null outside its trading. */
    static Session at(LocalDate tradeDate, Instant t) {
        for (Session s : values()) {
            if (!t.isBefore(s.start(tradeDate)) && t.isBefore(s.end(tradeDate))) {
                return s;
            }
        }
        return null;
    }
}
