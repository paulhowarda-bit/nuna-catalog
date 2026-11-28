package nuna.catalog.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The failed jobs a scan names, each with its table and why (backlog 327), as the store keeps them: what a report may
 * hold, and the text the SQL and Oracle stores keep them as.
 */
class FailedJobTest {

    private static final FailedJob CLOSED = new FailedJob("table-load", "brokerage", List.of("party"),
            "beneficial_owners", UUID.randomUUID(), "closed", "Forbidden: table beneficial_owners is in a regulated area"
            + " and has no classification; it is closed until it is classified");
    private static final FailedJob DEEP = new FailedJob("compaction", "lake", List.of("a", "b.c", "d"), "t",
            UUID.randomUUID(), "error", "the store did not answer");

    @Test
    void theTextReadsBackAsWritten() {
        List<FailedJob> jobs = List.of(CLOSED, DEEP);

        assertThat(FailedJob.read(FailedJob.write(jobs))).isEqualTo(jobs);
        assertThat(FailedJob.read(FailedJob.write(jobs)).get(1).namespace()).as("a level holding a dot stays one level")
                .containsExactly("a", "b.c", "d");
        assertThat(FailedJob.read(FailedJob.write(List.of()))).isEmpty();
    }

    @Test
    void aFailedJobNamesItsTableAndWhyInBoundedText() {
        UUID id = UUID.randomUUID();
        assertThatThrownBy(() -> new FailedJob("Table Load", "lake", List.of("ns"), "t", id, "error", "x"))
                .as("a kind is a label").isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FailedJob("table-load", "lake", List.of(), "t", id, "error", "x"))
                .as("a table has a namespace").isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FailedJob("table-load", "lake", List.of("ns"), "t\tu", id, "error", "x"))
                .as("no field holds a tab").isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FailedJob("table-load", "lake", List.of("ns"), "t", id, "error",
                "x".repeat(FailedJob.MAX_MESSAGE + 1))).as("a message is short")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FailedJob("table-load", "lake", List.of("ns"), "t", id, "error", "a\nb"))
                .as("a message is one line").isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aScanNamesAtMostTwentyFailedJobs() {
        Instant at = Instant.parse("2026-11-27T19:35:00Z");
        assertThat(scan(at, Collections.nCopies(MaintenanceScan.MAX_FAILED_JOBS, CLOSED)).failedJobs())
                .hasSize(MaintenanceScan.MAX_FAILED_JOBS);
        assertThatThrownBy(() -> scan(at, Collections.nCopies(MaintenanceScan.MAX_FAILED_JOBS + 1, CLOSED)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(MaintenanceScan.started(UUID.randomUUID(), "svc-maintenance", at).failedJobs())
                .as("a scan of an earlier release's worker names none").isEmpty();
    }

    private static MaintenanceScan scan(Instant at, List<FailedJob> jobs) {
        return new MaintenanceScan(UUID.randomUUID(), "svc-maintenance", at, Optional.of(at.plusSeconds(1)),
                Optional.of(MaintenanceScan.Outcome.SUCCEEDED), Map.of("table-load", jobs.size()), Optional.empty(),
                Optional.empty(), Optional.empty(), jobs);
    }
}
