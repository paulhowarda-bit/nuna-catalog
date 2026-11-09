package fidice.catalog.conformance;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RatchetTest {

    @Test
    void aBaselinePassThatFailsOrDisappearsIsARegression() {
        Map<String, String> baseline = Map.of("a", "PASS", "b", "PASS", "c", "FAIL");
        Map<String, String> current = Map.of("a", "FAIL", "c", "PASS");

        assertThat(Ratchet.regressions(baseline, current)).containsExactly("a", "b");
        assertThat(Ratchet.newPasses(baseline, current)).containsExactly("c");
    }

    @Test
    void everyTestThatDoesNotPassNeedsAReasonAndPassingTestsNeedNone() {
        Map<String, String> current = Map.of("a", "PASS", "b", "FAIL", "c", "SKIPPED");
        Map<String, String> reasons = Map.of("a", "stale", "b", "by design", "gone", "removed test");

        assertThat(Ratchet.unexplained(current, reasons)).containsExactly("c");
        assertThat(Ratchet.staleReasons(current, reasons)).containsExactly("a", "gone");
    }

    @Test
    void noPassesInTheBaselineMeansNoRegressions() {
        assertThat(Ratchet.regressions(Map.of("a", "NOT_RUN"), Map.of())).isEmpty();
    }

    @Test
    void filesRoundTrip(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("results.txt");
        Map<String, String> outcomes = Map.of("[class:X]/[method:y()]", "PASS", "[class:X]/[method:z()]", "NOT_RUN");

        Ratchet.write(file, outcomes);

        assertThat(Ratchet.read(file)).isEqualTo(outcomes);
    }
}
