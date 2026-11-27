package nuna.datagen.kit;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import nuna.datagen.kit.Engine.BalanceKey;
import nuna.datagen.kit.FinancialTransaction.LotShares;
import nuna.datagen.kit.FinancialTransaction.ReceivedLot;
import nuna.datagen.kit.Kit.Side;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Item 155: a lifecycle through the engine for each template the demo kit adds to v14's (70 to 91), on a cash account
 * and on a margin account carrying a debit. A cash account may not sell short (Regulation T), so the short side's
 * templates (80, 81, 90, 91 and the short half of 86) run on a margin account with a debit and on one without.
 * After every posting the books are checked: each journal entry balanced in its sub-ledger's measure, each sub-ledger
 * of the brokerage account summing to zero, the lots tied to their controls (3001 = 3002 + 3003, Securities at Cost =
 * the lots' remaining cost, and the short equivalents), every control and register mirror equal, and no ledger account
 * of a definition that may not reverse below zero.
 */
class DemoKitTemplatesTest {

    private static final Kit KIT = Kit.load();
    private static final long USD = 840, XYZ = 100001, ABC = 100002, CONTRA = 70_000_009L;
    private static final long DAY = LocalDate.of(2026, 10, 1).toEpochDay();

    /** The brokerage accounts the lifecycles run on: what each deposits, then buys of XYZ at $50.00. */
    enum Book {
        CASH(70_000_001L, "20000.00", 100),
        MARGIN(70_000_002L, "10000.00", 300),         // a $5,000.00 debit
        MARGIN_NO_DEBIT(70_000_003L, "20000.00", 100);

        final long id;
        final BigDecimal deposit;
        final long shares;

        Book(long id, String deposit, long shares) {
            this.id = id;
            this.deposit = new BigDecimal(deposit);
            this.shares = shares;
        }
    }

    private final Engine e = new Engine(KIT);
    private long nextId = 1;
    /** The brokerage account under test. */
    private long ba;

    // ------------------------------------------------------------------ helpers

    private FinancialTransaction txOn(int template, long account, Object... kv) {
        Map<Integer, Object> f = new TreeMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            f.put((Integer) kv[i], kv[i + 1]);
        }
        long id = nextId++;
        f.put(FinancialTransaction.ID, id);
        f.put(FinancialTransaction.EFFECTIVE_DATE, DAY);
        f.put(FinancialTransaction.ORIGINATOR, "test");
        f.put(FinancialTransaction.BROKERAGE_ACCOUNT, account);
        f.put(FinancialTransaction.CURRENCY, USD);
        f.putIfAbsent(FinancialTransaction.ORIGINATING_ID, id);
        return new FinancialTransaction(template, account, f);
    }

    private FinancialTransaction tx(int template, Object... kv) {
        return txOn(template, ba, kv);
    }

    /** Posts a transaction that must post, and checks the brokerage account's books after it. */
    private List<JournalLine> post(FinancialTransaction t) {
        List<JournalLine> lines;
        try {
            lines = e.post(t);
        } catch (Rejected r) {
            throw new AssertionError("template " + t.template() + " rejected: " + r.reason() + " " + r.getMessage(), r);
        }
        Map<Integer, BigDecimal> net = new TreeMap<>();
        for (JournalLine l : lines) {
            net.merge(l.subLedger(), l.side() == Side.Debit ? l.amount() : l.amount().negate(), BigDecimal::add);
        }
        net.forEach((sub, sum) -> assertThat(sum.signum()).as("J1 template %d sub-ledger %d", t.template(), sub)
            .isZero());
        check(t.brokerageAccount());
        return lines;
    }

    /** Posts a transaction the ledger must refuse, returning the reason, and checks it changed nothing. */
    private String refuse(FinancialTransaction t) {
        Map<BalanceKey, BigDecimal> before = e.balances(t.brokerageAccount());
        try {
            e.post(t);
        } catch (Rejected r) {
            assertThat(e.balances(t.brokerageAccount())).isEqualTo(before);
            return r.reason();
        }
        throw new AssertionError("template " + t.template() + " posted");
    }

    /** The checks after every posting (the class comment). */
    private void check(long account) {
        Map<Integer, BigDecimal> perSubLedger = new TreeMap<>();
        Set<Long> securities = new TreeSet<>();
        e.balances(account).forEach((k, v) -> {
            perSubLedger.merge(KIT.definition(k.definition()).root(), v, BigDecimal::add);
            if (k.security() != null) {
                securities.add(k.security());
            }
            if (!KIT.mayReverse(k.definition())) {
                BigDecimal normal = KIT.normal(k.definition()) == Side.Debit ? v : v.negate();
                assertThat(normal.signum()).as("%s of %d below zero", k, account).isNotNegative();
            }
        });
        perSubLedger.forEach((root, sum) -> assertThat(sum.signum()).as("sub-ledger %d of %d", root, account).isZero());
        for (long sec : securities) {
            assertThat(n(account, 3001, sec)).as("lots of %d", sec).isEqualByComparingTo(n(account, 3002, sec)
                .add(n(account, 3003, sec)));
            assertThat(n(account, 3004, sec)).as("sell hold of %d", sec).isEqualByComparingTo(n(account, 3005, sec));
            assertThat(e.balance(account, 1002, sec, null)).as("1002 of %d", sec)
                .isEqualByComparingTo(e.lotsCost(account, sec));
            assertThat(n(account, 3101, sec)).as("short lots of %d", sec).isEqualByComparingTo(n(account, 3102, sec)
                .add(n(account, 3103, sec)));
            assertThat(e.balance(account, 1107, sec, null).negate()).as("1107 of %d", sec)
                .isEqualByComparingTo(e.lotsProceeds(account, sec));
        }
        for (int[] pair : KIT.mirrored()) {
            assertThat(e.normalBalance(account, pair[0])).as("%d mirrors %d", pair[0], pair[1])
                .isEqualByComparingTo(e.normalBalance(account, pair[1]));
        }
    }

    private BigDecimal n(long account, int definition, long sec) {
        return e.normalBalance(account, definition, sec);
    }

    /** A definition's balance in its normal direction, on the account under test. */
    private BigDecimal bal(int definition) {
        return e.normalBalance(ba, definition);
    }

    private BigDecimal bal(int definition, long sec) {
        return e.normalBalance(ba, definition, sec);
    }

    private static BigDecimal d(String s) {
        return new BigDecimal(s);
    }

    private static List<LotShares> lots(Object... lotShares) {
        List<LotShares> out = new ArrayList<>();
        for (int i = 0; i < lotShares.length; i += 2) {
            out.add(new LotShares((Long) lotShares[i], d(lotShares[i + 1].toString())));
        }
        return out;
    }

    private static Set<Integer> subLedgers(List<JournalLine> lines) {
        Set<Integer> out = new TreeSet<>();
        lines.forEach(l -> out.add(l.subLedger()));
        return out;
    }

    /** Opens the book's account: its deposit, then its XYZ bought at $50.00 and settled. Returns the lot. */
    private long open(Book b) {
        ba = b.id;
        post(tx(30, 10, b.deposit, 11, "PAY-1"));
        return buy(XYZ, b.shares, "50.00");
    }

    private long buy(long sec, long qty, String price) {
        BigDecimal p = d(price), est = p.multiply(d("1.01")).setScale(2, RoundingMode.HALF_UP);
        BigDecimal estimate = est.multiply(BigDecimal.valueOf(qty)), value = p.multiply(BigDecimal.valueOf(qty));
        FinancialTransaction accepted = tx(40, 6, sec, 8, qty, 10, "ORD-" + nextId, 11, estimate, 12, est);
        post(accepted);
        FinancialTransaction fill = tx(41, 6, sec, 8, qty, 9, accepted.id(), 11, "FIL-" + nextId, 12, p, 13, value,
            14, estimate, 15, DAY, 16, "XNYS");
        post(fill);
        post(tx(42, 6, sec, 9, accepted.id(), 11, value, 12, DAY + 1));
        return fill.id();
    }

    /** Sells the named lots at the price; settles too when asked. Returns the acceptance. */
    private FinancialTransaction sell(long sec, String price, boolean settle, Object... lotShares) {
        List<LotShares> named = lots(lotShares);
        BigDecimal qty = named.stream().map(LotShares::shares).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal gross = d(price).multiply(qty).setScale(2, RoundingMode.HALF_UP), fees = d("0.04");
        FinancialTransaction accepted = tx(43, 6, sec, 8, qty, 10, "ORD-" + nextId);
        post(accepted);
        post(tx(44, 6, sec, 8, qty, 9, accepted.id(), 11, "FIL-" + nextId, 12, d(price), 13, gross, 14, d("0.03"),
            15, d("0.01"), 16, DAY, 17, "XNAS", 18, named));
        if (settle) {
            post(tx(45, 6, sec, 9, accepted.id(), 11, gross, 12, fees, 13, DAY + 1));
        }
        return accepted;
    }

    /** Sells short in full at the price and settles. Returns the short lot. */
    private long sellShort(long sec, long qty, String price) {
        BigDecimal p = d(price), est = p.multiply(d("0.99")).setScale(2, RoundingMode.HALF_UP);
        BigDecimal estimate = est.multiply(BigDecimal.valueOf(qty)), gross = p.multiply(BigDecimal.valueOf(qty));
        FinancialTransaction accepted = tx(60, 6, sec, 8, qty, 10, "ORD-" + nextId, 11, estimate, 12, est,
            13, "LOC-" + nextId);
        post(accepted);
        FinancialTransaction fill = tx(61, 6, sec, 8, qty, 9, accepted.id(), 11, "FIL-" + nextId, 12, p, 13, gross,
            14, d("0.03"), 15, d("0.01"), 16, DAY, 17, "ARCX", 18, estimate);
        post(fill);
        post(tx(62, 6, sec, 9, accepted.id(), 11, gross, 12, d("0.04"), 13, DAY + 1));
        return fill.id();
    }

    /** Covers the named short lots in full at the price and settles. */
    private void cover(long sec, String price, Object... lotShares) {
        List<LotShares> named = lots(lotShares);
        BigDecimal qty = named.stream().map(LotShares::shares).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal p = d(price), value = p.multiply(qty);
        FinancialTransaction accepted = tx(63, 6, sec, 8, qty, 10, "ORD-" + nextId, 11, value, 12, p);
        post(accepted);
        post(tx(64, 6, sec, 8, qty, 9, accepted.id(), 11, "FIL-" + nextId, 12, p, 13, value, 14, value, 15, DAY,
            16, "XNYS", 18, named));
        post(tx(65, 6, sec, 9, accepted.id(), 11, value, 12, DAY + 1));
    }

    // ------------------------------------------------------------------ money and fees

    @ParameterizedTest
    @EnumSource(names = {"CASH", "MARGIN"})
    void wireDepositsAndWithdrawalsMoveCashOnEveryClock(Book b) {
        open(b);
        BigDecimal cash = bal(1001), net = bal(1201), withdraw = bal(5201), trade = bal(4201);

        post(tx(70, 10, d("8000.00"), 11, "PAY-2"));
        assertThat(bal(1001)).isEqualByComparingTo(cash.add(d("8000")));
        assertThat(bal(1201)).isEqualByComparingTo(net.add(d("8000")));
        assertThat(bal(5201)).isEqualByComparingTo(withdraw.add(d("8000")));
        assertThat(bal(4201)).isEqualByComparingTo(trade.add(d("8000")));

        FinancialTransaction out = tx(71, 10, d("2500.00"), 11, DAY + 1, 12, "PAY-3");
        post(out);
        for (int held : List.of(1405, 1406, 5104, 4105)) {
            assertThat(bal(held)).as("%d", held).isEqualByComparingTo(d("2500"));
        }
        assertThat(bal(5201)).isEqualByComparingTo(withdraw.add(d("5500")));
        post(tx(72, 9, out.id(), 10, d("2500.00")));
        assertThat(bal(1001)).isEqualByComparingTo(cash.add(d("5500")));
        assertThat(bal(1201)).isEqualByComparingTo(net.add(d("5500")));
        for (int held : List.of(1405, 1406, 5104, 4105)) {
            assertThat(bal(held)).as("%d", held).isZero();
        }

        Map<BalanceKey, BigDecimal> before = e.balances(ba);
        FinancialTransaction cancelled = tx(71, 10, d("1000.00"), 11, DAY + 1, 12, "PAY-4");
        post(cancelled);
        assertThat(bal(4105)).isEqualByComparingTo(d("1000"));
        post(tx(73, 9, cancelled.id(), 10, d("1000.00")));
        assertThat(e.balances(ba)).isEqualTo(before);
    }

    @ParameterizedTest
    @EnumSource(names = {"CASH", "MARGIN"})
    void anAchDepositReturnedBeforeOrAfterSettlementLeavesTheBooksAsTheyWere(Book b) {
        open(b);
        Map<BalanceKey, BigDecimal> before = e.balances(ba);
        BigDecimal trade = bal(4201);

        FinancialTransaction early = tx(31, 10, d("3000.00"), 11, DAY + 1, 12, d("1000.00"), 13, "PAY-5");
        post(early);
        assertThat(bal(4201)).as("the good-faith credit").isEqualByComparingTo(trade.add(d("1000")));
        post(tx(74, 9, early.id(), 10, d("3000.00"), 12, d("1000.00"), 13, "R01"));
        assertThat(e.balances(ba)).isEqualTo(before);

        FinancialTransaction late = tx(31, 10, d("3000.00"), 11, DAY + 1, 12, d("1000.00"), 13, "PAY-6");
        post(late);
        post(tx(32, 9, late.id(), 10, d("3000.00"), 12, d("1000.00")));
        assertThat(bal(1001)).isEqualByComparingTo(b.deposit.subtract(d("50.00").multiply(BigDecimal.valueOf(b.shares)))
            .add(d("3000")));
        post(tx(75, 9, late.id(), 10, d("3000.00"), 13, "R10"));
        assertThat(e.balances(ba)).isEqualTo(before);
    }

    @ParameterizedTest
    @EnumSource(names = {"CASH", "MARGIN"})
    void aCashJournalMovesCollectedCashBetweenTwoBrokerageAccounts(Book b) {
        open(b);
        post(txOn(30, CONTRA, 10, d("5000.00"), 11, "PAY-7"));
        BigDecimal cash = bal(1001), other = e.normalBalance(CONTRA, 1001);

        FinancialTransaction in = txOn(76, CONTRA, 10, d("1500.00"), 11, "JNL-1", 12, ba);
        post(in);
        post(txOn(77, ba, 9, in.id(), 10, d("1500.00"), 11, "JNL-1", 12, CONTRA));
        FinancialTransaction back = tx(76, 10, d("600.00"), 11, "JNL-2", 12, CONTRA);
        post(back);
        post(txOn(77, CONTRA, 9, back.id(), 10, d("600.00"), 11, "JNL-2", 12, ba));

        assertThat(bal(1001)).isEqualByComparingTo(cash.add(d("900")));
        assertThat(e.normalBalance(CONTRA, 1001)).isEqualByComparingTo(other.subtract(d("900")));
        assertThat(e.normalBalance(CONTRA, 1201)).isEqualByComparingTo(d("4100"));
        assertThat(e.normalBalance(CONTRA, 5201)).isEqualByComparingTo(d("4100"));
        assertThat(e.normalBalance(CONTRA, 4201)).isEqualByComparingTo(d("4100"));
    }

    @ParameterizedTest
    @EnumSource(names = {"CASH", "MARGIN"})
    void anAccountFeeIsChargedOnEveryClockAndGivenBackNoMoreThanCharged(Book b) {
        open(b);
        Map<BalanceKey, BigDecimal> before = e.balances(ba);
        BigDecimal cash = bal(1001), withdraw = bal(5201);

        FinancialTransaction fee = tx(78, 10, d("25.00"), 11, "wire_out", 12, "PAY-8");
        post(fee);
        assertThat(bal(1304)).isEqualByComparingTo(d("25"));
        assertThat(bal(1001)).isEqualByComparingTo(cash.subtract(d("25")));
        assertThat(refuse(tx(79, 9, fee.id(), 10, d("30.00"), 11, "wire_out", 12, "billing_error")))
            .isEqualTo("BalanceBelowZero");
        post(tx(79, 9, fee.id(), 10, d("25.00"), 11, "wire_out", 12, "courtesy_waiver"));
        assertThat(e.balances(ba)).isEqualTo(before);

        post(tx(78, 6, XYZ, 10, d("12.34"), 11, "hard_to_borrow", 14, d("0.250000"), 15, DAY - 30, 16, DAY));
        post(tx(78, 10, d("75.00"), 11, "acats_out", 13, "AC202610010001"));
        assertThat(bal(1304)).isEqualByComparingTo(d("87.34"));
        assertThat(bal(5201)).isEqualByComparingTo(withdraw.subtract(d("87.34")));
    }

    @ParameterizedTest
    @EnumSource(names = {"MARGIN", "MARGIN_NO_DEBIT"})
    void aShortSaleCancelledAtTheCloseReleasesWhatItsUnfilledPartCommitted(Book b) {
        open(b);
        BigDecimal trade = bal(4201);
        FinancialTransaction accepted = tx(60, 6, ABC, 8, 100, 10, "ORD-9", 11, d("3960.00"), 12, d("39.60"),
            13, "LOC-9");
        post(accepted);
        assertThat(bal(4107)).isEqualByComparingTo(d("3960"));
        post(tx(61, 6, ABC, 8, 40, 9, accepted.id(), 11, "FIL-9", 12, d("40.00"), 13, d("1600.00"), 14, d("0.03"),
            15, d("0.01"), 16, DAY, 17, "ARCX", 18, d("1584.00")));
        assertThat(bal(4107)).isEqualByComparingTo(d("2376"));
        post(tx(80, 6, ABC, 8, 60, 9, accepted.id(), 11, d("2376.00")));
        assertThat(bal(4107)).isZero();
        post(tx(62, 6, ABC, 9, accepted.id(), 11, d("1600.00"), 12, d("0.04"), 13, DAY + 1));
        assertThat(bal(3102, ABC)).isEqualByComparingTo(d("40"));
        assertThat(bal(1107)).isEqualByComparingTo(d("1600"));
        assertThat(bal(4201)).isEqualByComparingTo(trade.subtract(d("0.04")));
    }

    @ParameterizedTest
    @EnumSource(names = {"MARGIN", "MARGIN_NO_DEBIT"})
    void aBuyToCoverCancelledAtTheCloseReleasesItsHoldAndCommitment(Book b) {
        open(b);
        long shortLot = sellShort(ABC, 100, "40.00");
        FinancialTransaction accepted = tx(63, 6, ABC, 8, 100, 10, "ORD-10", 11, d("4100.00"), 12, d("41.00"));
        post(accepted);
        assertThat(bal(1401)).isEqualByComparingTo(d("4100"));
        assertThat(bal(4101)).isEqualByComparingTo(d("4100"));
        post(tx(64, 6, ABC, 8, 30, 9, accepted.id(), 11, "FIL-10", 12, d("40.50"), 13, d("1215.00"),
            14, d("1230.00"), 15, DAY, 16, "XNYS", 18, lots(shortLot, 30)));
        post(tx(81, 6, ABC, 8, 70, 9, accepted.id(), 11, d("2870.00")));
        for (int held : List.of(1401, 1402, 4101)) {
            assertThat(bal(held)).as("%d", held).isZero();
        }
        post(tx(65, 6, ABC, 9, accepted.id(), 11, d("1215.00"), 12, DAY + 1));
        assertThat(bal(3102, ABC)).isEqualByComparingTo(d("70"));
        assertThat(bal(1107)).isEqualByComparingTo(d("2800"));
    }

    // ------------------------------------------------------------------ transfers

    @ParameterizedTest
    @EnumSource(names = {"CASH", "MARGIN"})
    void lotsReceivedByAcatsKeepTheDeliveringFirmsCostAndSellLikeAnyLot(Book b) {
        open(b);
        BigDecimal cash = bal(1001), trade = bal(4201);
        post(tx(82, 10, d("6000.00"), 11, "AC202610010002", 12, "S0412", 13, "full"));
        assertThat(bal(1001)).isEqualByComparingTo(cash.add(d("6000")));
        assertThat(bal(4201)).isEqualByComparingTo(trade.add(d("6000")));
        assertThat(bal(5201)).isEqualByComparingTo(bal(4201));

        List<ReceivedLot> received = List.of(
            new ReceivedLot(d("100"), d("3000.00"), LocalDate.of(2019, 3, 4).toEpochDay()),
            new ReceivedLot(d("50"), d("2250.50"), LocalDate.of(2021, 6, 10).toEpochDay()));
        FinancialTransaction in = tx(83, 6, ABC, 8, 150, 11, "AC202610010002", 12, "S0412", 13, "full", 18, received);
        List<JournalLine> lines = post(in);
        assertThat(subLedgers(lines)).containsExactly(Kit.SL_CA, Kit.SL_SHARE);
        long first = Engine.receivedLot(in.id(), 1), second = Engine.receivedLot(in.id(), 2);
        assertThat(e.balance(ba, 1002, ABC, null)).isEqualByComparingTo(d("5250.50"));
        assertThat(bal(1203)).isEqualByComparingTo(d("5250.50"));
        assertThat(e.balance(ba, 3001, ABC, first)).isEqualByComparingTo(d("100"));
        assertThat(e.balance(ba, 3001, ABC, second)).isEqualByComparingTo(d("50"));
        assertThat(bal(3002, ABC)).isEqualByComparingTo(d("150"));
        assertThat(e.lots().get(first)).isEqualTo(new Engine.LotRef(ba, ABC, d("3000.00"), d("100")));
        assertThat(bal(4201)).isEqualByComparingTo(trade.add(d("6000")));

        sell(ABC, "40.00", true, first, 100, second, 20);
        // 20 of the second lot's 50 shares at its $2,250.50: $900.20; the $1,350.30 left is its other 30.
        assertThat(e.balance(ba, 1002, ABC, null)).isEqualByComparingTo(d("1350.30"));
        assertThat(bal(3002, ABC)).isEqualByComparingTo(d("30"));
    }

    @Test
    void anAcatsReceiptMustBringLotsOfSharesAtACostOfZeroOrMore() {
        ba = Book.CASH.id;
        long acquired = LocalDate.of(2020, 1, 2).toEpochDay();
        assertThat(refuse(tx(83, 6, ABC, 8, 0, 11, "AC1", 12, "S0412", 13, "partial", 18, List.of())))
            .isEqualTo("LotNotHeld");
        assertThat(refuse(tx(83, 6, ABC, 8, 0, 11, "AC1", 12, "S0412", 13, "partial",
            18, List.of(new ReceivedLot(BigDecimal.ZERO, d("10.00"), acquired))))).isEqualTo("NegativeAmount");
        assertThat(refuse(tx(83, 6, ABC, 8, 10, 11, "AC1", 12, "S0412", 13, "partial",
            18, List.of(new ReceivedLot(d("10"), d("-1.00"), acquired))))).isEqualTo("NegativeAmount");
        // A gift of shares at no cost is a lot like any other.
        post(tx(83, 6, ABC, 8, 10, 11, "AC1", 12, "S0412", 13, "partial",
            18, List.of(new ReceivedLot(d("10"), d("0.00"), acquired))));
        assertThat(bal(3002, ABC)).isEqualByComparingTo(d("10"));
    }

    @ParameterizedTest
    @EnumSource(names = {"CASH", "MARGIN"})
    void anAcatsDeliveryTakesLotsOutAtTheirRemainingCost(Book b) {
        long first = open(b), second = buy(XYZ, 40, "55.00");
        post(tx(78, 10, d("75.00"), 11, "acats_out", 13, "AC202610010003"));
        BigDecimal cost = e.balance(ba, 1002, XYZ, null);
        if (b == Book.CASH) {
            // In full: every lot, then the cash; nothing is left on either clock.
            assertThat(refuse(tx(84, 6, XYZ, 8, 141, 11, "AC202610010003", 12, "S0587", 13, "full",
                18, lots(first, 101, second, 40)))).isEqualTo("LotNotHeld");
            List<JournalLine> lines = post(tx(84, 6, XYZ, 8, 140, 11, "AC202610010003", 12, "S0587", 13, "full",
                18, lots(first, 100, second, 40)));
            assertThat(subLedgers(lines)).containsExactly(Kit.SL_CA, Kit.SL_SHARE);
            assertThat(bal(1203)).isEqualByComparingTo(cost.negate());
            BigDecimal cash = bal(1001);
            assertThat(cash).isEqualByComparingTo(bal(5201));
            post(tx(85, 10, cash, 11, "AC202610010003", 12, "S0587", 13, "full"));
            assertThat(e.balances(ba).keySet()).allMatch(k -> KIT.definition(k.definition()).root() == Kit.SL_CA
                && k.definition() != 1001 && k.definition() != 1002);
        } else {
            // In part, the debit staying: a third of the first lot and all of the second.
            post(tx(84, 6, XYZ, 8, 140, 11, "AC202610010003", 12, "S0587", 13, "partial",
                18, lots(first, 100, second, 40)));
            assertThat(e.balance(ba, 1002, XYZ, null)).isEqualByComparingTo(d("10000.00"));
            assertThat(bal(1203)).isEqualByComparingTo(d("-7200.00"));
            assertThat(bal(3002, XYZ)).isEqualByComparingTo(d("200"));
            assertThat(bal(1001)).isEqualByComparingTo(d("-7275.00"));
        }
    }

    // ------------------------------------------------------------------ asset servicing

    @ParameterizedTest
    @EnumSource(names = {"CASH", "MARGIN"})
    void aSplitRescalesEachLotAtUnchangedCostAndTheFractionIsPaidInCash(Book b) {
        long first = open(b), second = buy(XYZ, 35, "52.00");
        BigDecimal cost = e.balance(ba, 1002, XYZ, null), held = bal(3002, XYZ), cash = bal(1001), gain = bal(1202);

        // 3 for 2: 100 or 300 shares become 150 or 450, the 35 become 52.5.
        List<JournalLine> lines = post(tx(86, 6, XYZ, 8, held, 10, "CA-1", 11, 3L, 12, 2L, 13, DAY));
        assertThat(subLedgers(lines)).containsExactly(Kit.SL_SHARE);
        assertThat(e.balance(ba, 3001, XYZ, first)).isEqualByComparingTo(BigDecimal.valueOf(b.shares * 3 / 2));
        assertThat(e.balance(ba, 3001, XYZ, second)).isEqualByComparingTo(d("52.5"));
        assertThat(e.balance(ba, 1002, XYZ, null)).isEqualByComparingTo(cost);
        assertThat(e.lots().get(second)).isEqualTo(new Engine.LotRef(ba, XYZ, d("1820.00"), d("52.5")));

        // The half share, at the adjusted $34.00: $17.00 for a cost of $1,820.00 x 0.5 / 52.5 = $17.33.
        post(tx(87, 6, XYZ, 8, d("0.5"), 10, d("17.00"), 11, "CA-1", 12, d("34.00"), 18, lots(second, "0.5")));
        assertThat(e.balance(ba, 1002, XYZ, null)).isEqualByComparingTo(cost.subtract(d("17.33")));
        assertThat(bal(1202)).isEqualByComparingTo(gain.subtract(d("0.33")));
        assertThat(bal(1001)).isEqualByComparingTo(cash.add(d("17.00")));
        assertThat(bal(3002, XYZ)).isEqualByComparingTo(BigDecimal.valueOf(b.shares * 3 / 2 + 52));

        // 1 for 10: the 52 shares become 5.2, the fifth of a share paid at $340.00. Its cost is what the lot's remaining
        // cost falls by (R12): $1,820.00 x 5.2 / 5.25 = $1,802.67 less $1,820.00 x 5 / 5.25 = $1,733.33, so $69.34.
        post(tx(86, 6, XYZ, 8, bal(3002, XYZ), 10, "CA-2", 11, 1L, 12, 10L, 13, DAY + 20));
        assertThat(e.balance(ba, 3001, XYZ, second)).isEqualByComparingTo(d("5.2"));
        assertThat(e.balance(ba, 1002, XYZ, null)).isEqualByComparingTo(cost.subtract(d("17.33")));
        post(tx(87, 6, XYZ, 8, d("0.2"), 10, d("68.00"), 11, "CA-2", 12, d("340.00"), 18, lots(second, "0.2")));
        assertThat(e.balance(ba, 1002, XYZ, null)).isEqualByComparingTo(cost.subtract(d("17.33")).subtract(d("69.34")));

        // Every share sold: Securities at Cost comes to nothing, to the cent.
        BigDecimal left = e.balance(ba, 3001, XYZ, first);
        sell(XYZ, "520.00", true, first, left, second, 5);
        assertThat(e.balance(ba, 1002, XYZ, null)).isZero();
        assertThat(bal(3002, XYZ)).isZero();
    }

    @Test
    void aSplitRescalesSharesNotYetPendingAndAPendingDeliverySettlesInTheOldShares() {
        long lot = open(Book.MARGIN);
        FinancialTransaction sold = sell(XYZ, "51.00", false, lot, 100);
        post(tx(86, 6, XYZ, 8, d("200"), 10, "CA-3", 11, 2L, 12, 1L, 13, DAY));
        assertThat(e.balance(ba, 3001, XYZ, lot)).isEqualByComparingTo(d("500"));
        assertThat(bal(3002, XYZ)).isEqualByComparingTo(d("400"));
        assertThat(e.balance(ba, 1002, XYZ, null)).isEqualByComparingTo(d("10000.00"));
        post(tx(45, 6, XYZ, 9, sold.id(), 11, d("5100.00"), 12, d("0.04"), 13, DAY + 1));
        assertThat(e.balance(ba, 3001, XYZ, lot)).isEqualByComparingTo(d("400"));
        assertThat(bal(3002, XYZ)).isEqualByComparingTo(d("400"));
    }

    @ParameterizedTest
    @EnumSource(names = {"MARGIN", "MARGIN_NO_DEBIT"})
    void aSplitRescalesShortLotsAtUnchangedProceeds(Book b) {
        open(b);
        long shortLot = sellShort(ABC, 100, "40.00");
        List<JournalLine> lines = post(tx(86, 6, ABC, 10, "CA-4", 11, 2L, 12, 1L, 13, DAY));
        assertThat(subLedgers(lines)).containsExactly(Kit.SL_SHARE);
        assertThat(bal(3101, ABC)).isEqualByComparingTo(d("200"));
        assertThat(bal(3102, ABC)).isEqualByComparingTo(d("200"));
        assertThat(bal(1107)).isEqualByComparingTo(d("4000"));
        assertThat(e.shortLots().get(shortLot)).isEqualTo(new Engine.LotRef(ba, ABC, d("4000.00"), d("200")));
        cover(ABC, "20.50", shortLot, 200);
        assertThat(bal(3102, ABC)).isZero();
        assertThat(bal(1107)).isZero();
    }

    @Test
    void aSplitThatWouldLeaveSharesTheUnitsCannotHoldIsRefused() {
        long lot = open(Book.CASH);
        // 100 shares 1 for 3: 33.333... shares, which no unit of 10^-5 holds.
        assertThat(refuse(tx(86, 6, XYZ, 8, d("100"), 10, "CA-5", 11, 1L, 12, 3L, 13, DAY)))
            .isEqualTo("FractionalShares");
        assertThat(refuse(tx(86, 6, XYZ, 8, d("100"), 10, "CA-5", 11, 0L, 12, 3L, 13, DAY)))
            .isEqualTo("NegativeAmount");
        assertThat(refuse(tx(86, 6, XYZ, 8, d("100"), 10, "CA-5", 11, -2L, 12, 1L, 13, DAY)))
            .isEqualTo("NegativeAmount");
        assertThat(e.balance(ba, 3001, XYZ, lot)).isEqualByComparingTo(d("100"));
    }

    @ParameterizedTest
    @EnumSource(names = {"CASH", "MARGIN"})
    void aDividendIsReceivableAtTheExDateAndCashOnEveryClockOnThePayDate(Book b) {
        open(b);
        BigDecimal cash = bal(1001), withdraw = bal(5201), trade = bal(4201);
        BigDecimal amount = d("0.24").multiply(BigDecimal.valueOf(b.shares)).setScale(2, RoundingMode.DOWN);
        FinancialTransaction entitled = tx(88, 6, XYZ, 8, b.shares, 10, amount, 11, "CA-6", 12, d("0.2400"),
            13, DAY + 5, 14, DAY + 5, 15, DAY + 15);
        List<JournalLine> lines = post(entitled);
        assertThat(subLedgers(lines)).containsExactly(Kit.SL_CA);
        assertThat(bal(1005)).isEqualByComparingTo(amount);
        assertThat(bal(1204)).isEqualByComparingTo(amount);
        assertThat(bal(5201)).isEqualByComparingTo(withdraw);
        assertThat(bal(4201)).isEqualByComparingTo(trade);

        post(tx(89, 6, XYZ, 9, entitled.id(), 10, amount, 11, "CA-6", 15, DAY + 15));
        assertThat(bal(1005)).isZero();
        assertThat(bal(1001)).isEqualByComparingTo(cash.add(amount));
        assertThat(bal(5201)).isEqualByComparingTo(withdraw.add(amount));
        assertThat(bal(4201)).isEqualByComparingTo(trade.add(amount));
    }

    @ParameterizedTest
    @EnumSource(names = {"MARGIN", "MARGIN_NO_DEBIT"})
    void aPaymentInLieuIsOwedOnBothClocksAtTheExDateAndPaidOnThePayDate(Book b) {
        open(b);
        sellShort(ABC, 100, "40.00");
        BigDecimal cash = bal(1001), withdraw = bal(5201), trade = bal(4201);
        FinancialTransaction accrued = tx(90, 6, ABC, 8, 100, 10, d("24.00"), 11, "CA-7", 12, d("0.2400"),
            13, DAY + 5, 14, DAY + 5, 15, DAY + 15);
        post(accrued);
        for (int owed : List.of(1305, 1108, 5107, 4109)) {
            assertThat(bal(owed)).as("%d", owed).isEqualByComparingTo(d("24"));
        }
        assertThat(bal(5201)).isEqualByComparingTo(withdraw.subtract(d("24")));
        assertThat(bal(4201)).isEqualByComparingTo(trade.subtract(d("24")));

        post(tx(91, 6, ABC, 9, accrued.id(), 10, d("24.00"), 11, "CA-7", 15, DAY + 15));
        for (int owed : List.of(1108, 5107, 4109)) {
            assertThat(bal(owed)).as("%d", owed).isZero();
        }
        assertThat(bal(1305)).isEqualByComparingTo(d("24"));
        assertThat(bal(1001)).isEqualByComparingTo(cash.subtract(d("24")));
        assertThat(bal(5201)).isEqualByComparingTo(withdraw.subtract(d("24")));
    }
}
