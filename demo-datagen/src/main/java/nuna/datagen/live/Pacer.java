package nuna.datagen.live;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Holds each event back until its business time comes round on the real clock, as a live firm's events would arrive
 * (Paul, 2026-11-23, D48): an order placed at 10:14 New York time is sent at 10:14 New York time. An event whose time
 * has passed (the catch-up days, or today's earlier hours after a start or restart) goes at once. Since the world runs
 * around the clock (D48, amended 2026-11-27) every hour sends events, so a long wait is rare.
 */
public final class Pacer {

    /** A wait longer than this is logged, so a quiet generator says when it next sends. */
    static final Duration ANNOUNCED = Duration.ofMinutes(5);

    /** Sleeps for a duration; a test passes one that records instead. */
    interface Sleeper {
        void sleep(Duration d) throws InterruptedException;
    }

    private final Clock clock;
    private final Sleeper sleeper;

    public Pacer() {
        this(Clock.systemUTC(), Thread::sleep);
    }

    Pacer(Clock clock, Sleeper sleeper) {
        this.clock = clock;
        this.sleeper = sleeper;
    }

    public void await(Instant at) throws InterruptedException {
        Duration wait = Duration.between(clock.instant(), at);
        if (wait.isNegative() || wait.isZero()) {
            return;
        }
        if (wait.compareTo(ANNOUNCED) > 0) {
            System.out.println("quiet until " + at + " (" + wait.toMinutes() + " minutes)");
        }
        sleeper.sleep(wait);
    }
}
