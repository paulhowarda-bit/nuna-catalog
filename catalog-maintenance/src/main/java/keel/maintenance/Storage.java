package keel.maintenance;

import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
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

    @Override
    void close();

    /** Whether one location names the same object as another, whatever S3 scheme each was written with. */
    static String normalize(String location) {
        if (location.startsWith("s3a://") || location.startsWith("s3n://")) {
            return "s3://" + location.substring(6);
        }
        return location;
    }

    /** One S3FileIO per profile of the settings, each serving the locations under its prefixes. */
    static Storage of(Map<String, WorkerSettings.StorageProfile> profiles) {
        Map<String, S3FileIO> byPrefix = new LinkedHashMap<>();
        List<S3FileIO> all = new java.util.ArrayList<>();
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
            io.initialize(properties);
            all.add(io);
            profile.prefixes().forEach(prefix -> byPrefix.put(normalize(prefix), io));
        });
        List<String> prefixes = byPrefix.keySet().stream().sorted(Comparator.comparingInt(String::length).reversed())
                .toList();
        return new Storage() {
            @Override
            public Iterable<FileInfo> list(String prefix) {
                return io(prefix).listPrefix(prefix);
            }

            @Override
            public void delete(Collection<String> locations) {
                Map<S3FileIO, List<String>> byIo = new LinkedHashMap<>();
                locations.forEach(location -> byIo.computeIfAbsent(io(location), io -> new java.util.ArrayList<>())
                        .add(location));
                byIo.forEach(S3FileIO::deleteFiles);
            }

            @Override
            public void close() {
                all.forEach(S3FileIO::close);
            }

            private S3FileIO io(String location) {
                String normalized = normalize(location);
                for (String prefix : prefixes) {
                    if (normalized.startsWith(prefix.endsWith("/") ? prefix : prefix + "/") || normalized.equals(prefix)) {
                        return byPrefix.get(prefix);
                    }
                }
                throw new IllegalStateException("no keel.maintenance.storage profile covers " + location);
            }
        };
    }
}
