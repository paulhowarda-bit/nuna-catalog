package nuna.maintenance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import nuna.catalog.objectstore.StoreKind;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The DR store as the verifier reads it (D37, amended 2026-11-20), without a store: a replica is named by its store
 * identity's name, serves the prefixes the catalog gives for that store (item 256), and only reads.
 */
class DrStoresTest {

    private static final WorkerSettings.Store LAKE = new WorkerSettings.Store(StoreKind.MINIO,
            URI.create("http://primary.invalid:9000"), "us-east-1", "primary", "secret", null);
    private static final WorkerSettings.Store ARCHIVE = new WorkerSettings.Store(StoreKind.CEPH,
            URI.create("http://archive.invalid:7480"), "us-east-1", "archive", "secret", null);
    private static final WorkerSettings.Replica DR = new WorkerSettings.Replica(URI.create("http://dr.invalid:9000"),
            "us-east-1", "dr", "secret", null);
    private static final List<Management.StorageProfile> PROFILES = List.of(
            new Management.StorageProfile("lakehouse", "minio", "http://primary.invalid:9000", List.of("s3://lake/t")),
            new Management.StorageProfile("cold", "ceph", "http://archive.invalid:7480", List.of("s3://cold/t")));

    @Test
    void aReplicaIsNamedByItsStore() {
        assertThatThrownBy(() -> DrStores.of(Map.of("lake", LAKE), Map.of("elsewhere", DR)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nuna.maintenance.dr.replicas.elsewhere names no store");
    }

    @Test
    void anUnreadableCaBundleNamesTheReplicasSetting() {
        WorkerSettings.Replica dr = new WorkerSettings.Replica(URI.create("https://dr.invalid:9000"), "us-east-1",
                "dr", "secret", Path.of("no-such-dr-ca.pem"));

        assertThatThrownBy(() -> DrStores.of(Map.of("lake", LAKE), Map.of("lake", dr)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("nuna.maintenance.dr.replicas.lake.ca-bundle: ");
    }

    @Test
    void theDrStoreServesItsStoresPrefixesAndOnlyReads() {
        Map<String, WorkerSettings.Store> stores = Map.of("lake", LAKE, "archive", ARCHIVE);
        try (DrStores dr = DrStores.of(stores, Map.of("lake", DR))) {
            assertThatThrownBy(() -> dr.newInputFile("s3a://lake/t/data/a.parquet")).as("before any prefix is given")
                    .isInstanceOf(IllegalStateException.class);

            dr.reach(Prefixes.resolve(PROFILES, stores));

            assertThat(dr.newInputFile("s3a://lake/t/data/a.parquet").location())
                    .isEqualTo("s3a://lake/t/data/a.parquet");
            assertThatThrownBy(() -> dr.newInputFile("s3://cold/t/data/a.parquet")).as("a store with no replica")
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("no nuna.maintenance.dr.replicas");
            assertThatThrownBy(() -> dr.newInputFile("s3://other/t/data/a.parquet"))
                    .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> dr.newOutputFile("s3://lake/t/data/b.parquet"))
                    .isInstanceOf(UnsupportedOperationException.class);
            assertThatThrownBy(() -> dr.deleteFile("s3://lake/t/data/a.parquet"))
                    .isInstanceOf(UnsupportedOperationException.class);
            assertThatThrownBy(() -> dr.deletePrefix("s3://lake/t"))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }
}
