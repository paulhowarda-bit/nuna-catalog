package nuna.maintenance;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * The DR verifier's passes as the worker runs them (item 101): each pass's tally goes to the log at INFO, the worker's
 * default level, one line a pass, so the DR runbook's wait for a pass (deploy/README.md, "Recovering in the DR region",
 * step 2) can watch the log. Without a catalog or a DR store; {@link DrVerifierTest} checks the passes themselves.
 */
class DrPassesTest {

    @Test
    void eachPassLogsItsTallyAtInfoInOneLine() {
        DrVerify.Version version = new DrVerify.Version(2, "s3://lake/t/metadata/00002.metadata.json",
                Optional.of(Instant.parse("2026-11-20T12:00:00Z")));
        DrVerify.Item item = new DrVerify.Item(UUID.randomUUID(), "table", "s3://lake/t", version, Optional.empty(),
                List.of(version), version.committedAt());
        DrVerifier verifier = new DrVerifier(new DrVerifier.Catalog() {
            @Override
            public Management.Page<DrVerify.Item> work(Optional<String> token) {
                return new Management.Page<>(List.of(item), Optional.empty());
            }

            @Override
            public boolean report(UUID id, DrVerify.Version reported) {
                return true;
            }
        }, checked -> new DrVerify.Result(Optional.of(version), Optional.empty()), Clock.systemUTC(),
                Duration.ofMinutes(10), new SimpleMeterRegistry(), () -> { });
        WorkerApplication.DrPasses passes = new WorkerApplication.DrPasses(verifier,
                new WorkerSettings.Dr(Duration.ofMinutes(1), Map.of()));
        Logger logger = (Logger) LoggerFactory.getLogger(WorkerApplication.DrPasses.class);
        ListAppender<ILoggingEvent> log = new ListAppender<>();
        log.start();
        logger.addAppender(log);
        try {
            passes.pass();
            passes.pass();
        } finally {
            logger.detachAppender(log);
        }

        assertThat(log.list).hasSize(2).allSatisfy(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.INFO);
            assertThat(event.getFormattedMessage()).matches("DR pass done: Pass\\[objects=1, reported=1, verified=2,"
                    + " behind=0, maxLag=PT0S, failed=0, deferred=0] in \\d+ ms");
        });
    }
}
