package nuna.catalog.server.security;

import nuna.catalog.server.rest.AuditPublisher;
import nuna.catalog.server.rest.RestSettings;
import java.time.Clock;

/** A {@link Denials} for tests outside its package that build the routes without Spring. */
public final class DenialsForTests {

    private DenialsForTests() {
    }

    public static Denials denials(AuditPublisher audit, RestSettings settings, Clock clock) {
        return new Denials(audit, settings, clock);
    }
}
