package nuna.catalog.conformance;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;

/**
 * Runs the Iceberg REST Compatibility Kit against our server (D31). Every test's outcome goes to
 * build/rck-results.txt. The build fails if a test recorded as PASS in rck-baseline.txt no longer
 * passes, or if a test that does not pass has no stated reason in rck-reasons.txt (or one that
 * passes still has one). Re-record the baseline deliberately with {@code -Prck.record=true}.
 */
@Tag("heavy")
class RckRatchetTest {

    private static final List<String> RCK_CLASSES = List.of(
            "org.apache.iceberg.rest.RESTCompatibilityKitCatalogTests",
            "org.apache.iceberg.rest.RESTCompatibilityKitViewCatalogTests");

    @Test
    void compatibilityKitHasNoRegressions() throws Exception {
        // The RCK client copies every system property into its catalog configuration.
        System.setProperty("uri", ConformanceServer.start().toString());
        System.setProperty("warehouse", ConformanceServer.WAREHOUSE);
        ConformanceServer.clientStorage().forEach(System::setProperty);
        System.setProperty("token", ConformanceServer.token());
        // The catalog assigns every location (D28), namespaces are created explicitly (a table's namespace
        // must exist), names may hold dots, commits retry on the server (D9), and v3 tables take variants.
        System.setProperty("rck.overrides-requested-location", "true");
        System.setProperty("rck.requires-namespace-create", "true");
        System.setProperty("rck.supports-names-with-dot", "true");
        System.setProperty("rck.supports-serverside-retry", "true");
        System.setProperty("rck.supports-variant", "true");

        LauncherDiscoveryRequest request = LauncherDiscoveryRequestBuilder.request()
                .selectors(RCK_CLASSES.stream().map(DiscoverySelectors::selectClass).toList())
                .configurationParameter("rck.local", "false")
                .build();
        Outcomes outcomes = new Outcomes();
        LauncherFactory.create().execute(request, outcomes);

        Map<String, String> current = outcomes.byTest();
        Path baselineFile = Path.of(System.getProperty("rck.baseline"));
        Path results = Path.of(System.getProperty("rck.results"));
        Ratchet.write(results, current);
        Ratchet.write(results.resolveSibling("rck-causes.txt"), outcomes.causes());
        if (Boolean.getBoolean("rck.record")) {
            Ratchet.write(baselineFile, current);
        }
        Map<String, String> baseline = Ratchet.read(baselineFile);

        System.out.println(outcomes.summary());
        List<String> gained = Ratchet.newPasses(baseline, current);
        if (!gained.isEmpty()) {
            System.out.println(gained.size() + " tests now pass that rck-baseline.txt does not record;"
                    + " re-record with -Prck.record=true");
        }

        assertThat(current).as("RCK tests discovered").isNotEmpty();
        // Without a baseline the ratchet would guard nothing, so its absence fails the build.
        assertThat(baselineFile).as("rck-baseline.txt (record it with -Prck.record=true)").exists();
        assertThat(Ratchet.regressions(baseline, current))
                .as("tests that pass in rck-baseline.txt and no longer pass")
                .isEmpty();
        Map<String, String> reasons = Ratchet.readReasons(Path.of(System.getProperty("rck.reasons")));
        assertThat(Ratchet.unexplained(current, reasons))
                .as("RCK tests that do not pass and have no reason in rck-reasons.txt")
                .isEmpty();
        assertThat(Ratchet.staleReasons(current, reasons))
                .as("rck-reasons.txt entries for tests that pass or no longer exist")
                .isEmpty();
    }
}
