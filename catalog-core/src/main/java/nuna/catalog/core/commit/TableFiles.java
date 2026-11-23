package nuna.catalog.core.commit;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Metadata files in object storage (build-plan 2.2 steps 4 and 6), as the commit engine needs them.
 * catalog-objectstore implements it for one storage profile, so catalog-core carries no storage SDK.
 */
public interface TableFiles {

    /**
     * What the name of a metadata file a commit on a branch writes carries, then the branch's id (D33): see
     * {@link #fileName}. Storage alone tells such a file from main's, as a DR reconcile reads storage after a
     * failover, when the store may not know a branch's latest commits: main's chain takes one only where main's
     * pointer, or a commit on main the archive proves (a merge), names it.
     */
    String BRANCH_MARK = ".branch-";

    /** Where a new table or view lives: a short path the catalog assigns, never derived from the namespace (D28). */
    String newLocation(UUID objectId);

    /** {@link #write(String, long, String, Optional)} for a commit on main. */
    default Written write(String locationRoot, long version, String json) {
        return write(locationRoot, version, json, Optional.empty());
    }

    /**
     * Writes a new metadata file under {@code locationRoot} at a name no other write uses ({@link #fileName}), for
     * pointer version {@code version}, and returns where it went and the SHA-256 of its bytes. The root must be
     * the one the catalog recorded for the object, never one read from the metadata.
     *
     * @param branch the id of the branch the commit is on; empty on main
     */
    Written write(String locationRoot, long version, String json, Optional<UUID> branch);

    /**
     * Iceberg's file naming, so engines and tools recognise the file: {@code 00042-<uuid>.metadata.json} on main,
     * and on a branch {@code 00042-<uuid>.branch-<branch id>.metadata.json} ({@link #BRANCH_MARK}).
     */
    static String fileName(long version, Optional<UUID> branch) {
        return String.format(Locale.ROOT, "%05d-%s%s.metadata.json", version, UUID.randomUUID(),
                branch.map(id -> BRANCH_MARK + id).orElse(""));
    }

    /** Whether the metadata file at {@code location} was written by a commit on a branch, by its name alone. */
    static boolean onBranch(String location) {
        return location.substring(location.lastIndexOf('/') + 1).contains(BRANCH_MARK);
    }

    /** Reads a metadata file and hashes the bytes read; {@code intact} says whether they match {@code expectedSha256}. */
    Read read(String location, String expectedSha256);

    /** Whether {@code location} lies strictly beneath {@code root}, judged on canonical locations. */
    boolean isWithin(String location, String root);

    /** The metadata files directly in {@code locationRoot}'s metadata directory, in no particular order. */
    List<String> metadataFiles(String locationRoot);

    /** Every table or view location beneath the table root, each the root a {@link #newLocation} gave out. */
    List<String> tableLocations();

    /**
     * Whether storage holds a file at {@code location}: any file of a table, data included. The DR reconcile's walk
     * asks it of each file a step adds (D37, amended 2026-11-20).
     */
    boolean exists(String location);

    /** Any file of a table, read whole: the DR reconcile's walk reads manifest lists and manifests with it. */
    byte[] bytes(String location);

    /** @param sha256 lower-case hex */
    record Written(String location, String sha256) {
    }

    /** @param sha256 of the bytes read, lower-case hex */
    record Read(String json, String sha256, boolean intact) {
    }
}
