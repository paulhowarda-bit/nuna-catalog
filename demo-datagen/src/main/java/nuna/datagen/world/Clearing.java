package nuna.datagen.world;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import nuna.datagen.world.Market.Security;

/**
 * The firm's clearing and custody (item 151), as a self-clearing broker-dealer and a participant of NSCC's Continuous
 * Net Settlement: every customer fill is also a street trade, which CNS nets per security and trade date into one
 * obligation (net shares to receive or deliver against net money) that settles at 08:00 on the settlement date (T+1).
 * At each day's end it writes the stock record, every position by owner and by location, and at each short sale's
 * acceptance the locate it was accepted under (Reg SHO 203(b)(1); the kit's locateId joins the two).
 *
 * <p>The stock record is double-entry, per security, in shares: the owners (each customer account's position, long
 * positive and short negative, at trade date, and the firm's own) add up to the locations (the firm's account at DTC,
 * shares borrowed to deliver short sales, net trades not yet settled through CNS, and shares failed to deliver).
 * Besides fills, a customer's position moves with ACATS transfers, which settle through DTC (templates 83 and 84), and
 * with splits (86, less any fraction paid as cash in lieu, 87); the firm's own shares split with them. Positions are
 * in shares to the ledger's five places, as a split can leave a fraction.
 *
 * <p>One break is seeded (unless the world is made without it): on the world's third business day or later, the first
 * net delivery that holds a customer short sale fails for the short sale's shares, borrow not received. Reg SHO 204(a)
 * has the firm close it out by the beginning of regular trading hours on the next settlement day: at 09:30 it buys the
 * shares in the market for its own account, a street trade of the firm's own that CNS settles the next day, and those
 * shares are delivered against the fail. So CNS differs from the customers' fills by that purchase, on its trade date.
 *
 * <p>It draws nothing at random: whether a security is hard to borrow, and at what rate, is the world's, and the
 * close-out is priced at the market at 09:30.
 */
final class Clearing {

    static final String REG_SHO_204 = "204(a)";
    /** The fee on a borrow of an easy-to-borrow security, a year: general collateral. */
    static final BigDecimal GENERAL_COLLATERAL = new BigDecimal("0.002500");
    private static final DateTimeFormatter BASIC = DateTimeFormatter.BASIC_ISO_DATE;

    /** The street side of one security's trades on one trade date. */
    private static final class Street {
        long buyQuantity, sellQuantity, shortQuantity;
        BigDecimal buyAmount = BigDecimal.ZERO, sellAmount = BigDecimal.ZERO;
    }

    private record Obligation(String id, Security security, LocalDate tradeDate, LocalDate settlementDate, long net,
                              BigDecimal netAmount, long shortQuantity) {}

    private static final class Fail {
        final String id;
        final Obligation obligation;
        final long quantity;
        final Instant deadline;
        boolean aged;
        LocalDate delivery;

        Fail(String id, Obligation obligation, long quantity, Instant deadline) {
            this.id = id;
            this.obligation = obligation;
            this.quantity = quantity;
            this.deadline = deadline;
        }
    }

    private final Emitter emitter;
    private final MarketCalendar calendar;
    private final Market market;
    private boolean seedBreak;
    private boolean breakSeeded;
    private long nextSettlement = 1, nextFail = 1, nextFailEvent = 1;

    /** Per security, each customer account's position and the firm's own, at trade date. */
    private final Map<Long, TreeMap<Long, BigDecimal>> customers = new TreeMap<>();
    private final Map<Long, BigDecimal> firm = new TreeMap<>();
    /** The day's street trades, per security. */
    private final Map<Long, Street> today = new TreeMap<>();
    /** Obligations not yet settled, by settlement date. */
    private final Map<LocalDate, List<Obligation>> due = new TreeMap<>();
    private final Map<String, Fail> fails = new LinkedHashMap<>();

    Clearing(Emitter emitter, MarketCalendar calendar, Market market, boolean seedBreak) {
        this.emitter = emitter;
        this.calendar = calendar;
        this.market = market;
        this.seedBreak = seedBreak;
    }

    void withoutBreak() {
        seedBreak = false;
    }

    /** Whether the seeded fail has happened. */
    boolean breakSeeded() {
        return breakSeeded;
    }

    /**
     * The locate a short sale was accepted under: found on the easy-to-borrow list, or for a security the world makes
     * hard to borrow, by the stock loan desk at its rate.
     *
     * @param hardToBorrowRate the security's borrow rate a year if it is hard to borrow, else null
     */
    void locate(Instant at, LocalDate day, String locate, long account, Security s, long quantity, String orderRef,
                Instant expires, BigDecimal hardToBorrowRate) {
        boolean easy = hardToBorrowRate == null;
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("locate_id", locate);
        row.put("brokerage_account", account);
        row.put("security_id", s.id());
        row.put("symbol", s.symbol());
        row.put("quantity", quantity);
        row.put("source", easy ? "easy_to_borrow_list" : "stock_loan_desk");
        row.put("easy_to_borrow", easy);
        row.put("borrow_rate", easy ? GENERAL_COLLATERAL : hardToBorrowRate.setScale(6, RoundingMode.UNNECESSARY));
        row.put("order_ref", orderRef);
        row.put("business_date", day);
        row.put("requested_at", at);
        row.put("expires_at", expires);
        emitter.emit(at, Feed.LOCATES, row);
    }

    /** A customer fill the ledger booked: the customer's position, and a street trade for CNS. */
    void trade(long account, Security s, String side, long quantity, BigDecimal value) {
        boolean buy = side.equals("buy") || side.equals("buy_to_cover");
        move(account, s, BigDecimal.valueOf(buy ? quantity : -quantity));
        street(s, buy, side.equals("sell_short"), quantity, value);
    }

    /** Shares an ACATS transfer the ledger booked brought in (positive) or delivered out (negative), through DTC. */
    void transfer(long account, Security s, BigDecimal quantity) {
        move(account, s, quantity);
    }

    /** A split on its ex-date, before any customer's: the firm's own shares, rescaled. */
    void split(Security s, long ratioNew, long ratioOld) {
        firm.computeIfPresent(s.id(), (k, q) -> q.multiply(BigDecimal.valueOf(ratioNew))
            .divide(BigDecimal.valueOf(ratioOld), 5, RoundingMode.DOWN));
    }

    /** A customer's position after the ledger rescaled it for a split and paid any fraction as cash in lieu. */
    void resized(long account, Security s, BigDecimal position) {
        BigDecimal now = customers.getOrDefault(s.id(), new TreeMap<>()).getOrDefault(account, BigDecimal.ZERO);
        move(account, s, position.subtract(now));
    }

    private void move(long account, Security s, BigDecimal quantity) {
        TreeMap<Long, BigDecimal> positions = customers.computeIfAbsent(s.id(), k -> new TreeMap<>());
        if (positions.merge(account, quantity, BigDecimal::add).signum() == 0) {
            positions.remove(account);
        }
    }

    private void street(Security s, boolean buy, boolean shortSale, long quantity, BigDecimal value) {
        Street st = today.computeIfAbsent(s.id(), k -> new Street());
        if (buy) {
            st.buyQuantity += quantity;
            st.buyAmount = st.buyAmount.add(value);
        } else {
            st.sellQuantity += quantity;
            st.sellAmount = st.sellAmount.add(value);
            if (shortSale) {
                st.shortQuantity += quantity;
            }
        }
    }

    /**
     * 08:00 on a settlement date: each obligation due today settles (one fails, if the break is still to be seeded),
     * fails whose close-out has settled are delivered, and fails past their settlement date age.
     *
     * @param businessDays the business days the world played before today
     */
    void settle(Instant at, LocalDate day, int businessDays) {
        for (Obligation ob : due.getOrDefault(day, List.of())) {
            long quantityDue = Math.abs(ob.net());
            long failed = 0;
            if (seedBreak && !breakSeeded && businessDays >= 2 && ob.net() < 0 && ob.shortQuantity() > 0) {
                breakSeeded = true;
                failed = Math.min(quantityDue, ob.shortQuantity());
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("settlement_id", "STL-" + (90_000_000 + nextSettlement++));
            row.put("obligation_id", ob.id());
            row.put("security_id", ob.security().id());
            row.put("symbol", ob.security().symbol());
            row.put("settlement_date", day);
            row.put("direction", direction(ob.net()));
            row.put("quantity_due", quantityDue);
            row.put("quantity_settled", quantityDue - failed);
            row.put("quantity_failed", failed);
            row.put("net_amount", ob.netAmount());
            row.put("status", failed > 0 ? "partially_settled" : "settled");
            row.put("settled_at", at);
            emitter.emit(at, Feed.SETTLEMENTS, row);
            if (failed > 0) {
                LocalDate closeOut = calendar.next(day);
                Fail f = new Fail("FTD-" + (95_000_000 + nextFail++), ob, failed,
                    closeOut.atTime(LocalTime.of(9, 30)).atZone(World.NEW_YORK).toInstant());
                fails.put(f.id, f);
                failEvent(at, day, f, "opened", null, null);
            }
        }
        due.remove(day);
        for (var it = fails.values().iterator(); it.hasNext(); ) {
            Fail f = it.next();
            if (day.equals(f.delivery)) {
                failEvent(at, day, f, "delivered", null, null);
                it.remove();
            } else if (!f.aged && f.obligation.settlementDate().isBefore(day)) {
                f.aged = true;
                failEvent(at, day, f, "aged", null, null);
            }
        }
    }

    /**
     * 09:30, the beginning of regular trading hours: each fail whose Reg SHO 204(a) deadline is now is closed out by
     * a purchase for the firm's own account, at the market, settling through CNS on the next settlement date.
     */
    void closeOuts(Instant at, LocalDate day) {
        for (Fail f : fails.values()) {
            if (f.delivery != null || at.isBefore(f.deadline)) {
                continue;
            }
            Security s = f.obligation.security();
            BigDecimal price = market.price(s);
            BigDecimal amount = price.multiply(BigDecimal.valueOf(f.quantity));
            firm.merge(s.id(), BigDecimal.valueOf(f.quantity), BigDecimal::add);
            street(s, true, false, f.quantity, amount);
            f.delivery = calendar.settlement(day);
            failEvent(at, day, f, "closed_out", price, amount);
        }
    }

    private void failEvent(Instant at, LocalDate day, Fail f, String type, BigDecimal price, BigDecimal amount) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("fail_event_id", nextFailEvent++);
        row.put("fail_id", f.id);
        row.put("event_type", type);
        row.put("obligation_id", f.obligation.id());
        row.put("security_id", f.obligation.security().id());
        row.put("symbol", f.obligation.security().symbol());
        row.put("settlement_date", f.obligation.settlementDate());
        row.put("quantity", f.quantity);
        row.put("reason", "borrow_not_received");
        row.put("reg_sho_rule", REG_SHO_204);
        row.put("close_out_deadline", f.deadline);
        row.put("close_out_price", price);
        row.put("close_out_amount", amount);
        row.put("business_date", day);
        row.put("event_time", at);
        emitter.emit(at, Feed.FAILS, row);
    }

    /** 17:00: the day's CNS obligations, one per security traded, then the stock record. */
    void endOfDay(Instant at, LocalDate day) {
        LocalDate settlement = calendar.settlement(day);
        for (Map.Entry<Long, Street> e : today.entrySet()) {
            Security s = security(e.getKey());
            Street st = e.getValue();
            long net = st.buyQuantity - st.sellQuantity;
            BigDecimal netAmount = st.sellAmount.subtract(st.buyAmount);
            Obligation ob = new Obligation("CNS-" + settlement.format(BASIC) + "-" + s.id(), s, day, settlement, net,
                netAmount, st.shortQuantity);
            due.computeIfAbsent(settlement, k -> new ArrayList<>()).add(ob);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("obligation_id", ob.id());
            row.put("security_id", s.id());
            row.put("symbol", s.symbol());
            row.put("cusip", s.cusip());
            row.put("trade_date", day);
            row.put("settlement_date", settlement);
            row.put("buy_quantity", st.buyQuantity);
            row.put("buy_amount", st.buyAmount);
            row.put("sell_quantity", st.sellQuantity);
            row.put("sell_amount", st.sellAmount);
            row.put("short_sale_quantity", st.shortQuantity);
            row.put("net_quantity", net);
            row.put("net_amount", netAmount);
            row.put("direction", direction(net));
            row.put("computed_at", at);
            emitter.emit(at, Feed.CNS_OBLIGATIONS, row);
        }
        today.clear();
        stockRecord(at, day);
    }

    private void stockRecord(Instant at, LocalDate day) {
        Map<Long, Long> pending = new TreeMap<>();
        for (List<Obligation> obligations : due.values()) {
            for (Obligation ob : obligations) {
                pending.merge(ob.security().id(), ob.net(), Long::sum);
            }
        }
        Map<Long, Long> failed = new TreeMap<>();
        for (Fail f : fails.values()) {
            if (f.delivery == null || f.delivery.isAfter(day)) {
                failed.merge(f.obligation.security().id(), f.quantity, Long::sum);
            }
        }
        TreeSet<Long> securities = new TreeSet<>(customers.keySet());
        securities.addAll(firm.keySet());
        securities.addAll(pending.keySet());
        securities.addAll(failed.keySet());
        for (long sec : securities) {
            Security s = security(sec);
            BigDecimal close = market.price(s);
            BigDecimal owned = BigDecimal.ZERO;
            for (Map.Entry<Long, BigDecimal> e : customers.getOrDefault(sec, new TreeMap<>()).entrySet()) {
                owned = owned.add(e.getValue());
                position(at, day, s, close, "owner", "customer", e.getKey(), e.getValue());
            }
            BigDecimal own = firm.getOrDefault(sec, BigDecimal.ZERO);
            owned = owned.add(own);
            position(at, day, s, close, "owner", "firm", null, own);
            BigDecimal p = BigDecimal.valueOf(pending.getOrDefault(sec, 0L));
            BigDecimal f = BigDecimal.valueOf(failed.getOrDefault(sec, 0L));
            BigDecimal held = owned.subtract(p).add(f);
            position(at, day, s, close, "location", "dtc", null, held.max(BigDecimal.ZERO));
            position(at, day, s, close, "location", "stock_borrow", null, held.min(BigDecimal.ZERO));
            position(at, day, s, close, "location", "cns_pending", null, p);
            position(at, day, s, close, "location", "fail_to_deliver", null, f.negate());
        }
    }

    private void position(Instant at, LocalDate day, Security s, BigDecimal close, String side, String category,
                          Long account, BigDecimal quantity) {
        if (quantity.signum() == 0) {
            return;
        }
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("business_date", day);
        row.put("position_id", category + "-" + (account == null ? "" : account + "-") + s.id());
        row.put("security_id", s.id());
        row.put("symbol", s.symbol());
        row.put("cusip", s.cusip());
        row.put("side", side);
        row.put("category", category);
        row.put("brokerage_account", account);
        row.put("quantity", quantity);
        row.put("close_price", close);
        row.put("market_value", close.multiply(quantity).setScale(2, RoundingMode.HALF_UP));
        emitter.emit(at, Feed.STOCK_RECORD, row);
    }

    private Security security(long id) {
        return market.byIndex((int) (id - 100001));
    }

    private static String direction(long net) {
        return net > 0 ? "receive" : net < 0 ? "deliver" : "flat";
    }
}
