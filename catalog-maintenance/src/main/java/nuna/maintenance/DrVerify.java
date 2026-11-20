package nuna.maintenance;

import nuna.catalog.iceberg.AddedFiles;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
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
 * whole at its pointer's version: every file every snapshot of that version reaches, found in one listing of its
 * location. Files are only read, never written. What a version adds is counted by catalog-iceberg's
 * {@link AddedFiles}, which the reconcile's walk counts with too.
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
        AddedFiles.TableVersion metadata = AddedFiles.read(pointer.metadataLocation(), this::bytes);
        Optional<String> gap = AddedFiles.firstMissing(null, metadata, this::bytes, present);
        return gap.isPresent() ? new Result(Optional.empty(), gap) : new Result(Optional.of(pointer), Optional.empty());
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
