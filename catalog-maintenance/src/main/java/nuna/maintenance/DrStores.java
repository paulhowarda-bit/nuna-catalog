package nuna.maintenance;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.iceberg.aws.s3.S3FileIO;
import org.apache.iceberg.io.FileInfo;
import org.apache.iceberg.io.InputFile;
import org.apache.iceberg.io.OutputFile;
import org.apache.iceberg.io.SupportsPrefixOperations;

/**
 * The DR store, as the verifier reads it (D37, amended 2026-11-20): for each storage profile that names a replica in
 * {@code nuna.maintenance.dr.replicas}, the profile's prefixes on the replica's endpoint, with the replica's keys and
 * CA bundle. The same buckets and paths as the primary's, so a location in the metadata names the file in either. It
 * only reads: a write or a delete is refused.
 */
final class DrStores implements SupportsPrefixOperations {

    private final Map<String, S3FileIO> byPrefix;
    private final List<String> prefixes;
    private final List<S3FileIO> all;

    private DrStores(Map<String, S3FileIO> byPrefix, List<S3FileIO> all) {
        this.byPrefix = byPrefix;
        this.prefixes = byPrefix.keySet().stream().sorted(Comparator.comparingInt(String::length).reversed()).toList();
        this.all = all;
    }

    /**
     * One S3FileIO per replica, serving its profile's prefixes and trusting the replica's CA bundle, if it names one.
     *
     * @throws IllegalArgumentException when a replica names no storage profile, or its CA bundle cannot be read
     */
    static DrStores of(Map<String, WorkerSettings.StorageProfile> profiles, Map<String, WorkerSettings.Replica> replicas) {
        Map<String, WorkerSettings.StorageProfile> asProfiles = new LinkedHashMap<>();
        replicas.forEach((name, replica) -> {
            WorkerSettings.StorageProfile profile = profiles.get(name);
            if (profile == null) {
                throw new IllegalArgumentException("nuna.maintenance.dr.replicas." + name + " names no storage profile:"
                        + " a replica is named by its profile's name in nuna.maintenance.storage, " + profiles.keySet());
            }
            asProfiles.put(name, new WorkerSettings.StorageProfile(profile.prefixes(), replica.endpoint(),
                    replica.region(), replica.pathStyleAccess(), replica.accessKeyId(), replica.secretAccessKey(),
                    replica.caBundle()));
        });
        Map<String, String> trust = TrustedS3Clients.register(asProfiles);
        Map<String, S3FileIO> byPrefix = new LinkedHashMap<>();
        List<S3FileIO> all = new ArrayList<>();
        asProfiles.forEach((name, profile) -> {
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
            profile.prefixes().forEach(prefix -> byPrefix.put(Storage.normalize(prefix), io));
        });
        return new DrStores(byPrefix, all);
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
        all.forEach(S3FileIO::close);
    }

    /** The replica serving a location; a location no replica's profile covers is checked nowhere. */
    private S3FileIO io(String location) {
        String normalized = Storage.normalize(location);
        for (String prefix : prefixes) {
            if (normalized.startsWith(prefix.endsWith("/") ? prefix : prefix + "/") || normalized.equals(prefix)) {
                return byPrefix.get(prefix);
            }
        }
        throw new IllegalStateException("no nuna.maintenance.dr.replicas entry covers " + location);
    }
}
