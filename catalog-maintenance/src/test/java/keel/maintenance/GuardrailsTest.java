package keel.maintenance;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Map;
import org.apache.iceberg.DataFile;
import org.apache.iceberg.Table;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.rest.RESTCatalog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The worker never expires snapshots of, or removes orphans from, a table under a legal hold (D24), a table in a
 * DR-frozen namespace (D37), or an unregistered table (D30 phase 1): the catalog's work list says so, and the worker
 * does what it says. Lifting the hold and the freeze lets the same scan go ahead.
 */
class GuardrailsTest {

    private static final Map<String, String> POLICY = Map.of("snapshot-expiry.enabled", "true",
            "snapshot-expiry.max-snapshot-age", "P5D", "snapshot-expiry.min-snapshots-to-keep", "1",
            "orphan-removal.enabled", "true", "orphan-removal.older-than", "P8D",
            // A job a hold does not stop, so the held table stays on the work list; it never has enough to do.
            "manifest-rewrite.enabled", "true", "manifest-rewrite.min-manifests", "1000");

    private final TestCatalog catalog = TestCatalog.get();

    @AfterEach
    void quiet() {
        catalog.quiet();
    }

    @Test
    void heldFrozenAndUnregisteredTablesAreUntouched() throws Exception {
        FakeClock clock = new FakeClock();
        try (RESTCatalog writer = catalog.writer(); Worker worker = catalog.worker(clock, Compactor.NONE)) {
            TableIdentifier held = maintained(writer, "held");
            TableIdentifier frozen = maintained(writer, "frozen");
            TableIdentifier unregistered = maintained(writer, "unregistered");
            String hold = catalog.hold(held);
            catalog.freeze(frozen.namespace());
            String unregisteredId = writer.loadTable(unregistered).uuid().toString();
            String unregisteredOrphan = writer.loadTable(unregistered).location() + "/data/orphan.parquet";
            catalog.expect(catalog.rest(catalog.writerTokens(), "POST", "/namespaces/"
                    + unregistered.namespace().level(0) + "/tables/" + unregistered.name() + "/unregister"), 200);

            catalog.advance(Duration.ofDays(9), clock);
            worker.runOnce();

            for (TableIdentifier id : new TableIdentifier[] {held, frozen}) {
                Table table = writer.loadTable(id);
                assertThat(table.snapshots()).as("%s keeps every snapshot", id).hasSize(2);
                assertThat(catalog.pending(table.uuid().toString())).as("%s has no pending deletion", id).isEmpty();
                assertThat(catalog.exists(table.location() + "/data/orphan.parquet")).isTrue();
            }
            assertThat(catalog.pending(unregisteredId)).isEmpty();
            assertThat(catalog.exists(unregisteredOrphan)).isTrue();

            catalog.release(held, hold);
            catalog.thaw(frozen.namespace());
            worker.runOnce();

            for (TableIdentifier id : new TableIdentifier[] {held, frozen}) {
                Table table = writer.loadTable(id);
                assertThat(table.snapshots()).as("%s, released", id).hasSize(1);
                assertThat(catalog.pending(table.uuid().toString())).as("%s, released", id)
                        .contains(table.location() + "/data/orphan.parquet");
            }
        }
    }

    /** A table with two snapshots and an orphan, whose policy runs expiry and orphan removal. */
    private TableIdentifier maintained(RESTCatalog writer, String name) {
        TableIdentifier id = TestCatalog.table(writer, name);
        Table table = writer.loadTable(id);
        DataFile a = TestCatalog.dataFile(table, "a");
        table.newAppend().appendFile(a).commit();
        table.newOverwrite().deleteFile(a).addFile(TestCatalog.dataFile(table, "b")).commit();
        catalog.put(table.location() + "/data/orphan.parquet", "never committed");
        catalog.policy(id, POLICY);
        return id;
    }
}
