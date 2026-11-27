package nuna.datagen.world;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Function;
import nuna.datagen.world.Market.Security;

/**
 * The market's live quotes (item 154): through the session, each security's best bid and offer as it changes, a quote
 * at a second of its own in each minute it is quoted. The most popular securities are quoted every minute, the tail
 * about one minute in five: at 300 securities about 37,000 quotes a business day. Each quote straddles the security's
 * price at that moment, by a half-spread of 2 to 8 basis points (at least half a cent), on the venues the order
 * management system routes to. It draws from a source of chance of its own, so the rest of the world draws as before.
 */
final class Quotes {

    /** The session's first and last minute quoted: 09:30 to 15:59. */
    static final LocalTime OPEN = LocalTime.of(9, 30);
    static final int MINUTES = 390;
    private static final List<String> VENUES = List.of("XNYS", "XNAS", "ARCX", "EDGX", "BATS", "IEXG");

    private final Market market;
    private final Rng rng;
    private final Emitter out;
    /** Each security's chance of being quoted in a minute, by its place in the market's popularity. */
    private final double[] rate;
    private long next = 1;

    Quotes(Market market, Rng rng, Emitter out) {
        this.market = market;
        this.rng = rng;
        this.out = out;
        rate = new double[market.securities().size()];
        for (int i = 0; i < rate.length; i++) {
            rate[i] = Math.min(1.0, Math.max(0.2, 3 / Math.sqrt(i + 1.0)));
        }
    }

    /** The quotes a business day is expected to hold, for the docs and the tests. */
    double expectedPerDay() {
        double perMinute = 0;
        for (double r : rate) {
            perMinute += r;
        }
        return perMinute * MINUTES;
    }

    /**
     * Plans the day's session: a task at the start of each minute draws which securities are quoted in it, and when.
     *
     * @param at       a time of the day as an instant
     * @param schedule plans a task at an instant
     */
    void plan(LocalDate day, Function<LocalTime, Instant> at, BiConsumer<Instant, Runnable> schedule) {
        for (int m = 0; m < MINUTES; m++) {
            LocalTime minute = OPEN.plusMinutes(m);
            schedule.accept(at.apply(minute), () -> {
                for (Security s : market.securities()) {
                    if (rng.chance(rate[market.index(s)])) {
                        // Never on the minute itself, where the bars move the prices.
                        Instant when = at.apply(minute.plusSeconds(1 + rng.below(59)));
                        schedule.accept(when, () -> quote(day, when, s));
                    }
                }
            });
        }
    }

    private void quote(LocalDate day, Instant when, Security s) {
        double price = market.price(s).doubleValue();
        double mid = price * (1 + rng.normal() * s.sigma() / Math.sqrt(Market.BARS) * 0.2);
        double half = Math.max(0.005, price * (2 + rng.uniform() * 6) / 10_000);
        BigDecimal bid = BigDecimal.valueOf(Math.max(0.01, mid - half)).setScale(2, RoundingMode.FLOOR);
        BigDecimal ask = BigDecimal.valueOf(mid + half).setScale(2, RoundingMode.CEILING);
        if (ask.compareTo(bid) <= 0) {
            ask = bid.add(new BigDecimal("0.01"));
        }
        // Round lots, fewer of them for the dearer securities.
        long lots = Math.max(1, Math.round(rng.lognormal(Math.max(1, 3000 / price), 0.7)));
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("quote_id", next++);
        row.put("security_id", s.id());
        row.put("symbol", s.symbol());
        row.put("business_date", day);
        row.put("quoted_at", when);
        row.put("bid_price", bid.setScale(4, RoundingMode.UNNECESSARY));
        row.put("bid_size", 100 * Math.min(lots, 500));
        row.put("bid_venue", rng.pick(VENUES));
        row.put("ask_price", ask.setScale(4, RoundingMode.UNNECESSARY));
        row.put("ask_size", 100 * Math.min(Math.max(1, Math.round(lots * (0.5 + rng.uniform()))), 500));
        row.put("ask_venue", rng.pick(VENUES));
        out.emit(when, Feed.QUOTES, row);
    }
}
