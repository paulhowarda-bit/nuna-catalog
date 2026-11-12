package keel.catalog.objectstore;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ProfileTableFilesTest {

    private static final StorageProfile PROFILE = StorageProfile.of(StoreKind.CEPH, "https://rgw.internal:8443",
            "us-east-1", "s3://lake/warehouse", List.of("s3://lake/warehouse"));

    /** Neither method under test touches the store. */
    private final ProfileTableFiles files = new ProfileTableFiles(PROFILE, null);

    @Test
    @DisplayName("a new object lives at the profile's table root plus its id")
    void newLocation() {
        UUID id = UUID.fromString("5f0e9a52-3a1c-4c39-9a8e-2b1d1c1e7f00");

        assertThat(files.newLocation(id)).isEqualTo("s3://lake/warehouse/5f0e9a52-3a1c-4c39-9a8e-2b1d1c1e7f00");
    }

    @ParameterizedTest(name = "{0} within {1}: {2}")
    @DisplayName("within means strictly beneath, by whole segments, on canonical locations only")
    @CsvSource({
            "s3://lake/warehouse/t/data, s3://lake/warehouse/t, true",
            "s3://lake/warehouse/t/a/b/c, s3://lake/warehouse/t, true",
            "s3://lake/warehouse/t, s3://lake/warehouse/t, false",
            "s3://lake/warehouse/t2/data, s3://lake/warehouse/t, false",
            "s3://lake/warehouse/data, s3://lake/warehouse/t, false",
            "s3://other/warehouse/t/data, s3://lake/warehouse/t, false",
            "s3://lake/warehouse/t/../u/data, s3://lake/warehouse/t, false",
            "s3://lake/warehouse/t//data, s3://lake/warehouse/t, false",
            "s3a://lake/warehouse/t/data, s3://lake/warehouse/t, false",
            "S3://lake/warehouse/t/data, s3://lake/warehouse/t, false",
            "/lake/warehouse/t/data, s3://lake/warehouse/t, false",
            "s3://lake/warehouse/t/%2e%2e/data, s3://lake/warehouse/t, false",
    })
    void isWithin(String location, String root, boolean within) {
        assertThat(files.isWithin(location, root)).isEqualTo(within);
    }
}
