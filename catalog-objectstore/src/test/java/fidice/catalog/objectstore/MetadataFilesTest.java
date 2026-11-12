package fidice.catalog.objectstore;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import fidice.catalog.core.commit.TableFiles;
import fidice.catalog.tests.s3.TestS3;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * Metadata file I/O against the run's S3 store ({@link TestS3}: Silo, Ceph or AWS). A second client with
 * the same full-access credentials stands in for anyone who can reach the bucket directly.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MetadataFilesTest {

    private static final String REGION = TestS3.REGION;
    private static final String JSON = """
            {"format-version":3,"table-uuid":"0d1c5b0a-6f3e-4c2a-9d7e-3b8f1a2c4e5f",\
            "properties":{"comment":"Zürich – 東京 – 📈"}}""";

    private TestS3 store;
    /** The bucket these tests write in; on AWS, this run's name for it. */
    private String lake;
    private S3Client direct;
    private StorageProfile profile;
    private MetadataFiles files;

    @BeforeAll
    void startStore() {
        store = TestS3.get();
        lake = store.bucket("metadata");
        direct = store.client();
        profile = StorageProfile.of(StoreKind.valueOf(store.profileKind()), store.endpoint.toString(), REGION,
                "s3://" + lake + "/warehouse", List.of("s3://" + lake + "/warehouse"));
        files = new MetadataFiles(profile, store.credentials);
    }

    /** The store itself is shared by the run and removed when the JVM exits. */
    @AfterAll
    void closeClients() {
        files.close();
        direct.close();
    }

    @Test
    @DisplayName("a written file sits at a catalog-assigned location, holds exactly the UTF-8 bytes, and reads back intact")
    void writeThenRead() {
        String table = newTable();

        StoredMetadata stored = files.write(table, 1, JSON);

        assertThat(stored.location())
                .matches(Pattern.quote(table) + "/metadata/00001-[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}\\.metadata\\.json");
        byte[] bytes = JSON.getBytes(UTF_8);
        assertThat(stored.sha256()).isEqualTo(sha256Hex(JSON));
        assertThat(directGet(stored.location())).isEqualTo(bytes);
        assertThat(files.read(stored.location(), stored.sha256(), OnMismatch.FAIL))
                .isEqualTo(new LoadedMetadata(stored.location(), JSON, stored.sha256(), true));
    }

    @Test
    @DisplayName("two writes of the same version go to two files, and neither overwrites the other")
    void everyWriteGetsItsOwnFile() {
        String table = newTable();

        StoredMetadata first = files.write(table, 7, "{\"writer\":1}");
        StoredMetadata second = files.write(table, 7, "{\"writer\":2}");

        assertThat(first.location()).isNotEqualTo(second.location()).contains("/metadata/00007-");
        assertThat(files.read(first.location(), first.sha256(), OnMismatch.FAIL).json()).isEqualTo("{\"writer\":1}");
        assertThat(files.read(second.location(), second.sha256(), OnMismatch.FAIL).json()).isEqualTo("{\"writer\":2}");
    }

    @Test
    @DisplayName("a 3 MiB metadata file round-trips")
    void largeFile() {
        String json = "{\"padding\":\"" + "x".repeat(3 * 1024 * 1024) + "\"}";

        StoredMetadata stored = files.write(newTable(), 1, json);

        assertThat(files.read(stored.location(), stored.sha256(), OnMismatch.FAIL).json()).isEqualTo(json);
    }

    @Test
    @DisplayName("a file changed behind the catalog's back fails closed for a regulated table")
    void tamperedFileFailsClosed() {
        StoredMetadata stored = files.write(newTable(), 1, JSON);
        String tampered = JSON.replace("\"format-version\":3", "\"format-version\":2");
        directPut(stored.location(), tampered);

        assertThatThrownBy(() -> files.read(stored.location(), stored.sha256(), OnMismatch.FAIL))
                .isInstanceOfSatisfying(MetadataIntegrityException.class, e -> {
                    assertThat(e.location()).isEqualTo(stored.location());
                    assertThat(e.expectedSha256()).isEqualTo(stored.sha256());
                    assertThat(e.actualSha256()).isEqualTo(sha256Hex(tampered));
                });
    }

    @Test
    @DisplayName("a file changed behind the catalog's back is returned flagged for any other table")
    void tamperedFileIsFlagged() {
        StoredMetadata stored = files.write(newTable(), 1, JSON);
        String tampered = JSON.replace("\"format-version\":3", "\"format-version\":2");
        directPut(stored.location(), tampered);

        assertThat(files.read(stored.location(), stored.sha256(), OnMismatch.FLAG))
                .isEqualTo(new LoadedMetadata(stored.location(), tampered, sha256Hex(tampered), false));
    }

    @Test
    @DisplayName("a write aimed outside the allowed prefixes is refused before anything reaches the store")
    void writeOutsideTheProfileTouchesNothing() {
        String id = UUID.randomUUID().toString();
        for (String table : List.of("s3://" + lake + "/elsewhere-" + id, "s3://" + lake + "/warehouse2/" + id,
                "s3://" + lake + "/warehouse")) {
            assertThatThrownBy(() -> files.write(table, 1, JSON)).isInstanceOf(LocationRejectedException.class);
        }

        assertThat(directKeys("elsewhere-" + id)).isEmpty();
        assertThat(directKeys("warehouse2/" + id)).isEmpty();
        assertThat(directKeys("warehouse/metadata/")).isEmpty();
    }

    @Test
    @DisplayName("a read aimed outside the allowed prefixes is refused even when the file exists")
    void readOutsideTheProfileIsRefused() {
        String id = UUID.randomUUID().toString();
        for (String location : List.of("s3://" + lake + "/elsewhere-" + id + "/metadata/00001-x.metadata.json",
                "s3://" + lake + "/warehouse2/" + id + "/metadata/00001-x.metadata.json")) {
            directPut(location, JSON);

            assertThatThrownBy(() -> files.read(location, sha256Hex(JSON), OnMismatch.FLAG))
                    .isInstanceOf(LocationRejectedException.class);
        }
    }

    @Test
    @DisplayName("the store refuses bytes that do not match the SHA-256 sent with them, and keeps nothing")
    void storeRefusesBytesThatDoNotMatchTheirHash() {
        ObjectLocation file = ObjectLocation.parse(newTable() + "/metadata/00001-changed.metadata.json");
        byte[] sent = "{\"sent\":true}".getBytes(UTF_8);
        byte[] hashed = "{\"hashed\":true}".getBytes(UTF_8);

        assertThatThrownBy(() -> files.put(file, sent, MetadataFiles.sha256(hashed)))
                .isInstanceOf(ObjectStoreException.class)
                .hasMessageContaining(file.toString());
        assertThat(directKeys(file.key())).isEmpty();
    }

    @Test
    @DisplayName("a missing file is reported as missing, in iceberg-core's words, naming the location")
    void missingFile() {
        String location = newTable() + "/metadata/00001-missing.metadata.json";

        assertThatThrownBy(() -> files.read(location, sha256Hex(JSON), OnMismatch.FAIL))
                .isInstanceOf(MetadataNotFoundException.class)
                .hasMessage("Failed to open input stream for file: " + location);
    }

    @Test
    @DisplayName("the commit engine's adapter writes where the catalog assigns and flags a changed file rather than refusing it")
    void commitEngineAdapter() {
        ProfileTableFiles adapter = new ProfileTableFiles(profile, files);
        String table = adapter.newLocation(UUID.randomUUID());

        TableFiles.Written written = adapter.write(table, 2, JSON);

        assertThat(written.location()).startsWith(table + "/metadata/00002-");
        assertThat(adapter.read(written.location(), written.sha256()))
                .isEqualTo(new TableFiles.Read(JSON, sha256Hex(JSON), true));
        String tampered = JSON.replace("\"format-version\":3", "\"format-version\":2");
        directPut(written.location(), tampered);
        assertThat(adapter.read(written.location(), written.sha256()))
                .isEqualTo(new TableFiles.Read(tampered, sha256Hex(tampered), false));
    }

    private String newTable() {
        return profile.newTableLocation(UUID.randomUUID());
    }

    private byte[] directGet(String location) {
        ObjectLocation file = ObjectLocation.parse(location);
        return direct.getObjectAsBytes(request -> request.bucket(file.bucket()).key(file.key())).asByteArray();
    }

    private void directPut(String location, String content) {
        ObjectLocation file = ObjectLocation.parse(location);
        direct.putObject(request -> request.bucket(file.bucket()).key(file.key()), RequestBody.fromString(content));
    }

    private List<String> directKeys(String prefix) {
        return direct.listObjectsV2(request -> request.bucket(lake).prefix(prefix)).contents().stream()
                .map(object -> object.key())
                .toList();
    }

    /** Computed here rather than by the code under test. */
    private static String sha256Hex(String content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content.getBytes(UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }
}
