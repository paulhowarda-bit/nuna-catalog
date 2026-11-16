package keel.maintenance;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** The wall clock, moved forward by what a test adds, or set to an instant from which it runs on. */
final class FakeClock extends Clock {

    private volatile Duration offset = Duration.ZERO;

    void advance(Duration duration) {
        offset = offset.plus(duration);
    }

    /** Reads {@code instant} now, and runs on from there with the wall clock. */
    void set(Instant instant) {
        offset = Duration.between(Instant.now(), instant);
    }

    @Override
    public Instant instant() {
        return Instant.now().plus(offset);
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }
}
