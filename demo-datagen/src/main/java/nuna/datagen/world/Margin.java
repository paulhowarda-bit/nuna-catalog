package nuna.datagen.world;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import nuna.datagen.kit.Engine;
import nuna.datagen.world.Market.Security;

/**
 * Margin (item 153): the house's requirement per security each day, each margin account's figures at the close and its
 * calls. It draws nothing at random.
 *
 * <p>Requirements. Regulation T's initial margin is 50%. FINRA Rule 4210(c) maintenance is 25% of a long position; a
 * short at $5 or more needs the greater of $5 a share and 30%, below $5 the greater of $2.50 a share and 100%. The
 * house asks more (a choice made here, Paul may overrule): 30% long at $10 or more, 35% from $5, 50% from $3, 100% below
 * $3 (not marginable); 35% short at $5 or more, 100% below; 100% long and short on a hard-to-borrow security (an
 * elevated requirement); and 50% long on a position over 70% of the account's long
 * value once that is over $25,000 (concentration). The binding requirement is the greater of FINRA's and the house's.
 *
 * <p>Intraday margin (FINRA Rule 4210(f)(8) as amended by Regulatory Notice 26-10, effective 2026-06-04, which replaced
 * the pattern day trader rules). The world uses the end-of-day method the rule permits: the account as it stood after
 * each IML-reducing transaction (a buy not covering a short, template 41, or a short sale, 61), valued at the day's
 * closing prices; the intraday margin deficit is the largest shortfall of equity against the binding requirement among
 * them. A deficit is an intraday call, met once the house surplus rises by its amount (deposits or positions closed),
 * outstanding at most 15 business days. A house surplus below zero at the close is a maintenance call, due in two
 * business days; one not met by then is marked liquidation due (the world sells nothing for it).
 *
 * <p>The money balance is cash (1001), unsettled sales (1003) and dividends receivable (1005) less unsettled purchases
 * (1101), accrued fees (1102), interest (1103) and payments in lieu (1108); a negative one is the debit balance.
 */
final class Margin {

    static final BigDecimal REG_T = new BigDecimal("0.50");
    static final BigDecimal FINRA_LONG = new BigDecimal("0.25"), FINRA_SHORT = new BigDecimal("0.30"),
        FINRA_SHORT_LOW = BigDecimal.ONE;
    static final BigDecimal FINRA_SHORT_PER_SHARE = new BigDecimal("5.00"),
        FINRA_SHORT_PER_SHARE_LOW = new BigDecimal("2.50");
    private static final BigDecimal CONCENTRATED = new BigDecimal("0.50"), CONCENTRATION = new BigDecimal("0.70"),
        CONCENTRATED_FROM = new BigDecimal(25_000);
    private static final int[] MONEY = {1001, 1003, 1005, 1101, 1102, 1103, 1108};
    private static final int INTRADAY_LIFE = 15, MAINTENANCE_DUE = 2;

    /** An account as it stood after an IML-reducing transaction: its money balance and positions. */
    private record Snapshot(BigDecimal money, Map<Long, BigDecimal> positions) {}

    private static final class Call {
        final String id, type;
        final long account;
        final BigDecimal amount, surplusAtIssue;
        final LocalDate issued, due;

        Call(String id, String type, long account, BigDecimal amount, BigDecimal surplusAtIssue, LocalDate issued,
             LocalDate due) {
            this.id = id;
            this.type = type;
            this.account = account;
            this.amount = amount;
            this.surplusAtIssue = surplusAtIssue;
            this.issued = issued;
            this.due = due;
        }
    }

    /** One account's figures at the close. */
    record Figures(BigDecimal money, BigDecimal longValue, BigDecimal shortValue, BigDecimal equity,
                   BigDecimal regTExcess, BigDecimal finra, BigDecimal house) {
        BigDecimal surplus() {
            return equity.subtract(finra.max(house));
        }
    }

    private final Emitter emitter;
    private final Engine engine;
    private final Market market;
    private final MarketCalendar calendar;
    /** The securities the house holds at 100% long and short: the hard-to-borrow, thinly lent ones. */
    private final java.util.function.Predicate<Security> elevated;
    private final Map<Long, List<Snapshot>> today = new TreeMap<>();
    private final Map<String, Call> open = new LinkedHashMap<>();
    private final Map<Long, Integer> openPerAccount = new HashMap<>();
    private long nextCall = 1, nextEvent = 1;

    Margin(Emitter emitter, Engine engine, Market market, MarketCalendar calendar,
           java.util.function.Predicate<Security> elevated) {
        this.elevated = elevated;
        this.emitter = emitter;
        this.engine = engine;
        this.market = market;
        this.calendar = calendar;
    }

    static BigDecimal houseLongRate(BigDecimal price) {
        if (price.compareTo(BigDecimal.valueOf(10)) >= 0) {
            return new BigDecimal("0.30");
        }
        if (price.compareTo(BigDecimal.valueOf(5)) >= 0) {
            return new BigDecimal("0.35");
        }
        return price.compareTo(BigDecimal.valueOf(3)) >= 0 ? new BigDecimal("0.50") : BigDecimal.ONE;
    }

    static BigDecimal houseShortRate(BigDecimal price) {
        return price.compareTo(BigDecimal.valueOf(5)) >= 0 ? new BigDecimal("0.35") : BigDecimal.ONE;
    }

    static String band(BigDecimal price) {
        if (price.compareTo(BigDecimal.valueOf(10)) >= 0) {
            return "10_and_over";
        }
        if (price.compareTo(BigDecimal.valueOf(5)) >= 0) {
            return "5_to_10";
        }
        return price.compareTo(BigDecimal.valueOf(3)) >= 0 ? "3_to_5" : "under_3";
    }

    /** FINRA's maintenance requirement on a short position. */
    static BigDecimal finraShort(BigDecimal price, BigDecimal shares) {
        BigDecimal value = price.multiply(shares);
        return price.compareTo(BigDecimal.valueOf(5)) >= 0
            ? FINRA_SHORT_PER_SHARE.multiply(shares).max(value.multiply(FINRA_SHORT))
            : FINRA_SHORT_PER_SHARE_LOW.multiply(shares).max(value.multiply(FINRA_SHORT_LOW));
    }

    /** 16:00:01, with the closing prices: each security's requirements for the next day. */
    void requirements(Instant at, LocalDate day) {
        for (Security s : market.securities()) {
            BigDecimal p = market.price(s);
            BigDecimal house = elevated.test(s) ? BigDecimal.ONE : houseLongRate(p);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("security_id", s.id());
            row.put("symbol", s.symbol());
            row.put("effective_date", calendar.next(day));
            row.put("band", band(p));
            row.put("marginable", house.compareTo(BigDecimal.ONE) < 0);
            row.put("close_price", p);
            row.put("reg_t_initial_rate", REG_T);
            row.put("finra_long_rate", FINRA_LONG);
            row.put("house_long_rate", house);
            row.put("finra_short_rate", p.compareTo(BigDecimal.valueOf(5)) >= 0 ? FINRA_SHORT : FINRA_SHORT_LOW);
            row.put("finra_short_per_share", p.compareTo(BigDecimal.valueOf(5)) >= 0 ? FINRA_SHORT_PER_SHARE
                : FINRA_SHORT_PER_SHARE_LOW);
            row.put("house_short_rate", elevated.test(s) ? BigDecimal.ONE : houseShortRate(p));
            row.put("business_date", day);
            row.put("event_time", at);
            emitter.emit(at, Feed.HOUSE_REQUIREMENTS, row);
        }
    }

    private BigDecimal money(long account) {
        BigDecimal m = BigDecimal.ZERO;
        for (int d : MONEY) {
            m = m.add(engine.balance(account, d, null, null));
        }
        return m;
    }

    private Map<Long, BigDecimal> positions(long account) {
        Map<Long, BigDecimal> out = new TreeMap<>();
        for (var e : engine.balances(account).entrySet()) {
            int d = e.getKey().definition();
            if ((d == 3002 || d == 3102) && e.getKey().lot() == null) {
                BigDecimal q = engine.normalBalance(account, d, e.getKey().security());
                out.merge(e.getKey().security(), d == 3002 ? q : q.negate(), BigDecimal::add);
            }
        }
        out.values().removeIf(q -> q.signum() == 0);
        return out;
    }

    /** An IML-reducing transaction posted (a buy, 41, or a short sale, 61): the account as it now stands. */
    void imlReducing(long account) {
        today.computeIfAbsent(account, k -> new ArrayList<>()).add(new Snapshot(money(account), positions(account)));
    }

    Figures figures(BigDecimal money, Map<Long, BigDecimal> positions) {
        BigDecimal lmv = BigDecimal.ZERO, smv = BigDecimal.ZERO;
        for (var e : positions.entrySet()) {
            BigDecimal v = price(e.getKey()).multiply(e.getValue().abs());
            if (e.getValue().signum() > 0) {
                lmv = lmv.add(v);
            } else {
                smv = smv.add(v);
            }
        }
        BigDecimal finra = BigDecimal.ZERO, house = BigDecimal.ZERO;
        for (var e : positions.entrySet()) {
            BigDecimal p = price(e.getKey()), q = e.getValue().abs(), v = p.multiply(q);
            if (e.getValue().signum() > 0) {
                finra = finra.add(v.multiply(FINRA_LONG));
                BigDecimal rate = elevated(e.getKey()) ? BigDecimal.ONE : houseLongRate(p);
                if (lmv.compareTo(CONCENTRATED_FROM) > 0 && v.compareTo(lmv.multiply(CONCENTRATION)) > 0) {
                    rate = rate.max(CONCENTRATED);
                }
                house = house.add(v.multiply(rate));
            } else {
                BigDecimal f = finraShort(p, q);
                finra = finra.add(f);
                house = house.add(f.max(v.multiply(elevated(e.getKey()) ? BigDecimal.ONE : houseShortRate(p))));
            }
        }
        BigDecimal equity = money.add(lmv).subtract(smv);
        BigDecimal regT = equity.subtract(lmv.add(smv).multiply(REG_T));
        return new Figures(money, cents(lmv), cents(smv), cents(equity), cents(regT), cents(finra), cents(house));
    }

    private boolean elevated(long security) {
        return elevated.test(market.byIndex((int) (security - 100001)));
    }

    private BigDecimal price(long security) {
        return market.price(market.byIndex((int) (security - 100001)));
    }

    /** 17:00: each margin account's figures, its intraday margin deficit, and its calls. */
    void endOfDay(Instant at, LocalDate day, List<World.Account> accounts) {
        for (World.Account a : accounts) {
            if (!a.margin()) {
                continue;
            }
            Map<Long, BigDecimal> positions = positions(a.id());
            BigDecimal m = money(a.id());
            List<Snapshot> snaps = today.getOrDefault(a.id(), List.of());
            if (positions.isEmpty() && m.signum() == 0 && snaps.isEmpty() && !openPerAccount.containsKey(a.id())) {
                continue;
            }
            Figures f = figures(m, positions);
            BigDecimal deficit = BigDecimal.ZERO;
            for (Snapshot s : snaps) {
                deficit = deficit.max(figures(s.money(), s.positions()).surplus().negate());
            }
            BigDecimal surplus = f.surplus();
            calls(at, day, a.id(), surplus, deficit);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("business_date", day);
            row.put("brokerage_account", a.id());
            row.put("money_balance", cents(m));
            row.put("debit_balance", cents(m.negate().max(BigDecimal.ZERO)));
            row.put("long_market_value", f.longValue());
            row.put("short_market_value", f.shortValue());
            row.put("equity", f.equity());
            row.put("reg_t_excess", f.regTExcess());
            row.put("finra_requirement", f.finra());
            row.put("house_requirement", f.house());
            row.put("house_surplus", cents(surplus));
            row.put("iml_reducing_transactions", snaps.size());
            row.put("intraday_margin_deficit", cents(deficit));
            row.put("open_calls", openPerAccount.getOrDefault(a.id(), 0));
            emitter.emit(at, Feed.ACCOUNT_MARGIN_EOD, row);
        }
        today.clear();
    }

    /**
     * The account's calls at the close: its open ones reviewed against its house surplus, an intraday call for its
     * intraday margin deficit, a maintenance call for a surplus below zero.
     */
    void calls(Instant at, LocalDate day, long account, BigDecimal surplus, BigDecimal deficit) {
        review(at, day, account, surplus);
        if (deficit.signum() > 0) {
            issue(at, day, account, "intraday", deficit, surplus, businessDaysAfter(day, INTRADAY_LIFE));
        }
        if (surplus.signum() < 0 && !hasOpen(account, "maintenance")) {
            issue(at, day, account, "maintenance", surplus.negate(), surplus, businessDaysAfter(day, MAINTENANCE_DUE));
        }
    }

    int openCalls(long account) {
        return openPerAccount.getOrDefault(account, 0);
    }

    private boolean hasOpen(long account, String type) {
        for (Call c : open.values()) {
            if (c.account == account && c.type.equals(type)) {
                return true;
            }
        }
        return false;
    }

    /** The account's open calls against its surplus now: met, expired or liquidation due. */
    private void review(Instant at, LocalDate day, long account, BigDecimal surplus) {
        for (var it = open.values().iterator(); it.hasNext(); ) {
            Call c = it.next();
            if (c.account != account) {
                continue;
            }
            String outcome = null;
            if (c.type.equals("maintenance")) {
                if (surplus.signum() >= 0) {
                    outcome = "met";
                } else if (!day.isBefore(c.due)) {
                    outcome = "liquidation_due";
                }
            } else if (surplus.subtract(c.surplusAtIssue).compareTo(c.amount) >= 0) {
                outcome = "met";
            } else if (!day.isBefore(c.due)) {
                outcome = "expired";
            }
            if (outcome != null) {
                it.remove();
                openPerAccount.merge(account, -1, Integer::sum);
                if (openPerAccount.get(account) == 0) {
                    openPerAccount.remove(account);
                }
                event(at, day, c, outcome, surplus);
            }
        }
    }

    private void issue(Instant at, LocalDate day, long account, String type, BigDecimal amount, BigDecimal surplus,
                       LocalDate due) {
        Call c = new Call("MC-" + (75_000_000 + nextCall++), type, account, cents(amount), surplus, day, due);
        open.put(c.id, c);
        openPerAccount.merge(account, 1, Integer::sum);
        event(at, day, c, "issued", surplus);
    }

    private void event(Instant at, LocalDate day, Call c, String type, BigDecimal surplus) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("call_event_id", nextEvent++);
        row.put("call_id", c.id);
        row.put("call_type", c.type);
        row.put("event_type", type);
        row.put("brokerage_account", c.account);
        row.put("amount", c.amount);
        row.put("house_surplus", cents(surplus));
        row.put("call_date", c.issued);
        row.put("due_date", c.due);
        row.put("business_date", day);
        row.put("event_time", at);
        emitter.emit(at, Feed.MARGIN_CALLS, row);
    }

    private LocalDate businessDaysAfter(LocalDate d, int n) {
        for (int i = 0; i < n; i++) {
            d = calendar.next(d);
        }
        return d;
    }

    private static BigDecimal cents(BigDecimal x) {
        return x.setScale(2, RoundingMode.HALF_UP);
    }
}
