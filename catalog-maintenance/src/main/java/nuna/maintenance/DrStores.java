package nuna.maintenance;

import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.iceberg.aws.s3.S3FileIO;
import org.apache.iceberg.io.FileInfo;
import org.apache.iceberg.io.InputFile;
import org.apache.iceberg.io.OutputFile;
import org.apache.iceberg.io.SupportsPrefixOperations;

/**
 * The DR store, as the verifier reads it (D37, amended 2026-11-20): for each store identity in
 * {@code nuna.maintenance.storage} that names a replica in {@code nuna.maintenance.dr.replicas}, the prefixes the
 * catalog gives for that store (item 256), read on the replica's endpoint with the replica's keys and CA bundle. The
 * same buckets and paths as the primary's, so a location in the metadata names the file in either. It only reads: a
 * write or a delete is refused.
 */
final class DrStores implements SupportsPrefixOperations {

    private final Map<String, S3FileIO> byStore;
    /** The prefixes the catalog gave at the verifier's last pass; none before the first. */
    private volatile Prefixes reached = Prefixes.NONE;

    private DrStores(Map<String, S3FileIO> byStore) {
        this.byStore = byStore;
    }

    /**
     * One S3FileIO per replica, addressed as its store is and trusting the replica's CA bundle, if it names one.
     *
     * @throws IllegalArgumentException when a replica names no store, or its CA bundle cannot be read
     */
    static DrStores of(Map<String, WorkerSettings.Store> stores, Map<String, WorkerSettings.Replica> replicas) {
        Map<String, WorkerSettings.Store> asStores = new LinkedHashMap<>();
        replicas.forEach((name, replica) -> {
            WorkerSettings.Store store = stores.get(name);
            if (store == null) {
                throw new IllegalArgumentException("nuna.maintenance.dr.replicas." + name + " names no store: a"
                        + " replica is named by its store's name in nuna.maintenance.storage, " + stores.keySet());
            }
            asStores.put(name, new WorkerSettings.Store(store.kind(), replica.endpoint(), replica.region(),
                    replica.accessKeyId(), replica.secretAccessKey(), replica.caBundle()));
        });
        Map<String, String> trust;
        try {
            trust = TrustedS3Clients.register(asStores);
        } catch (IllegalArgumentException e) {
            // Its message names the store's setting; the bundle is the replica's.
            throw new IllegalArgumentException(e.getMessage().replace("nuna.maintenance.storage.",
                    "nuna.maintenance.dr.replicas."), e);
        }
        Map<String, S3FileIO> byStore = new LinkedHashMap<>();
        asStores.forEach((name, replica) -> byStore.put(name, Storage.fileIo(replica, trust)));
        return new DrStores(byStore);
    }

    /** Takes the prefixes the catalog gives at this pass, in place of the last pass's. */
    void reach(Prefixes prefixes) {
        reached = prefixes;
    }

    @Override
    public InputFile newInputFile(String location) {
        return io(location).newInputFile(location);
    }

    @Override
    public Iterable<FileInfo> listPrefix(String prefix) {
        return io(prefix).listPrefix(prefix);
    }

    @Override
    public OutputFile newOutputFile(String location) {
        throw new UnsupportedOperationException("the DR store is only read: " + location);
    }

    @Override
    public void deleteFile(String location) {
        throw new UnsupportedOperationException("the DR store is only read: " + location);
    }

    @Override
    public void deletePrefix(String prefix) {
        throw new UnsupportedOperationException("the DR store is only read: " + prefix);
    }

    @Override
    public void close() {
        byStore.values().forEach(S3FileIO::close);
    }

    /**
     * The replica serving a location: that of the store whose prefix it lies beneath. A location beneath no prefix, or
     * beneath one of a store with no replica, is checked nowhere.
     */
    private S3FileIO io(String location) {
        Prefixes now = reached;
        Prefixes.Prefix prefix = now.holding(location).orElseThrow(() -> new IllegalStateException(location
                + " lies strictly beneath none of the storage prefixes the catalog gives the worker, " + now));
        S3FileIO io = byStore.get(prefix.store());
        if (io == null) {
            throw new IllegalStateException("no nuna.maintenance.dr.replicas entry names store " + prefix.store()
                    + ", which serves " + location);
        }
        return io;
    }
}
