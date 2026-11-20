package nuna.maintenance;

import nuna.catalog.iceberg.AddedFiles;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import org.apache.iceberg.TableMetadata;
import org.apache.iceberg.TableMetadataParser;
import org.apache.iceberg.io.FileInfo;
import org.apache.iceberg.io.SupportsPrefixOperations;

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
 * whole: the newest of its pointer's version and the older versions the work list names whose every file every
 * snapshot reaches is found in one listing of its location (D37, amended again 2026-11-20, item 99), so a table that
 * commits faster than its newest files reach the DR store still gets a first watermark; but never its empty creation
 * version below its pointer, which leaves a table that lost an old file with none, as before (Paul: "Never the empty
 * version"). Files are only read, never written. What a version adds is counted by catalog-iceberg's
 * {@link AddedFiles}, which the reconcile's walk counts with too.
 */
final class DrVerify {

    /** One version of an object's main pointer; {@code committedAt} when the journal says. */
    record Version(long version, String metadataLocation, Optional<Instant> committedAt) {
    }

    /**
     * One object of the catalog's DR work list ({@code GET /management/v1/maintenance/dr-work}).
     *
     * @param versions           the journaled versions above the watermark, oldest first, at most 100: without a
     *                           watermark, the newest, up to the pointer's
     * @param oldestUnverifiedAt when the oldest version above the watermark was committed, when the journal says
     */
    record Item(UUID id, String kind, String location, Version pointer, Optional<Version> watermark,
                List<Version> versions, Optional<Instant> oldestUnverifiedAt) {

        /** Whether it is checked whole, not version by version from its watermark. */
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
            // The watermark's own file is gone from the DR store: nothing builds on it, so check the versions whole.
            return whole(item);
        }
        AddedFiles.TableVersion safeMetadata = AddedFiles.read(watermark.metadataLocation(), this::bytes);
        boolean previousSafe = true;
        Optional<Version> safe = Optional.empty();
        Optional<String> missing = Optional.empty();
        for (Version version : item.versions()) {
            Optional<String> gap;
            AddedFiles.TableVersion metadata = null;
            if (!exists(version.metadataLocation())) {
                gap = Optional.of(version.metadataLocation());
            } else {
                metadata = AddedFiles.read(version.metadataLocation(), this::bytes);
                gap = previousSafe ? AddedFiles.firstMissing(safeMetadata, metadata, this::bytes, this::exists)
                        : AddedFiles.addsNothing(safeMetadata, metadata) ? Optional.empty()
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
        List<Version> versions = item.whole() ? candidates(item).reversed() : item.versions();
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

    /**
     * The newest whole version, newest first from the pointer's: every file its snapshots reach, found in one listing
     * of the table's location. A version whose metadata file the listing lacks is passed over unread; each other is
     * read once and passed over, without a walk of everything it reaches, when a file it adds to the next older one
     * held is missing, or when it reaches a file a newer one lacks (one missing but not among what the newer one adds
     * to it). So a walk of the whole table is made only for a version whose own files are all there: in-flight
     * versions fail on their own files, and a file lost long ago costs one walk, not one a version. A whole version
     * below the pointer that holds no data, the creation version a lost file leaves as the only one whole, gives none.
     * What is missing is the pointer's first gap.
     */
    private Result whole(Item item) {
        String root = Storage.normalize(item.location().endsWith("/") ? item.location() : item.location() + "/");
        Set<String> listed = new HashSet<>();
        for (FileInfo file : dr.listPrefix(item.location())) {
            listed.add(Storage.normalize(file.location()));
        }
        Predicate<String> present = location -> {
            String normalized = Storage.normalize(location);
            return normalized.startsWith(root) ? listed.contains(normalized) : exists(location);
        };
        List<Version> held = candidates(item).stream().filter(version -> present.test(version.metadataLocation()))
                .toList();
        Optional<String> missing = present.test(item.pointer().metadataLocation()) ? Optional.empty()
                : Optional.of(item.pointer().metadataLocation());
        Optional<String> carried = Optional.empty();
        AddedFiles.TableVersion metadata = held.isEmpty() ? null
                : AddedFiles.read(held.getFirst().metadataLocation(), this::bytes);
        for (int i = 0; i < held.size(); i++) {
            AddedFiles.TableVersion older = i + 1 < held.size()
                    ? AddedFiles.read(held.get(i + 1).metadataLocation(), this::bytes) : null;
            Optional<String> gap = older == null ? Optional.empty()
                    : AddedFiles.firstMissing(older, metadata, this::bytes, present);
            boolean addsAll = gap.isEmpty();
            if (gap.isEmpty()) {
                gap = carried;
            }
            if (gap.isEmpty()) {
                gap = AddedFiles.firstMissing(null, metadata, this::bytes, present);
                if (gap.isEmpty()) {
                    Version found = held.get(i);
                    // Never the empty creation version below the pointer (D37, amended again 2026-11-20): a rewind to
                    // it would empty a table that holds data since. At the pointer the table is that empty.
                    return found.version() < item.pointer().version() && empty(found)
                            ? new Result(Optional.empty(), missing) : new Result(Optional.of(found), missing);
                }
            }
            if (missing.isEmpty()) {
                missing = gap;
            }
            // A missing file it reaches but does not add to the older version, the older version reaches too.
            carried = addsAll ? gap : Optional.empty();
            metadata = older;
        }
        return new Result(Optional.empty(), missing);
    }

    /**
     * The versions a whole check tests, newest first: the pointer's, then the versions the work list names below it, at
     * most 100.
     */
    private static List<Version> candidates(Item item) {
        List<Version> candidates = new ArrayList<>();
        candidates.add(item.pointer());
        item.versions().reversed().stream().filter(version -> version.version() < item.pointer().version())
                .forEach(candidates::add);
        return candidates;
    }

    /**
     * Whether no snapshot of a version reaches a data or delete file: a table's creation version, with no snapshot or
     * an empty one (an empty create-as-select), and the schema and property changes before its first data. Its
     * metadata file is read again, then its manifest lists, newest first, until one lists a live file; a manifest that
     * does not count its files counts as holding some.
     */
    private boolean empty(Version version) {
        TableMetadata metadata = TableMetadataParser.read(dr, version.metadataLocation());
        return metadata.snapshots().reversed().stream().flatMap(snapshot -> snapshot.allManifests(dr).stream())
                .noneMatch(manifest -> manifest.hasAddedFiles() || manifest.hasExistingFiles());
    }

    private boolean exists(String location) {
        return dr.newInputFile(location).exists();
    }

    private byte[] bytes(String location) {
        try (InputStream in = dr.newInputFile(location).newStream()) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + location + " in the DR store", e);
        }
    }
}
