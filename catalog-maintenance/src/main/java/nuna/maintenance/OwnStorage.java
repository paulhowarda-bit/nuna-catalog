package nuna.maintenance;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.apache.iceberg.aws.s3.S3FileIO;
import org.apache.iceberg.io.FileInfo;

/**
 * The worker's own storage ({@link Storage#of}): one S3FileIO per store identity in {@code nuna.maintenance.storage},
 * each reaching the prefixes the catalog gave for its store at this scan (item 256; D30, amended 2026-11-24). A
 * location strictly beneath one of them is listed or deleted with that store's identity; one at a prefix, outside
 * every one, or anywhere before the catalog has given any, is refused. That is the worker's own check on where it
 * lists and deletes; the store's policy on its identity is the other (D30 (7)).
 */
final class OwnStorage implements Storage {

    private final Map<String, WorkerSettings.Store> stores;
    private final Map<String, String> trust;
    private final Map<String, S3FileIO> byStore = new LinkedHashMap<>();
    /** The prefixes the catalog gave at this scan; none before the first or after a scan that read none. */
    private volatile Prefixes reached = Prefixes.NONE;

    OwnStorage(Map<String, WorkerSettings.Store> stores) {
        this.stores = Map.copyOf(stores);
        this.trust = TrustedS3Clients.register(stores);
        stores.forEach((name, store) -> byStore.put(name, Storage.fileIo(store, trust)));
    }

    @Override
    public Prefixes reach(List<Management.StorageProfile> profiles) {
        Prefixes now = Prefixes.resolve(profiles, stores);
        reached = now;
        return now;
    }

    /** The store identity a location lies beneath at this scan, by name; none at a prefix or outside every one. */
    Optional<String> store(String location) {
        return reached.holding(location).map(Prefixes.Prefix::store);
    }

    @Override
    public Iterable<FileInfo> list(String prefix) {
        return io(prefix).listPrefix(prefix);
    }

    @Override
    public void delete(Collection<String> locations) {
        Map<S3FileIO, List<String>> byIo = new LinkedHashMap<>();
        locations.forEach(location -> byIo.computeIfAbsent(io(location), io -> new ArrayList<>()).add(location));
        byIo.forEach(S3FileIO::deleteFiles);
    }

    @Override
    public void close() {
        byStore.values().forEach(S3FileIO::close);
    }

    @Override
    public Map<String, String> clientProperties() {
        return trust;
    }

    private S3FileIO io(String location) {
        Prefixes now = reached;
        return now.holding(location).map(prefix -> byStore.get(prefix.store())).orElseThrow(() ->
                new IllegalStateException(location + " lies strictly beneath none of the storage prefixes the catalog"
                        + " gives the worker at this scan, " + now + ": the worker lists and deletes nothing there"));
    }
}
