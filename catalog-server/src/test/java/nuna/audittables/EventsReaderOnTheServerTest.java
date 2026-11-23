package nuna.audittables;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import nuna.catalog.audit.AuditRow;
import org.junit.jupiter.api.Test;

/**
 * The audit feed's reader on the catalog server's own classpath (backlog 148), where the KMS SDK is absent (D26): it
 * opens and pages a real audit table through {@link EventsReader#open}, the way {@code AuditFeed} does, reading over
 * an S3 client. Before item 148 the first file opened built Iceberg's AWS client factory, whose class needs the KMS
 * model classes, so on the server every feed and summary call failed with a {@link NoClassDefFoundError}; the
 * reader's own tests run in catalog-audit-tables, whose classpath has the KMS SDK, and missed it.
 *
 * <p>The table is written by the writer's own {@link EventsTable} to Iceberg's in-memory catalog under an
 * {@code s3://} location, and the S3 client is a double that serves those files ({@link AuditTableOverS3}), so this
 * needs no network and no Docker.
 */
class EventsReaderOnTheServerTest {

    private static final Instant T0 = Instant.parse("2026-11-22T07:00:00Z");
    private static final String READ = "nuna.catalog.read";
    private static final String COMMIT = "nuna.catalog.commit";

    @Test
    void opensAndPagesTheAuditTableWithoutTheKmsSdk() {
        assertThatThrownBy(() -> Class.forName("software.amazon.awssdk.services.kms.model.EncryptionAlgorithmSpec"))
                .as("this reads on the server's classpath, which leaves the KMS SDK out (D26)")
                .isInstanceOf(ClassNotFoundException.class);
        AuditTableOverS3 audit = new AuditTableOverS3();
        AuditRow first = AuditTableOverS3.row(T0.plusSeconds(10), READ, 0, 0);
        AuditRow second = AuditTableOverS3.row(T0.plusSeconds(20), COMMIT, 1, 0);
        AuditRow third = AuditTableOverS3.row(T0.plusSeconds(30), READ, 0, 1);
        audit.append(first, second);
        String location = audit.append(third);

        EventsReader reader = EventsReader.open("nuna_audit.audit.events", location, audit.s3());
        EventsReader.Page page = reader.page(new EventsReader.Query("lake", Set.of(), null, List.of(), null, null,
                Optional.empty(), 50));
        EventsReader.Summary summary = reader.summary(T0, T0.plusSeconds(60), 5);

        assertThat(page.rows()).containsExactly(third, second, first);
        assertThat(summary.byType()).isEqualTo(Map.of(READ, 2L, COMMIT, 1L));
        assertThat(reader.snapshotId()).contains(audit.table().currentSnapshot().snapshotId());
        assertThat(audit.s3().read()).as("the metadata, manifest list, manifests and data files, read over S3")
                .anyMatch(key -> key.endsWith(".metadata.json")).anyMatch(key -> key.endsWith(".parquet"));
    }
}
