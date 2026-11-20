package nuna.maintenance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The DR store as the verifier reads it (D37, amended 2026-11-20), without a store: a replica is named by its storage
 * profile's name, serves that profile's prefixes, and only reads.
 */
class DrStoresTest {

    private static final WorkerSettings.StorageProfile LAKE = new WorkerSettings.StorageProfile(
            List.of("s3://lake/"), URI.create("http://primary.invalid:9000"), "us-east-1", true, "primary", "secret",
            null);
    private static final WorkerSettings.Replica DR = new WorkerSettings.Replica(URI.create("http://dr.invalid:9000"),
            "us-east-1", true, "dr", "secret", null);

    @Test
    void aReplicaIsNamedByItsStorageProfile() {
        assertThatThrownBy(() -> DrStores.of(Map.of("lake", LAKE), Map.of("elsewhere", DR)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nuna.maintenance.dr.replicas.elsewhere names no storage profile");
    }

    @Test
    void anUnreadableCaBundleNamesTheReplicasSetting() {
        WorkerSettings.Replica dr = new WorkerSettings.Replica(URI.create("https://dr.invalid:9000"), "us-east-1",
                true, "dr", "secret", Path.of("no-such-dr-ca.pem"));

        assertThatThrownBy(() -> DrStores.of(Map.of("lake", LAKE), Map.of("lake", dr)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("nuna.maintenance.dr.replicas.lake.ca-bundle: ");
    }

    @Test
    void theDrStoreServesItsProfilesPrefixesAndOnlyReads() {
        try (DrStores stores = DrStores.of(Map.of("lake", LAKE), Map.of("lake", DR))) {
            assertThat(stores.newInputFile("s3a://lake/t/data/a.parquet").location())
                    .isEqualTo("s3a://lake/t/data/a.parquet");
            assertThatThrownBy(() -> stores.newInputFile("s3://other/t/data/a.parquet"))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("no nuna.maintenance.dr.replicas");
            assertThatThrownBy(() -> stores.newOutputFile("s3://lake/t/data/b.parquet"))
                    .isInstanceOf(UnsupportedOperationException.class);
            assertThatThrownBy(() -> stores.deleteFile("s3://lake/t/data/a.parquet"))
                    .isInstanceOf(UnsupportedOperationException.class);
            assertThatThrownBy(() -> stores.deletePrefix("s3://lake/t"))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }
}
