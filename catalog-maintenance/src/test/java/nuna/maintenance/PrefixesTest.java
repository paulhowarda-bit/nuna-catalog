package nuna.maintenance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import nuna.catalog.objectstore.StoreKind;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The storage prefixes the worker takes from the catalog at each scan (item 256; D30, amended 2026-11-24), and where
 * its own storage then lists and deletes: each profile served by the one store identity of its kind and endpoint,
 * each prefix parsed as the catalog's canonical form or refused, and only locations strictly beneath a prefix
 * reached. Needs only Java: nothing here reaches a store.
 */
class PrefixesTest {

    private static final Map<String, WorkerSettings.Store> STORES = stores();

    @Test
    void aPrefixIsParsedAsTheCatalogWritesItAndOneThatFailsIsRefused() {
        Prefixes prefixes = Prefixes.resolve(List.of(new Management.StorageProfile("lakehouse", "minio",
                "http://silo:9000", List.of("s3a://warehouse/tables", "s3n://archive/tables", "s3://",
                        "s3://bad:bucket/tables", "s3://warehouse//tables", "s3://warehouse/./tables",
                        "s3://warehouse/../tables", "s3://warehouse/tab*les", "s3://warehouse/*",
                        "s3://warehouse/tables/"))), STORES);

        assertThat(prefixes.prefixes()).extracting(prefix -> prefix.location().toString())
                .as("s3a and s3n as s3").containsExactlyInAnyOrder("s3://warehouse/tables", "s3://archive/tables");
        assertThat(prefixes.prefixes()).extracting(Prefixes.Prefix::store).containsOnly("lake");
        assertThat(prefixes.refused()).as("a bare s3://, a bucket with a colon, an empty or dot segment, a wildcard")
                .hasSize(8).allMatch(refused -> refused.contains(" of storage profile lakehouse: "))
                .anyMatch(refused -> refused.startsWith("prefix s3:// of"))
                .anyMatch(refused -> refused.startsWith("prefix s3://bad:bucket/tables of"))
                .anyMatch(refused -> refused.startsWith("prefix s3://warehouse//tables of"))
                .anyMatch(refused -> refused.startsWith("prefix s3://warehouse/./tables of"))
                .anyMatch(refused -> refused.startsWith("prefix s3://warehouse/../tables of"))
                .anyMatch(refused -> refused.startsWith("prefix s3://warehouse/tab*les of"))
                .anyMatch(refused -> refused.startsWith("prefix s3://warehouse/* of"))
                .anyMatch(refused -> refused.startsWith("prefix s3://warehouse/tables/ of"));
    }

    @Test
    void aProfileIsServedByTheOneStoreOfItsKindAndEndpoint() {
        Prefixes prefixes = Prefixes.resolve(List.of(
                new Management.StorageProfile("ledger", "ceph", "HTTPS://RGW.internal:7480/", List.of("s3://ledger/t")),
                new Management.StorageProfile("wrong-kind", "minio", "https://rgw.internal:7480",
                        List.of("s3://wrong-kind/t")),
                new Management.StorageProfile("nowhere", "minio", "http://elsewhere:9000", List.of("s3://nowhere/t")),
                new Management.StorageProfile("twice", "s3", "https://s3.us-east-1.amazonaws.com",
                        List.of("s3://twice/t"))), STORES);

        assertThat(prefixes.prefixes()).as("matched by endpoint however it is written")
                .extracting(prefix -> prefix.location() + " " + prefix.store()).containsExactly("s3://ledger/t ceph");
        assertThat(prefixes.refused()).hasSize(3)
                .anyMatch(refused -> refused.startsWith("storage profile wrong-kind ") && refused.contains("no store"))
                .anyMatch(refused -> refused.startsWith("storage profile nowhere ") && refused.contains("no store"))
                .anyMatch(refused -> refused.startsWith("storage profile twice ")
                        && refused.contains("[aws, aws-again]"));
    }

    @Test
    void theWorkersStorageListsAndDeletesOnlyStrictlyBeneathAPrefix() {
        try (OwnStorage storage = Storage.of(STORES)) {
            storage.reach(List.of(new Management.StorageProfile("lakehouse", "minio", "http://silo:9000",
                    List.of("s3://warehouse/tables"))));

            assertThat(storage.store("s3a://warehouse/tables/ns/t/data/a.parquet")).contains("lake");
            for (String location : List.of("s3://warehouse/tables", "s3://warehouse/tables/",
                    "s3a://warehouse/tables/")) {
                assertThatThrownBy(() -> storage.list(location)).as("%s, at the prefix", location)
                        .isInstanceOf(IllegalStateException.class).hasMessageContaining("strictly beneath none");
                assertThatThrownBy(() -> storage.delete(List.of(location))).as("%s, at the prefix", location)
                        .isInstanceOf(IllegalStateException.class);
            }
            for (String location : List.of("s3://warehouse/tablespace/t/", "s3://warehouse/other/t/a.parquet",
                    "s3://archive/tables/t/a.parquet")) {
                assertThatThrownBy(() -> storage.list(location)).as("%s, outside the prefix", location)
                        .isInstanceOf(IllegalStateException.class).hasMessageContaining("strictly beneath none");
                assertThatThrownBy(() -> storage.delete(List.of("s3://warehouse/tables/t/a.parquet", location)))
                        .as("%s, outside the prefix, beside one beneath it", location)
                        .isInstanceOf(IllegalStateException.class);
            }
        }
    }

    @Test
    void beforeTheCatalogGivesAnyPrefixTheWorkersStorageReachesNothing() {
        try (OwnStorage storage = Storage.of(STORES)) {
            assertThat(storage.store("s3://warehouse/tables/t/a.parquet")).isEmpty();
            assertThatThrownBy(() -> storage.list("s3://warehouse/tables/t/"))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    private static Map<String, WorkerSettings.Store> stores() {
        Map<String, WorkerSettings.Store> stores = new LinkedHashMap<>();
        stores.put("lake", new WorkerSettings.Store(StoreKind.MINIO, URI.create("http://silo:9000"), "us-east-1",
                "nuna-maintenance", "secret", null));
        stores.put("ceph", new WorkerSettings.Store(StoreKind.CEPH, URI.create("https://rgw.internal:7480"),
                "us-east-1", "nuna-maintenance", "secret", null));
        stores.put("aws", new WorkerSettings.Store(StoreKind.S3, URI.create("https://s3.us-east-1.amazonaws.com"),
                "us-east-1", null, null, null));
        stores.put("aws-again", new WorkerSettings.Store(StoreKind.S3,
                URI.create("https://S3.us-east-1.amazonaws.com:443"), "us-east-1", null, null, null));
        return stores;
    }
}
