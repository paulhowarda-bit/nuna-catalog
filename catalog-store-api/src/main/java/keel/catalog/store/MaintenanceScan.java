package keel.catalog.store;

import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;

/**
 * One scan of the work list by a maintenance worker, as it reports it to the catalog (D30, D38): when it started, and
 * once it has ended, when and how. A scan succeeds when it read the whole work list; a job failing in it is counted in
 * {@code failures} by kind, and the scan still succeeds (docs/observability/worker-metrics.md).
 *
 * @param id        the worker's id for the scan
 * @param worker    the maintenance principal that reported it
 * @param startedAt when it started, by the worker's clock
 * @param endedAt   when it ended; empty while it runs
 * @param outcome   how it ended; empty while it runs
 * @param failures  failed jobs and work list reads, by kind ({@code work-list}, {@code purge}, ...)
 * @param error     why a failed scan failed, at most {@value #MAX_ERROR} characters
 */
public record MaintenanceScan(UUID id, String worker, Instant startedAt, Optional<Instant> endedAt,
                             Optional<Outcome> outcome, Map<String, Integer> failures, Optional<String> error) {

    public enum Outcome { SUCCEEDED, FAILED }

    public static final int MAX_ERROR = 1000;
    private static final int MAX_KINDS = 32;

    public MaintenanceScan {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(worker, "worker");
        Objects.requireNonNull(startedAt, "startedAt");
        Objects.requireNonNull(endedAt, "endedAt");
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(error, "error");
        failures = Collections.unmodifiableMap(new TreeMap<>(failures));
        if (endedAt.isPresent() != outcome.isPresent()) {
            throw new IllegalArgumentException("a scan has an outcome exactly when it has ended");
        }
        if (endedAt.isPresent() && endedAt.get().isBefore(startedAt)) {
            throw new IllegalArgumentException("a scan ends after it starts");
        }
        if (failures.size() > MAX_KINDS || !failures.entrySet().stream().allMatch(failure ->
                TableAssessment.LABEL.matcher(failure.getKey()).matches() && failure.getValue() > 0)) {
            throw new IllegalArgumentException("failures are at most " + MAX_KINDS + " kinds such as work-list, each"
                    + " counted above 0: " + failures);
        }
        if (error.isPresent() && (error.get().isEmpty() || error.get().length() > MAX_ERROR)) {
            throw new IllegalArgumentException("an error is 1 to " + MAX_ERROR + " characters");
        }
    }

    /** A scan just started. */
    public static MaintenanceScan started(UUID id, String worker, Instant startedAt) {
        return new MaintenanceScan(id, worker, startedAt, Optional.empty(), Optional.empty(), Map.of(), Optional.empty());
    }
}
