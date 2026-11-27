package nuna.datagen.kit;

import static nuna.datagen.kit.Golden.BA_CASH;
import static nuna.datagen.kit.Golden.BA_MARGIN;
import static nuna.datagen.kit.Golden.BA_SHORT;
import static nuna.datagen.kit.Golden.D;
import static nuna.datagen.kit.Golden.T1;
import static nuna.datagen.kit.Golden.T3;
import static nuna.datagen.kit.Golden.USD;
import static nuna.datagen.kit.Golden.XYZ;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import nuna.datagen.kit.Engine.BalanceKey;
import nuna.datagen.kit.Engine.LedgerKey;
import nuna.datagen.kit.FinancialTransaction.LotShares;
import nuna.datagen.kit.Kit.BookingScheme;
import nuna.datagen.kit.Kit.Definition;
import nuna.datagen.kit.Kit.Entry;
import nuna.datagen.kit.Kit.Line;
import nuna.datagen.kit.Kit.Side;
import nuna.datagen.kit.Kit.Source;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The kit's own checks, ported one for one from {@code kit_checks()} in ledger kit v14's {@code schemes_v20.py}: 46
 * checks, each a test named by the Python check's text and asserting what it asserts.
 */
class KitChecksTest {

    private final Kit kit = Kit.load();

    /** Python's {@code RUNS}: the golden run's financial transactions, in order. */
    private static final List<FinancialTransaction> RUNS = Golden.transactions();

    private static final long L1 = Golden.lot("F-001"), L2 = Golden.lot("F-002"), L3 = Golden.lot("F-003");

    // ------------------------------------------------------------------ the Python helpers

    /** One posting of {@code run_lifecycle}: the transaction, its journal lines and every balance after it. */
    record Event(FinancialTransaction tx, List<JournalLine> lines, Map<Long, Map<BalanceKey, BigDecimal>> balances) {
        BigDecimal bal(long ba, int definition, Long security, Long lot) {
            return balances.getOrDefault(ba, Map.of())
                .getOrDefault(new BalanceKey(definition, security, lot), BigDecimal.ZERO);
        }
    }

    record Lifecycle(List<Event> events, Engine engine) {}

    /**
     * {@code run_lifecycle}: posts the transactions, asserting J1 in each journal entry's measure and that no register
     * goes negative after each.
     */
    private static Lifecycle runLifecycle(Kit k, List<FinancialTransaction> txs) throws Rejected {
        Engine engine = new Engine(k);
        List<Event> events = new ArrayList<>();
        for (FinancialTransaction t : txs) {
            List<JournalLine> lines = engine.post(t);
            for (int sub : new LinkedHashSet<>(lines.stream().map(JournalLine::subLedger).toList())) {
                BigDecimal dr = BigDecimal.ZERO, cr = BigDecimal.ZERO;
                for (JournalLine l : lines) {
                    if (l.subLedger() == sub) {
                        if (l.side() == Side.Debit) {
                            dr = dr.add(l.amount());
                        } else {
                            cr = cr.add(l.amount());
                        }
                    }
                }
                if (dr.compareTo(cr) != 0) {
                    throw new AssertionError("J1 " + t.id() + " " + sub + " " + dr + " " + cr);
                }
            }
            for (Kit.Mirror m : k.chart().mirrors()) {
                if (engine.normalBalance(t.brokerageAccount(), m.register()).signum() < 0) {
                    throw new AssertionError("register negative " + t.id() + " " + m.register());
                }
            }
            events.add(new Event(t, lines, engine.snapshot()));
        }
        return new Lifecycle(events, engine);
    }

    private Lifecycle golden() throws Rejected {
        return runLifecycle(kit, RUNS);
    }

    /** {@code fresh(n)}: an engine that has posted the first n financial transactions of the run. */
    private Engine fresh(int n) {
        return Golden.fresh(kit, n);
    }

    /** {@code _attempt}: the rejection reason, "Halt", or the journal lines. */
    private static Object attempt(Engine e, FinancialTransaction t) {
        try {
            return e.post(t);
        } catch (Rejected r) {
            return r.reason();
        } catch (Halt h) {
            return "Halt";
        }
    }

    /** Field changes as alternating field id and value. */
    private static Map<Integer, Object> fields(Object... kv) {
        Map<Integer, Object> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((Integer) kv[i], kv[i + 1]);
        }
        return m;
    }

    /** {@code _tx}: a copy of RUNS[i] with fields changed. */
    private static FinancialTransaction tx(int i, Object... kv) {
        return RUNS.get(i).with(fields(kv));
    }

    private static BigDecimal dec(String s) {
        return new BigDecimal(s);
    }

    private static List<LotShares> lots(Object... lotShares) {
        List<LotShares> out = new ArrayList<>();
        for (int i = 0; i < lotShares.length; i += 2) {
            out.add(new LotShares((Long) lotShares[i], dec(lotShares[i + 1].toString())));
        }
        return out;
    }

    private static String plain(BigDecimal a) {
        return a.stripTrailingZeros().toPlainString();
    }

    private static String line(int definition, String side, String amount) {
        return definition + " " + side + " " + plain(dec(amount));
    }

    /** {@code _sl}: one sub-ledger's journal lines as "definition side amount", sorted (amounts compared by value). */
    private static List<String> sl(List<JournalLine> lines, int sub) {
        return lines.stream().filter(l -> l.subLedger() == sub)
            .map(l -> l.definition() + " " + l.side() + " " + plain(l.amount())).sorted().toList();
    }

    private static List<String> sorted(String... lines) {
        return java.util.Arrays.stream(lines).sorted().toList();
    }

    /** {@code {(d, side): Decimal(a) for d, side, a in _sl(lines, sub)}} over the sub-ledgers in order (last wins). */
    private static Map<String, BigDecimal> bySide(List<JournalLine> lines, int... subs) {
        Map<String, BigDecimal> out = new LinkedHashMap<>();
        for (int sub : subs) {
            lines.stream().filter(l -> l.subLedger() == sub)
                .sorted(Comparator.comparingInt(JournalLine::definition)
                    .thenComparing(l -> l.side().name())
                    .thenComparing(JournalLine::amount))
                .forEach(l -> out.put(l.definition() + " " + l.side(), l.amount()));
        }
        return out;
    }

    private static Map<String, String> plainValues(Map<String, BigDecimal> m) {
        Map<String, String> out = new LinkedHashMap<>();
        m.forEach((k, v) -> out.put(k, plain(v)));
        return out;
    }

    private static List<JournalLine> allLines(List<Event> events) {
        return events.stream().flatMap(e -> e.lines().stream()).toList();
    }

    private static BigDecimal firstAmountOn(List<JournalLine> lines, int definition) {
        return lines.stream().filter(l -> l.definition() == definition).findFirst().orElseThrow().amount();
    }

    /** The SL-CA equity of the short brokerage account: -(1201 + 1202), debit-positive balances. */
    private static BigDecimal caeq(BigDecimal b1201, BigDecimal b1202) {
        return b1201.add(b1202).negate();
    }

    private Set<Integer> knownDimensions() {
        return kit.chart().dimensions().stream().map(Kit.Dimension::id).collect(Collectors.toSet());
    }

    /** {@code L(d, side, M(field))} with the definition's default bindings. */
    private Line l(int definition, Side side, int field) {
        return new Line(definition, side, Source.field(field), kit.defaultBindings(definition), null);
    }

    private static BookingScheme withEntries(BookingScheme s, List<Entry> entries) {
        return new BookingScheme(s.name(), s.id(), s.template(), s.calculations(), entries, s.note());
    }

    /** The ACH deposit settlement whose withdraw-clock journal entry takes the good-faith amount (field 12). */
    private Kit goodFaithCashKit() {
        BookingScheme s = kit.schemeFor(32);
        assertThat(s.name()).isEqualTo("ACH_DEPOSIT_SETTLED");
        List<Entry> ent = s.entries();
        return kit.withScheme(withEntries(s, List.of(ent.get(0),
            new Entry(5, List.of(l(5001, Side.Debit, 12), l(5201, Side.Credit, 12))), ent.get(2))));
    }

    /** RUNS[:16] with D1 and D2 (14, 15) carrying a good-faith amount of 3,000 on the 5,000 deposit. */
    private static List<FinancialTransaction> goodFaith3000() {
        List<FinancialTransaction> gf = new ArrayList<>(RUNS.subList(0, 16));
        gf.set(14, gf.get(14).with(fields(12, 3000L)));
        gf.set(15, gf.get(15).with(fields(12, 3000L)));
        return gf;
    }

    /** Posts financial transactions numbered from a start, field 9 defaulting to the transaction's own id. */
    private static final class Poster {
        final Engine engine;
        final long ba;
        long c;

        Poster(Kit kit, long ba, long start) {
            this.engine = new Engine(kit);
            this.ba = ba;
            this.c = start;
        }

        List<JournalLine> p(int template, Object... kv) throws Rejected {
            c++;
            Map<Integer, Object> v = new HashMap<>(Map.<Integer, Object>of(1, c, 5, ba, 7, USD, 6, XYZ));
            v.putAll(fields(kv));
            v.putIfAbsent(9, c);
            return engine.post(new FinancialTransaction(template, ba, v));
        }
    }

    // ------------------------------------------------------------------ loader rules (kit v9; B20 kit v13)

    @Test
    @DisplayName("R10/R4: every definition passes the chart loader")
    void everyDefinitionPassesTheChartLoader() {
        assertThat(kit.validateChart()).isEmpty();
    }

    @Test
    @DisplayName("R10: an SL-CA definition keyed by Currency only is rejected")
    void slCaDefinitionKeyedByCurrencyOnlyIsRejected() {
        Definition bad = new Definition(1999, 1000, "Asset", "Money", "1999", "Test - Cash by Currency only", "test",
            Side.Debit, List.of(2), Kit.SL_CA);
        assertThat(Kit.validateChart(List.of(bad), knownDimensions())).containsExactly("R10 1999");
    }

    @Test
    @DisplayName("R10: an SL-CA definition with BrokerageAccount second is rejected")
    void slCaDefinitionWithBrokerageAccountSecondIsRejected() {
        Definition bad = new Definition(1999, 1000, "Asset", "Money", "1999", "Test - Cash by Currency only", "test",
            Side.Debit, List.of(2, 1), Kit.SL_CA);
        assertThat(Kit.validateChart(List.of(bad), knownDimensions())).containsExactly("R10 1999");
    }

    @Test
    @DisplayName("A General Ledger definition keyed by Currency only is accepted (not under a customer root)")
    void generalLedgerDefinitionKeyedByCurrencyOnlyIsAccepted() {
        Definition gl = new Definition(9101, 9100, "Liability", "Money", "9101", "Customer Cash Balances [Control]",
            "Net cash of every brokerage account: credit balances owed to them less margin debits owed by them. "
                + "ControlOfTotal of SL-CA 1001: equals the sum of 1001 across every brokerage account, per currency. "
                + "Presented gross by brokerage account.",
            Side.Credit, List.of(Kit.CURRENCY), 9);
        assertThat(Kit.validateChart(List.of(gl), knownDimensions())).isEmpty();
    }

    @Test
    @DisplayName("A ledger account with no BrokerageAccount opens and resolves to the same ledger account")
    void ledgerAccountWithNoBrokerageAccountResolvesToTheSameLedgerAccount() {
        Engine e = new Engine(kit);
        long first = e.resolve(new LedgerKey(9101, List.of(USD)));
        long again = e.resolve(new LedgerKey(9101, List.of(USD)));
        assertThat(again).isEqualTo(first);
    }

    @Test
    @DisplayName("B20 (v13): all 45 booking schemes (v14's 22, item 155's 22, item 153's bust) pass the mirror proof: every line on a mirrored definition has its counterpart from the same source")
    void allBookingSchemesPassTheMirrorProof() {
        assertThat(kit.schemes().bookingSchemes()).hasSize(45);
        assertThat(kit.validateMirrors()).isEmpty();
    }

    @Test
    @DisplayName("B20 (v13): a booking scheme whose withdraw-clock cash line takes the good-faith amount instead of the deposit is rejected at load")
    void withdrawClockCashLineOnTheGoodFaithAmountIsRejectedAtLoad() {
        assertThat(goodFaithCashKit().validateMirrors()).containsExactly("ACH_DEPOSIT_SETTLED 5001 1001");
    }

    // ------------------------------------------------------------------ the engine halts on a control mismatch (J4, v13)

    @Test
    @DisplayName("J4 (v13): an ACH deposit whose good-faith amount (3,000) is less than the deposit (5,000) posts and every control holds; v12 listed 4003 as a mirror of 1004 and would have rejected it ControlBreak")
    void goodFaithAmountBelowTheDepositPostsAndEveryControlHolds() {
        assertThatCode(() -> runLifecycle(kit, goodFaith3000())).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("J4 (v13): the same deposit posted through the defective booking scheme, bypassing the loader, halts the engine (no ControlBreak rejection)")
    void defectiveBookingSchemeHaltsTheEngine() throws Rejected {
        List<FinancialTransaction> gf = goodFaith3000();
        // The Python posts gf[:15] through the kit's own schemes and only gf[15] through the defective set; an engine
        // is bound to its kit here, so all 16 go through the defective kit. The two differ only in template 32
        // (ACH_DEPOSIT_SETTLED), which none of gf[:15] uses.
        assertThat(gf.subList(0, 15)).noneMatch(t -> t.template() == 32);
        Engine e6 = new Engine(goodFaithCashKit());
        for (FinancialTransaction t : gf.subList(0, 15)) {
            e6.post(t);
        }
        assertThat(attempt(e6, gf.get(15))).isEqualTo("Halt");
    }

    // ------------------------------------------------------------------ field 9 links: the gateways' contract (F12)

    @Test
    @DisplayName("F12: every fill, settlement and cancel (25) names the acceptance or initiation of its own template, already posted for its brokerage account (the gateways' contract)")
    void everyLinkedTransactionNamesItsOwnLifecycleStart() throws Rejected {
        List<Event> events = golden().events();
        Map<Long, Integer> pos = new HashMap<>();
        Map<Long, List<Long>> tpl = new HashMap<>();
        for (int i = 0; i < events.size(); i++) {
            FinancialTransaction t = events.get(i).tx();
            pos.put(t.id(), i);
            tpl.put(t.id(), List.of(t.brokerageAccount(), (long) t.template()));
        }
        List<FinancialTransaction> linked = events.stream().map(Event::tx)
            .filter(t -> !kit.template(t.template()).startsLifecycle()).toList();
        assertThat(linked).hasSize(25);
        for (FinancialTransaction t : linked) {
            long origin = t.longValue(FinancialTransaction.ORIGINATING_ID);
            assertThat(tpl.get(origin)).as("%s", t.id()).isEqualTo(List.of(t.longValue(FinancialTransaction.BROKERAGE_ACCOUNT),
                (long) kit.template(t.template()).originTemplate()));
            assertThat(pos.get(origin)).as("%s", t.id()).isLessThan(pos.get(t.id()));
        }
    }

    @Test
    @DisplayName("F12: every lifecycle start names itself in field 9")
    void everyLifecycleStartNamesItself() throws Rejected {
        List<FinancialTransaction> starts = golden().events().stream().map(Event::tx)
            .filter(t -> kit.template(t.template()).startsLifecycle()).toList();
        assertThat(starts).isNotEmpty()
            .allMatch(t -> t.longValue(FinancialTransaction.ORIGINATING_ID) == t.id());
    }

    @Test
    @DisplayName("J13 retired (v13): a fill naming an order that was never accepted is not refused by the ledger; the order gateway checks lifecycles")
    void fillNamingAnOrderNeverAcceptedIsNotRefused() {
        assertThat(attempt(fresh(2), tx(2, 1, 7900L, 9, 7999L))).isInstanceOf(List.class);
    }

    // ------------------------------------------------------------------ resubmission under the same id (F4)

    @Test
    @DisplayName("F4: a sell fill rejected LotNotHeld (it named a lot that is no lot) is resubmitted under the same financialTransactionId with the right lot and posts; a second resubmission is DuplicateTransaction")
    void rejectedSellFillIsResubmittedThenDuplicate() {
        Engine e3 = fresh(7);
        Object r1 = attempt(e3, tx(7, 18, lots(7999L, 30)));
        Object r2 = attempt(e3, RUNS.get(7));
        Object r3 = attempt(e3, RUNS.get(7));
        assertThat(r1).isEqualTo("LotNotHeld");
        assertThat(r2).isInstanceOf(List.class);
        assertThat(r3).isEqualTo("DuplicateTransaction");
    }

    @Test
    @DisplayName("The rejection changed nothing (balances as before it)")
    void theRejectionChangedNothing() {
        Engine e3 = fresh(7);
        Map<Long, Map<BalanceKey, BigDecimal>> before = e3.snapshot();
        attempt(e3, tx(7, 18, lots(7999L, 30)));
        // As the Python asserts it: the balances taken before the rejection equal a fresh engine's of the same prefix.
        assertThat(before).isEqualTo(fresh(7).snapshot());
    }

    // ------------------------------------------------------------------ lots named upstream (v13)

    @Test
    @DisplayName("Lots (v13): S3's first fill names 30 of F-001 (1,501.50); the second 10 of F-001 and 10 of F-002 (500.50 + 500.60 = 1,001.10): remaining cost before less after")
    void s3FillsAreCostedRemainingCostBeforeLessAfter() throws Rejected {
        List<Event> events = golden().events();
        int i = Golden.index("S3");
        List<JournalLine> s3a = events.get(i).lines(), s3b = events.get(i + 1).lines();
        Map<String, BigDecimal> ca1 = bySide(s3a, Kit.SL_CA), ca2 = bySide(s3b, Kit.SL_CA);
        List<String> sh2 = s3b.stream().filter(l -> l.definition() == 3003)
            .sorted(Comparator.comparingLong(JournalLine::lot).thenComparing(JournalLine::amount))
            .map(l -> l.lot() + " " + plain(l.amount())).toList();
        assertThat(ca1.get("1002 Credit")).isEqualByComparingTo("1501.50");
        assertThat(ca2.get("1002 Credit")).isEqualByComparingTo("1001.10");
        assertThat(sh2).containsExactly(L1 + " 10", L2 + " 10");
    }

    @Test
    @DisplayName("Lots (v13): a sell naming lots that are not the earliest (20 of F-003, 10 of F-002) costs 1,000.80 + 500.60 = 1,501.40, gain 449.50, and every self-check holds")
    void sellNamingLotsThatAreNotTheEarliest() throws Rejected {
        Engine e7 = fresh(7);
        List<JournalLine> posted = e7.post(tx(7, 18, lots(L3, 20, L2, 10)));
        Map<String, BigDecimal> ca = bySide(posted, Kit.SL_CA);
        assertThat(ca.get("1002 Credit")).isEqualByComparingTo("1501.40");
        assertThat(ca.get("1202 Credit")).isEqualByComparingTo("449.50");
    }

    @Test
    @DisplayName("LotNotHeld (v13): after the first fill took 30 of F-001's 40, a fill naming 20 more of F-001 is rejected")
    void fillNamingMoreOfALotThanIsFreeIsRejected() {
        assertThat(attempt(fresh(8), tx(8, 18, lots(L1, 20)))).isEqualTo("LotNotHeld");
    }

    @Test
    @DisplayName("LotNotHeld (v13): a fill naming another brokerage account's lot is rejected")
    void fillNamingAnotherBrokerageAccountsLotIsRejected() {
        assertThat(attempt(fresh(7), tx(7, 18, lots(Golden.lot("M-001"), 30)))).isEqualTo("LotNotHeld");
    }

    @Test
    @DisplayName("Lots (v13): three single-share sales of a 3-share lot bought for 30.02 (10.0050 a share) relieve 10.01, 10.00 and 10.01, and Securities at Cost and the lot end at exactly zero")
    void threeSingleShareSalesRelieveTheLotToExactlyZero() throws Rejected {
        Poster e8 = new Poster(kit, BA_CASH, 7800);
        List<BigDecimal> reliefs = new ArrayList<>();
        e8.p(30, 10, 1000L, 11, "PAY-T");
        e8.p(40, 8, 3L, 10, "ORD-T", 11, dec("31.00"), 12, dec("10.3333"));
        long acc = e8.c;
        e8.p(41, 8, 3L, 9, acc, 11, "F-T", 12, dec("10.0050"), 13, dec("30.02"), 14, dec("31.00"), 15, D, 16, "XNYS");
        long lot = e8.c;
        e8.p(42, 9, acc, 11, dec("30.02"), 12, T1);
        e8.p(43, 8, 3L, 10, "ORD-S");
        long sacc = e8.c;
        for (int i = 0; i < 3; i++) {
            List<JournalLine> ls = e8.p(44, 8, 1L, 9, sacc, 11, "S-T" + i, 12, dec("10.0000"), 13, dec("10.00"),
                14, 0L, 15, 0L, 16, T3, 17, "XNYS", 18, lots(lot, 1));
            reliefs.add(firstAmountOn(ls, 1002));
        }
        e8.p(45, 9, sacc, 11, dec("30.00"), 12, 0L, 13, T3 + 1);
        assertThat(reliefs).usingElementComparator(BigDecimal::compareTo)
            .containsExactly(dec("10.01"), dec("10.00"), dec("10.01"));
        assertThat(e8.engine.balance(BA_CASH, 1002, XYZ, null)).isEqualByComparingTo("0");
        assertThat(e8.engine.balance(BA_CASH, 3001, XYZ, lot)).isEqualByComparingTo("0");
    }

    // ------------------------------------------------------------------ a sale at a loss, above estimate, fees (kit v10, v11)

    @Test
    @DisplayName("Loss (v10): 30 XYZ sold for 1,200.00 against cost 1,501.50 posts Debit 1202 301.50; the second fill Debit 201.10; Realized Gain/Loss ends at a 502.60 loss; every amount positive; every self-check holds through settlement")
    void saleAtALossThroughSettlement() throws Rejected {
        List<FinancialTransaction> loss = new ArrayList<>(RUNS.subList(0, 7));
        loss.add(tx(7, 1, 7950L, 12, dec("40.00"), 13, dec("1200.00")));
        loss.add(tx(8, 1, 7951L, 12, dec("40.00"), 13, dec("800.00")));
        loss.add(tx(9, 1, 7952L, 11, dec("2000.00")));
        Lifecycle run = runLifecycle(kit, loss);
        List<JournalLine> f1 = run.events().get(7).lines(), f2 = run.events().get(8).lines();
        List<String> want1 = sorted(line(1003, "Debit", "1200.00"), line(1002, "Credit", "1501.50"),
            line(1202, "Debit", "301.50"), line(1301, "Debit", "0.04"), line(1302, "Debit", "0.02"),
            line(1102, "Credit", "0.06"));
        BigDecimal g = run.engine().balance(BA_CASH, 1202, null, null).negate();
        assertThat(sl(f1, Kit.SL_CA)).isEqualTo(want1);
        assertThat(sl(f2, Kit.SL_CA)).contains(line(1202, "Debit", "201.10"));
        assertThat(g).isEqualByComparingTo("-502.60");
        assertThat(allLines(run.events())).allMatch(l -> l.amount().signum() > 0);
    }

    @Test
    @DisplayName("Above estimate (v10): 40 XYZ filled at 52.00 against the 51.50 estimate posts Debit Net Equity - Trade Clock 20.00; the mirrors hold")
    void fillAboveTheEstimate() throws Rejected {
        List<FinancialTransaction> above = new ArrayList<>(RUNS.subList(0, 2));
        above.add(tx(2, 12, dec("52.00"), 13, dec("2080.00")));
        Lifecycle run = runLifecycle(kit, above);
        assertThat(run.events()).isNotEmpty();
        assertThat(sl(run.events().get(2).lines(), Kit.SL_BP)).isEqualTo(sorted(line(4101, "Debit", "2060"),
            line(4102, "Credit", "2080.00"), line(4201, "Debit", "20.00")));
    }

    @Test
    @DisplayName("J12 (v11): fees larger than a sale's proceeds post Debit Net Equity - Trade Clock 2,049.12 at the fill and Credit Cash 749.56 at settlement, by the same rule; every self-check holds")
    void feesLargerThanTheProceeds() throws Rejected {
        List<FinancialTransaction> fees = new ArrayList<>(RUNS.subList(0, 7));
        fees.add(tx(7, 1, 7960L, 14, dec("4000.00")));
        fees.add(tx(8, 1, 7961L));
        fees.add(tx(9, 1, 7962L, 12, dec("4000.06")));
        Lifecycle run = runLifecycle(kit, fees);
        assertThat(run.events()).isNotEmpty();
        assertThat(sl(run.events().get(7).lines(), Kit.SL_BP)).contains(line(4201, "Debit", "2049.12"));
        assertThat(sl(run.events().get(9).lines(), Kit.SL_CA)).contains(line(1001, "Credit", "749.56"));
    }

    @Test
    @DisplayName("F13 (v11): a withdrawal whose amount is negative is rejected NegativeAmount at admission, and balances and ledger accounts are unchanged")
    void negativeWithdrawalIsRejectedAtAdmission() {
        Engine e4 = fresh(16);
        Map<Long, Map<BalanceKey, BigDecimal>> balBefore = e4.snapshot();
        Map<LedgerKey, Long> idxBefore = new LinkedHashMap<>(e4.ledgerAccounts());
        Object r = attempt(e4, tx(16, 1, 7970L, 9, 7970L, 10, dec("-2000.00")));
        assertThat(r).isEqualTo("NegativeAmount");
        assertThat(e4.snapshot()).isEqualTo(balBefore);
        assertThat(e4.ledgerAccounts()).isEqualTo(idxBefore);
    }

    // ------------------------------------------------------------------ one cash balance (v13)

    @Test
    @DisplayName("One cash balance (v13): M1c settles 40,000 entirely from Cash, which goes 25,000 to -15,000 (the margin debit); no loan ledger account exists in the chart")
    void m1cSettlesEntirelyFromCash() throws Rejected {
        List<Event> events = golden().events();
        Event m1c = events.get(Golden.index("M1c"));
        assertThat(plainValues(bySide(m1c.lines(), Kit.SL_CA)))
            .isEqualTo(Map.of("1101 Debit", "40000", "1001 Credit", "40000"));
        assertThat(m1c.bal(BA_MARGIN, 1001, null, null)).isEqualByComparingTo("-15000");
        assertThat(kit.name(1104)).isNull();
    }

    @Test
    @DisplayName("One cash balance (v13): M10a's deposit reduces the margin debit by itself, Cash -67,500 to -57,500; there is no paydown transaction")
    void m10aDepositReducesTheMarginDebit() throws Rejected {
        List<Event> events = golden().events();
        int i10 = Golden.index("M10a");
        assertThat(events.get(i10 - 1).bal(BA_MARGIN, 1001, null, null)).isEqualByComparingTo("-67500");
        assertThat(events.get(i10).bal(BA_MARGIN, 1001, null, null)).isEqualByComparingTo("-57500");
        assertThat(kit.template(50)).isNull();
    }

    @Test
    @DisplayName("One cash balance (v13): M13c, a withdrawal larger than the cash, credits Cash 5,500: -57,120 to -62,620")
    void m13cWithdrawalLargerThanTheCash() throws Rejected {
        List<Event> events = golden().events();
        int i13 = Golden.index("M13c");
        assertThat(events.get(i13 - 1).bal(BA_MARGIN, 1001, null, null)).isEqualByComparingTo("-57120");
        assertThat(events.get(i13).bal(BA_MARGIN, 1001, null, null)).isEqualByComparingTo("-62620");
    }

    private void settlementLeavesNetEquitiesUnchanged(String block) throws Rejected {
        List<Event> events = golden().events();
        int i = Golden.index(block);
        Event before = events.get(i - 1), after = events.get(i);
        for (int d : new int[] {5201, 4201}) {
            assertThat(after.bal(BA_MARGIN, d, null, null)).as("%s", d)
                .isEqualByComparingTo(before.bal(BA_MARGIN, d, null, null));
        }
    }

    @Test
    @DisplayName("M12b: settlement leaves Net Equity - Withdraw Clock and Net Equity - Trade Clock unchanged")
    void m12bSettlementLeavesNetEquitiesUnchanged() throws Rejected {
        settlementLeavesNetEquitiesUnchanged("M12b");
    }

    @Test
    @DisplayName("M13c: settlement leaves Net Equity - Withdraw Clock and Net Equity - Trade Clock unchanged")
    void m13cSettlementLeavesNetEquitiesUnchanged() throws Rejected {
        settlementLeavesNetEquitiesUnchanged("M13c");
    }

    @Test
    @DisplayName("No kind (v13): the margin brokerage account's settlements and withdrawals select the same booking schemes as the cash brokerage account's")
    void marginAndCashSelectTheSameBookingSchemes() {
        Map<String, String> names = new HashMap<>();
        for (Golden.Run r : Golden.runs()) {
            BookingScheme s = kit.schemeFor(r.template());
            names.put(r.block(), s == null ? null : s.name());
        }
        assertThat(names.get("M1c")).isEqualTo(names.get("B7"));
        assertThat(names.get("M12a")).isEqualTo(names.get("W1"));
        assertThat(names.get("M12b")).isEqualTo(names.get("W2"));
    }

    private Object postRetiredTemplate(int t) {
        return attempt(fresh(2), new FinancialTransaction(t, BA_MARGIN, Map.<Integer, Object>of(1, 7990L + t,
            5, BA_MARGIN, 7, USD, 9, 7990L + t, 10, 100L)));
    }

    @Test
    @DisplayName("A retired template (50) has no booking scheme and is rejected NoBookingScheme")
    void retiredTemplate50IsRejected() {
        assertThat(postRetiredTemplate(50)).isEqualTo("NoBookingScheme");
    }

    @Test
    @DisplayName("A retired template (53) has no booking scheme and is rejected NoBookingScheme")
    void retiredTemplate53IsRejected() {
        assertThat(postRetiredTemplate(53)).isEqualTo("NoBookingScheme");
    }

    @Test
    @DisplayName("Cancellation (v13): AchWithdrawalCancelled (35) restores the hold pair, the encumbrance, the commitment and both net equities to their values before the initiation")
    void cancellationRestoresTheInitiation() throws Rejected {
        int iw1 = Golden.index("W1");
        Engine e5 = fresh(iw1 + 1);
        Engine pre = fresh(iw1);
        e5.post(new FinancialTransaction(35, BA_CASH, Map.<Integer, Object>of(1, 7180L, 5, BA_CASH, 7, USD,
            9, RUNS.get(iw1).id(), 10, 2000L)));
        for (int d : new int[] {1405, 1406, 5104, 5201, 4105, 4201}) {
            assertThat(e5.balance(BA_CASH, d, null, null)).as("%s", d)
                .isEqualByComparingTo(pre.balance(BA_CASH, d, null, null));
        }
    }

    // ------------------------------------------------------------------ lots, ControlOfTotal (kit v9)

    @Test
    @DisplayName("Every lot's Lot value (6) is its buy fill's financialTransactionId")
    void everyLotIsKeyedByItsBuyFill() throws Rejected {
        Lifecycle run = golden();
        Set<List<Long>> fills = new HashSet<>();
        for (Event e : run.events()) {
            if (e.tx().template() == 41) {
                FinancialTransaction t = e.tx();
                fills.add(List.of(t.longValue(FinancialTransaction.BROKERAGE_ACCOUNT),
                    t.longValue(FinancialTransaction.SECURITY), t.id()));
            }
        }
        Set<List<Long>> lotKeys = run.engine().ledgerAccounts().keySet().stream()
            .filter(k -> k.definition() == 3001).map(LedgerKey::values).collect(Collectors.toSet());
        assertThat(lotKeys).hasSize(6).isEqualTo(fills);
    }

    @Test
    @DisplayName("ControlOfTotal: GL Customer Cash Balances [Control] = SL-CA Cash summed over the three brokerage accounts, after each of 44 blocks")
    void generalLedgerControlEqualsCashSummedAfterEachBlock() throws Rejected {
        List<Event> events = golden().events();
        Map<String, BigDecimal> glb = Golden.RUN.glControlByBlock();
        Map<String, Integer> last = new LinkedHashMap<>();   // first-occurrence order, each block's last event
        for (int i = 0; i < events.size(); i++) {
            last.put(Golden.runs().get(i).block(), i);
        }
        assertThat(last).hasSize(44);
        BigDecimal glBal = BigDecimal.ZERO;
        List<String> bad = new ArrayList<>();
        for (Map.Entry<String, Integer> e : last.entrySet()) {
            glBal = glBal.add(glb.getOrDefault(e.getKey(), BigDecimal.ZERO));
            BigDecimal total = BigDecimal.ZERO;
            for (Map<BalanceKey, BigDecimal> ba : events.get(e.getValue()).balances().values()) {
                for (Map.Entry<BalanceKey, BigDecimal> b : ba.entrySet()) {
                    if (b.getKey().definition() == 1001) {
                        total = total.add(b.getValue());
                    }
                }
            }
            if (total.compareTo(glBal) != 0) {
                bad.add(e.getKey() + " " + total + " " + glBal);
            }
        }
        assertThat(bad).isEmpty();
    }

    @Test
    @DisplayName("No journal line in the four lifecycles has a negative or zero amount")
    void noJournalLineHasANegativeOrZeroAmount() throws Rejected {
        List<JournalLine> lines = allLines(golden().events());
        assertThat(lines).isNotEmpty().allMatch(l -> l.amount().signum() > 0);
    }

    // ------------------------------------------------------------------ short sales (v14)

    @Test
    @DisplayName("Short (v14): after every financial transaction of the Short sheet, Net Equity - Withdraw Clock = SL-CA equity, and Net Equity - Trade Clock = SL-CA equity less open-order commitments (4101, 4107)")
    void shortSheetNetEquitiesEqualSlCaEquity() throws Rejected {
        List<Event> ish = golden().events().stream().filter(e -> e.tx().brokerageAccount() == BA_SHORT).toList();
        assertThat(ish).isNotEmpty();
        for (Event e : ish) {
            BigDecimal caeq = caeq(e.bal(BA_SHORT, 1201, null, null), e.bal(BA_SHORT, 1202, null, null));
            assertThat(e.bal(BA_SHORT, 5201, null, null).negate()).as("withdraw clock after %s", e.tx().id())
                .isEqualByComparingTo(caeq);
            assertThat(e.bal(BA_SHORT, 4201, null, null).negate()).as("trade clock after %s", e.tx().id())
                .isEqualByComparingTo(caeq.add(e.bal(BA_SHORT, 4101, null, null)).add(e.bal(BA_SHORT, 4107, null, null)));
        }
    }

    @Test
    @DisplayName("Short (v14): SH4b's cover of 200 ABC at 80 relieves the short lot's 10,000 of proceeds (1107, 4108, 5106) and posts the 6,000 loss to Realized Gain/Loss and Net Equity - Withdraw Clock (J12: debits)")
    void sh4bCoverRelievesTheProceedsAndPostsTheLoss() throws Rejected {
        List<Event> events = golden().events();
        Map<String, BigDecimal> s4b = bySide(events.get(Golden.index("SH4b")).lines(), Kit.SL_CA, Kit.SL_BP, Kit.SL_RCB);
        assertThat(s4b.get("1107 Debit")).isEqualByComparingTo("10000");
        assertThat(s4b.get("4108 Debit")).isEqualByComparingTo("10000");
        assertThat(s4b.get("5106 Debit")).isEqualByComparingTo("10000");
        assertThat(s4b.get("1202 Debit")).isEqualByComparingTo("6000");
        assertThat(s4b.get("5201 Debit")).isEqualByComparingTo("6000");
        assertThat(s4b.get("4201 Credit")).isEqualByComparingTo("10000");
    }

    @Test
    @DisplayName("Short (v14): the short lot's Lot value is its short sale fill's financialTransactionId")
    void shortLotIsKeyedByItsShortSaleFill() throws Rejected {
        Set<Long> shKeys = golden().engine().ledgerAccounts().keySet().stream()
            .filter(k -> k.definition() == 3101).map(k -> k.values().get(2)).collect(Collectors.toSet());
        assertThat(shKeys).isEqualTo(Set.of(Golden.lot("SH-001")));
    }

    @Test
    @DisplayName("LotNotHeld (v14): a cover naming 201 shares of a 200-share short lot is rejected")
    void coverNamingMoreSharesThanTheShortLotIsRejected() {
        Object r = attempt(fresh(Golden.index("SH4a") + 1),
            tx(Golden.index("SH4b"), 18, lots(Golden.lot("SH-001"), 201)));
        assertThat(r).isEqualTo("LotNotHeld");
    }

    @Test
    @DisplayName("LotNotHeld (v14): a cover naming a share lot (another brokerage account's buy fill) is rejected")
    void coverNamingAShareLotIsRejected() {
        Object r = attempt(fresh(Golden.index("SH4a") + 1),
            tx(Golden.index("SH4b"), 18, lots(Golden.lot("M-002"), 200)));
        assertThat(r).isEqualTo("LotNotHeld");
    }

    @Test
    @DisplayName("Short lots (v14): three single-share covers of a 3-share short lot sold for 30.02 relieve 10.01, 10.00 and 10.01 of proceeds; Securities Sold Short at Proceeds and the short lot end at exactly zero")
    void threeSingleShareCoversRelieveTheShortLotToExactlyZero() throws Rejected {
        Poster e9 = new Poster(kit, BA_SHORT, 7850);
        List<BigDecimal> rel = new ArrayList<>();
        e9.p(30, 10, 1000L, 11, "PAY-ST");
        e9.p(60, 8, 3L, 10, "ORD-ST", 11, dec("30.02"), 12, dec("10.0067"), 13, "LOC-T");
        long sa = e9.c;
        e9.p(61, 8, 3L, 9, sa, 11, "SH-T", 12, dec("10.0067"), 13, dec("30.02"), 14, 0L, 15, 0L, 16, D, 17, "XNYS",
            18, dec("30.02"));
        long slot = e9.c;
        e9.p(62, 9, sa, 11, dec("30.02"), 12, 0L, 13, T1);
        for (int i = 0; i < 3; i++) {
            e9.p(63, 8, 1L, 10, "ORD-C" + i, 11, dec("10.00"), 12, dec("10.00"));
            long ca = e9.c;
            List<JournalLine> ls = e9.p(64, 8, 1L, 9, ca, 11, "CV-T" + i, 12, dec("10.0000"), 13, dec("10.00"),
                14, dec("10.00"), 15, T1, 16, "XNYS", 18, lots(slot, 1));
            rel.add(firstAmountOn(ls, 1107));
            e9.p(65, 9, ca, 11, dec("10.00"), 12, T1 + 1);
        }
        assertThat(rel).usingElementComparator(BigDecimal::compareTo)
            .containsExactly(dec("10.01"), dec("10.00"), dec("10.01"));
        assertThat(e9.engine.balance(BA_SHORT, 1107, XYZ, null)).isEqualByComparingTo("0");
        assertThat(e9.engine.balance(BA_SHORT, 3101, XYZ, slot)).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("Day trade (v14): a short covered before it settles posts; Encumbrance - Short Sale Proceeds (MayReverse, R16) is -10,000 until the short settles, then zero; Net Equity - Withdraw Clock equals SL-CA equity throughout")
    void shortCoveredBeforeItSettles() throws Rejected {
        Map<String, FinancialTransaction> byId = new HashMap<>();   // the last transaction of each block, as Python's dict
        for (int i = 0; i < RUNS.size(); i++) {
            byId.put(Golden.runs().get(i).block(), RUNS.get(i));
        }
        Engine e10 = new Engine(kit);
        List<String> seen5106 = new ArrayList<>();
        for (String block : List.of("SH0", "SH1a", "SH1b", "SH4a", "SH4b", "SH1c", "SH4c")) {
            e10.post(byId.get(block));
            seen5106.add(plain(e10.balance(BA_SHORT, 5106, null, null)));
            assertThat(e10.balance(BA_SHORT, 5201, null, null).negate()).as("after %s", block).isEqualByComparingTo(
                caeq(e10.balance(BA_SHORT, 1201, null, null), e10.balance(BA_SHORT, 1202, null, null)));
        }
        assertThat(seen5106).containsExactly("0", "0", "0", "0", "10000", "0", "0");
    }

    @Test
    @DisplayName("J16 (v14): a second settlement of the same ACH withdrawal under a new financialTransactionId is rejected BalanceBelowZero (the hold, reserve, encumbrance and commitment would go below zero) and changes nothing")
    void secondSettlementOfAWithdrawalIsRejected() {
        int iw2 = Golden.index("W2");
        Engine e11 = fresh(iw2 + 1);
        Map<Long, Map<BalanceKey, BigDecimal>> b0 = e11.snapshot();
        Object r = attempt(e11, tx(iw2, 1, 7181L));
        assertThat(r).isEqualTo("BalanceBelowZero");
        assertThat(e11.snapshot()).isEqualTo(b0);
    }

    @Test
    @DisplayName("J16 (v14): cancelling a withdrawal that has already settled is rejected BalanceBelowZero")
    void cancellingASettledWithdrawalIsRejected() {
        int iw2 = Golden.index("W2");
        Object r = attempt(fresh(iw2 + 1), new FinancialTransaction(35, BA_CASH, Map.<Integer, Object>of(1, 7182L,
            5, BA_CASH, 7, USD, 9, RUNS.get(iw2 - 1).id(), 10, 2000L)));
        assertThat(r).isEqualTo("BalanceBelowZero");
    }

    @Test
    @DisplayName("J16 (v14): no Posting of the four lifecycles takes a definition that is not MayReverse below zero; margin Cash goes to -62,620 because Cash is MayReverse")
    void noPostingTakesAFlooredDefinitionBelowZero() throws Rejected {
        List<String> below = new ArrayList<>();
        for (Event e : golden().events()) {
            e.balances().forEach((ba, m) -> m.forEach((k, v) -> {
                int d = k.definition();
                BigDecimal normal = kit.normal(d) == Side.Debit ? v : v.negate();
                if (!kit.mayReverse(d) && normal.signum() < 0) {
                    below.add(e.tx().id() + " " + ba + " " + k + " " + v);
                }
            }));
        }
        assertThat(below).isEmpty();
        assertThat(kit.mayReverse(1001)).isTrue();
    }

    @Test
    @DisplayName("B20 (v14): a short sale booking scheme that credits net equity instead of committing the proceeds (4108) is rejected at load")
    void shortSaleSchemeCreditingNetEquityInsteadOfCommittingIsRejected() {
        BookingScheme s = kit.schemeFor(61);
        assertThat(s.name()).isEqualTo("EQUITY_SHORT_SELL_EXECUTED");
        List<Entry> ent = s.entries();
        List<Line> bp = new ArrayList<>(ent.get(2).lines().stream().filter(x -> x.definition() != 4108).toList());
        bp.add(l(4201, Side.Credit, 13));
        Kit bad = kit.withScheme(withEntries(s, List.of(ent.get(0), ent.get(1), new Entry(4, bp), ent.get(3))));
        assertThat(bad.validateMirrors()).containsExactly("EQUITY_SHORT_SELL_EXECUTED 4108 1107");
    }
}
