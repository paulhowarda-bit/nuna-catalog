package nuna.maintenance;

import nuna.catalog.objectstore.StoreKind;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The storage prefixes for tests' own storage, which stands in for the worker's ({@link OwnStorage}) and checks no
 * location: every profile the catalog gives, each served by a store of its kind and endpoint named after it.
 */
final class TestReach {

    /**
     * A stub catalog's answer to the worker's read of its storage prefixes (item 256): one MinIO profile, whose
     * prefix only a test's own storage reaches.
     */
    static final String ONE_PROFILE = "{\"profiles\":[{\"name\":\"test\",\"kind\":\"minio\","
            + "\"endpoint\":\"http://store.invalid:9000\",\"prefixes\":[\"s3://warehouse/tables\"]}]}";

    /** The stub catalogs' route for it. */
    static final String ROUTE = "/management/v1/maintenance/storage-prefixes";

    private TestReach() {
    }

    /** Every profile's prefixes, each served by a store of the profile's kind and endpoint. */
    static Prefixes everyProfile(List<Management.StorageProfile> profiles) {
        Map<String, WorkerSettings.Store> stores = new LinkedHashMap<>();
        profiles.forEach(profile -> stores.put(profile.name(), new WorkerSettings.Store(
                StoreKind.valueOf(profile.kind().toUpperCase(Locale.ROOT)), URI.create(profile.endpoint()),
                "us-east-1", null, null, null)));
        return Prefixes.resolve(profiles, stores);
    }
}
