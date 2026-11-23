package nuna.catalog.server.governance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import nuna.catalog.core.CatalogError;
import nuna.catalog.store.MaintenanceJob;
import nuna.catalog.store.MaintenanceNight;
import org.junit.jupiter.api.Test;

/** A job's report as the worker sends it, and a night as the route answers it (backlog 126), without a server. */
class MaintenanceNightsTest {

    private static final UUID ID = UUID.randomUUID();
    private static final UUID TABLE = UUID.randomUUID();

    @Test
    void aReportReadsAsAJob() {
        MaintenanceJob job = MaintenanceNights.job(ID, "maintenance", Management.JSON.readTree("""
                {"night":"2026-11-21","opens":"2026-11-21T01:00:00Z","closes":"2026-11-21T05:00:00Z",
                 "kind":"compaction","object-id":"%s","slot":2,"started-at":"2026-11-21T01:00:00.1234Z",
                 "ended-at":"2026-11-21T01:30:00Z","outcome":"cut-off","files-in":40,"files-out":4}
                """.formatted(TABLE)));

        assertThat(job).isEqualTo(new MaintenanceJob(ID, "maintenance", LocalDate.parse("2026-11-21"),
                Instant.parse("2026-11-21T01:00:00Z"), Instant.parse("2026-11-21T05:00:00Z"),
                MaintenanceJob.Kind.COMPACTION, TABLE, Optional.of(2), Instant.parse("2026-11-21T01:00:00.123Z"),
                Optional.of(Instant.parse("2026-11-21T01:30:00Z")), MaintenanceJob.Outcome.CUT_OFF, 40, 4, 0, 0, 0));
    }

    @Test
    void aReportThatIsNoJobIsABadRequest() {
        String good = """
                {"night":"2026-11-21","opens":"2026-11-21T01:00:00Z","closes":"2026-11-21T05:00:00Z",
                 "kind":"deletion","object-id":"%s","started-at":"2026-11-21T01:00:00Z",
                 "ended-at":"2026-11-21T01:00:01Z","outcome":"succeeded","files-deleted":3,"bytes-deleted":300}
                """.formatted(TABLE);
        assertThat(MaintenanceNights.job(ID, "w", Management.JSON.readTree(good)).bytesDeleted()).isEqualTo(300);
        for (String bad : new String[] {
            good.replace("\"deletion\"", "\"vacuum\""),
            good.replace("\"deletion\"", "\"compaction\""),
            good.replace("\"succeeded\"", "\"running\""),
            good.replace("\"2026-11-21\"", "\"21 Nov\""),
            good.replace(TABLE.toString(), "not-an-id"),
            good.replace("\"bytes-deleted\":300", "\"bytes-deleted\":-1"),
            good.replace("\"bytes-deleted\":300", "\"bytes-deleted\":\"300\""),
            good.replace("\"bytes-deleted\":300", "\"bytes-deleted\":300,\"slot\":1"),
            good.replace("\"ended-at\":\"2026-11-21T01:00:01Z\",", ""),
            good.replace("\"closes\":\"2026-11-21T05:00:00Z\"", "\"closes\":\"2026-11-21T01:00:00Z\""),
            "{}",
            "[]"}) {
            assertThat(catchThrowable(() -> MaintenanceNights.job(ID, "w", Management.JSON.readTree(bad)))).as(bad)
                    .isInstanceOfSatisfying(CatalogError.class, error -> assertThat(error.status()).isEqualTo(400));
        }
    }

    @Test
    void aNightAnswersItsTotals() {
        MaintenanceNight night = new MaintenanceNight(LocalDate.parse("2026-11-21"),
                Instant.parse("2026-11-21T01:00:00Z"), Instant.parse("2026-11-21T05:00:00Z"), 3, 120, 12, 40, 9, 4096,
                1, 7, 2, 1);

        assertThat(MaintenanceNights.night(night).toString()).isEqualTo("{\"night\":\"2026-11-21\","
                + "\"opens\":\"2026-11-21T01:00:00Z\",\"closes\":\"2026-11-21T05:00:00Z\",\"tables-compacted\":3,"
                + "\"files-compacted\":120,\"files-written\":12,\"snapshots-expired\":40,\"files-deleted\":9,"
                + "\"bytes-deleted\":4096,\"jobs-running\":1,\"jobs-succeeded\":7,\"jobs-cut-off\":2,"
                + "\"jobs-failed\":1}");
    }
}
