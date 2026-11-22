package nuna.catalog.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import nuna.catalog.store.MaintenanceJob.Kind;
import nuna.catalog.store.MaintenanceJob.Outcome;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** A job of a maintenance night as the store keeps it (backlog 126): what it may hold, and which report wins. */
class MaintenanceJobTest {

    private static final Instant OPENS = Instant.parse("2026-11-21T22:30:00Z");
    private static final Instant CLOSES = Instant.parse("2026-11-22T04:00:00Z");
    private static final UUID ID = UUID.randomUUID();

    @Test
    void aCompactionAndOnlyACompactionHasASlot() {
        assertThat(job(Kind.COMPACTION, Optional.of(1), OPENS, null, Outcome.RUNNING).slot()).contains(1);
        assertThat(job(Kind.ORPHAN_SCAN, Optional.empty(), OPENS, OPENS, Outcome.SUCCEEDED).slot()).isEmpty();
        assertThatThrownBy(() -> job(Kind.COMPACTION, Optional.empty(), OPENS, null, Outcome.RUNNING))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> job(Kind.SNAPSHOT_EXPIRY, Optional.of(1), OPENS, OPENS, Outcome.SUCCEEDED))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> job(Kind.COMPACTION, Optional.of(0), OPENS, null, Outcome.RUNNING))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> job(Kind.COMPACTION, Optional.of(MaintenanceJob.MAX_SLOT + 1), OPENS, null,
                Outcome.RUNNING)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aJobEndsExactlyWhenItIsNoLongerRunningAndAfterItStarts() {
        assertThatThrownBy(() -> job(Kind.PURGE, Optional.empty(), OPENS, OPENS, Outcome.RUNNING))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> job(Kind.PURGE, Optional.empty(), OPENS, null, Outcome.FAILED))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> job(Kind.PURGE, Optional.empty(), OPENS, OPENS.minusMillis(1), Outcome.SUCCEEDED))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MaintenanceJob(ID, "w", LocalDate.of(2026, 11, 21), CLOSES, OPENS, Kind.PURGE,
                UUID.randomUUID(), Optional.empty(), OPENS, Optional.of(OPENS), Outcome.SUCCEEDED, 0, 0, 0, 0, 0))
                .as("a window that closes before it opens").isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MaintenanceJob(ID, "w", LocalDate.of(2026, 11, 21), OPENS, CLOSES, Kind.PURGE,
                UUID.randomUUID(), Optional.empty(), OPENS, Optional.of(OPENS), Outcome.SUCCEEDED, 0, 0, 0, -1, 0))
                .as("a count below 0").isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aReportReplacesTheOneKeptUnlessThatEndedLaterOrHasEndedAndThisHasNot() {
        MaintenanceJob running = job(Kind.COMPACTION, Optional.of(1), OPENS, null, Outcome.RUNNING);
        MaintenanceJob ended = job(Kind.COMPACTION, Optional.of(1), OPENS, OPENS.plusSeconds(60), Outcome.SUCCEEDED);
        MaintenanceJob endedLater = job(Kind.COMPACTION, Optional.of(1), OPENS, OPENS.plusSeconds(90),
                Outcome.CUT_OFF);

        assertThat(running.replaces(running)).as("running, again").isTrue();
        assertThat(ended.replaces(running)).isTrue();
        assertThat(running.replaces(ended)).as("its start, once it ended").isFalse();
        assertThat(ended.replaces(ended)).as("the same end, again").isTrue();
        assertThat(endedLater.replaces(ended)).isTrue();
        assertThat(ended.replaces(endedLater)).as("an earlier end, reported late").isFalse();
    }

    private static MaintenanceJob job(Kind kind, Optional<Integer> slot, Instant started, Instant ended,
                                      Outcome outcome) {
        return new MaintenanceJob(ID, "svc-maintenance", LocalDate.of(2026, 11, 21), OPENS, CLOSES, kind,
                UUID.randomUUID(), slot, started, Optional.ofNullable(ended), outcome, 0, 0, 0, 0, 0);
    }
}
