package nuna.datagen.kit;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import nuna.datagen.kit.FinancialTransaction.LotShares;
import nuna.datagen.kit.FinancialTransaction.ReceivedLot;
import nuna.datagen.kit.Kit.Binding;
import nuna.datagen.kit.Kit.BookingScheme;
import nuna.datagen.kit.Kit.Calculation;
import nuna.datagen.kit.Kit.Entry;
import nuna.datagen.kit.Kit.Line;
import nuna.datagen.kit.Kit.Side;
import nuna.datagen.kit.Kit.Source;
import nuna.datagen.kit.Kit.Template;

/**
 * The posting engine, ported from the kit's simulation ({@code schemes_v20.py}): posted balances per brokerage
 * account, the ledger account index looked up on (definition, key values), the financial transactions posted, and
 * each lot's reference, the opening fill's amount and shares (schema 11 R12). A lot's remaining cost is computed from
 * its shares not yet pending, never carried.
 *
 * <p>{@link #post} runs DuplicateTransaction, F13 at admission, NoBookingScheme, the calculations (LotNotHeld,
 * FractionalShares), J1, J11, J12 and J16 (BalanceBelowZero), then applies the lines and what the calculations change
 * in the lots' references (a lot received by transfer, a split's rescaling) and runs the self-checks (J4), which
 * {@link Halt}. A rejection changes nothing: the calculations read state and never write it.
 *
 * <p>Balances are kept debit-positive, as the kit keeps them, and summed per definition and per (definition, security)
 * as they change, so a self-check reads a sum instead of scanning the brokerage account's ledger accounts.
 */
public final class Engine {

    /** Python's default decimal context: 28 significant digits, half even. */
    private static final MathContext CONTEXT = new MathContext(28, RoundingMode.HALF_EVEN);

    /** The templates whose fill opens a lot (an EquityBuyExecuted) or a short lot (an EquityShortSellExecuted). */
    private static final int OPENS_LOT = 41, OPENS_SHORT_LOT = 61;

    /** A ledger account's place in one brokerage account's balances: its definition, security and lot. */
    public record BalanceKey(int definition, Long security, Long lot) {}

    /** A ledger account in the index: its definition and its key values in the definition's dimension order. */
    public record LedgerKey(int definition, List<Long> values) {}

    /** A lot's reference (R12): whose it is, in what security, and the opening fill's amount and shares. */
    public record LotRef(long brokerageAccount, long security, BigDecimal amount, BigDecimal shares) {}

    private record DefinitionSecurity(int definition, long security) {}

    /** One brokerage account's balances and lots. */
    private static final class Book {
        final Map<BalanceKey, BigDecimal> balances = new HashMap<>();
        final Map<Integer, BigDecimal> byDefinition = new HashMap<>();
        final Map<DefinitionSecurity, BigDecimal> byDefinitionSecurity = new HashMap<>();
        final Map<Long, List<Long>> lotsBySecurity = new HashMap<>();
        final Map<Long, List<Long>> shortLotsBySecurity = new HashMap<>();

        BigDecimal get(BalanceKey k) {
            return balances.getOrDefault(k, BigDecimal.ZERO);
        }

        void add(BalanceKey k, BigDecimal delta) {
            balances.merge(k, delta, BigDecimal::add);
            byDefinition.merge(k.definition(), delta, BigDecimal::add);
            if (k.security() != null) {
                byDefinitionSecurity.merge(new DefinitionSecurity(k.definition(), k.security()), delta, BigDecimal::add);
            }
        }
    }

    /**
     * A lot a calculation yields: the lot, its shares and, from IdentifyLotsNamed, IdentifyShortLotsNamed and
     * OpenLotsReceived, the cost or proceeds it relieves or opens, under that output's name.
     */
    private record Part(long lot, BigDecimal shares, String amountName, BigDecimal amount) {}

    /**
     * A calculation's outputs: named values, the lots for a journal line that repeats over them, and what it changes
     * in the lots' references once the posting is applied (null: nothing). A calculation reads state and never writes
     * it, so a rejection changes nothing.
     */
    private record Outcome(Map<String, BigDecimal> values, List<Part> lots, Runnable apply) {
        Outcome(Map<String, BigDecimal> values, List<Part> lots) {
            this(values, lots, null);
        }
    }

    private final Kit kit;
    private final Map<Long, Book> books = new HashMap<>();
    private final Map<LedgerKey, Long> index = new LinkedHashMap<>();
    private long nextLedgerAccountId = 20000000001L;
    private final Set<Long> posted = new HashSet<>();
    private final Map<Long, LotRef> lots = new LinkedHashMap<>();
    private final Map<Long, LotRef> shortLots = new LinkedHashMap<>();

    public Engine(Kit kit) {
        this.kit = kit;
    }

    public Kit kit() {
        return kit;
    }

    public static BigDecimal cents(BigDecimal x) {
        return x.setScale(2, RoundingMode.HALF_UP);
    }

    /** Resolves a ledger account (schema 11 "Resolving a ledger account"), opening it on a miss. */
    public long resolve(LedgerKey key) {
        Long id = index.get(key);
        if (id == null) {
            id = nextLedgerAccountId++;
            index.put(key, id);
        }
        return id;
    }

    public Map<LedgerKey, Long> ledgerAccounts() {
        return Collections.unmodifiableMap(index);
    }

    public Map<Long, LotRef> lots() {
        return Collections.unmodifiableMap(lots);
    }

    public Map<Long, LotRef> shortLots() {
        return Collections.unmodifiableMap(shortLots);
    }

    public Set<Long> brokerageAccounts() {
        return Collections.unmodifiableSet(books.keySet());
    }

    private Book book(long ba) {
        return books.computeIfAbsent(ba, k -> new Book());
    }

    /** A ledger account's balance, debit-positive. */
    public BigDecimal balance(long ba, int definition, Long security, Long lot) {
        Book b = books.get(ba);
        return b == null ? BigDecimal.ZERO : b.get(new BalanceKey(definition, security, lot));
    }

    /** Every non-zero balance of a brokerage account, debit-positive, trailing zeros stripped (for comparing). */
    public Map<BalanceKey, BigDecimal> balances(long ba) {
        Map<BalanceKey, BigDecimal> out = new HashMap<>();
        Book b = books.get(ba);
        if (b != null) {
            b.balances.forEach((k, v) -> {
                if (v.signum() != 0) {
                    out.put(k, v.stripTrailingZeros());
                }
            });
        }
        return out;
    }

    /** Every non-zero balance of every brokerage account. */
    public Map<Long, Map<BalanceKey, BigDecimal>> snapshot() {
        Map<Long, Map<BalanceKey, BigDecimal>> out = new TreeMap<>();
        books.keySet().forEach(ba -> {
            Map<BalanceKey, BigDecimal> m = balances(ba);
            if (!m.isEmpty()) {
                out.put(ba, m);
            }
        });
        return out;
    }

    private BigDecimal inNormalDirection(int definition, BigDecimal debitPositive) {
        return kit.normal(definition) == Side.Debit ? debitPositive : debitPositive.negate();
    }

    /** A definition's balance in its normal direction, summed over the brokerage account's ledger accounts. */
    public BigDecimal normalBalance(long ba, int definition) {
        Book b = books.get(ba);
        return inNormalDirection(definition,
            b == null ? BigDecimal.ZERO : b.byDefinition.getOrDefault(definition, BigDecimal.ZERO));
    }

    /** A definition's balance in its normal direction, summed over the ledger accounts of one security. */
    public BigDecimal normalBalance(long ba, int definition, long security) {
        Book b = books.get(ba);
        return inNormalDirection(definition, b == null ? BigDecimal.ZERO
            : b.byDefinitionSecurity.getOrDefault(new DefinitionSecurity(definition, security), BigDecimal.ZERO));
    }

    /** The lot's shares not yet pending delivery: its 3001 balance less its 3003 balance (3003 is credit-normal). */
    public BigDecimal freeShares(long ba, long sec, long lot) {
        return balance(ba, 3001, sec, lot).add(balance(ba, 3003, sec, lot));
    }

    /** R12: the opening fill's amount x r / its shares, rounded half up to the cent. */
    private static BigDecimal remaining(LotRef ref, BigDecimal r) {
        return cents(ref.amount().multiply(r, CONTEXT).divide(ref.shares(), CONTEXT));
    }

    public BigDecimal remainingCost(long lot, BigDecimal r) {
        return remaining(lots.get(lot), r);
    }

    /** The short lot's shares not yet pending cover: its 3101 balance (credit-normal) less its 3103 balance. */
    public BigDecimal freeShort(long ba, long sec, long lot) {
        return balance(ba, 3101, sec, lot).negate().subtract(balance(ba, 3103, sec, lot));
    }

    public BigDecimal remainingProceeds(long lot, BigDecimal r) {
        return remaining(shortLots.get(lot), r);
    }

    /** The remaining cost of the brokerage account's open lots in a security. */
    public BigDecimal lotsCost(long ba, long sec) {
        BigDecimal total = BigDecimal.ZERO;
        for (long lot : book(ba).lotsBySecurity.getOrDefault(sec, List.of())) {
            total = total.add(remainingCost(lot, freeShares(ba, sec, lot)));
        }
        return total;
    }

    /** The remaining proceeds of the brokerage account's open short lots in a security. */
    public BigDecimal lotsProceeds(long ba, long sec) {
        BigDecimal total = BigDecimal.ZERO;
        for (long lot : book(ba).shortLotsBySecurity.getOrDefault(sec, List.of())) {
            total = total.add(remainingProceeds(lot, freeShort(ba, sec, lot)));
        }
        return total;
    }

    /**
     * IdentifyLotsNamed: per named (lot, shares), cost = remaining cost before - remaining cost after; realizedGain
     * only when the booking scheme gives proceeds (an ACATS delivery gives none: a transfer is not a sale).
     */
    private Outcome identifyNamed(long ba, Long sec, List<LotShares> named, BigDecimal proceeds) throws Rejected {
        List<Part> out = new ArrayList<>();
        Map<Long, BigDecimal> taken = new HashMap<>();
        BigDecimal total = BigDecimal.ZERO;
        for (LotShares ls : named) {
            LotRef ref = lots.get(ls.lot());
            if (ref == null || ref.brokerageAccount() != ba || sec == null || ref.security() != sec) {
                throw new Rejected("LotNotHeld", ls.lot() + " is not a lot of this brokerage account and security");
            }
            BigDecimal r0 = freeShares(ba, sec, ls.lot()).subtract(taken.getOrDefault(ls.lot(), BigDecimal.ZERO));
            BigDecimal s = ls.shares();
            if (s.signum() <= 0 || s.compareTo(r0) > 0) {
                throw new Rejected("LotNotHeld", ls.lot() + ": " + s + " named, " + r0 + " not yet pending");
            }
            BigDecimal cost = remainingCost(ls.lot(), r0).subtract(remainingCost(ls.lot(), r0.subtract(s)));
            out.add(new Part(ls.lot(), s, "cost", cost));
            total = total.add(cost);
            taken.merge(ls.lot(), s, BigDecimal::add);
        }
        return new Outcome(proceeds == null ? Map.of("totalCost", total)
            : Map.of("totalCost", total, "realizedGain", cents(proceeds).subtract(total)), out);
    }

    /** The lot id of the n-th lot (from 1) an ACATS receipt brings in: the receipt's id times 1,000 plus n. */
    public static long receivedLot(long transaction, int n) {
        return transaction * 1000 + n;
    }

    /**
     * OpenLotsReceived (item 155): a lot per received element, keyed {@link #receivedLot}, whose reference (R12) is the
     * delivering firm's cost and the shares received, so its remaining cost carries over from the other firm.
     */
    private Outcome openReceived(FinancialTransaction t, long ba, Long sec) throws Rejected {
        List<ReceivedLot> received = t.receivedLots();
        if (received.isEmpty()) {
            throw new Rejected("LotNotHeld", t.id() + ": no lots received");
        }
        List<Part> out = new ArrayList<>();
        BigDecimal cost = BigDecimal.ZERO, shares = BigDecimal.ZERO;
        for (int i = 0; i < received.size(); i++) {
            ReceivedLot r = received.get(i);
            BigDecimal c = cents(r.cost());
            out.add(new Part(receivedLot(t.id(), i + 1), r.shares(), "cost", c));
            cost = cost.add(c);
            shares = shares.add(r.shares());
        }
        Runnable apply = () -> {
            Book b = book(ba);
            for (Part p : out) {
                lots.put(p.lot(), new LotRef(ba, sec, p.amount(), p.shares()));
                b.lotsBySecurity.computeIfAbsent(sec, k -> new ArrayList<>()).add(p.lot());
            }
        };
        return new Outcome(Map.of("totalCost", cost, "totalShares", shares), out, apply);
    }

    /**
     * RescaleLots and RescaleShortLots (item 155): a split of ratioNew for ratioOld rescales each lot's shares not
     * pending delivery or cover, and its reference shares, by the ratio; the reference's cost or proceeds is unchanged,
     * so the lot's remaining cost or proceeds is too (R12). Shares already pending are delivered or covered in the
     * shares they were traded in. Yields each lot's change in shares (negative for a reverse split) and their total.
     */
    private Outcome rescale(long id, long ba, Long sec, BigDecimal ratioNew, BigDecimal ratioOld, boolean shortSide)
        throws Rejected {
        if (ratioNew.signum() <= 0 || ratioOld.signum() <= 0) {
            throw new Rejected("NegativeAmount", id + ": split ratio " + ratioNew + " for " + ratioOld);
        }
        Map<Long, LotRef> refs = shortSide ? shortLots : lots;
        List<Part> out = new ArrayList<>();
        Map<Long, LotRef> rescaled = new LinkedHashMap<>();
        BigDecimal total = BigDecimal.ZERO;
        for (long lot : sortedLots(shortSide ? book(ba).shortLotsBySecurity : book(ba).lotsBySecurity, sec)) {
            BigDecimal free = shortSide ? freeShort(ba, sec, lot) : freeShares(ba, sec, lot);
            if (free.signum() == 0) {
                continue;
            }
            LotRef ref = refs.get(lot);
            BigDecimal now = scaled(id, lot, free, ratioNew, ratioOld);
            if (now.stripTrailingZeros().scale() > kit.chart().unitExponent()) {
                throw new Rejected("FractionalShares", id + ": lot " + lot + " would hold " + now + " shares");
            }
            BigDecimal delta = now.subtract(free);
            out.add(new Part(lot, delta, null, null));
            total = total.add(delta);
            rescaled.put(lot, new LotRef(ba, sec, ref.amount(), scaled(id, lot, ref.shares(), ratioNew, ratioOld)));
        }
        return new Outcome(Map.of("totalShares", total), out, () -> refs.putAll(rescaled));
    }

    private static BigDecimal scaled(long id, long lot, BigDecimal shares, BigDecimal ratioNew, BigDecimal ratioOld)
        throws Rejected {
        try {
            return shares.multiply(ratioNew).divide(ratioOld);
        } catch (ArithmeticException e) {      // no exact decimal: a third of a share
            throw new Rejected("FractionalShares", id + ": lot " + lot + ", " + shares + " x " + ratioNew + " / "
                + ratioOld + " has no exact number of shares");
        }
    }

    /** IdentifyShortLotsNamed: per named (short lot, shares), proceeds = remaining before - remaining after. */
    private Outcome identifyShortNamed(long ba, Long sec, List<LotShares> named, BigDecimal cost) throws Rejected {
        List<Part> out = new ArrayList<>();
        Map<Long, BigDecimal> taken = new HashMap<>();
        BigDecimal total = BigDecimal.ZERO;
        for (LotShares ls : named) {
            LotRef ref = shortLots.get(ls.lot());
            if (ref == null || ref.brokerageAccount() != ba || sec == null || ref.security() != sec) {
                throw new Rejected("LotNotHeld", ls.lot() + " is not a short lot of this brokerage account and security");
            }
            BigDecimal r0 = freeShort(ba, sec, ls.lot()).subtract(taken.getOrDefault(ls.lot(), BigDecimal.ZERO));
            BigDecimal s = ls.shares();
            if (s.signum() <= 0 || s.compareTo(r0) > 0) {
                throw new Rejected("LotNotHeld", ls.lot() + ": " + s + " named, " + r0 + " not yet pending cover");
            }
            BigDecimal proceeds = remainingProceeds(ls.lot(), r0).subtract(remainingProceeds(ls.lot(), r0.subtract(s)));
            out.add(new Part(ls.lot(), s, "proceeds", proceeds));
            total = total.add(proceeds);
            taken.merge(ls.lot(), s, BigDecimal::add);
        }
        return new Outcome(Map.of("totalProceeds", total, "realizedGain", total.subtract(cents(cost))), out);
    }

    /** SettlePending: every pending share of the security, per lot in lot order. */
    private Outcome settlePending(long ba, Long sec) {
        List<Part> out = new ArrayList<>();
        for (long lot : sortedLots(book(ba).lotsBySecurity, sec)) {
            BigDecimal v = balance(ba, 3003, sec, lot);
            if (v.signum() != 0) {
                out.add(new Part(lot, v.negate(), null, null));
            }
        }
        return new Outcome(Map.of(), out);
    }

    /** SettleShortPending: every pending cover share of the security, per short lot in lot order. */
    private Outcome settleShortPending(long ba, Long sec) {
        List<Part> out = new ArrayList<>();
        for (long lot : sortedLots(book(ba).shortLotsBySecurity, sec)) {
            BigDecimal v = balance(ba, 3103, sec, lot);
            if (v.signum() != 0) {
                out.add(new Part(lot, v, null, null));
            }
        }
        return new Outcome(Map.of(), out);
    }

    private static List<Long> sortedLots(Map<Long, List<Long>> bySecurity, Long sec) {
        if (sec == null) {
            return List.of();
        }
        List<Long> l = new ArrayList<>(bySecurity.getOrDefault(sec, List.of()));
        Collections.sort(l);
        return l;
    }

    private static final class Resolution {
        final List<JournalLine> lines = new ArrayList<>();
        final List<LedgerKey> keys = new ArrayList<>();
    }

    /**
     * Posts a financial transaction through its template's booking scheme and returns the journal lines as posted.
     *
     * @throws Rejected when the ledger refuses it; nothing changed
     * @throws Halt when a self-check fails after posting: a defect, and posting must stop
     */
    public List<JournalLine> post(FinancialTransaction t) throws Rejected {
        long id = t.id();
        if (posted.contains(id)) {
            throw new Rejected("DuplicateTransaction", String.valueOf(id));
        }
        Template template = kit.template(t.template());
        if (template == null) {
            throw new Rejected("NoBookingScheme", id + ": template " + t.template());
        }
        Map<Integer, String> fields = kit.fieldsOf(template);
        for (Map.Entry<Integer, String> f : fields.entrySet()) {
            if (kit.nonNegative(f.getValue()) && t.has(f.getKey()) && t.decimal(f.getKey()).signum() < 0) {
                throw new Rejected("NegativeAmount", id + ": " + f.getValue() + " " + t.get(f.getKey()));
            }
        }
        if ("lots".equals(fields.get(FinancialTransaction.LOTS))) {
            for (LotShares ls : t.lots()) {
                if (ls.shares().signum() < 0) {
                    throw new Rejected("NegativeAmount", id + ": lots.shares " + ls.shares());
                }
            }
        }
        if ("receivedLots".equals(fields.get(FinancialTransaction.LOTS))) {
            for (ReceivedLot r : t.receivedLots()) {
                // A received lot of no shares would have no remaining cost to compute (R12 divides by its shares).
                if (r.shares().signum() <= 0 || r.cost().signum() < 0) {
                    throw new Rejected("NegativeAmount", id + ": receivedLots " + r.shares() + " shares at " + r.cost());
                }
            }
        }
        BookingScheme scheme = kit.schemeFor(t.template());
        if (scheme == null) {
            throw new Rejected("NoBookingScheme", id + ": template " + t.template());
        }
        long ba = t.brokerageAccount();
        Long sec = t.longOrNull(FinancialTransaction.SECURITY);
        Map<Integer, Outcome> co = new HashMap<>();
        for (Calculation c : scheme.calculations()) {
            Map<String, Source> in = c.inputs();
            Outcome o = switch (c.kind()) {
                case Kit.IDENTIFY_LOTS_NAMED -> {
                    if (t.lots().isEmpty()) {
                        throw new Rejected("LotNotHeld", id + ": no lots named");
                    }
                    yield identifyNamed(ba, sec, t.lots(),
                        in.containsKey("proceeds") ? value(t, co, in.get("proceeds"), null) : null);
                }
                case Kit.OPEN_LOTS_RECEIVED -> openReceived(t, ba, sec);
                case Kit.RESCALE_LOTS, Kit.RESCALE_SHORT_LOTS -> rescale(id, ba, sec,
                    value(t, co, in.get("ratioNew"), null), value(t, co, in.get("ratioOld"), null),
                    c.kind() == Kit.RESCALE_SHORT_LOTS);
                case Kit.SETTLE_PENDING -> settlePending(ba, sec);
                case Kit.IDENTIFY_SHORT_LOTS_NAMED -> {
                    if (t.lots().isEmpty()) {
                        throw new Rejected("LotNotHeld", id + ": no short lots named");
                    }
                    yield identifyShortNamed(ba, sec, t.lots(), value(t, co, in.get("cost"), null));
                }
                case Kit.SETTLE_SHORT_PENDING -> settleShortPending(ba, sec);
                // May be negative: the journal line decides (J12).
                case Kit.DIFFERENCE -> new Outcome(Map.of("result",
                    value(t, co, in.get("minuend"), null).subtract(value(t, co, in.get("subtrahend"), null))), List.of());
                case Kit.SUM -> {
                    BigDecimal sum = BigDecimal.ZERO;
                    for (String term : List.of("term1", "term2", "term3", "term4")) {
                        if (in.containsKey(term)) {
                            sum = sum.add(value(t, co, in.get(term), null));
                        }
                    }
                    yield new Outcome(Map.of("result", sum), List.of());
                }
                default -> throw new IllegalStateException("calculation kind " + c.kind());
            };
            co.put(c.id(), o);
        }
        Resolution r = buildLines(t, scheme, co);
        // J1, in each journal entry's measure.
        for (int sub : new LinkedHashSet<>(r.lines.stream().map(JournalLine::subLedger).toList())) {
            BigDecimal dr = BigDecimal.ZERO, cr = BigDecimal.ZERO;
            for (JournalLine l : r.lines) {
                if (l.subLedger() == sub) {
                    if (l.side() == Side.Debit) {
                        dr = dr.add(l.amount());
                    } else {
                        cr = cr.add(l.amount());
                    }
                }
            }
            if (dr.compareTo(cr) != 0) {
                throw new Rejected("Unbalanced", id + ": sub-ledger " + sub + " debits " + dr + " credits " + cr);
            }
        }
        // J16: no ledger account of a definition that is not MayReverse below zero in its normal direction.
        Book book = book(ba);
        Map<BalanceKey, BigDecimal> after = new LinkedHashMap<>();
        for (JournalLine l : r.lines) {
            BalanceKey k = new BalanceKey(l.definition(), l.security(), l.lot());
            BigDecimal delta = l.side() == Side.Debit ? l.amount() : l.amount().negate();
            after.put(k, after.getOrDefault(k, book.get(k)).add(delta));
        }
        for (Map.Entry<BalanceKey, BigDecimal> e : after.entrySet()) {
            int d = e.getKey().definition();
            if (!kit.mayReverse(d) && inNormalDirection(d, e.getValue()).signum() < 0) {
                throw new Rejected("BalanceBelowZero", id + ": " + e.getKey() + " would be " + e.getValue());
            }
        }
        List<JournalLine> out = new ArrayList<>(r.lines.size());
        for (int i = 0; i < r.lines.size(); i++) {
            JournalLine l = r.lines.get(i);
            out.add(new JournalLine(l.subLedger(), l.definition(), l.side(), l.amount(), l.security(), l.lot(),
                resolve(r.keys.get(i))));
        }
        Set<Long> touched = new HashSet<>();
        for (JournalLine l : out) {
            book.add(new BalanceKey(l.definition(), l.security(), l.lot()),
                l.side() == Side.Debit ? l.amount() : l.amount().negate());
            if (l.security() != null) {
                touched.add(l.security());
            }
        }
        if (t.template() == OPENS_LOT) {
            lots.put(id, new LotRef(ba, sec, cents(t.decimal(13)), t.decimal(FinancialTransaction.QUANTITY)));
            book.lotsBySecurity.computeIfAbsent(sec, k -> new ArrayList<>()).add(id);
        }
        if (t.template() == OPENS_SHORT_LOT) {
            shortLots.put(id, new LotRef(ba, sec, cents(t.decimal(13)), t.decimal(FinancialTransaction.QUANTITY)));
            book.shortLotsBySecurity.computeIfAbsent(sec, k -> new ArrayList<>()).add(id);
        }
        for (Calculation c : scheme.calculations()) {
            Runnable apply = co.get(c.id()).apply();
            if (apply != null) {
                apply.run();
            }
        }
        posted.add(id);
        selfCheck(ba, touched);
        return out;
    }

    private Resolution buildLines(FinancialTransaction t, BookingScheme scheme, Map<Integer, Outcome> co) {
        Resolution r = new Resolution();
        for (Entry entry : scheme.entries()) {
            for (Line ln : entry.lines()) {
                List<Part> elems = ln.repeat() != null ? co.get(ln.repeat()).lots() : Collections.singletonList(null);
                for (Part e : elems) {
                    Long lot = null, lsec = null;
                    List<Long> key = new ArrayList<>();
                    for (Binding b : ln.dimensions()) {
                        long v = b.source().field() != null ? t.longValue(b.source().field()) : e.lot();
                        if (b.dimension() == Kit.LOT) {
                            lot = v;
                        }
                        if (b.dimension() == Kit.SECURITY) {
                            lsec = t.longOrNull(FinancialTransaction.SECURITY);
                        }
                        key.add(v);
                    }
                    BigDecimal amount = value(t, co, ln.amount(), e);
                    Side side = ln.side();
                    if (amount.signum() < 0) {       // J12: the absolute value, on the other side
                        amount = amount.negate();
                        side = side.flip();
                    }
                    if (amount.signum() == 0) {      // J11
                        continue;
                    }
                    r.keys.add(new LedgerKey(ln.definition(), List.copyOf(key)));
                    r.lines.add(new JournalLine(entry.subLedger(), ln.definition(), side, amount, lsec, lot, 0));
                }
            }
        }
        return r;
    }

    private static BigDecimal value(FinancialTransaction t, Map<Integer, Outcome> co, Source src, Part elem) {
        if (src.field() != null) {
            return t.decimal(src.field());
        }
        if (src.calculation() != null) {
            String name = src.output();
            if (elem != null) {                   // the repeated element's own outputs first, as in the kit
                if (name.equals("lot")) {
                    return BigDecimal.valueOf(elem.lot());
                }
                if (name.equals("shares")) {
                    return elem.shares();
                }
                if (name.equals(elem.amountName())) {
                    return elem.amount();
                }
            }
            BigDecimal v = co.get(src.calculation()).values().get(name);
            if (v == null) {
                throw new IllegalStateException("calculation " + src.calculation() + " has no output " + name);
            }
            return v;
        }
        throw new IllegalStateException("source " + src);
    }

    /**
     * J4 as engine self-checks: per security the transaction touched, the SL-SHARE identities and the sell hold, and
     * Securities at Cost and Securities Sold Short at Proceeds against their lots; then every control and register
     * mirror. Each held before this posting, so checking only what it touched is checking everything.
     */
    private void selfCheck(long ba, Set<Long> securities) {
        for (long sec : securities) {
            if (normalBalance(ba, 3001, sec).compareTo(normalBalance(ba, 3002, sec).add(normalBalance(ba, 3003, sec))) != 0) {
                throw new Halt("J4b lots " + ba + " " + sec);
            }
            if (normalBalance(ba, 3004, sec).compareTo(normalBalance(ba, 3005, sec)) != 0) {
                throw new Halt("J4b sell hold " + ba + " " + sec);
            }
            BigDecimal atCost = balance(ba, 1002, sec, null), cost = lotsCost(ba, sec);
            if (atCost.compareTo(cost) != 0) {
                throw new Halt("J4c " + ba + " " + sec + " " + atCost + " " + cost);
            }
            if (normalBalance(ba, 3101, sec).compareTo(normalBalance(ba, 3102, sec).add(normalBalance(ba, 3103, sec))) != 0) {
                throw new Halt("J4b short lots " + ba + " " + sec);
            }
            BigDecimal atProceeds = balance(ba, 1107, sec, null).negate(), proceeds = lotsProceeds(ba, sec);
            if (atProceeds.compareTo(proceeds) != 0) {
                throw new Halt("J4c short " + ba + " " + sec + " " + atProceeds + " " + proceeds);
            }
        }
        for (int[] pair : kit.mirrored()) {
            BigDecimal a = normalBalance(ba, pair[0]), b = normalBalance(ba, pair[1]);
            if (a.compareTo(b) != 0) {
                throw new Halt("J4 " + pair[0] + " " + pair[1] + " " + a + " " + b);
            }
        }
    }
}
