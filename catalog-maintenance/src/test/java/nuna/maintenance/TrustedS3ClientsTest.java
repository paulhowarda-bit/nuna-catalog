package nuna.maintenance;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import nuna.catalog.objectstore.StoreKind;
import nuna.catalog.tests.s3.PrivateCa;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.iceberg.aws.s3.S3FileIO;
import org.apache.iceberg.io.StorageCredential;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code nuna.maintenance.storage.<store>.ca-bundle} in the worker's S3 clients (backlog 21), with no container:
 * the store is an HTTPS server in the test JVM whose certificate the test's private CA signed, answering a listing
 * and a GET as S3 does. The worker's own clients ({@link Storage}) trust the store's bundle; so do the clients
 * reading table files with vended credentials, opened as Iceberg's REST catalog opens a table's ({@link Catalogs}),
 * when the catalog names the store's endpoint, and only then. Without a bundle the JVM's CAs alone are trusted, as
 * before.
 */
class TrustedS3ClientsTest {

    private static final String LISTING = """
            <?xml version="1.0" encoding="UTF-8"?>
            <ListBucketResult xmlns="http://s3.amazonaws.com/doc/2006-03-01/"><Name>lake</Name><Prefix>tables</Prefix>\
            <KeyCount>0</KeyCount><MaxKeys>1000</MaxKeys><IsTruncated>false</IsTruncated></ListBucketResult>""";
    private static final byte[] MANIFEST = "a manifest list".getBytes(UTF_8);
    private static final String FILE = "s3://lake/tables/t/metadata/snap-1.avro";

    private PrivateCa.Server store;

    @TempDir
    Path dir;

    @BeforeEach
    void startStore() {
        store = serve();
    }

    @AfterEach
    void stopStore() {
        store.close();
    }

    @Test
    @DisplayName("without a CA bundle, the worker's own client trusts the JVM's CAs alone, and refuses the store")
    void ownClientWithoutTheBundle() {
        try (Storage storage = reached(Map.of("onprem", store(store.endpoint, null)))) {
            assertThat(storage.clientProperties()).as("Iceberg opens every client as before").isEmpty();
            assertThatThrownBy(() -> storage.list("s3://lake/tables/t/").forEach(file -> { }))
                    .hasStackTraceContaining("javax.net.ssl.SSLHandshakeException");
        }
        assertThat(store.requests).isEmpty();
    }

    @Test
    @DisplayName("with the store's CA bundle, the worker's own client trusts the store")
    void ownClientTrustsTheBundle() {
        try (Storage storage = reached(Map.of("onprem", store(store.endpoint, PrivateCa.shared().bundle)))) {
            assertThat(storage.list("s3://lake/tables/t/")).isEmpty();
        }
        assertThat(store.requests).hasSize(1);
        assertThat(store.requests.getFirst()).startsWith("GET /lake").contains("list-type=2");
    }

    @Test
    @DisplayName("a client reading with vended credentials trusts the bundle of the endpoint the catalog names, and no"
            + " other")
    void vendedClientsTrustTheirEndpointsBundle() {
        try (Storage storage = reached(Map.of("onprem", store(store.endpoint, PrivateCa.shared().bundle)));
             PrivateCa.Server elsewhere = serve()) {
            try (S3FileIO io = vended(storage, store.endpoint)) {
                assertThat(read(io)).isEqualTo(MANIFEST);
            }
            try (S3FileIO io = vended(storage, elsewhere.endpoint)) {
                assertThatThrownBy(() -> read(io)).hasStackTraceContaining("javax.net.ssl.SSLHandshakeException");
            }
            assertThat(elsewhere.requests).isEmpty();
        }
        assertThat(store.requests).hasSize(1);
        assertThat(store.requests.getFirst()).startsWith("GET /lake/tables/t/metadata/snap-1.avro");
    }

    @Test
    @DisplayName("an endpoint is matched however it is written")
    void endpointsMatchWhateverTheirSpelling() {
        assertThat(TrustedS3Clients.endpoint("HTTPS://Ceph.Example.Internal"))
                .isEqualTo("https://ceph.example.internal:443")
                .isEqualTo(TrustedS3Clients.endpoint("https://ceph.example.internal:443/"));
        assertThat(TrustedS3Clients.endpoint("http://minio:9000")).isEqualTo("http://minio:9000");
    }

    @Test
    @DisplayName("a CA bundle the worker cannot read or parse stops it starting, naming the store and the file")
    void unreadableBundleStopsStartup() throws IOException {
        Path missing = dir.resolve("missing.pem");
        Path empty = Files.writeString(dir.resolve("empty.pem"), "");
        Path broken = Files.writeString(dir.resolve("broken.pem"),
                "-----BEGIN CERTIFICATE-----\nnot base64!\n-----END CERTIFICATE-----\n");

        for (Path bundle : List.of(missing, empty, broken)) {
            assertThatThrownBy(() -> Storage.of(Map.of("onprem", store(store.endpoint, bundle))))
                    .as(bundle.getFileName().toString())
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageStartingWith("nuna.maintenance.storage.onprem.ca-bundle: ")
                    .hasMessageContaining(bundle.toString());
        }
    }

    @Test
    @DisplayName("a bundle needs the store's endpoint, and two stores on one endpoint cannot name different bundles")
    void bundlesBelongToOneEndpoint() throws IOException {
        assertThatThrownBy(() -> new WorkerSettings.Store(StoreKind.CEPH, null, "us-east-1", null, null,
                PrivateCa.shared().bundle)).hasMessageContaining("needs an endpoint");

        Path copy = Files.copy(PrivateCa.shared().bundle, dir.resolve("copy.pem"));
        Map<String, WorkerSettings.Store> stores = new LinkedHashMap<>();
        stores.put("tables", store(store.endpoint, PrivateCa.shared().bundle));
        stores.put("archive", new WorkerSettings.Store(StoreKind.CEPH, store.endpoint, "us-east-1", "worker",
                "worker-secret", copy));
        assertThatThrownBy(() -> Storage.of(stores)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("for the endpoint " + TrustedS3Clients.endpoint(store.endpoint.toString()));

        stores.put("archive", new WorkerSettings.Store(StoreKind.CEPH, store.endpoint, "us-east-1", "worker",
                "worker-secret", PrivateCa.shared().bundle));
        try (Storage storage = Storage.of(stores)) {
            assertThat(storage.clientProperties()).isNotEmpty();
        }
    }

    private static PrivateCa.Server serve() {
        return PrivateCa.shared().serve(request -> request.contains("list-type=2")
                ? PrivateCa.Answer.xml(LISTING)
                : new PrivateCa.Answer(200, "application/octet-stream", MANIFEST));
    }

    private static WorkerSettings.Store store(URI endpoint, Path caBundle) {
        return new WorkerSettings.Store(StoreKind.MINIO, endpoint, "us-east-1", "worker", "worker-secret", caBundle);
    }

    /** The worker's own storage on {@code stores}, reaching {@code s3://lake/tables} as the catalog gives it. */
    private static Storage reached(Map<String, WorkerSettings.Store> stores) {
        Storage storage = Storage.of(stores);
        storage.reach(List.of(new Management.StorageProfile("lakehouse", "minio",
                stores.values().iterator().next().endpoint().toString(), List.of("s3://lake/tables"))));
        return storage;
    }

    /**
     * A table's FileIO as Iceberg's REST catalog opens it for {@link Catalogs}: the worker's client properties, then
     * the load's config and its storage credential for the table's prefix, which name the catalog's endpoint.
     */
    private static S3FileIO vended(Storage storage, URI endpoint) {
        Map<String, String> properties = new HashMap<>(storage.clientProperties());
        properties.put("http-client.type", "apache");
        properties.put("client.region", "us-east-1");
        properties.put("s3.endpoint", endpoint.toString());
        properties.put("s3.path-style-access", "true");
        S3FileIO io = new S3FileIO();
        io.setCredentials(List.of(StorageCredential.create("s3://lake/tables/t", Map.of("s3.access-key-id",
                "ASIAVENDED", "s3.secret-access-key", "vended-secret", "s3.session-token", "token"))));
        io.initialize(properties);
        return io;
    }

    private static byte[] read(S3FileIO io) {
        try (InputStream in = io.newInputFile(FILE).newStream()) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
