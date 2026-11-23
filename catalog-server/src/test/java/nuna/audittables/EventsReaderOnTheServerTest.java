package nuna.audittables;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import nuna.catalog.audit.AuditRow;
import org.apache.iceberg.BaseTable;
import org.apache.iceberg.CatalogProperties;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.inmemory.InMemoryCatalog;
import org.apache.iceberg.io.FileIO;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

/**
 * The audit feed's reader on the catalog server's own classpath (backlog 148), where the KMS SDK is absent (D26): it
 * opens and pages a real audit table through {@link EventsReader#open}, the way {@code AuditFeed} does, reading over
 * an S3 client. Before item 148 the first file opened built Iceberg's AWS client factory, whose class needs the KMS
 * model classes, so on the server every feed and summary call failed with a {@link NoClassDefFoundError}; the
 * reader's own tests run in catalog-audit-tables, whose classpath has the KMS SDK, and missed it.
 *
 * <p>In package {@code nuna.audittables} to write the table with the writer's own {@link EventsTable}. The table is
 * written to Iceberg's in-memory catalog under an {@code s3://} location, and the S3 client is a double that serves
 * those files, so this needs no network and no Docker.
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
        InMemoryCatalog catalog = new InMemoryCatalog();
        catalog.initialize("nuna_audit", Map.of(CatalogProperties.WAREHOUSE_LOCATION,
                "s3://audit-" + UUID.randomUUID() + "/warehouse"));
        EventsTable events = EventsTable.open(catalog, Namespace.of("audit"), "nuna.catalog.audit");
        AuditRow first = row(10, READ, 0, 0);
        AuditRow second = row(20, COMMIT, 1, 0);
        AuditRow third = row(30, READ, 0, 1);
        events.reload();
        events.append(List.of(first, second), Map.of(0, 1L, 1, 1L));
        events.reload();
        events.append(List.of(third), Map.of(0, 2L, 1, 1L));
        BaseTable table = (BaseTable) catalog.loadTable(TableIdentifier.of("audit", EventsTable.NAME));
        Files s3 = new Files(table.io());

        EventsReader reader = EventsReader.open("nuna_audit.audit.events",
                table.operations().current().metadataFileLocation(), s3);
        EventsReader.Page page = reader.page(new EventsReader.Query("lake", Set.of(), null, List.of(), null, null,
                Optional.empty(), 50));
        EventsReader.Summary summary = reader.summary(T0, T0.plusSeconds(60), 5);

        assertThat(page.rows()).containsExactly(third, second, first);
        assertThat(summary.byType()).isEqualTo(Map.of(READ, 2L, COMMIT, 1L));
        assertThat(reader.snapshotId()).contains(table.currentSnapshot().snapshotId());
        assertThat(s3.read).as("the metadata, manifest list, manifests and data files, read over S3")
                .anyMatch(key -> key.endsWith(".metadata.json")).anyMatch(key -> key.endsWith(".parquet"));
    }

    private static AuditRow row(int second, String type, int partition, long offset) {
        String id = UUID.randomUUID().toString();
        return new AuditRow(id, T0.plusSeconds(second), type, "urn:nuna:deployment:test", "ana", null, "spark",
                "request-" + id, "lake", "TABLE", UUID.randomUUID().toString(), List.of("sales", "orders"), null,
                null, null, "{\"n\":\"" + id + "\"}", partition, offset);
    }

    /**
     * An S3 client that serves the files a {@link FileIO} holds, {@code s3://<bucket>/<key>}: whole, by range and
     * their length. Every other call is the SDK's default, which throws.
     */
    private static final class Files implements S3Client {

        private final FileIO files;
        private final List<String> read = new ArrayList<>();

        Files(FileIO files) {
            this.files = files;
        }

        @Override
        public HeadObjectResponse headObject(HeadObjectRequest request) {
            return HeadObjectResponse.builder().contentLength((long) bytes(request.bucket(), request.key()).length)
                    .build();
        }

        @Override
        public <T> T getObject(GetObjectRequest request, ResponseTransformer<GetObjectResponse, T> transformer) {
            byte[] all = bytes(request.bucket(), request.key());
            byte[] part = range(all, request.range());
            GetObjectResponse response = GetObjectResponse.builder().contentLength((long) part.length).build();
            try {
                return transformer.transform(response, AbortableInputStream.create(new ByteArrayInputStream(part)));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public ResponseInputStream<GetObjectResponse> getObject(GetObjectRequest request) {
            return getObject(request, ResponseTransformer.toInputStream());
        }

        @Override
        public String serviceName() {
            return SERVICE_NAME;
        }

        @Override
        public void close() {
        }

        private synchronized byte[] bytes(String bucket, String key) {
            String path = "s3://" + bucket + "/" + key;
            read.add(key);
            if (!files.newInputFile(path).exists()) {
                throw NoSuchKeyException.builder().statusCode(404).message("no such key: " + path).build();
            }
            try (InputStream in = files.newInputFile(path).newStream()) {
                return in.readAllBytes();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        /** {@code bytes=a-b}, {@code bytes=a-} or {@code bytes=-n}; null for the whole object. */
        private static byte[] range(byte[] all, String range) {
            if (range == null) {
                return all;
            }
            String[] ends = range.substring("bytes=".length()).split("-", -1);
            int from;
            int to;
            if (ends[0].isEmpty()) {
                from = Math.max(0, all.length - Integer.parseInt(ends[1]));
                to = all.length;
            } else {
                from = Integer.parseInt(ends[0]);
                to = ends[1].isEmpty() ? all.length : Math.min(all.length, Integer.parseInt(ends[1]) + 1);
            }
            return Arrays.copyOfRange(all, from, to);
        }
    }
}
