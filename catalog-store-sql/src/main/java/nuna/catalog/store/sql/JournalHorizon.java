package nuna.catalog.store.sql;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * How far ahead PostgreSQL's monthly journal partitions reach, and when a store extends them again
 * (backlog 9). A migration extends them {@link #MONTHS} ahead; a server that runs longer than that
 * without one would run out, and its commits would pile up in the default partition. So each store,
 * at most once every {@link #EVERY}, extends them again before a write that journals, and the
 * horizon stays {@link #MONTHS} ahead of its clock while it runs. A store that writes nothing needs
 * no partitions. The first extension is due {@link #EVERY} after the store is made: the server
 * migrates at start, which extends them then.
 */
final class JournalHorizon {

    /** How far ahead the journal's monthly partitions reach. */
    static final int MONTHS = 12;

    /** How often a store extends them: daily, as Oracle's job does (V5). */
    static final Duration EVERY = Duration.ofDays(1);

    private final Clock clock;
    private final AtomicReference<Instant> due;

    JournalHorizon(Clock clock) {
        this.clock = clock;
        this.due = new AtomicReference<>(clock.instant().plus(EVERY));
    }

    /** The instant the partitions should reach now: {@link #MONTHS} months on from the clock, in UTC. */
    Instant target() {
        return through(clock.instant());
    }

    /**
     * When an extension is due, the instant to extend the partitions through, for one caller only:
     * the others go on without it. The next is due {@link #EVERY} later, whether this one succeeds
     * or not.
     */
    Optional<Instant> claim() {
        Instant now = clock.instant();
        Instant next = due.get();
        if (now.isBefore(next) || !due.compareAndSet(next, now.plus(EVERY))) {
            return Optional.empty();
        }
        return Optional.of(through(now));
    }

    private static Instant through(Instant now) {
        return now.atOffset(ZoneOffset.UTC).plusMonths(MONTHS).toInstant();
    }
}
