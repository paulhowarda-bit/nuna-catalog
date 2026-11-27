package nuna.datagen.world;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import nuna.datagen.kit.Engine;

/**
 * The firm's customer protection and net capital (item 153), from the ledger and the stock record. It draws nothing at
 * random. Synthetic, and simplified (choices made here, Paul may overrule):
 *
 * <p>The SEC Rule 15c3-3 reserve computation, weekly as of the last business day of the week: credits are the
 * customers' free credit balances (cash, 1001, above zero); debits are their margin debit balances less 1% (Exhibit A,
 * Note E), the value of the shares the firm borrowed to deliver (the stock record's {@code stock_borrow}) and of its
 * fails to deliver. When credits exceed debits the special reserve bank account must hold the excess; the firm keeps
 * it at the requirement rounded up to $50,000, depositing (or withdrawing) the difference by the second business day.
 *
 * <p>Net capital under SEC Rule 15c3-1's alternative method, at each month end: ownership equity is a fixed
 * $25,000,000 plus the month's margin interest and account fees earned (1303, 1304) to date; nonallowable assets are a
 * fixed $1,250,000; haircuts are 15% of the firm's own positions (the stock record's {@code firm}, from close-outs);
 * the minimum is the greater of $250,000 and 2% of aggregate debit items, the early warning level 5% of them.
 */
final class Regulatory {

    private static final BigDecimal DEBIT_REDUCTION = new BigDecimal("0.01"), ROUND = new BigDecimal(50_000);
    private static final BigDecimal BASE_EQUITY = new BigDecimal("25000000.00"),
        NONALLOWABLE = new BigDecimal("1250000.00"), HAIRCUT = new BigDecimal("0.15"),
        MINIMUM = new BigDecimal("250000.00");

    private final Emitter emitter;
    private final Engine engine;
    private final MarketCalendar calendar;
    private BigDecimal borrowed = BigDecimal.ZERO, failsToDeliver = BigDecimal.ZERO, firmPositions = BigDecimal.ZERO;
    private BigDecimal reserveAccount = BigDecimal.ZERO.setScale(2), earned = BigDecimal.ZERO;

    Regulatory(Emitter emitter, Engine engine, MarketCalendar calendar) {
        this.emitter = emitter;
        this.engine = engine;
        this.calendar = calendar;
    }

    /** The day's stock record, as clearing writes it: borrows, fails to deliver and the firm's own positions. */
    void observe(Feed feed, Map<String, Object> row) {
        if (feed != Feed.STOCK_RECORD) {
            return;
        }
        BigDecimal value = ((BigDecimal) row.get("market_value")).abs();
        switch ((String) row.get("category")) {
            case "stock_borrow" -> borrowed = borrowed.add(value);
            case "fail_to_deliver" -> failsToDeliver = failsToDeliver.add(value);
            case "firm" -> firmPositions = firmPositions.add(value);
            default -> {
            }
        }
    }

    private BigDecimal[] customerBalances(List<World.Account> accounts) {
        BigDecimal credits = BigDecimal.ZERO, debits = BigDecimal.ZERO;
        for (World.Account a : accounts) {
            BigDecimal cash = engine.balance(a.id(), 1001, null, null);
            if (cash.signum() > 0) {
                credits = credits.add(cash);
            } else {
                debits = debits.subtract(cash);
            }
        }
        return new BigDecimal[] {credits, debits};
    }

    /** 17:00, after the stock record: on the week's last business day, the reserve computation. */
    void endOfDay(Instant at, LocalDate day, List<World.Account> accounts) {
        for (World.Account a : accounts) {
            if (calendar.isMonthEnd(day)) {
                earned = earned.add(engine.normalBalance(a.id(), 1303)).add(engine.normalBalance(a.id(), 1304));
            }
        }
        LocalDate next = calendar.next(day);
        if (next.get(java.time.temporal.IsoFields.WEEK_OF_WEEK_BASED_YEAR)
            != day.get(java.time.temporal.IsoFields.WEEK_OF_WEEK_BASED_YEAR) || next.minusDays(7).isAfter(day)) {
            reserve(at, day, accounts);
        }
        if (!calendar.isMonthEnd(day)) {
            reset();
        }
    }

    private void reset() {
        borrowed = BigDecimal.ZERO;
        failsToDeliver = BigDecimal.ZERO;
        firmPositions = BigDecimal.ZERO;
    }

    private void reserve(Instant at, LocalDate day, List<World.Account> accounts) {
        BigDecimal[] b = customerBalances(accounts);
        BigDecimal credits = b[0].setScale(2, RoundingMode.HALF_UP);
        BigDecimal reduction = b[1].multiply(DEBIT_REDUCTION).setScale(2, RoundingMode.HALF_UP);
        BigDecimal debits = b[1].subtract(reduction).add(borrowed).add(failsToDeliver).setScale(2, RoundingMode.HALF_UP);
        BigDecimal excess = credits.subtract(debits);
        BigDecimal required = excess.max(BigDecimal.ZERO);
        BigDecimal target = required.divide(ROUND, 0, RoundingMode.CEILING).multiply(ROUND).setScale(2);
        BigDecimal deposit = target.subtract(reserveAccount);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("computation_date", day);
        row.put("free_credit_balances", credits);
        row.put("total_credits", credits);
        row.put("customer_debit_balances", b[1].setScale(2, RoundingMode.HALF_UP));
        row.put("debit_reduction", reduction);
        row.put("securities_borrowed", borrowed.setScale(2, RoundingMode.HALF_UP));
        row.put("fails_to_deliver", failsToDeliver.setScale(2, RoundingMode.HALF_UP));
        row.put("total_debits", debits);
        row.put("excess_of_credits", excess);
        row.put("reserve_required", required);
        row.put("reserve_account_before", reserveAccount);
        row.put("deposit", deposit);
        row.put("reserve_account_after", target);
        row.put("deposit_due_date", calendar.next(calendar.next(day)));
        row.put("business_date", day);
        row.put("event_time", at);
        emitter.emit(at, Feed.RESERVE_COMPUTATION, row);
        reserveAccount = target;
    }

    /** At a month's close: the net capital computation as of its last business day. */
    void close(YearMonth period, LocalDate periodEnd, List<World.Account> accounts, List<Map<String, Object>> out) {
        BigDecimal[] b = customerBalances(accounts);
        BigDecimal adi = b[1].subtract(b[1].multiply(DEBIT_REDUCTION)).add(borrowed).add(failsToDeliver)
            .setScale(2, RoundingMode.HALF_UP);
        BigDecimal equity = BASE_EQUITY.add(earned).setScale(2, RoundingMode.HALF_UP);
        BigDecimal haircuts = firmPositions.multiply(HAIRCUT).setScale(2, RoundingMode.HALF_UP);
        BigDecimal net = equity.subtract(NONALLOWABLE).subtract(haircuts);
        BigDecimal minimum = MINIMUM.max(adi.multiply(new BigDecimal("0.02"))).setScale(2, RoundingMode.HALF_UP);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("period", period.toString());
        row.put("as_of_date", periodEnd);
        row.put("method", "alternative");
        row.put("total_ownership_equity", equity);
        row.put("nonallowable_assets", NONALLOWABLE);
        row.put("haircuts", haircuts);
        row.put("net_capital", net);
        row.put("aggregate_debit_items", adi);
        row.put("minimum_net_capital", minimum);
        row.put("excess_net_capital", net.subtract(minimum));
        row.put("early_warning_level", adi.multiply(new BigDecimal("0.05")).setScale(2, RoundingMode.HALF_UP));
        out.add(row);
        reset();
    }
}
