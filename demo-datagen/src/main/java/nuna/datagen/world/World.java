package nuna.datagen.world;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.TreeMap;
import nuna.datagen.kit.Engine;
import nuna.datagen.kit.Engine.BalanceKey;
import nuna.datagen.kit.Engine.LedgerKey;
import nuna.datagen.kit.FinancialTransaction;
import nuna.datagen.kit.FinancialTransaction.LotShares;
import nuna.datagen.kit.JournalLine;
import nuna.datagen.kit.Kit;
import nuna.datagen.kit.Rejected;
import nuna.datagen.world.Market.Bar;
import nuna.datagen.world.Market.Security;

/**
 * The brokerage world: brokerage accounts with personas trading synthetic securities, deposits and withdrawals,
 * settlement at T+1 and month-end margin interest, every financial transaction booked through the demo kit's posting
 * engine. Seed, start date and scale give the same events, in the same order, every time.
 *
 * <p>A business day runs on the New York clock: settlements at 08:00, the session from 09:30 to 16:00 in five-minute
 * bars, unfilled day orders expired at the close, balances at 17:00. Each day ends with {@link Sink#endOfDay}.
 */
public final class World {

    public static final ZoneId NEW_YORK = ZoneId.of("America/New_York");
    public static final long USD = 840;

    /** How the world is drawn. */
    public record Settings(long seed, LocalDate start, int accounts, int securities) {
        public static Settings of(long seed, LocalDate start, int accounts) {
            return new Settings(seed, start, accounts, 300);
        }
    }

    /** A brokerage account and the persona that trades it. */
    public record Account(long id, String kind, String persona, LocalDate opened, String disposalMethod) {
        boolean margin() {
            return kind.equals("margin");
        }
    }

    private static final BigDecimal SEC_FEE_RATE = new BigDecimal("0.0000278");
    private static final BigDecimal TAF_PER_SHARE = new BigDecimal("0.000166"), TAF_MAX = new BigDecimal("8.30");
    private static final BigDecimal MARGIN_RATE = new BigDecimal("0.12");
    private static final List<String> VENUES = List.of("XNYS", "XNAS", "ARCX", "EDGX", "BATS");

    private final Settings settings;
    private final Kit kit;
    private final Engine engine;
    private final MarketCalendar calendar = new MarketCalendar();
    private final Rng rng;
    private final Market market;
    private final List<Account> accounts = new ArrayList<>();
    private Sink sink;
    private LocalDate day;
    private boolean started;
    private int businessDays;
    private int unexpectedRejections;
    private boolean doubleSettlementSeeded;
    private long nextFt = 100_000_001L, nextOrder = 1, nextFill = 1, nextPayment = 1, nextLocate = 1, seq;

    private record Task(Instant at, long seq, Runnable run) {}

    private final PriorityQueue<Task> tasks =
        new PriorityQueue<>(Comparator.comparing(Task::at).thenComparingLong(Task::seq));

    /** An order in the order management system. */
    private final class Order {
        final long id = nextOrder++;
        final String ref = "ORD-" + (10_000_000 + id);
        final Account account;
        final Security security;
        final String side;              // buy, sell, sell_short, buy_to_cover
        final String type;              // market, limit
        final long quantity;
        final BigDecimal estimatedPrice;
        final BigDecimal limitPrice;
        final String locate;
        final Instant acceptedAt;
        final LocalDate tradeDate;
        long acceptance;
        long filled;
        BigDecimal estimateLeft;
        BigDecimal value = BigDecimal.ZERO;     // fill amount or gross proceeds, summed
        BigDecimal fees = BigDecimal.ZERO;
        String status = "accepted";

        Order(Account account, Security security, String side, String type, long quantity, BigDecimal estimatedPrice,
              BigDecimal limitPrice, String locate, Instant at) {
            this.account = account;
            this.security = security;
            this.side = side;
            this.type = type;
            this.quantity = quantity;
            this.estimatedPrice = estimatedPrice;
            this.limitPrice = limitPrice;
            this.locate = locate;
            this.acceptedAt = at;
            this.tradeDate = day;
            this.estimateLeft = estimatedPrice.multiply(BigDecimal.valueOf(quantity));
        }

        long open() {
            return quantity - filled;
        }
    }

    private record Payment(Account account, long initiation, BigDecimal amount, BigDecimal goodFaith) {}

    private final Map<LocalDate, List<Order>> settlements = new TreeMap<>();
    private final Map<LocalDate, List<Payment>> depositSettlements = new TreeMap<>();
    private final Map<LocalDate, List<Payment>> withdrawalSettlements = new TreeMap<>();
    private final Map<Long, Order> openOrders = new LinkedHashMap<>();
    /** Per brokerage account, the securities it holds long and short, and the lots it opened, in lot order. */
    private final Map<Long, Map<Long, List<Long>>> longLots = new HashMap<>(), shortLots = new HashMap<>();
    private final Map<Long, Map<Long, Long>> openCovers = new HashMap<>();

    public World(Settings settings, Kit kit) {
        this.settings = settings;
        this.kit = kit;
        this.engine = new Engine(kit);
        this.rng = new Rng(settings.seed());
        this.market = new Market(rng, settings.securities());
        String[] personas = {"dormant", "buy_and_hold", "active_trader", "short_seller"};
        double[] weights = {0.30, 0.42, 0.23, 0.05};
        for (int i = 0; i < settings.accounts(); i++) {
            double u = rng.uniform(), c = 0;
            String persona = personas[personas.length - 1];
            for (int p = 0; p < personas.length; p++) {
                c += weights[p];
                if (u < c) {
                    persona = personas[p];
                    break;
                }
            }
            boolean margin = persona.equals("short_seller") || rng.chance(persona.equals("active_trader") ? 0.6 : 0.2);
            accounts.add(new Account(70_000_001L + i, margin ? "margin" : "cash", persona,
                settings.start().minusDays(30 + rng.below(3000)), rng.chance(0.15) ? "HIFO" : "FIFO"));
        }
        this.day = calendar.isBusinessDay(settings.start()) ? settings.start() : calendar.next(settings.start());
    }

    public Engine engine() {
        return engine;
    }

    public Market market() {
        return market;
    }

    public MarketCalendar calendar() {
        return calendar;
    }

    public List<Account> accounts() {
        return accounts;
    }

    /** The business day the next {@link #runDay} plays. */
    public LocalDate day() {
        return day;
    }

    public int unexpectedRejections() {
        return unexpectedRejections;
    }

    private Instant at(LocalTime t) {
        return day.atTime(t).atZone(NEW_YORK).toInstant();
    }

    private void schedule(Instant at, Runnable run) {
        tasks.add(new Task(at, seq++, run));
    }

    private void emit(Instant at, Feed feed, Map<String, Object> row) {
        sink.accept(new Event(at, feed, row));
    }

    /** Plays the next business day into the sink and returns its date. */
    public LocalDate runDay(Sink sink) {
        this.sink = sink;
        LocalDate d = day;
        if (!started) {
            started = true;
            reference();
            for (Account a : accounts) {
                BigDecimal amount = dollars(rng.lognormal(a.margin() ? 40000 : 15000, a.persona().equals("dormant") ? 0.7 : 1.0), 100);
                schedule(at(LocalTime.of(7, 30)), () -> cashDeposit(a, amount));
            }
        }
        settleDue();
        for (Account a : accounts) {
            planDay(a);
        }
        LocalTime t = LocalTime.of(9, 30);
        for (int b = 0; b < Market.BARS; b++) {
            LocalTime start = t;
            t = t.plusMinutes(5);
            schedule(at(t), () -> bars(start));
        }
        schedule(at(LocalTime.of(16, 0, 1)), this::close);
        schedule(at(LocalTime.of(17, 0)), this::endOfDay);
        while (!tasks.isEmpty()) {
            Task task = tasks.poll();
            current = task.at();
            task.run().run();
        }
        businessDays++;
        sink.endOfDay(d);
        day = calendar.next(d);
        return d;
    }

    // ---------------------------------------------------------------- reference data, sent once

    private void reference() {
        Instant at = at(LocalTime.of(7, 0));
        for (LocalDate c = day.withDayOfYear(1); c.getYear() <= day.getYear() + 1; c = c.plusDays(1)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("calendar_date", c);
            row.put("is_business_day", calendar.isBusinessDay(c));
            row.put("holiday", calendar.holiday(c));
            row.put("settlement_date", calendar.isBusinessDay(c) ? calendar.settlement(c) : null);
            row.put("is_month_end", calendar.isBusinessDay(c) && calendar.isMonthEnd(c));
            emit(at, Feed.CALENDAR, row);
        }
        for (Security s : market.securities()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("security_id", s.id());
            row.put("symbol", s.symbol());
            row.put("name", s.name());
            row.put("security_type", s.type());
            row.put("sector", s.sector());
            row.put("cusip", s.cusip());
            row.put("isin", s.isin());
            row.put("currency_id", USD);
            row.put("listed_on", VENUES.get(market.index(s) % 3));
            row.put("unit_exponent", 5);
            emit(at, Feed.SECURITIES, row);
        }
        for (Kit.Root r : kit.chart().roots()) {
            emit(at, Feed.CHART_OF_ACCOUNTS, chartRow(r.id(), r.parent(), r.code(), r.name(), r.naturalClass(),
                r.measure(), "root", null, r.description(), List.of()));
        }
        for (Kit.Summary s : kit.chart().summaries()) {
            emit(at, Feed.CHART_OF_ACCOUNTS, chartRow(s.id(), s.parent(), s.code(), s.name(), s.naturalClass(),
                s.measure(), "summary", null, null, List.of()));
        }
        for (Kit.Definition d : kit.chart().definitions()) {
            emit(at, Feed.CHART_OF_ACCOUNTS, chartRow(d.id(), d.parent(), d.code(), d.name(), d.naturalClass(),
                d.measure(), "definition", d.normalSide().name(), d.description(), d.keyDimensions()));
        }
        for (Kit.BookingScheme b : kit.schemes().bookingSchemes()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("booking_scheme_id", b.id());
            row.put("name", b.name());
            row.put("template_id", b.template());
            row.put("template_name", kit.template(b.template()).name());
            row.put("calculations", b.calculations().size());
            row.put("journal_lines", b.lines().size());
            row.put("note", b.note());
            emit(at, Feed.BOOKING_SCHEMES, row);
        }
        for (Account a : accounts) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("brokerage_account", a.id());
            row.put("account_kind", a.kind());
            row.put("persona", a.persona());
            row.put("opened_on", a.opened());
            row.put("disposal_method", a.disposalMethod());
            row.put("base_currency_id", USD);
            row.put("status", "open");
            emit(at, Feed.ACCOUNTS, row);
        }
    }

    private Map<String, Object> chartRow(int id, Integer parent, String code, String name, String naturalClass,
                                         String measure, String level, String normalSide, String description,
                                         List<Integer> keyDimensions) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("definition_id", id);
        row.put("parent_id", parent);
        row.put("code", code);
        row.put("name", name);
        row.put("natural_class", naturalClass);
        row.put("measure", measure);
        row.put("level", level);
        row.put("normal_side", normalSide);
        row.put("may_reverse", level.equals("definition") && kit.mayReverse(id));
        row.put("key_dimensions", keyDimensions.stream().map(k -> kit.chart().dimensions().stream()
            .filter(x -> x.id() == k).findFirst().orElseThrow().name()).reduce((x, y) -> x + "," + y).orElse(null));
        row.put("description", description);
        return row;
    }

    // ---------------------------------------------------------------- booking

    private FinancialTransaction ft(int template, Account a, String originator, Map<Integer, Object> f) {
        Map<Integer, Object> fields = new TreeMap<>(f);
        long id = nextFt++;
        fields.put(FinancialTransaction.ID, id);
        fields.put(FinancialTransaction.EFFECTIVE_DATE, day.toEpochDay());
        fields.put(FinancialTransaction.ORIGINATOR, originator);
        fields.put(FinancialTransaction.BROKERAGE_ACCOUNT, a.id());
        fields.put(FinancialTransaction.CURRENCY, USD);
        fields.putIfAbsent(FinancialTransaction.ORIGINATING_ID, id);
        return new FinancialTransaction(template, a.id(), fields);
    }

    /** Books a financial transaction now and emits it and its journal lines, or its rejection. */
    private boolean book(Instant at, FinancialTransaction t, boolean rejectionExpected) {
        List<JournalLine> lines;
        try {
            lines = engine.post(t);
        } catch (Rejected r) {
            if (!rejectionExpected) {
                unexpectedRejections++;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("financial_transaction_id", t.id());
            row.put("template_id", t.template());
            row.put("template_name", kit.template(t.template()).name());
            row.put("brokerage_account", t.brokerageAccount());
            row.put("originating_financial_transaction_id", t.longOrNull(FinancialTransaction.ORIGINATING_ID));
            row.put("reason", r.reason());
            row.put("detail", r.getMessage());
            row.put("business_date", day);
            row.put("event_time", at);
            emit(at, Feed.POSTING_REJECTIONS, row);
            return false;
        }
        emit(at, Feed.FINANCIAL_TRANSACTIONS, transactionRow(at, t));
        Kit.BookingScheme scheme = kit.schemeFor(t.template());
        int n = 0;
        for (JournalLine l : lines) {
            n++;
            Kit.Definition d = kit.definition(l.definition());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("journal_line_id", t.id() * 1000 + n);
            row.put("financial_transaction_id", t.id());
            row.put("line_number", n);
            row.put("booking_scheme_id", scheme.id());
            row.put("template_id", t.template());
            row.put("sub_ledger_id", l.subLedger());
            row.put("sub_ledger", rootCode(l.subLedger()));
            row.put("definition_id", l.definition());
            row.put("definition_name", d.name());
            row.put("ledger_account_id", l.ledgerAccountId());
            row.put("brokerage_account", t.brokerageAccount());
            row.put("currency_id", d.measure().equals("Money") ? USD : null);
            row.put("security_id", l.security());
            row.put("lot", l.lot());
            row.put("measure", d.measure());
            row.put("side", l.side().name());
            row.put("amount", l.amount());
            row.put("effective_date", day);
            row.put("business_date", day);
            row.put("event_time", at);
            emit(at, Feed.JOURNAL_LINES, row);
        }
        return true;
    }

    private String rootCode(int root) {
        return kit.chart().roots().stream().filter(r -> r.id() == root).findFirst().orElseThrow().code();
    }

    private Map<String, Object> transactionRow(Instant at, FinancialTransaction t) {
        Kit.Template tpl = kit.template(t.template());
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("financial_transaction_id", t.id());
        row.put("template_id", t.template());
        row.put("template_name", tpl.name());
        row.put("booking_scheme_id", kit.schemeFor(t.template()).id());
        row.put("brokerage_account", t.brokerageAccount());
        row.put("security_id", t.longOrNull(FinancialTransaction.SECURITY));
        row.put("currency_id", t.longOrNull(FinancialTransaction.CURRENCY));
        row.put("quantity", t.has(FinancialTransaction.QUANTITY) ? t.decimal(FinancialTransaction.QUANTITY) : null);
        row.put("originating_financial_transaction_id", t.longOrNull(FinancialTransaction.ORIGINATING_ID));
        row.put("effective_date", LocalDate.ofEpochDay(t.longValue(FinancialTransaction.EFFECTIVE_DATE)));
        row.put("originator", t.get(FinancialTransaction.ORIGINATOR));
        row.put("business_date", day);
        row.put("event_time", at);
        tpl.fields().forEach((fid, name) -> {
            if (!t.has(fid)) {
                return;
            }
            Object v = t.get(fid);
            String column = snake(name);
            if (name.equals("lots")) {
                List<Map<String, Object>> lots = new ArrayList<>();
                for (LotShares ls : t.lots()) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("lot", ls.lot());
                    m.put("shares", ls.shares());
                    lots.add(m);
                }
                row.put(column, lots);
            } else if (name.endsWith("Date") || name.equals("periodStart") || name.equals("periodEnd")) {
                row.put(column, LocalDate.ofEpochDay(((Number) v).longValue()));
            } else if (v instanceof String) {
                row.put(column, v);
            } else {
                row.put(column, t.decimal(fid));
            }
        });
        return row;
    }

    static String snake(String camel) {
        return camel.replaceAll("([a-z])([A-Z])", "$1_$2").toLowerCase();
    }

    // ---------------------------------------------------------------- money movement

    private void cashDeposit(Account a, BigDecimal amount) {
        book(current, ft(30, a, "payments", Map.of(10, amount, 11, "PAY-" + (50_000_000 + nextPayment++))), false);
    }

    /** The business time of the task running now. */
    private Instant current;

    private void planDay(Account a) {
        double orders = switch (a.persona()) {
            case "dormant" -> 0.02;
            case "buy_and_hold" -> 0.10;
            case "active_trader" -> 1.4;
            default -> 1.6;
        };
        int n = rng.poisson(orders);
        for (int i = 0; i < n; i++) {
            schedule(at(LocalTime.of(9, 31).plusSeconds(rng.below(6 * 3600 - 60))), () -> placeOrder(a));
        }
        boolean active = !a.persona().equals("dormant");
        if (rng.chance(active ? 0.03 : 0.005)) {
            BigDecimal amount = dollars(rng.lognormal(2500, 0.8), 10);
            schedule(at(LocalTime.of(8, 30).plusSeconds(rng.below(8 * 3600))), () -> achDeposit(a, amount));
        }
        if (rng.chance(active ? 0.01 : 0.002)) {
            BigDecimal amount = dollars(rng.lognormal(10000, 0.8), 100);
            schedule(at(LocalTime.of(8, 30).plusSeconds(rng.below(8 * 3600))), () -> book(current,
                ft(30, a, "payments", Map.of(10, amount, 11, "PAY-" + (50_000_000 + nextPayment++))), false));
        }
        if (rng.chance(active ? 0.015 : 0.003)) {
            double fraction = 0.1 + rng.uniform() * 0.4;
            boolean cancel = rng.chance(0.08);
            schedule(at(LocalTime.of(8, 30).plusSeconds(rng.below(8 * 3600))), () -> withdraw(a, fraction, cancel));
        }
    }

    private void achDeposit(Account a, BigDecimal amount) {
        BigDecimal goodFaith = amount.min(new BigDecimal("1000.00"));
        LocalDate expected = rng.chance(0.7) ? calendar.next(day) : calendar.next(calendar.next(day));
        FinancialTransaction t = ft(31, a, "payments", Map.of(10, amount, 11, expected.toEpochDay(), 12, goodFaith,
            13, "PAY-" + (50_000_000 + nextPayment++)));
        if (book(current, t, false)) {
            depositSettlements.computeIfAbsent(expected, k -> new ArrayList<>()).add(new Payment(a, t.id(), amount, goodFaith));
        }
    }

    private void withdraw(Account a, double fraction, boolean cancel) {
        BigDecimal withdrawable = engine.normalBalance(a.id(), 5201).min(engine.balance(a.id(), 1001, null, null));
        BigDecimal amount = dollars(withdrawable.doubleValue() * fraction, 10);
        if (amount.compareTo(new BigDecimal(100)) < 0) {
            return;
        }
        LocalDate expected = calendar.next(day);
        FinancialTransaction t = ft(33, a, "payments", Map.of(10, amount, 11, expected.toEpochDay(),
            12, "PAY-" + (50_000_000 + nextPayment++)));
        if (!book(current, t, false)) {
            return;
        }
        if (cancel) {
            Instant later = current.plusSeconds(600 + rng.below(3600));
            Instant latest = at(LocalTime.of(16, 30));
            schedule(later.isAfter(latest) ? latest : later, () -> book(current, ft(35, a, "payments", Map.of(9, t.id(), 10, amount)), false));
        } else {
            withdrawalSettlements.computeIfAbsent(expected, k -> new ArrayList<>()).add(new Payment(a, t.id(), amount, null));
        }
    }

    private void settleDue() {
        Instant eight = at(LocalTime.of(8, 0));
        schedule(eight, () -> {
            for (Payment p : depositSettlements.getOrDefault(day, List.of())) {
                book(current, ft(32, p.account(), "payments", Map.of(9, p.initiation(), 10, p.amount(), 12, p.goodFaith())), false);
            }
            for (Payment p : withdrawalSettlements.getOrDefault(day, List.of())) {
                FinancialTransaction t = ft(34, p.account(), "payments", Map.of(9, p.initiation(), 10, p.amount()));
                boolean posted = book(current, t, false);
                // Only where no other withdrawal of the account is pending, so the resubmission must go below zero.
                if (posted && !doubleSettlementSeeded && businessDays >= 2
                    && engine.normalBalance(p.account().id(), 1405).signum() == 0) {
                    // A seeded break: the payment gateway resubmits the settlement under a new id. The ledger refuses
                    // it, BalanceBelowZero (J16): the hold, reserve, encumbrance and commitment would go below zero.
                    doubleSettlementSeeded = true;
                    book(current, ft(34, p.account(), "payments", Map.of(9, p.initiation(), 10, p.amount())), true);
                }
            }
            for (Order o : settlements.getOrDefault(day, List.of())) {
                settle(o);
            }
            depositSettlements.remove(day);
            withdrawalSettlements.remove(day);
            settlements.remove(day);
        });
    }

    // ---------------------------------------------------------------- orders

    private BigDecimal buyingPower(Account a) {
        BigDecimal bp = engine.normalBalance(a.id(), 4201);
        if (a.margin()) {
            BigDecimal longValue = BigDecimal.ZERO;
            for (long sec : longLots.getOrDefault(a.id(), Map.of()).keySet()) {
                longValue = longValue.add(engine.normalBalance(a.id(), 3002, sec).multiply(market.price(market.byIndex((int) (sec - 100001)))));
            }
            bp = bp.add(longValue.multiply(new BigDecimal("0.5")));
        }
        return bp;
    }

    private void placeOrder(Account a) {
        boolean shortSeller = a.persona().equals("short_seller");
        if (shortSeller && rng.chance(0.5) && current.isBefore(at(LocalTime.of(15, 30)))) {
            List<Long> covers = shortsToCover(a);
            if (!covers.isEmpty() && rng.chance(0.5)) {
                cover(a, covers.get(rng.below(covers.size())));
            } else {
                sellShort(a);
            }
            return;
        }
        List<Long> held = sellable(a);
        double sellChance = switch (a.persona()) {
            case "buy_and_hold" -> 0.2;
            case "dormant" -> 0.5;
            default -> 0.45;
        };
        if (!held.isEmpty() && rng.chance(sellChance)) {
            sell(a, held.get(rng.below(held.size())));
        } else {
            buy(a);
        }
    }

    private void buy(Account a) {
        Security s = market.popular();
        BigDecimal p = market.price(s);
        BigDecimal bp = buyingPower(a);
        double notional = bp.doubleValue() * (0.05 + rng.uniform() * 0.25);
        boolean limit = rng.chance(0.6);
        BigDecimal est = p.multiply(new BigDecimal(limit ? "1.003" : "1.01")).setScale(2, RoundingMode.HALF_UP);
        long qty = (long) Math.floor(notional / est.doubleValue());
        if (qty < 1 || est.multiply(BigDecimal.valueOf(qty)).compareTo(bp) > 0) {
            return;
        }
        Order o = new Order(a, s, "buy", limit ? "limit" : "market", qty, est, limit ? est : null, null, current);
        FinancialTransaction t = ft(40, a, "oms", Map.of(6, s.id(), 8, qty, 10, o.ref,
            11, o.estimateLeft, 12, est));
        accept(o, t);
    }

    private void sell(Account a, long sec) {
        Security s = market.byIndex((int) (sec - 100001));
        long free = freeToSell(a, sec);
        if (free < 1) {
            return;
        }
        long qty = rng.chance(0.6) ? free : Math.max(1, (long) Math.ceil(free * (0.2 + rng.uniform() * 0.6)));
        BigDecimal p = market.price(s);
        boolean limit = rng.chance(0.5);
        BigDecimal limitPrice = limit ? p.multiply(new BigDecimal("0.997")).setScale(2, RoundingMode.HALF_UP) : null;
        Order o = new Order(a, s, "sell", limit ? "limit" : "market", qty, p, limitPrice, null, current);
        accept(o, ft(43, a, "oms", Map.of(6, s.id(), 8, qty, 10, o.ref)));
    }

    private void sellShort(Account a) {
        BigDecimal equity = engine.normalBalance(a.id(), 4201);
        if (equity.compareTo(new BigDecimal(2000)) < 0) {
            return;
        }
        Security s = market.popular();
        if (longLots.getOrDefault(a.id(), Map.of()).containsKey(s.id()) && engine.normalBalance(a.id(), 3002, s.id()).signum() > 0) {
            return;
        }
        BigDecimal p = market.price(s);
        BigDecimal est = p.multiply(new BigDecimal("0.99")).setScale(2, RoundingMode.HALF_UP);
        long qty = (long) Math.floor(equity.doubleValue() * (0.1 + rng.uniform() * 0.2) / est.doubleValue());
        if (qty < 1) {
            return;
        }
        String locate = "LOC-" + (30_000_000 + nextLocate++);
        Order o = new Order(a, s, "sell_short", "market", qty, est, null, locate, current);
        accept(o, ft(60, a, "oms", Map.of(6, s.id(), 8, qty, 10, o.ref, 11, o.estimateLeft, 12, est, 13, locate)));
    }

    private void cover(Account a, long sec) {
        Security s = market.byIndex((int) (sec - 100001));
        long free = freeToCover(a, sec);
        if (free < 1) {
            return;
        }
        long qty = rng.chance(0.6) ? free : Math.max(1, free / 2);
        BigDecimal est = market.price(s).multiply(new BigDecimal("1.01")).setScale(2, RoundingMode.HALF_UP);
        Order o = new Order(a, s, "buy_to_cover", "market", qty, est, null, null, current);
        openCovers.computeIfAbsent(a.id(), k -> new HashMap<>()).merge(sec, qty, Long::sum);
        accept(o, ft(63, a, "oms", Map.of(6, s.id(), 8, qty, 10, o.ref, 11, o.estimateLeft, 12, est)));
    }

    private void accept(Order o, FinancialTransaction t) {
        if (!book(current, t, false)) {
            return;
        }
        o.acceptance = t.id();
        openOrders.put(o.id, o);
        upsertOrder(o);
        // Shorts and covers fill in full (v14 books no cancel for them); a buy or sell may stay part-filled to the close.
        boolean full = o.side.equals("sell_short") || o.side.equals("buy_to_cover") || rng.chance(0.85);
        int fills = 1 + (o.quantity >= 10 ? rng.below(3) : 0);
        long left = full ? o.quantity : Math.max(0, o.quantity - Math.max(1, o.quantity / 3));
        Instant last = at(LocalTime.of(15, 58));
        Instant t0 = current;
        for (int i = 0; i < fills && left > 0; i++) {
            long q = i == fills - 1 ? left : Math.max(1, left / (fills - i));
            left -= q;
            long qf = q;
            Instant when = t0.plusSeconds(20 + rng.below(900) + 300L * i);
            if (when.isAfter(last)) {
                when = last.plusSeconds(i);
            }
            schedule(when, () -> fill(o, qf));
        }
    }

    private void fill(Order o, long q) {
        Account a = o.account;
        Security s = o.security;
        BigDecimal p = market.price(s);
        if (o.limitPrice != null) {
            p = o.side.equals("buy") ? p.min(o.limitPrice) : p.max(o.limitPrice);
        }
        String fillId = "FIL-" + (40_000_000 + nextFill++);
        String venue = VENUES.get(rng.below(VENUES.size()));
        boolean last = o.filled + q == o.quantity;
        BigDecimal value = p.multiply(BigDecimal.valueOf(q));
        BigDecimal estimate = last ? o.estimateLeft : o.estimatedPrice.multiply(BigDecimal.valueOf(q)).min(o.estimateLeft);
        Map<Integer, Object> f = new HashMap<>(Map.of(6, s.id(), 8, q, 9, o.acceptance, 11, fillId, 12, p));
        int template;
        BigDecimal fees = BigDecimal.ZERO;
        switch (o.side) {
            case "buy", "buy_to_cover" -> {
                template = o.side.equals("buy") ? 41 : 64;
                f.put(13, value);
                f.put(14, estimate);
                f.put(15, day.toEpochDay());
                f.put(16, venue);
                if (template == 64) {
                    f.put(18, nameLots(shortLots, a, s.id(), q, true));
                }
            }
            default -> {
                template = o.side.equals("sell") ? 44 : 61;
                BigDecimal sec = value.multiply(SEC_FEE_RATE).setScale(2, RoundingMode.CEILING);
                BigDecimal taf = TAF_PER_SHARE.multiply(BigDecimal.valueOf(q)).setScale(2, RoundingMode.HALF_UP).min(TAF_MAX);
                fees = sec.add(taf);
                f.put(13, value);
                f.put(14, sec);
                f.put(15, taf);
                f.put(16, day.toEpochDay());
                f.put(17, venue);
                f.put(18, template == 44 ? nameLots(longLots, a, s.id(), q, false) : estimate);
            }
        }
        FinancialTransaction t = ft(template, a, "oms", f);
        if (!book(current, t, false)) {
            return;
        }
        if (template == 41) {
            longLots.computeIfAbsent(a.id(), k -> new TreeMap<>()).computeIfAbsent(s.id(), k -> new ArrayList<>()).add(t.id());
        }
        if (template == 61) {
            shortLots.computeIfAbsent(a.id(), k -> new TreeMap<>()).computeIfAbsent(s.id(), k -> new ArrayList<>()).add(t.id());
        }
        if (template == 64) {
            openCovers.get(a.id()).merge(s.id(), -q, Long::sum);
        }
        if (template == 41 || template == 64 || template == 61) {
            o.estimateLeft = o.estimateLeft.subtract(estimate);
        }
        o.filled += q;
        o.value = o.value.add(value);
        o.fees = o.fees.add(fees);
        o.status = o.filled == o.quantity ? "filled" : "partially_filled";
        if (o.filled == o.quantity) {
            openOrders.remove(o.id);
        }
        if (o.filled == q) {
            settlements.computeIfAbsent(calendar.settlement(day), k -> new ArrayList<>()).add(o);
        }
        upsertOrder(o);
    }

    /** The lots a fill names: the account's disposal method over lots with free shares (short lots oldest first). */
    private List<LotShares> nameLots(Map<Long, Map<Long, List<Long>>> book, Account a, long sec, long q, boolean shortSide) {
        List<Long> lots = new ArrayList<>(book.getOrDefault(a.id(), Map.of()).getOrDefault(sec, List.of()));
        if (!shortSide && a.disposalMethod().equals("HIFO")) {
            lots.sort(Comparator.comparing((Long l) -> {
                Engine.LotRef r = engine.lots().get(l);
                return r.amount().divide(r.shares(), 10, RoundingMode.HALF_UP);
            }).reversed().thenComparing(l -> l));
        }
        List<LotShares> out = new ArrayList<>();
        long need = q;
        for (long lot : lots) {
            if (need == 0) {
                break;
            }
            BigDecimal free = shortSide ? engine.freeShort(a.id(), sec, lot) : engine.freeShares(a.id(), sec, lot);
            long take = Math.min(need, free.longValue());
            if (take > 0) {
                out.add(new LotShares(lot, BigDecimal.valueOf(take)));
                need -= take;
            }
        }
        return out;
    }

    private List<Long> sellable(Account a) {
        List<Long> out = new ArrayList<>();
        for (long sec : longLots.getOrDefault(a.id(), Map.of()).keySet()) {
            if (freeToSell(a, sec) > 0) {
                out.add(sec);
            }
        }
        return out;
    }

    private long freeToSell(Account a, long sec) {
        return engine.normalBalance(a.id(), 3002, sec).subtract(engine.normalBalance(a.id(), 3004, sec)).longValue();
    }

    private List<Long> shortsToCover(Account a) {
        List<Long> out = new ArrayList<>();
        for (long sec : shortLots.getOrDefault(a.id(), Map.of()).keySet()) {
            if (freeToCover(a, sec) > 0) {
                out.add(sec);
            }
        }
        return out;
    }

    private long freeToCover(Account a, long sec) {
        long free = 0;
        for (long lot : shortLots.getOrDefault(a.id(), Map.of()).getOrDefault(sec, List.of())) {
            free += engine.freeShort(a.id(), sec, lot).longValue();
        }
        return free - openCovers.getOrDefault(a.id(), Map.of()).getOrDefault(sec, 0L);
    }

    private void settle(Order o) {
        Account a = o.account;
        LocalDate settlementDate = day;
        Map<Integer, Object> f = new HashMap<>(Map.of(6, o.security.id(), 9, o.acceptance));
        int template = switch (o.side) {
            case "buy" -> 42;
            case "buy_to_cover" -> 65;
            case "sell" -> 45;
            default -> 62;
        };
        if (template == 42 || template == 65) {
            f.put(11, o.value);
            f.put(12, settlementDate.toEpochDay());
        } else {
            f.put(11, o.value);
            f.put(12, o.fees);
            f.put(13, settlementDate.toEpochDay());
        }
        book(current, ft(template, a, "settlement", f), false);
    }

    private void close() {
        Instant now = current;
        for (Security s : market.securities()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("business_date", day);
            row.put("security_id", s.id());
            row.put("symbol", s.symbol());
            row.put("close_price", market.price(s));
            row.put("currency_id", USD);
            emit(now, Feed.PRICES_EOD, row);
        }
        for (Order o : new ArrayList<>(openOrders.values())) {
            long open = o.open();
            Map<Integer, Object> f = new HashMap<>(Map.of(6, o.security.id(), 8, open, 9, o.acceptance));
            int template;
            if (o.side.equals("buy")) {
                template = 46;
                f.put(11, o.estimateLeft);
            } else if (o.side.equals("sell")) {
                template = 47;
            } else {
                // A short or cover always fills in full; one still open had a fill refused, already counted.
                openOrders.remove(o.id);
                continue;
            }
            if (book(now, ft(template, o.account, "oms", f), false)) {
                o.estimateLeft = BigDecimal.ZERO;
                o.status = o.filled == 0 ? "expired" : "partially_filled_expired";
                openOrders.remove(o.id);
                upsertOrder(o);
            }
        }
    }

    private void endOfDay() {
        Instant now = current;
        if (calendar.isMonthEnd(day)) {
            for (Account a : accounts) {
                BigDecimal cash = engine.balance(a.id(), 1001, null, null);
                if (!a.margin() || cash.signum() >= 0) {
                    continue;
                }
                BigDecimal debit = cash.negate();
                BigDecimal interest = debit.multiply(MARGIN_RATE).multiply(BigDecimal.valueOf(day.lengthOfMonth()))
                    .divide(new BigDecimal(360), 2, RoundingMode.HALF_UP);
                if (interest.signum() <= 0) {
                    continue;
                }
                book(now, ft(51, a, "interest", Map.of(10, interest, 11, day.withDayOfMonth(1).toEpochDay(),
                    12, day.toEpochDay(), 13, MARGIN_RATE, 14, debit)), false);
                book(now, ft(52, a, "interest", Map.of(10, interest, 11, day.toEpochDay())), false);
            }
        }
        for (Account a : accounts) {
            Map<BalanceKey, BigDecimal> balances = new TreeMap<>(Comparator.comparingInt(BalanceKey::definition)
                .thenComparing(k -> k.security() == null ? 0 : k.security())
                .thenComparing(k -> k.lot() == null ? 0 : k.lot()));
            balances.putAll(engine.balances(a.id()));
            for (Map.Entry<BalanceKey, BigDecimal> e : balances.entrySet()) {
                BalanceKey k = e.getKey();
                Kit.Definition d = kit.definition(k.definition());
                List<Long> key = new ArrayList<>();
                for (int dim : d.keyDimensions()) {
                    key.add(switch (dim) {
                        case Kit.BROKERAGE_ACCOUNT -> a.id();
                        case Kit.CURRENCY -> USD;
                        case Kit.SECURITY -> k.security();
                        default -> k.lot();
                    });
                }
                BigDecimal raw = engine.balance(a.id(), k.definition(), k.security(), k.lot());
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("business_date", day);
                row.put("brokerage_account", a.id());
                row.put("ledger_account_id", engine.ledgerAccounts().get(new LedgerKey(k.definition(), key)));
                row.put("sub_ledger", rootCode(d.root()));
                row.put("definition_id", k.definition());
                row.put("definition_name", d.name());
                row.put("security_id", k.security());
                row.put("lot", k.lot());
                row.put("measure", d.measure());
                row.put("normal_side", d.normalSide().name());
                row.put("balance", d.normalSide() == Kit.Side.Debit ? raw : raw.negate());
                emit(now, Feed.BALANCES_EOD, row);
            }
        }
    }

    private void bars(LocalTime start) {
        Instant now = current;
        Instant barStart = at(start);
        for (Bar b : market.step()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("security_id", b.security().id());
            row.put("symbol", b.security().symbol());
            row.put("bar_start", barStart);
            row.put("business_date", day);
            row.put("open", b.open());
            row.put("high", b.high());
            row.put("low", b.low());
            row.put("close", b.close());
            row.put("volume", b.volume());
            emit(now, Feed.BARS_5M, row);
        }
    }

    private void upsertOrder(Order o) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("order_id", o.id);
        row.put("order_ref", o.ref);
        row.put("brokerage_account", o.account.id());
        row.put("security_id", o.security.id());
        row.put("symbol", o.security.symbol());
        row.put("side", o.side);
        row.put("order_type", o.type);
        row.put("limit_price", o.limitPrice);
        row.put("quantity", o.quantity);
        row.put("filled_quantity", o.filled);
        row.put("average_price", o.filled == 0 ? null : o.value.divide(BigDecimal.valueOf(o.filled), 6, RoundingMode.HALF_UP));
        row.put("status", o.status);
        row.put("locate_id", o.locate);
        row.put("accepted_financial_transaction_id", o.acceptance);
        row.put("trade_date", o.tradeDate);
        row.put("accepted_at", o.acceptedAt);
        row.put("updated_at", current);
        emit(current, Feed.ORDERS, row);
    }

    private static BigDecimal dollars(double v, int multiple) {
        long m = Math.max(multiple, Math.round(v / multiple) * multiple);
        return BigDecimal.valueOf(m).setScale(2, RoundingMode.UNNECESSARY);
    }

    /** The business days a world started on {@code start} has played after n calls to {@link #runDay}. */
    public static List<LocalDate> days(LocalDate start, int n) {
        MarketCalendar c = new MarketCalendar();
        List<LocalDate> out = new ArrayList<>();
        LocalDate d = c.isBusinessDay(start) ? start : c.next(start);
        for (int i = 0; i < n; i++) {
            out.add(d);
            d = c.next(d);
        }
        return out;
    }
}
