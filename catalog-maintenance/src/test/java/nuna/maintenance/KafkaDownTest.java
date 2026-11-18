package nuna.maintenance;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.apache.iceberg.DataFile;
import org.apache.iceberg.Snapshot;
import org.apache.iceberg.Table;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.rest.RESTCatalog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;

/**
 * Kafka down (D23, D30): the catalog cannot audit the credentials a table load hands out, so it answers 503 and the
 * worker can do nothing to the table. The scan reports the failure, changes nothing and does not stop; the first scan
 * after Kafka is back does the work. Heavy: it pauses the broker and waits out the catalog's audit timeouts.
 */
@Tag("heavy")
class KafkaDownTest {

    private final TestCatalog catalog = TestCatalog.get();

    @AfterEach
    void quiet() {
        catalog.quiet();
    }

    @Test
    void aScanWithoutKafkaChangesNothingAndTheNextOneCatchesUp() throws Exception {
        FakeClock clock = new FakeClock();
        try (RESTCatalog writer = catalog.writer(); Worker worker = catalog.worker(clock, Compactor.NONE)) {
            TableIdentifier id = TestCatalog.table(writer, "unaudited");
            Table table = writer.loadTable(id);
            DataFile a = TestCatalog.dataFile(table, "a");
            table.newAppend().appendFile(a).commit();
            table.newOverwrite().deleteFile(a).addFile(TestCatalog.dataFile(table, "b")).commit();
            catalog.policy(id, Map.of("snapshot-expiry.enabled", "true", "snapshot-expiry.max-snapshot-age", "P5D",
                    "snapshot-expiry.min-snapshots-to-keep", "1"));
            catalog.advance(Duration.ofDays(6), clock);
            String object = table.uuid().toString();

            String kafka = TestCatalog.kafka().getContainerId();
            DockerClientFactory.instance().client().pauseContainerCmd(kafka).exec();
            ScanReport down;
            try {
                down = worker.runOnce();
            } finally {
                DockerClientFactory.instance().client().unpauseContainerCmd(kafka).exec();
            }

            assertThat(down.failures).as("the table could not be loaded").isNotEmpty();
            assertThat(writer.loadTable(id).snapshots()).hasSize(2);
            assertThat(catalog.pending(object)).isEmpty();

            Instant deadline = Instant.now().plus(Duration.ofMinutes(2));
            while (count(writer.loadTable(id)) > 1 && Instant.now().isBefore(deadline)) {
                worker.runOnce();
            }
            assertThat(writer.loadTable(id).snapshots()).as("expired once Kafka was back").hasSize(1);
            assertThat(catalog.pending(object)).isNotEmpty();
        }
    }

    private static int count(Table table) {
        int snapshots = 0;
        for (Snapshot ignored : table.snapshots()) {
            snapshots++;
        }
        return snapshots;
    }
}
