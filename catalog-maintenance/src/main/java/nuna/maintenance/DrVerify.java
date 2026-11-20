package nuna.maintenance;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.apache.iceberg.ContentFile;
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
import org.apache.iceberg.io.FileInfo;
import org.apache.iceberg.io.SupportsPrefixOperations;
import org.apache.iceberg.util.Tasks;
import org.apache.iceberg.util.ThreadPools;

/**
 * One table's or view's check in the DR store (D37, amended 2026-11-20; docs/observability/dr-watermark.md): how far
 * its versions above its DR watermark are whole there. A version is safe when every file it adds, data included, is
 * in the DR store and the version before it is safe: its metadata file, and for each snapshot it adds, the manifest
 * list, the manifests the snapshot adds, the data and delete files those add, and the statistics files it adds. A
 * commit that adds no snapshot is safe on its metadata file alone. A version that adds nothing to the newest safe
 * version, no snapshot and no statistics file it lacks, is safe on its metadata file alone too, whatever came between:
 * so the version a rewind writes, the watermark's metadata again, is safe as soon as its file arrives. A view's
 * version is safe when its metadata file is there.
 *
 * <p>A table or view with no watermark, or whose journaled versions do not follow its watermark one by one, is checked
 * whole at its pointer's version: every file every snapshot of that version reaches, found in one listing of its
 * location. Files are only read, never written.
 */
final class DrVerify {

    /** One version of an object's main pointer; {@code committedAt} when the journal says. */
    record Version(long version, String metadataLocation, Optional<Instant> committedAt) {
    }

    /**
     * One object of the catalog's DR work list ({@code GET /management/v1/maintenance/dr-work}).
     *
     * @param versions           the journaled versions above the watermark, oldest first, at most 100; none without
     *                           a watermark
     * @param oldestUnverifiedAt when the oldest version above the watermark was committed, when the journal says
     */
    record Item(UUID id, String kind, String location, Version pointer, Optional<Version> watermark,
                List<Version> versions, Optional<Instant> oldestUnverifiedAt) {

        /** Whether it is checked whole at its pointer, not version by version from its watermark. */
        boolean whole() {
            if (watermark.isEmpty() || versions.isEmpty()) {
                return true;
            }
            long next = watermark.get().version() + 1;
            for (Version version : versions) {
                if (version.version() != next++) {
                    return true;
                }
            }
            return false;
        }
    }

    /**
     * What a check found: the newest safe version above the watermark, if any, and the first file found missing
     * after it.
     */
    record Result(Optional<Version> safe, Optional<String> missing) {
    }

    private final SupportsPrefixOperations dr;

    /** @param dr the DR store, read-only */
    DrVerify(SupportsPrefixOperations dr) {
        this.dr = dr;
    }

    Result check(Item item) {
        if ("view".equals(item.kind())) {
            return view(item);
        }
        if (item.whole()) {
            return whole(item);
        }
        Version watermark = item.watermark().orElseThrow();
        if (!exists(watermark.metadataLocation())) {
            // The watermark's own file is gone from the DR store: nothing builds on it, so check the pointer whole.
            return whole(item);
        }
        TableMetadata safeMetadata = TableMetadataParser.read(dr, watermark.metadataLocation());
        boolean previousSafe = true;
        Optional<Version> safe = Optional.empty();
        Optional<String> missing = Optional.empty();
        for (Version version : item.versions()) {
            Optional<String> gap;
            TableMetadata metadata = null;
            if (!exists(version.metadataLocation())) {
                gap = Optional.of(version.metadataLocation());
            } else {
                metadata = TableMetadataParser.read(dr, version.metadataLocation());
                gap = previousSafe ? added(safeMetadata, metadata, this::exists)
                        : addsNothing(safeMetadata, metadata) ? Optional.empty()
                        : Optional.of("version " + version.version() + " adds files to version "
                                + safe.map(Version::version).orElse(watermark.version()) + " after a version not"
                                + " whole in the DR store");
            }
            if (gap.isEmpty()) {
                safe = Optional.of(version);
                safeMetadata = metadata;
                previousSafe = true;
                missing = Optional.empty();
            } else {
                previousSafe = false;
                if (missing.isEmpty()) {
                    missing = gap;
                }
            }
        }
        return new Result(safe, missing);
    }

    /** A view's versions: each is safe once its metadata file is in the DR store. */
    private Result view(Item item) {
        List<Version> versions = item.whole() ? List.of(item.pointer()) : item.versions();
        Optional<Version> safe = Optional.empty();
        Optional<String> missing = Optional.empty();
        for (Version version : versions) {
            if (exists(version.metadataLocation())) {
                safe = Optional.of(version);
                missing = Optional.empty();
            } else if (missing.isEmpty()) {
                missing = Optional.of(version.metadataLocation());
            }
        }
        return new Result(safe, missing);
    }

    /** The pointer's version, whole: every file its snapshots reach, found in one listing of the table's location. */
    private Result whole(Item item) {
        Version pointer = item.pointer();
        String root = Storage.normalize(item.location().endsWith("/") ? item.location() : item.location() + "/");
        Set<String> listed = new HashSet<>();
        for (FileInfo file : dr.listPrefix(item.location())) {
            listed.add(Storage.normalize(file.location()));
        }
        Predicate<String> present = location -> {
            String normalized = Storage.normalize(location);
            return normalized.startsWith(root) ? listed.contains(normalized) : exists(location);
        };
        if (!present.test(pointer.metadataLocation())) {
            return new Result(Optional.empty(), Optional.of(pointer.metadataLocation()));
        }
        TableMetadata metadata = TableMetadataParser.read(dr, pointer.metadataLocation());
        Optional<String> gap = added(null, metadata, present);
        return gap.isPresent() ? new Result(Optional.empty(), gap) : new Result(Optional.of(pointer), Optional.empty());
    }

    /**
     * The first file {@code metadata} adds to {@code base} (everything it reaches, when there is no base) that
     * {@code present} does not find; none when every one is there. A manifest a snapshot of the base added, and a file
     * a snapshot of the base added, were checked with that snapshot.
     */
    private Optional<String> added(TableMetadata base, TableMetadata metadata, Predicate<String> present) {
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
            for (ManifestFile manifest : snapshot.allManifests(dr)) {
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
                    .run(manifest -> read(manifest, specs, baseSequences, files));
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

    /** The data and delete files a manifest lists live, but those a snapshot of the base added. */
    private void read(ManifestFile manifest, Map<Integer, PartitionSpec> specs, Set<Long> baseSequences,
                      Set<String> files) {
        try {
            if (manifest.content() == ManifestContent.DATA) {
                try (ManifestReader<org.apache.iceberg.DataFile> reader = ManifestFiles.read(manifest, dr, specs)) {
                    reader.forEach(file -> keep(file, baseSequences, files));
                }
            } else {
                try (ManifestReader<org.apache.iceberg.DeleteFile> reader = ManifestFiles.readDeleteManifest(manifest,
                        dr, specs)) {
                    reader.forEach(file -> keep(file, baseSequences, files));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read manifest " + manifest.path() + " in the DR store", e);
        }
    }

    private static void keep(ContentFile<?> file, Set<Long> baseSequences, Set<String> files) {
        Long added = file.fileSequenceNumber();
        if (added == null || !baseSequences.contains(added)) {
            files.add(file.location());
        }
    }

    /** Whether a version adds nothing to the base: no snapshot and no statistics file the base lacks. */
    private static boolean addsNothing(TableMetadata base, TableMetadata metadata) {
        Set<Long> snapshots = base.snapshots().stream().map(Snapshot::snapshotId).collect(Collectors.toSet());
        return metadata.snapshots().stream().allMatch(snapshot -> snapshots.contains(snapshot.snapshotId()))
                && statistics(base).containsAll(statistics(metadata));
    }

    private static Set<String> statistics(TableMetadata metadata) {
        Set<String> paths = new HashSet<>();
        metadata.statisticsFiles().stream().map(StatisticsFile::path).forEach(paths::add);
        metadata.partitionStatisticsFiles().stream().map(PartitionStatisticsFile::path).forEach(paths::add);
        return paths;
    }

    private boolean exists(String location) {
        return dr.newInputFile(location).exists();
    }
}
