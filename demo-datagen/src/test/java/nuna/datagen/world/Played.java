package nuna.datagen.world;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import nuna.datagen.kit.Kit;

/**
 * A world played for the tests of its order management system, clearing and reconciliation views: two weeks of 400
 * accounts, with its seeded fail to deliver or without, each played once per test run. Bars and journal lines are not
 * kept: nothing here reads them.
 */
public final class Played implements Sink {

    public static final World.Settings SETTINGS = World.Settings.of(151, LocalDate.of(2026, 9, 1), 400);
    public static final int DAYS = 10;
    private static final Kit KIT = Kit.load();
    private static Played seeded, clean;

    public final Map<Feed, List<Map<String, Object>>> rows = new EnumMap<>(Feed.class);
    public final List<LocalDate> days = new ArrayList<>();
    public int unexpectedRejections;
    public boolean breakSeeded;

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

    private static Played play(boolean withBreak) {
        World w = new World(SETTINGS, KIT);
        if (!withBreak) {
            w.withoutSeededFail();
        }
        Played p = new Played();
        for (int i = 0; i < DAYS; i++) {
            w.runDay(p);
        }
        p.unexpectedRejections = w.unexpectedRejections();
        p.breakSeeded = w.clearingBreakSeeded();
        return p;
    }

    public List<Map<String, Object>> of(Feed f) {
        return rows.getOrDefault(f, List.of());
    }

    @Override
    public void accept(Event e) {
        if (e.feed() != Feed.BARS_5M && e.feed() != Feed.JOURNAL_LINES) {
            rows.computeIfAbsent(e.feed(), k -> new ArrayList<>()).add(e.row());
        }
    }

    @Override
    public void endOfDay(LocalDate day) {
        days.add(day);
    }
}
