package nuna.catalog.server.rest;

import io.micrometer.core.instrument.MeterRegistry;

/** The server's alerts over a registry a test reads, for tests outside this package. */
public final class ServerAlertsForTests {

    private ServerAlertsForTests() {
    }

    public static ServerAlerts alerts(MeterRegistry registry) {
        return new ServerAlerts(1_000_000, 100, registry);
    }
}
