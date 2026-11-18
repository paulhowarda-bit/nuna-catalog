package nuna.catalog.core.commit;

import java.util.List;
import java.util.UUID;

/**
 * Metadata files in object storage (build-plan 2.2 steps 4 and 6), as the commit engine needs them.
 * catalog-objectstore implements it for one storage profile, so catalog-core carries no storage SDK.
 */
public interface TableFiles {

    /** Where a new table or view lives: a short path the catalog assigns, never derived from the namespace (D28). */
    String newLocation(UUID objectId);

    /**
     * Writes a new metadata file under {@code locationRoot} at a name no other write uses, for pointer
     * version {@code version}, and returns where it went and the SHA-256 of its bytes. The root must be
     * the one the catalog recorded for the object, never one read from the metadata.
     */
    Written write(String locationRoot, long version, String json);

    /** Reads a metadata file and hashes the bytes read; {@code intact} says whether they match {@code expectedSha256}. */
    Read read(String location, String expectedSha256);

    /** Whether {@code location} lies strictly beneath {@code root}, judged on canonical locations. */
    boolean isWithin(String location, String root);

    /** The metadata files directly in {@code locationRoot}'s metadata directory, in no particular order. */
    List<String> metadataFiles(String locationRoot);

    /** Every table or view location beneath the table root, each the root a {@link #newLocation} gave out. */
    List<String> tableLocations();

    /** @param sha256 lower-case hex */
    record Written(String location, String sha256) {
    }

    /** @param sha256 of the bytes read, lower-case hex */
    record Read(String json, String sha256, boolean intact) {
    }
}
