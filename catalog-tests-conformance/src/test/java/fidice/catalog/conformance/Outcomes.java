package fidice.catalog.conformance;

import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.TestPlan;

/**
 * Records every RCK test's outcome. A test that never starts (for example because its class's
 * setup failed) stays NOT_RUN, so the result file always lists every discovered test.
 */
final class Outcomes implements TestExecutionListener {

    private final Map<String, String> byTest = new TreeMap<>();
    private final Map<String, String> failedContainers = new TreeMap<>();
    private final Map<String, String> causes = new TreeMap<>();

    @Override
    public void testPlanExecutionStarted(TestPlan plan) {
        plan.getRoots().forEach(root -> plan.getDescendants(root).stream()
                .filter(TestIdentifier::isTest)
                .forEach(id -> byTest.put(name(id), "NOT_RUN")));
    }

    @Override
    public void executionSkipped(TestIdentifier id, String reason) {
        if (id.isTest()) {
            byTest.put(name(id), "SKIPPED");
        }
    }

    @Override
    public void executionFinished(TestIdentifier id, TestExecutionResult result) {
        String status = switch (result.getStatus()) {
            case SUCCESSFUL -> Ratchet.PASS;
            case FAILED -> "FAIL";
            case ABORTED -> "ABORTED";
        };
        if (id.isTest()) {
            byTest.put(name(id), status);
            result.getThrowable().ifPresent(cause -> causes.put(name(id), String.valueOf(cause).lines()
                    .findFirst().orElse("").replace('	', ' ')));
        } else if (result.getStatus() == TestExecutionResult.Status.FAILED) {
            failedContainers.put(name(id), result.getThrowable().map(Throwable::toString).orElse("(no cause)"));
        }
    }

    /** The first line of each failed or aborted test's exception. */
    Map<String, String> causes() {
        return causes;
    }

    Map<String, String> byTest() {
        return byTest;
    }

    String summary() {
        Map<String, Long> counts = byTest.values().stream()
                .collect(Collectors.groupingBy(s -> s, TreeMap::new, Collectors.counting()));
        StringBuilder text = new StringBuilder("RCK: " + byTest.size() + " tests " + counts);
        failedContainers.forEach((container, cause) -> text.append("\n  setup failed: ")
                .append(container).append(" -> ").append(cause.lines().findFirst().orElse("")));
        return text.toString();
    }

    private static String name(TestIdentifier id) {
        return id.getUniqueId()
                .replace("[engine:junit-jupiter]/", "")
                .replace("org.apache.iceberg.rest.", "");
    }
}
