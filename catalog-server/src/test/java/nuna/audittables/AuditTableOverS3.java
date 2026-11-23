package nuna.audittables;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import nuna.catalog.audit.AuditRow;
import org.apache.iceberg.BaseTable;
import org.apache.iceberg.CatalogProperties;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.inmemory.InMemoryCatalog;
import org.apache.iceberg.io.FileIO;
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
 * For the catalog server's tests of the audit feed's reader: an audit table written by the writer's own
 * {@link EventsTable} to Iceberg's in-memory catalog under an {@code s3://} location, and an S3 client double that
 * serves its files, so a test reads it through {@link EventsReader#open} with no network and no Docker.
 */
public final class AuditTableOverS3 {

    private final InMemoryCatalog catalog = new InMemoryCatalog();
    private final EventsTable events;
    private final Map<Integer, Long> offsets = new HashMap<>();
    private final Files s3;

    public AuditTableOverS3() {
        catalog.initialize("nuna_audit", Map.of(CatalogProperties.WAREHOUSE_LOCATION,
                "s3://audit-" + UUID.randomUUID() + "/warehouse"));
        events = EventsTable.open(catalog, Namespace.of("audit"), "nuna.catalog.audit");
        s3 = new Files(table().io());
    }

    /** Commits {@code rows} as the writer does, and gives the metadata file that commit wrote. */
    public String append(AuditRow... rows) {
        for (AuditRow row : rows) {
            offsets.merge(row.topicPartition(), row.topicOffset() + 1, Math::max);
        }
        events.reload();
        events.append(List.of(rows), Map.copyOf(offsets));
        return metadataLocation();
    }

    /** The table as last committed. */
    public BaseTable table() {
        return (BaseTable) catalog.loadTable(TableIdentifier.of("audit", EventsTable.NAME));
    }

    /** The metadata file of the last commit: where the catalog's pointer to the table points. */
    public String metadataLocation() {
        return table().operations().current().metadataFileLocation();
    }

    /** The S3 client double that serves the table's files. */
    public Files s3() {
        return s3;
    }

    /** A record of {@code type} at {@code time} on the catalog {@code lake}, at that partition and offset. */
    public static AuditRow row(Instant time, String type, int partition, long offset) {
        String id = UUID.randomUUID().toString();
        return new AuditRow(id, time, type, "urn:nuna:deployment:test", "ana", null, "spark", "request-" + id,
                "lake", "TABLE", UUID.randomUUID().toString(), List.of("sales", "orders"), null, null, null,
                "{\"n\":\"" + id + "\"}", partition, offset);
    }

    /**
     * An S3 client that serves the files a {@link FileIO} holds, {@code s3://<bucket>/<key>}: whole, by range and
     * their length. Once closed it refuses every call, as the SDK's client does. Every other call is the SDK's
     * default, which throws.
     */
    public static final class Files implements S3Client {

        private final FileIO files;
        private final List<String> read = new ArrayList<>();
        private volatile boolean closed;

        Files(FileIO files) {
            this.files = files;
        }

        /** The keys read, in order. */
        public synchronized List<String> read() {
            return List.copyOf(read);
        }

        /** Whether it was closed. */
        public boolean closed() {
            return closed;
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
            closed = true;
        }

        private synchronized byte[] bytes(String bucket, String key) {
            if (closed) {
                throw new IllegalStateException("Connection pool shut down: the S3 client was closed");
            }
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
