package nuna.datagen.world;

import java.time.Instant;
import java.util.Map;
import java.util.NavigableMap;
import java.util.StringJoiner;
import java.util.TreeMap;

/**
 * The scale dial's history (item 321; Paul, 2026-11-27: "we also need a dial to rachet up activity to simulate a bigger
 * brokerage as needed"): from which instant the world ran at which scale, 1 (the world as its settings drew it) to
 * {@link #MAX}. At scale n the firm's customers do n times what the world's own accounts did, and the firm onboards new
 * customers toward n times its accounts ({@link World}). The history is kept with the world's settings, so a restart
 * replays each day at the scale it ran at; a change takes effect from an instant no event has been drawn at.
 */
public final class Scale {

    public static final int MIN = 1, MAX = 50;
    /** Scale 1 from the start: the world as its settings draw it. */
    public static final Scale ONE = new Scale(new TreeMap<>());

    private final NavigableMap<Instant, Integer> from;

    private Scale(NavigableMap<Instant, Integer> from) {
        this.from = from;
    }

    /** The scale at an instant. */
    public int at(Instant t) {
        Map.Entry<Instant, Integer> e = from.floorEntry(t);
        return e == null ? 1 : e.getValue();
    }

    /**
     * This history with scale n from an instant on; any change recorded at or after it, which nothing has drawn at
     * yet, gives way to it. Unchanged when the scale at the instant is n already.
     *
     * @throws IllegalArgumentException for a scale outside 1 to {@link #MAX}
     */
    public Scale with(Instant at, int n) {
        if (n < MIN || n > MAX) {
            throw new IllegalArgumentException("the scale is from " + MIN + " to " + MAX + ", not " + n);
        }
        NavigableMap<Instant, Integer> next = new TreeMap<>(from.headMap(at, false));
        Scale before = new Scale(next);
        if (before.at(at) != n) {
            next.put(at, n);
        }
        return before;
    }

    /** The form kept in the lake: {@code instant=n} entries, separated by commas; empty for {@link #ONE}. */
    @Override
    public String toString() {
        StringJoiner j = new StringJoiner(",");
        from.forEach((t, n) -> j.add(t + "=" + n));
        return j.toString();
    }

    public static Scale parse(String s) {
        NavigableMap<Instant, Integer> m = new TreeMap<>();
        if (s != null && !s.isBlank()) {
            for (String entry : s.split(",")) {
                int eq = entry.indexOf('=');
                m.put(Instant.parse(entry.substring(0, eq).trim()), Integer.parseInt(entry.substring(eq + 1).trim()));
            }
        }
        return new Scale(m);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Scale s && s.from.equals(from);
    }

    @Override
    public int hashCode() {
        return from.hashCode();
    }
}
