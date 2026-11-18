package nuna.catalog.store;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * A table's compaction as the maintenance worker last reported it (D30, D38): a Kubernetes Job it started in the
 * cleanup window, and how that ended, as the worker saw it.
 *
 * @param startedAt when the worker started the Job, by its clock
 * @param endedAt   when the worker saw it end; empty while it runs
 * @param outcome   how it ended, or {@link Outcome#RUNNING}
 */
public record CompactionRun(Instant startedAt, Optional<Instant> endedAt, Outcome outcome) {

    public enum Outcome {
        RUNNING,
        SUCCEEDED,
        FAILED,
        /** Stopped by the cleanup window's end; the file groups it finished stay committed. */
        CUT_OFF
    }

    public CompactionRun {
        Objects.requireNonNull(startedAt, "startedAt");
        Objects.requireNonNull(endedAt, "endedAt");
        Objects.requireNonNull(outcome, "outcome");
        if (endedAt.isPresent() == (outcome == Outcome.RUNNING)) {
            throw new IllegalArgumentException("a compaction has an end exactly when it is no longer running");
        }
        if (endedAt.isPresent() && endedAt.get().isBefore(startedAt)) {
            throw new IllegalArgumentException("a compaction ends after it starts");
        }
    }
}
