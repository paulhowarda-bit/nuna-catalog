package keel.maintenance;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.iceberg.DeleteFile;
import org.apache.iceberg.ManifestContent;
import org.apache.iceberg.ManifestFile;
import org.apache.iceberg.ManifestFiles;
import org.apache.iceberg.ManifestReader;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.PartitionStatisticsFile;
import org.apache.iceberg.Snapshot;
import org.apache.iceberg.StatisticsFile;
import org.apache.iceberg.Table;
import org.apache.iceberg.TableMetadata;
import org.apache.iceberg.HasTableOperations;
import org.apache.iceberg.io.CloseableIterable;
import org.apache.iceberg.io.FileIO;
import org.apache.iceberg.io.FileInfo;
import org.apache.iceberg.util.Tasks;
import org.apache.iceberg.util.ThreadPools;

/**
 * Orphan removal in Java (D30 (8)): the files under a table's location that no metadata reaches and that are older
 * than the policy's orphan age. Reachable is every metadata file still in the table's metadata log, and every
 * manifest list, manifest, data file, delete file, statistics file and partition statistics file of every snapshot,
 * which covers every branch and tag.
 *
 * <p>The listing comes first and the metadata it is checked against is read again after it, so a file a writer
 * wrote before the listing and committed before that second read is reachable. Only a file written before the
 * listing and committed after that read can look orphaned; the orphan age, longer than any write takes, keeps it.
 *
 * <p>Memory: one reachable set of every file the table reaches, the listing streamed past it, and the candidates.
 * The second read adds only what was committed meanwhile.
 */
final class OrphanScan {

    private OrphanScan() {
    }

    /**
     * The orphans of {@code table} older than {@code cutoff}, beneath its location.
     *
     * @param afterListing runs between the listing and the second metadata read (tests use it)
     */
    static List<String> find(Table table, Storage storage, Instant cutoff, Runnable afterListing) {
        TableMetadata before = metadata(table);
        String root = before.location().endsWith("/") ? before.location() : before.location() + "/";
        Reach reach = new Reach();
        reach.collect(before, table.io());
        List<String> candidates = new ArrayList<>();
        long cut = cutoff.toEpochMilli();
        for (FileInfo file : storage.list(root)) {
            if (file.createdAtMillis() < cut && !reach.files.contains(Storage.normalize(file.location()))) {
                candidates.add(file.location());
            }
        }
        afterListing.run();
        table.refresh();
        Reach since = reach.next();
        since.collect(metadata(table), table.io());
        candidates.removeIf(location -> since.files.contains(Storage.normalize(location)));
        return candidates;
    }

    private static TableMetadata metadata(Table table) {
        return ((HasTableOperations) table).operations().current();
    }

    /** What some metadata reaches; a second pass reads only snapshots and manifests the first had not. */
    private static final class Reach {

        final Set<String> files = ConcurrentHashMap.newKeySet();
        final Set<Long> snapshots = new HashSet<>();
        final Set<String> manifests = ConcurrentHashMap.newKeySet();

        /** A pass that skips what this one read, keeping only that, not its files. */
        Reach next() {
            Reach next = new Reach();
            next.snapshots.addAll(snapshots);
            next.manifests.addAll(manifests);
            return next;
        }

        void collect(TableMetadata metadata, FileIO io) {
            add(metadata.metadataFileLocation());
            metadata.previousFiles().forEach(entry -> add(entry.file()));
            metadata.statisticsFiles().stream().map(StatisticsFile::path).forEach(this::add);
            metadata.partitionStatisticsFiles().stream().map(PartitionStatisticsFile::path).forEach(this::add);
            List<ManifestFile> unread = new ArrayList<>();
            for (Snapshot snapshot : metadata.snapshots()) {
                if (!snapshots.add(snapshot.snapshotId())) {
                    continue;
                }
                add(snapshot.manifestListLocation());
                for (ManifestFile manifest : snapshot.allManifests(io)) {
                    if (manifests.add(manifest.path())) {
                        add(manifest.path());
                        unread.add(manifest);
                    }
                }
            }
            Map<Integer, PartitionSpec> specs = metadata.specsById();
            Tasks.foreach(unread).executeWith(ThreadPools.getWorkerPool()).stopOnFailure().throwFailureWhenFinished()
                    .run(manifest -> read(manifest, io, specs));
        }

        private void read(ManifestFile manifest, FileIO io, Map<Integer, PartitionSpec> specs) {
            try {
                if (manifest.content() == ManifestContent.DATA) {
                    try (CloseableIterable<String> paths = ManifestFiles.readPaths(manifest, io, specs)) {
                        paths.forEach(this::add);
                    }
                } else {
                    try (ManifestReader<DeleteFile> deletes = ManifestFiles.readDeleteManifest(manifest, io, specs)) {
                        deletes.forEach(file -> add(file.location()));
                    }
                }
            } catch (IOException e) {
                throw new UncheckedIOException("cannot read manifest " + manifest.path(), e);
            }
        }

        private void add(String location) {
            if (location != null) {
                files.add(Storage.normalize(location));
            }
        }
    }
}
