package nuna.catalog.iceberg;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.apache.iceberg.ContentFile;
import org.apache.iceberg.DataFile;
import org.apache.iceberg.DeleteFile;
import org.apache.iceberg.ManifestContent;
import org.apache.iceberg.ManifestFile;
import org.apache.iceberg.ManifestFiles;
import org.apache.iceberg.ManifestReader;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.PartitionStatisticsFile;
import org.apache.iceberg.Snapshot;
import org.apache.iceberg.StatisticsFile;
import org.apache.iceberg.TableMetadata;
import org.apache.iceberg.TableMetadataParser;
import org.apache.iceberg.inmemory.InMemoryInputFile;
import org.apache.iceberg.io.FileIO;
import org.apache.iceberg.io.InputFile;
import org.apache.iceberg.io.OutputFile;
import org.apache.iceberg.util.Tasks;
import org.apache.iceberg.util.ThreadPools;

/**
 * The files a version of a table adds to an earlier one, for the DR checks (D37, amended 2026-11-20): for each snapshot
 * it adds, the manifest list, the manifests that snapshot adds and the data and delete files those add, and the
 * statistics and partition statistics files the version adds. Its metadata file is the caller's to find. The
 * maintenance worker's verifier counts a version's files here, and so does the reconcile's walk, so the two agree on
 * what a version adds. Files are only read, never written.
 */
public final class AddedFiles {

    /** Reads a file whole, as the store a check runs against holds it. */
    @FunctionalInterface
    public interface Reader {
        byte[] read(String location);
    }

    private AddedFiles() {
    }

    /**
     * The first file the table metadata {@code json} adds to {@code baseJson} that {@code present} does not find; none
     * when every one is there. For a caller that holds metadata as JSON and reads files through {@code reader}.
     */
    public static Optional<String> firstMissing(String baseLocation, String baseJson, String location, String json,
                                                Reader reader, Predicate<String> present) {
        return firstMissing(TableMetadataParser.fromJson(baseLocation, baseJson),
                TableMetadataParser.fromJson(location, json), io(reader), present);
    }

    /**
     * The first file {@code metadata} adds to {@code base} (everything it reaches, when {@code base} is null) that
     * {@code present} does not find; none when every one is there. A manifest a snapshot of the base added, and a file
     * a snapshot of the base added, belong to the base. Manifest lists and manifests are read through {@code io}.
     */
    public static Optional<String> firstMissing(TableMetadata base, TableMetadata metadata, FileIO io,
                                                Predicate<String> present) {
        Set<Long> baseSnapshots = base == null ? Set.of()
                : base.snapshots().stream().map(Snapshot::snapshotId).collect(Collectors.toSet());
        // Sequence numbers tell which snapshot added a file only from format version 2: in version 1 they are all 0.
        Set<Long> baseSequences = base == null || metadata.formatVersion() < 2 ? Set.of()
                : base.snapshots().stream().map(Snapshot::sequenceNumber).collect(Collectors.toSet());
        Set<String> manifestsRead = new HashSet<>();
        Set<String> files = ConcurrentHashMap.newKeySet();
        Map<Integer, PartitionSpec> specs = metadata.specsById();
        for (Snapshot snapshot : metadata.snapshots()) {
            if (baseSnapshots.contains(snapshot.snapshotId())) {
                continue;
            }
            if (!present.test(snapshot.manifestListLocation())) {
                return Optional.of(snapshot.manifestListLocation());
            }
            List<ManifestFile> unread = new ArrayList<>();
            for (ManifestFile manifest : snapshot.allManifests(io)) {
                if (manifest.snapshotId() != null && baseSnapshots.contains(manifest.snapshotId())
                        || !manifestsRead.add(manifest.path())) {
                    continue;
                }
                if (!present.test(manifest.path())) {
                    return Optional.of(manifest.path());
                }
                unread.add(manifest);
            }
            Tasks.foreach(unread).executeWith(ThreadPools.getWorkerPool()).stopOnFailure().throwFailureWhenFinished()
                    .run(manifest -> read(manifest, io, specs, baseSequences, files));
        }
        Set<String> statistics = base == null ? Set.of() : statistics(base);
        statistics(metadata).stream().filter(path -> !statistics.contains(path)).forEach(files::add);
        List<String> absent = new ArrayList<>();
        Tasks.foreach(files).executeWith(ThreadPools.getWorkerPool()).stopOnFailure().throwFailureWhenFinished()
                .run(location -> {
                    if (!present.test(location)) {
                        synchronized (absent) {
                            absent.add(location);
                        }
                    }
                });
        return absent.stream().sorted().findFirst();
    }

    /** Whether a version adds nothing to the base: no snapshot and no statistics file the base lacks. */
    public static boolean addsNothing(TableMetadata base, TableMetadata metadata) {
        Set<Long> snapshots = base.snapshots().stream().map(Snapshot::snapshotId).collect(Collectors.toSet());
        return metadata.snapshots().stream().allMatch(snapshot -> snapshots.contains(snapshot.snapshotId()))
                && statistics(base).containsAll(statistics(metadata));
    }

    /** The data and delete files a manifest lists live, but those a snapshot of the base added. */
    private static void read(ManifestFile manifest, FileIO io, Map<Integer, PartitionSpec> specs,
                             Set<Long> baseSequences, Set<String> files) {
        try {
            if (manifest.content() == ManifestContent.DATA) {
                try (ManifestReader<DataFile> reader = ManifestFiles.read(manifest, io, specs)) {
                    reader.forEach(file -> keep(file, baseSequences, files));
                }
            } else {
                try (ManifestReader<DeleteFile> reader = ManifestFiles.readDeleteManifest(manifest, io, specs)) {
                    reader.forEach(file -> keep(file, baseSequences, files));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read manifest " + manifest.path(), e);
        }
    }

    private static void keep(ContentFile<?> file, Set<Long> baseSequences, Set<String> files) {
        Long added = file.fileSequenceNumber();
        if (added == null || !baseSequences.contains(added)) {
            files.add(file.location());
        }
    }

    private static Set<String> statistics(TableMetadata metadata) {
        Set<String> paths = new HashSet<>();
        metadata.statisticsFiles().stream().map(StatisticsFile::path).forEach(paths::add);
        metadata.partitionStatisticsFiles().stream().map(PartitionStatisticsFile::path).forEach(paths::add);
        return paths;
    }

    /** Manifest lists and manifests read whole through {@code reader}; nothing is written. */
    private static FileIO io(Reader reader) {
        return new FileIO() {
            @Override
            public InputFile newInputFile(String path) {
                return new InMemoryInputFile(path, reader.read(path));
            }

            @Override
            public OutputFile newOutputFile(String path) {
                throw new UnsupportedOperationException("a DR check only reads");
            }

            @Override
            public void deleteFile(String path) {
                throw new UnsupportedOperationException("a DR check only reads");
            }

            @Override
            public Map<String, String> properties() {
                return Map.of();
            }
        };
    }
}
