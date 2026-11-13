package keel.maintenance;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** The wall clock, moved forward by what a test adds. */
final class FakeClock extends Clock {

    private volatile Duration offset = Duration.ZERO;

    void advance(Duration duration) {
        offset = offset.plus(duration);
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
