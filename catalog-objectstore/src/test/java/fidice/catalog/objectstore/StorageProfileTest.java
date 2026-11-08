package fidice.catalog.objectstore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class StorageProfileTest {

    /** Two approved prefixes in two buckets; new tables go under the first. */
    private static final StorageProfile PROFILE = StorageProfile.of(StoreKind.CEPH, "https://rgw.internal:8443",
            "us-east-1", "s3://lake/warehouse", List.of("s3://lake/warehouse", "s3://archive/dr"));

    @Test
    @DisplayName("plain configuration values become a profile")
    void fromPlainValues() {
        assertThat(PROFILE.kind()).isEqualTo(StoreKind.CEPH);
        assertThat(PROFILE.endpoint()).hasToString("https://rgw.internal:8443");
        assertThat(PROFILE.region()).isEqualTo("us-east-1");
        assertThat(PROFILE.tableRoot()).hasToString("s3://lake/warehouse");
        assertThat(PROFILE.allowedPrefixes()).extracting(ObjectLocation::toString)
                .containsExactly("s3://lake/warehouse", "s3://archive/dr");
    }

    @ParameterizedTest(name = "{0}")
    @DisplayName("locations strictly beneath an allowed prefix are approved")
    @ValueSource(strings = {
            "s3://lake/warehouse/t",
            "s3://lake/warehouse/5f0e9a52-3a1c-4c39-9a8e-2b1d1c1e7f00/metadata/00001-x.metadata.json",
            "s3://archive/dr/t/metadata/00042-y.metadata.json",
            "s3://lake/warehouse/...",
    })
    void approved(String location) {
        assertThat(PROFILE.approve(location)).hasToString(location);
    }

    @ParameterizedTest(name = "{0}")
    @DisplayName("prefix-boundary, bucket and cross-prefix confusion is rejected")
    @ValueSource(strings = {
            "s3://lake/warehouse",
            "s3://lake/warehouse2/t",
            "s3://lake/warehouses/t",
            "s3://lake/warehouse-x/t",
            "s3://lake/warehouse.x/t",
            "s3://lake/Warehouse/t",
            "s3://lake/ware/house/t",
            "s3://lake/x/warehouse/t",
            "s3://lake/dr/t",
            "s3://archive/warehouse/t",
            "s3://archive/dr",
            "s3://archive/dr2/t",
            "s3://lakes/warehouse/t",
            "s3://lake.evil/warehouse/t",
            "s3://other/warehouse/t",
            "s3://lake",
    })
    void outsideTheAllowedPrefixesIsRejected(String location) {
        assertThatThrownBy(() -> PROFILE.approve(location))
                .isInstanceOf(LocationRejectedException.class)
                .hasMessageContaining("not beneath an allowed prefix");
    }

    @ParameterizedTest(name = "{0}")
    @DisplayName("traversal, encoded, scheme and look-alike tricks aimed at an allowed prefix are rejected")
    @ValueSource(strings = {
            "s3://lake/warehouse/../secrets/x",
            "s3://lake/warehouse/t/../../secrets",
            "s3://lake/warehouse/./t",
            "s3://lake/warehouse/%2e%2e/secrets",
            "s3://lake/warehouse/%2E%2E/secrets",
            "s3://lake/warehouse%2f..%2fsecrets",
            "s3://lake/warehouse/%252e%252e/secrets",
            "s3://lake/warehouse/..\\..\\secrets",
            "s3://lake/warehouse/\uff0e\uff0e/secrets",
            "s3://lake/warehouse/\u2024\u2024/secrets",
            "s3://lake/warehous\u0435/t",
            "s3://lake/warehouse//t",
            "s3://lake/warehouse/t/",
            "s3://lake/warehouse/*",
            "s3://lake/warehouse*/t",
            "s3://lake/warehouse/${aws:username}",
            "s3://lake/warehouse/t?versionId=1",
            "s3a://lake/warehouse/t",
            "S3://lake/warehouse/t",
            "https://lake.s3.amazonaws.com/warehouse/t",
            "https://rgw.internal:8443/lake/warehouse/t",
            "s3://evil@lake/warehouse/t",
            "s3://lake:443/warehouse/t",
            "s3://LAKE/warehouse/t",
            " s3://lake/warehouse/t",
            "s3://lake/warehouse/t\n",
            "s3://lake/warehouse/t\u0000",
            "",
    })
    void tricksAreRejected(String location) {
        assertThatThrownBy(() -> PROFILE.approve(location)).isInstanceOf(LocationRejectedException.class);
    }

    @Test
    @DisplayName("s3://lake/abc is not beneath an allowed s3://lake/ab")
    void prefixBoundary() {
        StorageProfile profile = StorageProfile.of(StoreKind.S3, "https://s3.us-east-1.amazonaws.com", "us-east-1",
                "s3://lake/ab", List.of("s3://lake/ab"));

        assertThat(profile.approve("s3://lake/ab/c")).hasToString("s3://lake/ab/c");
        assertThatThrownBy(() -> profile.approve("s3://lake/abc"))
                .isInstanceOf(LocationRejectedException.class)
                .hasMessageContaining("not beneath an allowed prefix");
        assertThatThrownBy(() -> profile.approve("s3://lake/abc/x"))
                .isInstanceOf(LocationRejectedException.class)
                .hasMessageContaining("not beneath an allowed prefix");
    }

    @Test
    @DisplayName("a whole-bucket prefix approves everything in that bucket and nothing in another")
    void wholeBucket() {
        StorageProfile profile = StorageProfile.of(StoreKind.MINIO, "http://localhost:9000", "us-east-1",
                "s3://lake", List.of("s3://lake"));

        assertThat(profile.approve("s3://lake/any/t")).hasToString("s3://lake/any/t");
        assertThatThrownBy(() -> profile.approve("s3://lakes/any/t")).isInstanceOf(LocationRejectedException.class);
    }

    @Test
    @DisplayName("a new table is placed at the table root plus its ID, and that location is approved")
    void newTableLocation() {
        UUID tableId = UUID.fromString("5f0e9a52-3a1c-4c39-9a8e-2b1d1c1e7f00");

        String location = PROFILE.newTableLocation(tableId);

        assertThat(location).isEqualTo("s3://lake/warehouse/5f0e9a52-3a1c-4c39-9a8e-2b1d1c1e7f00");
        assertThat(PROFILE.approve(location)).hasToString(location);
        assertThat(PROFILE.newTableLocation(UUID.randomUUID())).hasSize(location.length());
    }

    @Test
    @DisplayName("the table root may equal an allowed prefix or lie beneath one, and nowhere else")
    void tableRootMustBeWithinAnAllowedPrefix() {
        assertThat(StorageProfile.of(StoreKind.S3, "https://s3.amazonaws.com", "us-east-1",
                "s3://lake/warehouse/prod", List.of("s3://lake/warehouse")).tableRoot())
                .hasToString("s3://lake/warehouse/prod");
        assertThatThrownBy(() -> StorageProfile.of(StoreKind.S3, "https://s3.amazonaws.com", "us-east-1",
                "s3://lake/warehouse2", List.of("s3://lake/warehouse")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("is not within an allowed prefix");
        assertThatThrownBy(() -> StorageProfile.of(StoreKind.S3, "https://s3.amazonaws.com", "us-east-1",
                "s3://other/warehouse", List.of("s3://lake/warehouse")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("is not within an allowed prefix");
    }

    @Test
    @DisplayName("a profile needs at least one allowed prefix, and each must be canonical")
    void allowedPrefixesAreRequiredAndCanonical() {
        assertThatThrownBy(() -> new StorageProfile(StoreKind.S3, URI.create("https://s3.amazonaws.com"),
                "us-east-1", ObjectLocation.parse("s3://lake/warehouse"), List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("is not within an allowed prefix");
        assertThatThrownBy(() -> StorageProfile.of(StoreKind.S3, "https://s3.amazonaws.com", "us-east-1",
                "s3://lake/warehouse", List.of("s3://lake/warehouse/")))
                .isInstanceOf(LocationRejectedException.class);
        // The literal example "s3://b/ab" names a bucket S3 itself would refuse (names are 3 to 63 characters).
        assertThatThrownBy(() -> StorageProfile.of(StoreKind.S3, "https://s3.amazonaws.com", "us-east-1",
                "s3://b/ab", List.of("s3://b/ab")))
                .isInstanceOf(LocationRejectedException.class)
                .hasMessageContaining("is not a valid S3 bucket name");
    }

    @ParameterizedTest(name = "{0}")
    @DisplayName("the endpoint is http(s)://host[:port] and nothing more")
    @ValueSource(strings = {
            "ftp://rgw.internal",
            "localhost:9000",
            "rgw.internal",
            "http:rgw.internal",
            "https://admin:secret@rgw.internal",
            "https://rgw.internal/",
            "https://rgw.internal/lake",
            "https://rgw.internal?bucket=lake",
            "https://rgw.internal#x",
    })
    void endpointIsValidated(String endpoint) {
        assertThatThrownBy(() -> StorageProfile.of(StoreKind.CEPH, endpoint, "us-east-1",
                "s3://lake/warehouse", List.of("s3://lake/warehouse")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a blank region is refused")
    void regionIsRequired() {
        assertThatThrownBy(() -> StorageProfile.of(StoreKind.S3, "https://s3.amazonaws.com", " ",
                "s3://lake/warehouse", List.of("s3://lake/warehouse")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("region");
    }
}
