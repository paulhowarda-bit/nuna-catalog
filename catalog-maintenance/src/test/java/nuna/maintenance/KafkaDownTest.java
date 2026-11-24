package nuna.maintenance;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Map;
import org.apache.iceberg.DataFile;
import org.apache.iceberg.Table;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.rest.RESTCatalog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;

/**
 * Kafka down (D23, amended 2026-11-23: "Log as the main audit path"; D30): the catalog audits the credentials a table
 * load hands out to its log {@code nuna.audit}, so an outage blocks nothing and the worker's scan expires the table's
 * snapshots as it would with Kafka up. Heavy: it pauses the broker.
 */
@Tag("heavy")
class KafkaDownTest {

    private final TestCatalog catalog = TestCatalog.get();

    @AfterEach
    void quiet() {
        catalog.quiet();
    }

    @Test
    void aScanWithKafkaDownStillDoesItsWork() throws Exception {
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

            assertThat(down.failures).as("nothing failed for want of Kafka").isEmpty();
            assertThat(writer.loadTable(id).snapshots()).as("expired with Kafka down").hasSize(1);
            assertThat(catalog.pending(object)).isNotEmpty();
        }
    }
}
