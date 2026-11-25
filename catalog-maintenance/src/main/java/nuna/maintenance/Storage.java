package nuna.maintenance;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
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

    @Override
    void close();

    /**
     * The client properties that make the worker's other S3 clients, those reading table files with the credentials
     * the catalog vends it ({@link Catalogs}), trust the profiles' CA bundles as these do (backlog 21); none when no
     * profile names one.
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
     * @param kind     the kind of store, {@code s3}
     * @param endpoint the store's endpoint as the settings name it; none for AWS S3's own
     * @param listCode the store's code when it refused the listing (AccessDenied, NoSuchBucket), or the client's
     *                 failure when the store did not answer
     */
    record Reach(String kind, Optional<URI> endpoint, String prefix, Listed listed, Optional<String> listCode,
                 long deleted, long deleteRefused, long deleteFailed, Optional<String> deleteCode) {
    }

    /**
     * Lists at most one key beneath each prefix, with the worker's own identity, and counts the deletes beneath each
     * afresh from now: what the store lets the worker do, as a scan starts (backlog 250). A refusal is reported, never
     * thrown. None for a storage that keeps no account.
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
     * One S3FileIO per profile of the settings, each serving the locations under its prefixes and trusting the CA
     * bundle of its endpoint, if a profile names one. Each prefix keeps account of what the store lets the worker do
     * beneath it ({@link StorePrefix}).
     *
     * @throws IllegalArgumentException when a profile's CA bundle cannot be read ({@link TrustedS3Clients#register})
     */
    static Storage of(Map<String, WorkerSettings.StorageProfile> profiles) {
        Map<String, String> trust = TrustedS3Clients.register(profiles);
        Map<String, StorePrefix> byPrefix = new LinkedHashMap<>();
        List<S3FileIO> all = new ArrayList<>();
        profiles.forEach((name, profile) -> {
            S3FileIO io = new S3FileIO();
            Map<String, String> properties = new HashMap<>();
            properties.put("client.region", profile.region());
            properties.put("s3.path-style-access", Boolean.toString(profile.pathStyleAccess()));
            properties.put("http-client.type", "apache");
            if (profile.endpoint() != null) {
                properties.put("s3.endpoint", profile.endpoint().toString());
            }
            if (profile.accessKeyId() != null) {
                properties.put("s3.access-key-id", profile.accessKeyId());
                properties.put("s3.secret-access-key", profile.secretAccessKey());
            }
            properties.putAll(trust);
            io.initialize(properties);
            all.add(io);
            profile.prefixes().forEach(prefix -> byPrefix.put(normalize(prefix),
                    new StorePrefix(normalize(prefix), Optional.ofNullable(profile.endpoint()), io)));
        });
        List<String> prefixes = byPrefix.keySet().stream().sorted(Comparator.comparingInt(String::length).reversed())
                .toList();
        List<StorePrefix> inOrder = byPrefix.keySet().stream().sorted().map(byPrefix::get).toList();
        return new Storage() {
            private volatile boolean checked;

            @Override
            public Iterable<FileInfo> list(String prefix) {
                return covering(prefix).io.listPrefix(prefix);
            }

            @Override
            public void delete(Collection<String> locations) {
                Map<StorePrefix, List<String>> beneath = new LinkedHashMap<>();
                locations.forEach(location -> beneath.computeIfAbsent(covering(location), prefix -> new ArrayList<>())
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
            public void close() {
                all.forEach(S3FileIO::close);
            }

            @Override
            public Map<String, String> clientProperties() {
                return trust;
            }

            @Override
            public List<Reach> check() {
                List<Reach> reach = inOrder.stream().map(StorePrefix::check).toList();
                checked = true;
                return reach;
            }

            @Override
            public List<Reach> reach() {
                return checked ? inOrder.stream().map(StorePrefix::reach).toList() : List.of();
            }

            private StorePrefix covering(String location) {
                String normalized = normalize(location);
                for (String prefix : prefixes) {
                    if (normalized.startsWith(prefix.endsWith("/") ? prefix : prefix + "/") || normalized.equals(prefix)) {
                        return byPrefix.get(prefix);
                    }
                }
                throw new IllegalStateException("no nuna.maintenance.storage profile covers " + location);
            }
        };
    }
}
