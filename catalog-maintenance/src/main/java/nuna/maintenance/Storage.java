package nuna.maintenance;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.iceberg.aws.s3.S3FileIO;
import org.apache.iceberg.io.FileInfo;

/**
 * Table storage as the worker's own identity reaches it (D21, D30): the only identity a deployment's delete policy
 * lets delete. The worker lists and deletes through this alone; reads and writes go through the catalog's vended
 * credentials.
 */
interface Storage extends AutoCloseable {

    /** Every object under {@code prefix}, as the store lists them. */
    Iterable<FileInfo> list(String prefix);

    /** Deletes these objects; one already gone is no failure. */
    void delete(Collection<String> locations);

    /**
     * Takes the storage profiles the catalog gives the worker at this scan (item 256): from now on it lists and
     * deletes strictly beneath their prefixes alone, and nowhere when there are none, the catalog's answer naming none
     * or the worker failing to read it. No earlier answer is kept.
     *
     * @return the prefixes it now reaches, and what it refused
     */
    Prefixes reach(List<Management.StorageProfile> profiles);

    @Override
    void close();

    /**
     * The client properties that make the worker's other S3 clients, those reading table files with the credentials
     * the catalog vends it ({@link Catalogs}), trust the stores' CA bundles as these do (backlog 21); none when no
     * store names one.
     */
    default Map<String, String> clientProperties() {
        return Map.of();
    }

    /** Whether one location names the same object as another, whatever S3 scheme it was written with. */
    static String normalize(String location) {
        if (location.startsWith("s3a://") || location.startsWith("s3n://")) {
            return "s3://" + location.substring(6);
        }
        return location;
    }

    /**
     * The worker's own storage: one S3FileIO per store identity of the settings, trusting the CA bundle of its
     * endpoint, if it names one, and reaching nothing until the catalog's prefixes are handed to it.
     *
     * @throws IllegalArgumentException when a store's CA bundle cannot be read ({@link TrustedS3Clients#register})
     */
    static OwnStorage of(Map<String, WorkerSettings.Store> stores) {
        return new OwnStorage(stores);
    }

    /**
     * An S3FileIO on one store as an identity reaches it: its endpoint, if it names one, region, addressing by path
     * on MinIO and Ceph, its keys or the AWS SDK's default chain, and the client properties {@code trust} that select
     * the registered CA bundles.
     */
    static S3FileIO fileIo(WorkerSettings.Store store, Map<String, String> trust) {
        S3FileIO io = new S3FileIO();
        Map<String, String> properties = new HashMap<>();
        properties.put("client.region", store.region());
        properties.put("s3.path-style-access", Boolean.toString(store.kind().pathStyle()));
        properties.put("http-client.type", "apache");
        if (store.endpoint() != null) {
            properties.put("s3.endpoint", store.endpoint().toString());
        }
        if (store.accessKeyId() != null) {
            properties.put("s3.access-key-id", store.accessKeyId());
            properties.put("s3.secret-access-key", store.secretAccessKey());
        }
        properties.putAll(trust);
        io.initialize(properties);
        return io;
    }
}
