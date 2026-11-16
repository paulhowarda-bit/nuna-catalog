package keel.maintenance;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import java.time.Clock;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;

/**
 * The maintenance worker as a service (D30 (1)): no web server, one scan of the work list every
 * {@code keel.maintenance.scan-interval}, the first at start. One replica: two would only race each other for
 * the same commits and claims, which the catalog settles, at twice the cost.
 */
@SpringBootApplication
@EnableConfigurationProperties(WorkerSettings.class)
public class WorkerApplication {

    public static void main(String[] args) {
        new SpringApplicationBuilder(WorkerApplication.class).web(WebApplicationType.NONE).run(args);
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    /** The worker, its scans observed in Boot's registry and their trace carried to the catalog (backlog 49). */
    @Bean(destroyMethod = "close")
    Worker worker(WorkerSettings settings, Clock clock, MeterRegistry meters, ObservationRegistry observations,
                  Tracer tracer, Propagator propagator) {
        return Worker.of(settings, clock, meters, observations, TraceHeaders.of(tracer, propagator));
    }

    @Bean
    Scans scans(Worker worker, WorkerSettings settings) {
        return new Scans(worker, settings);
    }

    /** Runs the scans on one non-daemon thread, which keeps the service alive; a scan never overlaps the next. */
    static final class Scans implements SmartLifecycle {

        private static final Logger LOG = LoggerFactory.getLogger(Scans.class);

        private final Worker worker;
        private final WorkerSettings settings;
        private ScheduledExecutorService scheduler;

        Scans(Worker worker, WorkerSettings settings) {
            this.worker = worker;
            this.settings = settings;
        }

        @Override
        public synchronized void start() {
            scheduler = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("keel-maintenance")
                    .daemon(false).factory());
            scheduler.scheduleWithFixedDelay(this::scan, 0, settings.scanInterval().toMillis(), TimeUnit.MILLISECONDS);
            LOG.info("Scanning {} every {}", settings.catalogUri(), settings.scanInterval());
        }

        private void scan() {
            try {
                ScanReport report = worker.runOnce();
                LOG.info("Scan done: {}", report);
                report.failures.forEach(failure -> LOG.warn("Scan failure: {}", failure));
            } catch (RuntimeException e) {
                // A scheduled task that throws is never run again.
                LOG.error("Scan failed", e);
            }
        }

        @Override
        public synchronized void stop() {
            scheduler.shutdownNow();
            try {
                scheduler.awaitTermination(1, TimeUnit.MINUTES);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        @Override
        public synchronized boolean isRunning() {
            return scheduler != null && !scheduler.isShutdown();
        }
    }
}
