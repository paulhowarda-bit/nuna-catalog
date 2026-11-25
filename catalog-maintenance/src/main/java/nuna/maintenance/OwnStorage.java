package nuna.maintenance;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.apache.iceberg.aws.s3.S3FileIO;
import org.apache.iceberg.io.FileInfo;

/**
 * The worker's own storage ({@link Storage#of}): one S3FileIO per store identity in {@code nuna.maintenance.storage},
 * each reaching the prefixes the catalog gave for its store at this scan (item 256; D30, amended 2026-11-24). A
 * location strictly beneath one of them is listed or deleted with that store's identity; one at a prefix, outside
 * every one, or anywhere before the catalog has given any, is refused. That is the worker's own check on where it
 * lists and deletes; the store's policy on its identity is the other (D30 (7)). Each prefix keeps account of what the
 * store let the worker do beneath it since the scan's {@link #check} (backlog 250, {@link StorePrefix}).
 */
final class OwnStorage implements Storage {

    private final Map<String, WorkerSettings.Store> stores;
    private final Map<String, String> trust;
    private final Map<String, S3FileIO> byStore = new LinkedHashMap<>();
    /**
     * The prefixes the catalog gave at this scan, each with its account; none before the first read, or after one
     * that named none or failed.
     */
    private volatile Reached reached = new Reached(Prefixes.NONE, Map.of());
    /** Whether {@link #check} has run since the prefixes were last taken. */
    private volatile boolean checked;

    /** The prefixes reached, and the account of each, by prefix, in the order of their locations. */
    private record Reached(Prefixes prefixes, Map<Prefixes.Prefix, StorePrefix> accounts) {
    }

    OwnStorage(Map<String, WorkerSettings.Store> stores) {
        this.stores = Map.copyOf(stores);
        this.trust = TrustedS3Clients.register(stores);
        stores.forEach((name, store) -> byStore.put(name, Storage.fileIo(store, trust)));
    }

    @Override
    public Prefixes reach(List<Management.StorageProfile> profiles) {
        Prefixes now = Prefixes.resolve(profiles, stores);
        Map<Prefixes.Prefix, StorePrefix> accounts = new LinkedHashMap<>();
        now.prefixes().stream().sorted(Comparator.comparing(prefix -> prefix.location().toString()))
                .forEach(prefix -> {
                    WorkerSettings.Store store = stores.get(prefix.store());
                    accounts.put(prefix, new StorePrefix(prefix.location().toString(),
                            store.kind().name().toLowerCase(Locale.ROOT), Optional.ofNullable(store.endpoint()),
                            byStore.get(prefix.store())));
                });
        checked = false;
        reached = new Reached(now, accounts);
        return now;
    }

    /** The store identity a location lies beneath at this scan, by name; none at a prefix or outside every one. */
    Optional<String> store(String location) {
        return reached.prefixes().holding(location).map(Prefixes.Prefix::store);
    }

    @Override
    public Iterable<FileInfo> list(String prefix) {
        return account(prefix).io.listPrefix(prefix);
    }

    @Override
    public void delete(Collection<String> locations) {
        Map<StorePrefix, List<String>> beneath = new LinkedHashMap<>();
        locations.forEach(location -> beneath.computeIfAbsent(account(location), prefix -> new ArrayList<>())
                .add(location));
        RuntimeException first = null;
        for (Map.Entry<StorePrefix, List<String>> objects : beneath.entrySet()) {
            try {
                objects.getKey().delete(objects.getValue());
            } catch (RuntimeException e) {
                first = first == null ? e : first;
            }
        }
        if (first != null) {
            throw first;
        }
    }

    @Override
    public List<Reach> check() {
        List<Reach> reach = reached.accounts().values().stream().map(StorePrefix::check).toList();
        checked = true;
        return reach;
    }

    @Override
    public List<Reach> reach() {
        return checked ? reached.accounts().values().stream().map(StorePrefix::reach).toList() : List.of();
    }

    @Override
    public void close() {
        byStore.values().forEach(S3FileIO::close);
    }

    @Override
    public Map<String, String> clientProperties() {
        return trust;
    }

    /** The account of the prefix a location lies strictly beneath at this scan. */
    private StorePrefix account(String location) {
        Reached now = reached;
        return now.prefixes().holding(location).map(now.accounts()::get).orElseThrow(() ->
                new IllegalStateException(location + " lies strictly beneath none of the storage prefixes the catalog"
                        + " gives the worker at this scan, " + now.prefixes() + ": the worker lists and deletes"
                        + " nothing there"));
    }
}
