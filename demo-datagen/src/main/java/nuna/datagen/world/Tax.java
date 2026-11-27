package nuna.datagen.world;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import nuna.datagen.kit.Engine;
import nuna.datagen.kit.FinancialTransaction;
import nuna.datagen.kit.FinancialTransaction.LotShares;
import nuna.datagen.world.Market.Security;

/**
 * Cost basis and tax (item 153): the tax lots, each lot's part of a sale (IRC 6045), wash sales (IRC 1091) and, at each
 * month-end close, the month's 1099-B and 1099-DIV lines. A long tax lot is the ledger's share lot (its id is the buy
 * fill's financialTransactionId, or the ACATS receipt's id times 1,000 plus its place), so its book cost is the
 * ledger's remaining cost (R12) and the open lots' cost per account and security is Securities at Cost (1002), to the
 * cent: tie 2.
 *
 * <p>A lot's tax basis is its book cost plus the losses wash sales disallowed into it. A loss on a long lot sold
 * (template 44) is washed by shares of the same security the account bought (template 41) within 30 days before or
 * after the sale, each replacement share once, still held; the disallowed loss moves into the replacement lot's basis
 * and the sold lot's holding period onto it. A wash found after the close of the sale's month changes the lot's basis
 * and is recorded in wash_sales, but the month's 1099-B line is not reissued (a corrected 1099-B is left out).
 *
 * <p>A realization's gain is the ledger's: its share of the sale's gross proceeds (by shares, the last lot taking the
 * rounding) less the cost relieved, so a sale's realizations add up to its Realized Gain/Loss (1202) movement. The
 * 1099-B line reports proceeds (box 1d, gross), cost basis (1e: book cost and earlier wash adjustments), the loss
 * disallowed (1g) and gain or loss (proceeds less basis plus the loss disallowed): tie 8. A short sale is realized when
 * covered (template 64), short term, with no wash sale. It draws nothing at random.
 */
final class Tax {

    private static final MathContext CONTEXT = new MathContext(28, RoundingMode.HALF_EVEN);
    private static final LocalDate COVERED_FROM = LocalDate.of(2011, 1, 1);

    private static final class Lot {
        final long id, account;
        final Security security;
        final LocalDate acquired;
        final String acquiredBy;
        LocalDate holdingStart;
        BigDecimal washAdjustment = BigDecimal.ZERO;
        /** Shares that may still replace a loss sold: a purchase's shares, once each. */
        BigDecimal replacementLeft;

        Lot(long id, long account, Security security, LocalDate acquired, String acquiredBy, BigDecimal replacement) {
            this.id = id;
            this.account = account;
            this.security = security;
            this.acquired = acquired;
            this.acquiredBy = acquiredBy;
            this.holdingStart = acquired;
            this.replacementLeft = replacement;
        }
    }

    /** A realization of the month not yet on a 1099-B line. */
    private static final class Realization {
        final String id;
        final long transaction, account, lot;
        final Security security;
        final BigDecimal shares, proceeds, cost, basisAdjustment;
        final LocalDate acquired, sold;
        final String term;
        final boolean covered;
        BigDecimal disallowed = BigDecimal.ZERO;

        Realization(String id, long transaction, long account, long lot, Security security, BigDecimal shares,
                    BigDecimal proceeds, BigDecimal cost, BigDecimal basisAdjustment, LocalDate acquired, LocalDate sold,
                    String term, boolean covered) {
            this.id = id;
            this.transaction = transaction;
            this.account = account;
            this.lot = lot;
            this.security = security;
            this.shares = shares;
            this.proceeds = proceeds;
            this.cost = cost;
            this.basisAdjustment = basisAdjustment;
            this.acquired = acquired;
            this.sold = sold;
            this.term = term;
            this.covered = covered;
        }
    }

    /** A loss sold whose shares no purchase has washed yet, for 30 days after the sale. */
    private record OpenLoss(Realization realization, long account, long security, LocalDate sold, long holdingDays,
                            BigDecimal lossPerShare, BigDecimal[] sharesLeft) {}

    private record Dividend(long account, Security security, String corporateAction, BigDecimal qualifiedFraction) {}

    private final Emitter emitter;
    private final Engine engine;
    private final Map<Long, Lot> lots = new HashMap<>();
    /** Open long lots per account and security, in the order they opened. */
    private final Map<Long, Map<Long, List<Long>>> open = new TreeMap<>();
    private final Map<Long, LocalDate> shortOpened = new HashMap<>();
    private final List<OpenLoss> openLosses = new ArrayList<>();
    private final Map<String, Realization> month = new LinkedHashMap<>();
    private final Map<Long, Dividend> dividends = new HashMap<>();
    private final List<Map<String, Object>> dividendLines = new ArrayList<>();
    private long nextWash = 1;

    Tax(Emitter emitter, Engine engine) {
        this.emitter = emitter;
        this.engine = engine;
    }

    /** A long lot opened: a buy fill's (41) or an ACATS receipt's (83). A purchase may wash a loss sold before it. */
    void opened(Instant at, LocalDate day, long account, Security s, long lot, LocalDate acquired, boolean purchase) {
        BigDecimal shares = engine.freeShares(account, s.id(), lot);
        Lot l = new Lot(lot, account, s, acquired, purchase ? "purchase" : "acats_in",
            purchase ? shares : BigDecimal.ZERO);
        lots.put(lot, l);
        open.computeIfAbsent(account, k -> new TreeMap<>()).computeIfAbsent(s.id(), k -> new ArrayList<>()).add(lot);
        if (!purchase) {
            return;
        }
        for (OpenLoss loss : openLosses) {
            if (loss.account() != account || loss.security() != s.id() || loss.sharesLeft()[0].signum() == 0
                || l.replacementLeft.signum() == 0) {
                continue;
            }
            BigDecimal m = loss.sharesLeft()[0].min(l.replacementLeft);
            BigDecimal disallowed = Engine.cents(loss.lossPerShare().multiply(m, CONTEXT));
            loss.sharesLeft()[0] = loss.sharesLeft()[0].subtract(m);
            l.replacementLeft = l.replacementLeft.subtract(m);
            wash(at, day, loss.realization(), loss.sold(), loss.holdingDays(), l, m, disallowed, "at_replacement");
        }
    }

    void shortOpened(long lot, LocalDate day) {
        shortOpened.put(lot, day);
    }

    /** Whether a lot can be busted as it stands: no wash sale has moved a loss into it or used it as a replacement. */
    boolean untouched(long lot) {
        Lot l = lots.get(lot);
        return l != null && l.washAdjustment.signum() == 0 && l.replacementLeft.compareTo(
            engine.freeShares(l.account, l.security.id(), lot)) == 0;
    }

    /**
     * Long lots out, after the ledger posted it: a sale (44, gross proceeds), cash in lieu of a fraction (87, its
     * amount) or an ACATS delivery (84, no proceeds: a transfer is no sale).
     */
    void disposed(Instant at, LocalDate day, FinancialTransaction t, Security s, List<LotShares> named,
                  BigDecimal proceeds) {
        long account = t.brokerageAccount();
        BigDecimal total = BigDecimal.ZERO;
        for (LotShares ls : named) {
            total = total.add(ls.shares());
        }
        BigDecimal gross = proceeds == null ? null : Engine.cents(proceeds);
        BigDecimal left = gross;
        int n = 0;
        for (LotShares ls : named) {
            n++;
            Lot l = lots.get(ls.lot());
            BigDecimal after = engine.freeShares(account, s.id(), ls.lot());
            BigDecimal before = after.add(ls.shares());
            BigDecimal cost = engine.remainingCost(ls.lot(), before).subtract(engine.remainingCost(ls.lot(), after));
            BigDecimal basis = Engine.cents(l.washAdjustment.multiply(ls.shares(), CONTEXT).divide(before, CONTEXT));
            l.washAdjustment = l.washAdjustment.subtract(basis);
            if (after.signum() == 0) {
                close(l);
            }
            if (proceeds == null) {
                continue;
            }
            BigDecimal part = n == named.size() ? left
                : Engine.cents(gross.multiply(ls.shares(), CONTEXT).divide(total, CONTEXT));
            left = left.subtract(part);
            Realization r = realize(at, day, t, s, ls.lot(), "long", ls.shares(), part, cost, basis, l.acquired,
                l.holdingStart);
            BigDecimal loss = cost.subtract(part);
            if (t.template() == 44 && loss.signum() > 0) {
                washBefore(at, day, r, l, ls.shares(), loss);
            }
        }
    }

    /** Short lots covered (template 64), after the ledger posted it: each lot's proceeds less its share of the cost. */
    void covered(Instant at, LocalDate day, FinancialTransaction t, Security s, List<LotShares> named, BigDecimal cost) {
        long account = t.brokerageAccount();
        BigDecimal total = BigDecimal.ZERO;
        for (LotShares ls : named) {
            total = total.add(ls.shares());
        }
        BigDecimal left = Engine.cents(cost);
        int n = 0;
        for (LotShares ls : named) {
            n++;
            BigDecimal after = engine.freeShort(account, s.id(), ls.lot());
            BigDecimal proceeds = engine.remainingProceeds(ls.lot(), after.add(ls.shares()))
                .subtract(engine.remainingProceeds(ls.lot(), after));
            BigDecimal part = n == named.size() ? left
                : Engine.cents(cost.multiply(ls.shares(), CONTEXT).divide(total, CONTEXT));
            left = left.subtract(part);
            LocalDate opened = shortOpened.getOrDefault(ls.lot(), day);
            realize(at, day, t, s, ls.lot(), "short", ls.shares(), proceeds, part, BigDecimal.ZERO, opened, day);
            if (after.signum() == 0) {
                shortOpened.remove(ls.lot());
            }
        }
    }

    private Realization realize(Instant at, LocalDate day, FinancialTransaction t, Security s, long lot, String side,
                                BigDecimal shares, BigDecimal proceeds, BigDecimal cost, BigDecimal basis,
                                LocalDate acquired, LocalDate holdingStart) {
        String id = "RG-" + t.id() + "-" + lot;
        String term = side.equals("long") && day.isAfter(holdingStart.plusYears(1)) ? "long_term" : "short_term";
        Realization r = new Realization(id, t.id(), t.brokerageAccount(), lot, s, shares, proceeds, cost, basis,
            acquired, day, term, !acquired.isBefore(COVERED_FROM));
        month.put(id, r);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("realization_id", id);
        row.put("financial_transaction_id", t.id());
        row.put("template_id", t.template());
        row.put("brokerage_account", t.brokerageAccount());
        row.put("security_id", s.id());
        row.put("symbol", s.symbol());
        row.put("lot", lot);
        row.put("position_side", side);
        row.put("shares", shares);
        row.put("date_acquired", acquired);
        row.put("date_sold", day);
        row.put("proceeds", proceeds);
        row.put("cost", cost);
        row.put("basis_wash_adjustment", basis);
        row.put("gain_loss", proceeds.subtract(cost));
        row.put("term", term);
        row.put("business_date", day);
        row.put("event_time", at);
        emitter.emit(at, Feed.REALIZED_GAINS, row);
        return r;
    }

    /** A loss sold: shares of the same security bought within the 30 days before wash it now; the rest waits. */
    private void washBefore(Instant at, LocalDate day, Realization r, Lot sold, BigDecimal shares, BigDecimal loss) {
        BigDecimal left = shares;
        long holdingDays = ChronoUnit.DAYS.between(sold.holdingStart, day);
        BigDecimal perShare = loss.divide(shares, CONTEXT);
        for (long id : open.getOrDefault(r.account, Map.of()).getOrDefault(r.security.id(), List.of())) {
            Lot l = lots.get(id);
            if (left.signum() == 0) {
                break;
            }
            if (id == sold.id || l.replacementLeft.signum() == 0 || l.acquired.isBefore(day.minusDays(30))) {
                continue;
            }
            BigDecimal m = left.min(l.replacementLeft).min(engine.freeShares(r.account, r.security.id(), id));
            if (m.signum() <= 0) {
                continue;
            }
            left = left.subtract(m);
            l.replacementLeft = l.replacementLeft.subtract(m);
            wash(at, day, r, day, holdingDays, l, m, Engine.cents(perShare.multiply(m, CONTEXT)), "at_sale");
        }
        if (left.signum() > 0) {
            openLosses.add(new OpenLoss(r, r.account, r.security.id(), day, holdingDays, perShare,
                new BigDecimal[] {left}));
        }
    }

    private void wash(Instant at, LocalDate day, Realization r, LocalDate sold, long holdingDays, Lot replacement,
                      BigDecimal shares, BigDecimal disallowed, String found) {
        if (disallowed.signum() == 0) {
            return;
        }
        r.disallowed = r.disallowed.add(disallowed);
        replacement.washAdjustment = replacement.washAdjustment.add(disallowed);
        LocalDate tacked = replacement.acquired.minusDays(holdingDays);
        if (tacked.isBefore(replacement.holdingStart)) {
            replacement.holdingStart = tacked;
        }
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("wash_sale_id", "WS-" + (70_000_000 + nextWash++));
        row.put("realization_id", r.id);
        row.put("brokerage_account", r.account);
        row.put("security_id", r.security.id());
        row.put("symbol", r.security.symbol());
        row.put("loss_lot", r.lot);
        row.put("replacement_lot", replacement.id);
        row.put("shares", shares);
        row.put("loss_disallowed", disallowed);
        row.put("sale_date", sold);
        row.put("replacement_date", replacement.acquired);
        row.put("found", found);
        row.put("business_date", day);
        row.put("event_time", at);
        emitter.emit(at, Feed.WASH_SALES, row);
    }

    private void close(Lot l) {
        List<Long> ids = open.getOrDefault(l.account, Map.of()).get(l.security.id());
        if (ids != null) {
            ids.remove(l.id);
            if (ids.isEmpty()) {
                open.get(l.account).remove(l.security.id());
            }
        }
        lots.remove(l.id);
    }

    /** A lot a bust emptied (template 92): it leaves the tax lots, as if never bought. */
    void busted(long lot) {
        Lot l = lots.get(lot);
        if (l != null) {
            close(l);
        }
    }

    /** The part of a long position held long enough at the ex-date for its dividend to be qualified (61 days). */
    BigDecimal qualifiedFraction(long account, long security, LocalDate exDate) {
        BigDecimal held = BigDecimal.ZERO, qualified = BigDecimal.ZERO;
        for (long id : open.getOrDefault(account, Map.of()).getOrDefault(security, List.of())) {
            BigDecimal shares = engine.freeShares(account, security, id);
            held = held.add(shares);
            if (!lots.get(id).holdingStart.isAfter(exDate.minusDays(61))) {
                qualified = qualified.add(shares);
            }
        }
        return held.signum() == 0 ? BigDecimal.ZERO : qualified.divide(held, 6, RoundingMode.DOWN);
    }

    void dividendEntitled(long entitlement, long account, Security s, String corporateAction, BigDecimal fraction) {
        dividends.put(entitlement, new Dividend(account, s, corporateAction, fraction));
    }

    /** A cash dividend paid (template 89): a line of the month's 1099-DIV. */
    void dividendPaid(long transaction, long entitlement, BigDecimal amount, LocalDate payDate) {
        Dividend d = dividends.remove(entitlement);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("tax_year", payDate.getYear());
        row.put("line_id", "1099DIV-" + transaction);
        row.put("brokerage_account", d.account());
        row.put("corporate_action_id", d.corporateAction());
        row.put("security_id", d.security().id());
        row.put("symbol", d.security().symbol());
        row.put("pay_date", payDate);
        row.put("ordinary_dividends", amount);
        row.put("qualified_dividends", amount.multiply(d.qualifiedFraction()).setScale(2, RoundingMode.DOWN));
        row.put("federal_tax_withheld", BigDecimal.ZERO.setScale(2));
        row.put("financial_transaction_id", transaction);
        dividendLines.add(row);
    }

    /** 17:00: every open lot as it stands. Expired open losses are dropped. */
    void endOfDay(Instant at, LocalDate day) {
        openLosses.removeIf(l -> l.sharesLeft()[0].signum() == 0 || l.sold().isBefore(day.minusDays(30)));
        open.forEach((account, bySecurity) -> bySecurity.forEach((security, ids) -> {
            for (long id : ids) {
                Lot l = lots.get(id);
                BigDecimal shares = engine.freeShares(account, security, id);
                if (shares.signum() == 0) {
                    continue;
                }
                BigDecimal cost = engine.remainingCost(id, shares);
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("business_date", day);
                row.put("lot", id);
                row.put("brokerage_account", account);
                row.put("security_id", security);
                row.put("symbol", l.security.symbol());
                row.put("acquired_by", l.acquiredBy);
                row.put("acquired_date", l.acquired);
                row.put("holding_period_start", l.holdingStart);
                row.put("shares", shares);
                row.put("cost", cost);
                row.put("wash_sale_adjustment", l.washAdjustment);
                row.put("tax_basis", cost.add(l.washAdjustment));
                row.put("covered", !l.acquired.isBefore(COVERED_FROM));
                emitter.emit(at, Feed.TAX_LOTS, row);
            }
        }));
    }

    /** The month's 1099-B and 1099-DIV lines, for the close; the month's realizations and dividends are then done. */
    void close(YearMonth period, List<Map<String, Object>> form1099b, List<Map<String, Object>> form1099div) {
        for (Realization r : month.values()) {
            BigDecimal basis = r.cost.add(r.basisAdjustment);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("period", period.toString());
            row.put("tax_year", r.sold.getYear());
            row.put("line_id", "1099B-" + r.id.substring(3));
            row.put("brokerage_account", r.account);
            row.put("realization_id", r.id);
            row.put("financial_transaction_id", r.transaction);
            row.put("lot", r.lot);
            row.put("description", r.shares.stripTrailingZeros().toPlainString() + " sh " + r.security.symbol());
            row.put("security_id", r.security.id());
            row.put("symbol", r.security.symbol());
            row.put("cusip", r.security.cusip());
            row.put("quantity", r.shares);
            row.put("date_acquired", r.acquired);
            row.put("date_sold", r.sold);
            row.put("proceeds", r.proceeds);
            row.put("cost_basis", basis);
            row.put("wash_sale_loss_disallowed", r.disallowed);
            row.put("gain_or_loss", r.proceeds.subtract(basis).add(r.disallowed));
            row.put("basis_wash_adjustment", r.basisAdjustment);
            row.put("term", r.term);
            row.put("covered", r.covered);
            row.put("gross_proceeds_reported", true);
            form1099b.add(row);
        }
        month.clear();
        for (Map<String, Object> line : dividendLines) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("period", period.toString());
            row.putAll(line);
            form1099div.add(row);
        }
        dividendLines.clear();
    }
}
