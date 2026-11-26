package nuna.catalog.server.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.Container;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * The alert rules in deploy/grafana/rules, run by promtool (backlog 261). The catalog's rules have unit tests,
 * deploy/grafana/tests/nuna-catalog.test.yaml: a catalog answering every call 5xx pages, 409 conflicts alone do not,
 * a catalog that stops pushing pages, and a page whose series stop keeps firing for its keep_firing_for. promtool
 * comes in the Prometheus image the demo runs ({@code prometheus.image}), so these start a container: heavy.
 */
@Tag("heavy")
class AlertRulesTest {

    private static final Path GRAFANA = Path.of(System.getProperty("repo.root"), "deploy", "grafana");

    private static GenericContainer<?> prometheus;

    @BeforeAll
    static void start() {
        // Only promtool runs: the container sleeps while the tests exec it.
        prometheus = new GenericContainer<>(DockerImageName.parse(System.getProperty("prometheus.image")))
                .withCreateContainerCmdModifier(command -> command.withEntrypoint("sleep"))
                .withCommand("3600")
                .withCopyFileToContainer(MountableFile.forHostPath(GRAFANA.resolve("rules")), "/grafana/rules")
                .withCopyFileToContainer(MountableFile.forHostPath(GRAFANA.resolve("tests")), "/grafana/tests");
        prometheus.start();
    }

    @AfterAll
    static void stop() {
        prometheus.stop();
    }

    @Test
    void everyRuleFileLoads() throws Exception {
        promtool("check", "rules", "/grafana/rules/nuna-catalog.rules.yaml",
                "/grafana/rules/nuna-maintenance.rules.yaml");
    }

    @Test
    void theCatalogRulesPassTheirUnitTests() throws Exception {
        promtool("test", "rules", "/grafana/tests/nuna-catalog.test.yaml");
    }

    private static void promtool(String... arguments) throws Exception {
        Container.ExecResult result = prometheus.execInContainer(
                Stream.concat(Stream.of("promtool"), Stream.of(arguments)).toArray(String[]::new));
        assertThat(result.getExitCode()).as("promtool %s%n%s%s", String.join(" ", arguments), result.getStdout(),
                result.getStderr()).isZero();
    }
}
