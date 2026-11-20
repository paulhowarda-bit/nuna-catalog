package nuna.maintenance;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import org.apache.iceberg.Table;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.rest.RESTCatalog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Purge (D30 (6)): a dropped table past its window is claimed, everything beneath its location deleted, and the
 * purge completed; one another worker has claimed is left to it. A worker that claimed a purge and died gets its
 * claim back and finishes it.
 */
@Tag("heavy")
class PurgeTest {

    /** The soft-drop window the server keeps a dropped table for (nuna.rest.drop-retention, 30 days by default). */
    private static final Duration PAST_THE_WINDOW = Duration.ofDays(31);

    private final TestCatalog catalog = TestCatalog.get();

    @AfterEach
    void quiet() {
        catalog.quiet();
    }

    @Test
    void purgeRemovesThePrefixAfterAClaim() throws Exception {
        FakeClock clock = new FakeClock();
        try (RESTCatalog writer = catalog.writer(); Worker worker = catalog.worker(clock, Compactor.NONE)) {
            Dropped mine = drop(writer, "purged");
            Dropped theirs = drop(writer, "theirs");
            String twin = mine.location + "-twin/data/kept.parquet";
            catalog.put(twin, "beside the purged table, not beneath it");
            catalog.advance(PAST_THE_WINDOW, clock);
            catalog.expect(catalog.manage(catalog.otherWorkerTokens(), "POST", "/objects/" + theirs.id
                    + "/purge-claim", null), 200);

            worker.runOnce();

            assertThat(catalog.list(mine.location + "/")).isEmpty();
            assertThat(catalog.state(mine.id)).isEqualTo("PURGED");
            assertThat(catalog.exists(twin)).isTrue();
            assertThat(catalog.list(theirs.location + "/")).as("another worker's claim").hasSize(theirs.files);
            assertThat(catalog.state(theirs.id)).isEqualTo("PURGING");
            catalog.expect(catalog.manage(catalog.otherWorkerTokens(), "POST", "/objects/" + theirs.id + "/purge", null),
                    204);
        }
    }

    @Test
    void aClaimedPurgeResumesAfterACrash() throws Exception {
        FakeClock clock = new FakeClock();
        try (RESTCatalog writer = catalog.writer(); Worker worker = catalog.worker(clock, Compactor.NONE)) {
            Dropped dropped = drop(writer, "resumed");
            catalog.advance(PAST_THE_WINDOW, clock);
            // A worker claimed it, deleted part of it, and died.
            assertThat(catalog.management().claimPurge(TestCatalog.CATALOG, java.util.UUID.fromString(dropped.id)))
                    .isEqualTo(Management.Claim.CLAIMED);
            List<String> files = catalog.list(dropped.location + "/");
            try (Storage storage = Storage.of(catalog.storageProfiles())) {
                storage.delete(files.subList(0, 1));
            }

            worker.runOnce();

            assertThat(catalog.list(dropped.location + "/")).isEmpty();
            assertThat(catalog.state(dropped.id)).isEqualTo("PURGED");
        }
    }

    private record Dropped(String id, String location, int files) {
    }

    /** A table with a committed file and a stray one, dropped without purge. */
    private Dropped drop(RESTCatalog writer, String name) {
        TableIdentifier id = TestCatalog.table(writer, name);
        Table table = writer.loadTable(id);
        table.newAppend().appendFile(TestCatalog.dataFile(table, "a")).commit();
        catalog.put(table.location() + "/data/stray.parquet", "never committed");
        int files = catalog.list(table.location() + "/").size();
        assertThat(writer.dropTable(id, false)).isTrue();
        return new Dropped(table.uuid().toString(), table.location(), files);
    }
}
