package nuna.maintenance;

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
 * {@code nuna.maintenance.scan-interval}, the first at start. One replica: two would only race each other for
 * the same commits and claims, which the catalog settles, at twice the cost. With a DR replica named
 * ({@code nuna.maintenance.dr.replicas}), the DR verifier runs too, every {@code nuna.maintenance.dr.verify-interval}
 * on a thread of its own (D37, amended 2026-11-20).
 */
@SpringBootApplication
@EnableConfigurationProperties({WorkerSettings.class, WorkerSettings.Dr.class})
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

    /** The DR verifier's passes; none when no replica is named. */
    @Bean
    DrPasses drPasses(WorkerSettings settings, WorkerSettings.Dr dr, Clock clock, MeterRegistry meters,
                      ObservationRegistry observations) {
        return new DrPasses(dr.enabled() ? DrVerifier.of(settings, dr, clock, meters, observations) : null, dr);
    }

    /**
     * Runs the DR verifier's passes on one daemon thread of their own, so a long scan never delays them; a pass never
     * overlaps the next.
     */
    static final class DrPasses implements SmartLifecycle {

        private static final Logger LOG = LoggerFactory.getLogger(DrPasses.class);

        private final DrVerifier verifier;
        private final WorkerSettings.Dr settings;
        private ScheduledExecutorService scheduler;

        /** @param verifier none when no replica is named */
        DrPasses(DrVerifier verifier, WorkerSettings.Dr settings) {
            this.verifier = verifier;
            this.settings = settings;
        }

        @Override
        public synchronized void start() {
            if (verifier == null) {
                LOG.info("No DR replica named (nuna.maintenance.dr.replicas): the DR verifier is off");
                return;
            }
            scheduler = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("nuna-dr-verify")
                    .daemon(true).factory());
            scheduler.scheduleWithFixedDelay(this::pass, 0, settings.verifyInterval().toMillis(),
                    TimeUnit.MILLISECONDS);
            LOG.info("Verifying the DR store every {}", settings.verifyInterval());
        }

        private void pass() {
            try {
                DrVerifier.Pass pass = verifier.runOnce();
                LOG.debug("DR pass done: {}", pass);
            } catch (RuntimeException e) {
                // A scheduled task that throws is never run again.
                LOG.error("DR pass failed", e);
            }
        }

        @Override
        public synchronized void stop() {
            if (scheduler == null) {
                return;
            }
            scheduler.shutdownNow();
            try {
                scheduler.awaitTermination(1, TimeUnit.MINUTES);
                verifier.close();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                LOG.warn("Closing the DR store failed", e);
            }
            scheduler = null;
        }

        @Override
        public synchronized boolean isRunning() {
            return scheduler != null && !scheduler.isShutdown();
        }
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
            scheduler = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("nuna-maintenance")
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
