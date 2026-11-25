package nuna.maintenance;

import java.net.URI;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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

    /** How the store answered the worker's listing beneath a prefix (backlog 250). */
    enum Listed { OK, REFUSED, FAILED }

    /**
     * What the store let the worker's own identity do beneath one prefix it covers (backlog 250): its listing of at
     * most one key at the scan's start, and the deletes since, done, refused by the store and failed without its
     * answer, with the code of the first not done. It names the prefix and the store's codes, never an object key, a
     * credential or the store's message.
     *
     * @param kind     the kind of store, {@code s3}, {@code minio} or {@code ceph}, as the worker's settings name it
     * @param endpoint the store's endpoint as the settings name it
     * @param listCode the store's code when it refused the listing (AccessDenied, NoSuchBucket), or the client's
     *                 failure when the store did not answer
     */
    record Reach(String kind, Optional<URI> endpoint, String prefix, Listed listed, Optional<String> listCode,
                 long deleted, long deleteRefused, long deleteFailed, Optional<String> deleteCode) {
    }

    /**
     * Lists at most one key beneath each prefix the storage reaches at this scan ({@link #reach(List)}), with the
     * worker's own identity, and counts the deletes beneath each afresh from now: what the store lets the worker do,
     * as a scan starts (backlog 250). A refusal is reported, never thrown. None for a storage that keeps no account.
     */
    default List<Reach> check() {
        return List.of();
    }

    /** What the store let the worker do beneath each prefix since the last {@link #check}; none before the first. */
    default List<Reach> reach() {
        return List.of();
    }

    /** Whether one location names the same object as another, whatever S3 scheme each was written with. */
    static String normalize(String location) {
        if (location.startsWith("s3a://") || location.startsWith("s3n://")) {
            return "s3://" + location.substring(6);
        }
        return location;
    }

    /**
     * The worker's own storage: one S3FileIO per store identity of the settings, trusting the CA bundle of its
     * endpoint, if it names one, and reaching nothing until the catalog's prefixes are handed to it. Each prefix it
     * reaches keeps account of what the store lets the worker do beneath it ({@link StorePrefix}).
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
