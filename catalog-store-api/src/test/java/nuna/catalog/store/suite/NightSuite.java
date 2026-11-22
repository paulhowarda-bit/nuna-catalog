package nuna.catalog.store.suite;

import static org.assertj.core.api.Assertions.assertThat;

import nuna.catalog.store.MaintenanceJob;
import nuna.catalog.store.MaintenanceJob.Kind;
import nuna.catalog.store.MaintenanceJob.Outcome;
import nuna.catalog.store.MaintenanceNight;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The jobs of the maintenance worker's nights and each night's totals (D45, backlog 126). The jobs are shared by every
 * test on the backend, and recording one deletes the nights before its {@code keepFrom}, so each test keeps to nights
 * of a year of its own, chosen at random, far from today's, and keeps its {@code keepFrom} within that year.
 */
public abstract class NightSuite extends GroupSuite {

    private static final String WORKER = "svc-maintenance";

    @Test
    @DisplayName("Nights: a job's report is kept by its id; a later one replaces it, one repeated late or a running one"
            + " once it has ended changes nothing; the earlier start, the night, kind and object stay")
    void aJobIsKeptByItsId() {
        LocalDate night = year().atDay(10);
        Instant opens = opens(night);
        UUID table = UUID.randomUUID();
        MaintenanceJob running = compaction(UUID.randomUUID(), night, table, 2, opens.plusSeconds(60), null,
                Outcome.RUNNING, 0, 0);
        MaintenanceJob ended = compaction(running.id(), night, table, 2, opens.plusSeconds(60),
                opens.plusSeconds(600), Outcome.SUCCEEDED, 40, 3);

        store.recordMaintenanceJob(running, night);

        assertThat(jobs(night)).containsExactly(running);
        store.recordMaintenanceJob(ended, night);
        assertThat(jobs(night)).containsExactly(ended);
        store.recordMaintenanceJob(running, night);
        assertThat(jobs(night)).as("its start, reported again once it ended").containsExactly(ended);
        MaintenanceJob earlierEnd = compaction(running.id(), night, table, 2, opens.plusSeconds(60),
                opens.plusSeconds(300), Outcome.FAILED, 1, 1);
        store.recordMaintenanceJob(earlierEnd, night);
        assertThat(jobs(night)).as("an end reported late").containsExactly(ended);

        // A worker restarted in the window finds tonight's Job and reports it as starting when it found it, on a slot
        // of its own; the Job keeps its first start, and its night and object.
        MaintenanceJob found = new MaintenanceJob(running.id(), "svc-other", night.plusDays(1), opens.plusSeconds(1),
                opens.plusSeconds(7200), Kind.COMPACTION, UUID.randomUUID(), Optional.of(3), opens.plusSeconds(900),
                Optional.of(opens.plusSeconds(1200)), Outcome.CUT_OFF, 50, 4, 0, 0, 0);
        store.recordMaintenanceJob(found, night);
        assertThat(jobs(night)).containsExactly(new MaintenanceJob(running.id(), "svc-other", night,
                opens.plusSeconds(1), opens.plusSeconds(7200), Kind.COMPACTION, table, Optional.of(3),
                opens.plusSeconds(60), Optional.of(opens.plusSeconds(1200)), Outcome.CUT_OFF, 50, 4, 0, 0, 0));
        assertThat(jobs(night.plusDays(1))).isEmpty();
    }

    @Test
    @DisplayName("Nights: each night's totals sum its jobs, newest night first, at most as many as asked, none after"
            + " the date asked")
    void aNightsTotalsSumItsJobs() {
        LocalDate first = year().atDay(100);
        LocalDate second = first.plusDays(1);
        LocalDate third = first.plusDays(2);
        Instant opens = opens(first);
        // The first night: two compactions succeeded and one cut off (its files count, its table does not), one
        // failed, an expiry, a deletion and a purge, a manifest rewrite (whose files are manifests) and an orphan
        // scan still running.
        record(compaction(UUID.randomUUID(), first, UUID.randomUUID(), 1, opens, opens.plusSeconds(60),
                Outcome.SUCCEEDED, 100, 10));
        record(compaction(UUID.randomUUID(), first, UUID.randomUUID(), 2, opens, opens.plusSeconds(90),
                Outcome.SUCCEEDED, 20, 2));
        record(compaction(UUID.randomUUID(), first, UUID.randomUUID(), 1, opens.plusSeconds(60),
                opens.plusSeconds(3600), Outcome.CUT_OFF, 7, 1));
        record(compaction(UUID.randomUUID(), first, UUID.randomUUID(), 2, opens.plusSeconds(90),
                opens.plusSeconds(95), Outcome.FAILED, 0, 0));
        record(job(first, Kind.SNAPSHOT_EXPIRY, opens.plusSeconds(10), Outcome.SUCCEEDED, 0, 0, 31, 0, 0));
        record(job(first, Kind.DELETION, opens.plusSeconds(20), Outcome.SUCCEEDED, 0, 0, 0, 12, 4096));
        record(job(first, Kind.PURGE, opens.plusSeconds(30), Outcome.SUCCEEDED, 0, 0, 0, 3, 1_000_000_000_000L));
        record(job(first, Kind.MANIFEST_REWRITE, opens.plusSeconds(40), Outcome.SUCCEEDED, 9, 1, 0, 0, 0));
        record(new MaintenanceJob(UUID.randomUUID(), WORKER, first, opens, opens.plusSeconds(7200), Kind.ORPHAN_SCAN,
                UUID.randomUUID(), Optional.empty(), opens.plusSeconds(50), Optional.empty(), Outcome.RUNNING, 0, 0,
                0, 0, 0));
        // The second night: one expiry that failed, reported by a worker that saw the window a minute later.
        Instant opens2 = opens(second);
        record(new MaintenanceJob(UUID.randomUUID(), WORKER, second, opens2.plusSeconds(60), opens2.plusSeconds(7260),
                Kind.SNAPSHOT_EXPIRY, UUID.randomUUID(), Optional.empty(), opens2.plusSeconds(70),
                Optional.of(opens2.plusSeconds(71)), Outcome.FAILED, 0, 0, 0, 0, 0));
        record(job(second, Kind.SNAPSHOT_EXPIRY, opens2.plusSeconds(80), Outcome.SUCCEEDED, 0, 0, 5, 0, 0));
        record(job(third, Kind.SNAPSHOT_EXPIRY, opens(third), Outcome.SUCCEEDED, 0, 0, 1, 0, 0));

        assertThat(store.listMaintenanceNights(second, 2)).containsExactly(
                new MaintenanceNight(second, opens2, opens2.plusSeconds(7260), 0, 0, 0, 5, 0, 0, 0, 1, 0, 1),
                new MaintenanceNight(first, opens, opens.plusSeconds(7200), 2, 127, 13, 31, 15, 1_000_000_004_096L, 1,
                        6, 1, 1));
        assertThat(store.listMaintenanceNights(third, 1)).as("at most one, the newest")
                .extracting(MaintenanceNight::night).containsExactly(third);
        assertThat(store.listMaintenanceNights(first, 1)).extracting(MaintenanceNight::night).containsExactly(first);
    }

    @Test
    @DisplayName("Nights: a night's jobs of the kinds asked, by start; none of another night or kind")
    void aNightsJobsOfAKindByStart() {
        LocalDate night = year().atDay(200);
        Instant opens = opens(night);
        MaintenanceJob late = compaction(UUID.randomUUID(), night, UUID.randomUUID(), 1, opens.plusSeconds(300), null,
                Outcome.RUNNING, 0, 0);
        MaintenanceJob early = compaction(UUID.randomUUID(), night, UUID.randomUUID(), 2, opens.plusSeconds(5),
                opens.plusSeconds(200), Outcome.SUCCEEDED, 8, 1);
        MaintenanceJob scan = new MaintenanceJob(UUID.randomUUID(), WORKER, night, opens, opens.plusSeconds(7200),
                Kind.ORPHAN_SCAN, UUID.randomUUID(), Optional.empty(), opens.plusSeconds(100),
                Optional.of(opens.plusSeconds(110)), Outcome.SUCCEEDED, 0, 0, 0, 0, 0);
        MaintenanceJob expiry = job(night, Kind.SNAPSHOT_EXPIRY, opens.plusSeconds(1), Outcome.SUCCEEDED, 0, 0, 2, 0,
                0);
        MaintenanceJob nextNight = compaction(UUID.randomUUID(), night.plusDays(1), UUID.randomUUID(), 1,
                opens(night.plusDays(1)), null, Outcome.RUNNING, 0, 0);
        for (MaintenanceJob job : List.of(late, early, scan, expiry, nextNight)) {
            record(job);
        }

        assertThat(store.listMaintenanceJobs(night, EnumSet.of(Kind.COMPACTION, Kind.ORPHAN_SCAN)))
                .containsExactly(early, scan, late);
        assertThat(store.listMaintenanceJobs(night, EnumSet.of(Kind.SNAPSHOT_EXPIRY))).containsExactly(expiry);
        assertThat(store.listMaintenanceJobs(night, EnumSet.of(Kind.PURGE))).isEmpty();
        assertThat(store.listMaintenanceJobs(night, EnumSet.noneOf(Kind.class))).isEmpty();
    }

    @Test
    @DisplayName("Nights: recording a job deletes the jobs of every night before the date it keeps from")
    void oldNightsAreDeleted() {
        LocalDate old = year().atDay(1);
        LocalDate kept = old.plusDays(31);
        MaintenanceJob oldJob = job(old, Kind.SNAPSHOT_EXPIRY, opens(old), Outcome.SUCCEEDED, 0, 0, 1, 0, 0);
        MaintenanceJob keptJob = job(kept, Kind.SNAPSHOT_EXPIRY, opens(kept), Outcome.SUCCEEDED, 0, 0, 1, 0, 0);
        store.recordMaintenanceJob(oldJob, old);
        store.recordMaintenanceJob(keptJob, old);
        assertThat(store.listMaintenanceNights(kept, 2)).extracting(MaintenanceNight::night)
                .containsExactly(kept, old);

        MaintenanceJob next = job(kept.plusDays(1), Kind.DELETION, opens(kept.plusDays(1)), Outcome.SUCCEEDED, 0, 0, 0,
                1, 10);
        store.recordMaintenanceJob(next, old.plusDays(1));

        assertThat(store.listMaintenanceJobs(old, EnumSet.allOf(Kind.class))).as("a night before the one kept from")
                .isEmpty();
        assertThat(store.listMaintenanceJobs(kept, EnumSet.allOf(Kind.class))).containsExactly(keptJob);
        assertThat(store.listMaintenanceNights(kept.plusDays(1), 3)).extracting(MaintenanceNight::night)
                .containsExactly(kept.plusDays(1), kept);
    }

    /** A year of the test's own, far from today's and every other test's, most likely. */
    private static java.time.Year year() {
        return java.time.Year.of(ThreadLocalRandom.current().nextInt(2200, 9000));
    }

    /** The night's window opening, 22:30 UTC on its date, for two hours. */
    private static Instant opens(LocalDate night) {
        return night.atTime(22, 30).toInstant(ZoneOffset.UTC);
    }

    private void record(MaintenanceJob job) {
        store.recordMaintenanceJob(job, job.night().minusDays(31));
    }

    private List<MaintenanceJob> jobs(LocalDate night) {
        return store.listMaintenanceJobs(night, EnumSet.allOf(Kind.class));
    }

    private static MaintenanceJob compaction(UUID id, LocalDate night, UUID table, int slot, Instant started,
                                             Instant ended, Outcome outcome, long in, long out) {
        Instant opens = opens(night);
        return new MaintenanceJob(id, WORKER, night, opens, opens.plus(Duration.ofHours(2)), Kind.COMPACTION, table,
                Optional.of(slot), started, Optional.ofNullable(ended), outcome, in, out, 0, 0, 0);
    }

    private static MaintenanceJob job(LocalDate night, Kind kind, Instant started, Outcome outcome, long in, long out,
                                      long expired, long deleted, long bytes) {
        Instant opens = opens(night);
        return new MaintenanceJob(UUID.randomUUID(), WORKER, night, opens, opens.plus(Duration.ofHours(2)), kind,
                UUID.randomUUID(), Optional.empty(), started, Optional.of(started.plusSeconds(1)), outcome, in, out,
                expired, deleted, bytes);
    }
}
