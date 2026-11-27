package nuna.datagen.world;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import nuna.datagen.kit.Kit;
import nuna.datagen.live.Sources;

/**
 * A world played for the tests of its order management system, clearing and reconciliation views: two weeks of 400
 * accounts, with its seeded fail to deliver or without, each played once per test run. The seed is one whose two weeks
 * hold ACATS transfers in and out and a split, which move positions besides fills. The streams (bars, quotes, sessions
 * and click events) are not kept, nor journal lines other than Realized Gain/Loss's (tie 8 reads those); the source
 * databases' books ({@link Sources.Book}: the back office, the bank's payments and the General Ledger, which ties 5
 * and 6 read) are.
 */
public final class Played implements Sink {

    public static final World.Settings SETTINGS = World.Settings.of(160, LocalDate.of(2026, 9, 1), 400);
    public static final int DAYS = 10;
    private static final Kit KIT = Kit.load();
    /**
     * A world that crosses a month end (item 153): from 2026-09-21, ten business days, so September closes and the
     * seeded bust corrects it on 2026-10-01.
     */
    public static final World.Settings CLOSING = World.Settings.of(160, LocalDate.of(2026, 9, 21), 400);
    private static Played seeded, clean, closing, closingClean;

    public final Map<Feed, List<Map<String, Object>>> rows = new EnumMap<>(Feed.class);
    public final List<LocalDate> days = new ArrayList<>();
    public int unexpectedRejections;
    public boolean breakSeeded;
    public boolean bustSeeded;
    public final List<MonthEnd> closes = new ArrayList<>();
    public final List<MonthEnd.Restatement> restatements = new ArrayList<>();
    /** The source databases' tables as Main cdc would load them after the last day played. */
    public final Sources.Book book = new Sources.Book();

    private Played() {
    }

    /** The world as it runs in the demo, with its seeded fail. */
    public static synchronized Played seeded() {
        if (seeded == null) {
            seeded = play(true);
        }
        return seeded;
    }

    /** The same world without the seeded fail. */
    public static synchronized Played clean() {
        if (clean == null) {
            clean = play(false);
        }
        return clean;
    }

    /** The world crossing September's end, with its seeded bust (and fail) or without either. */
    public static synchronized Played closing(boolean seeded) {
        if (seeded) {
            if (closing == null) {
                closing = play(CLOSING, true);
            }
            return closing;
        }
        if (closingClean == null) {
            closingClean = play(CLOSING, false);
        }
        return closingClean;
    }

    private static Played play(boolean withBreak) {
        return play(SETTINGS, withBreak);
    }

    private static Played play(World.Settings settings, boolean withBreak) {
        World w = new World(settings, KIT);
        if (!withBreak) {
            w.withoutSeededFail();
            w.withoutSeededBust();
        }
        Played p = new Played();
        for (int i = 0; i < DAYS; i++) {
            w.runDay(p);
        }
        p.unexpectedRejections = w.unexpectedRejections();
        p.breakSeeded = w.clearingBreakSeeded();
        p.bustSeeded = w.bustSeeded();
        return p;
    }

    public List<Map<String, Object>> of(Feed f) {
        return rows.getOrDefault(f, List.of());
    }

    @Override
    public void accept(Event e) {
        book.accept(e);
        // Of the journal lines, only Realized Gain/Loss (1202): tie 8 reads them. No stream: no tie reads one.
        if (!e.feed().streaming() && (e.feed() != Feed.JOURNAL_LINES
            || Integer.valueOf(1202).equals(e.row().get("definition_id")))) {
            rows.computeIfAbsent(e.feed(), k -> new ArrayList<>()).add(e.row());
        }
    }

    @Override
    public void endOfDay(LocalDate day) {
        days.add(day);
        book.endOfDay(day);
    }

    @Override
    public void monthEnd(MonthEnd close) {
        closes.add(close);
    }

    @Override
    public void restated(MonthEnd.Restatement restatement) {
        restatements.add(restatement);
    }
}
