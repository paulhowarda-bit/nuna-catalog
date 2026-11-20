package nuna.catalog.store.sql;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** A UTC clock that stands still until a test moves it. */
final class MovableClock extends Clock {

    private volatile Instant now;

    MovableClock(Instant now) {
        this.now = now;
    }

    void advance(Duration by) {
        now = now.plus(by);
    }

    @Override
    public Instant instant() {
        return now;
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        throw new UnsupportedOperationException("a UTC clock");
    }
}
