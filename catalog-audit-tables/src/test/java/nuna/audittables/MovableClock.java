package nuna.audittables;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** A clock a test moves forward by hand. */
final class MovableClock extends Clock {

    private Instant now = Instant.parse("2026-11-20T09:00:00Z");

    synchronized void advance(Duration duration) {
        now = now.plus(duration);
    }

    @Override
    public synchronized Instant instant() {
        return now;
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        throw new UnsupportedOperationException();
    }
}
