package nuna.datagen.live;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import nuna.datagen.kit.Kit;
import nuna.datagen.world.World;

/**
 * The firm's General Ledger (item 153), kept in Oracle {@code treasury} beside the bank's payments: one control account
 * per money definition of the customers' sub-ledger (SL-2, definitions 1001 to 1305), each day's journal entry of its
 * debits and credits, and its balance at the day's end. The GL control of Cash (1001) is Customer Cash Balances
 * [Control]: its balance is every brokerage account's Cash summed (tie 6, ControlOfTotal), as each control's is its
 * definition's.
 *
 * <p>A choice made here, Paul may overrule: the GL is summarized from the sub-ledger, one entry per control a day,
 * rather than a chart of the firm's own (bank, revenue and capital accounts), which the world does not keep.
 */
final class GeneralLedger {

    private static final class Day {
        BigDecimal debit = BigDecimal.ZERO.setScale(2), credit = BigDecimal.ZERO.setScale(2);
        long lines;
    }

    private final Kit kit;
    private final Map<Integer, Day> today = new TreeMap<>();
    /** Each control's balance, debit positive. */
    private final Map<Integer, BigDecimal> balances = new TreeMap<>();

    GeneralLedger(Kit kit) {
        this.kit = kit;
    }

    /** Whether a definition has a GL control: the customers' money sub-ledger's. */
    static boolean controlled(int definition, String measure) {
        return definition >= 1000 && definition < 2000 && "Money".equals(measure);
    }

    static String account(int definition) {
        return "GL-" + definition;
    }

    String name(int definition) {
        return definition == 1001 ? "Customer Cash Balances [Control]"
            : "Customer " + kit.definition(definition).name() + " [Control]";
    }

    /** A journal line the ledger posted. */
    void line(Map<String, Object> row) {
        int d = (Integer) row.get("definition_id");
        if (!controlled(d, (String) row.get("measure"))) {
            return;
        }
        Day day = today.computeIfAbsent(d, k -> new Day());
        BigDecimal amount = (BigDecimal) row.get("amount");
        if ("Debit".equals(row.get("side"))) {
            day.debit = day.debit.add(amount);
        } else {
            day.credit = day.credit.add(amount);
        }
        day.lines++;
    }

    /** The day's journal entries, one per control that moved, and the day's movements taken into the balances. */
    List<Map<String, Object>> journal(LocalDate date) {
        List<Map<String, Object>> out = new ArrayList<>();
        Instant posted = date.atTime(LocalTime.of(17, 30)).atZone(World.NEW_YORK).toInstant();
        today.forEach((d, day) -> {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("journal_id", "GLJ-" + date + "-" + d);
            r.put("business_date", date);
            r.put("gl_account", account(d));
            r.put("gl_account_name", name(d));
            r.put("definition_id", d);
            r.put("debit_total", day.debit);
            r.put("credit_total", day.credit);
            r.put("line_count", day.lines);
            r.put("posted_at", posted);
            out.add(r);
            balances.merge(d, day.debit.subtract(day.credit), BigDecimal::add);
        });
        today.clear();
        return out;
    }

    /** Every control's balance at the day's end, on its normal side. */
    List<Map<String, Object>> balances(LocalDate date) {
        List<Map<String, Object>> out = new ArrayList<>();
        balances.forEach((d, debitPositive) -> {
            boolean debit = kit.definition(d).normalSide() == Kit.Side.Debit;
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("balance_id", "GLB-" + date + "-" + d);
            r.put("business_date", date);
            r.put("gl_account", account(d));
            r.put("gl_account_name", name(d));
            r.put("definition_id", d);
            r.put("normal_side", debit ? "Debit" : "Credit");
            r.put("balance", debit ? debitPositive : debitPositive.negate());
            out.add(r);
        });
        return out;
    }
}
