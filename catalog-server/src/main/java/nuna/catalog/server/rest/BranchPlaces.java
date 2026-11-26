package nuna.catalog.server.rest;

import nuna.catalog.store.CatalogObject;
import java.util.Map;
import java.util.UUID;

/**
 * Where a writer on one branch only writes a table's files (backlog 113, security finding 11): beneath
 * {@code <table root>/branches/<ref id>/}, its data in {@code data} and its manifests in {@code metadata}. Its vended
 * credentials write only there, and its load tells it so through Iceberg's {@code write.data.path} and
 * {@code write.metadata.path}, so it never needs to write main's files. The catalog writes the branch's metadata files
 * itself, marked with the branch's id, as for any writer.
 */
final class BranchPlaces {

    static final String DATA_PATH = "write.data.path";
    static final String METADATA_PATH = "write.metadata.path";

    private BranchPlaces() {
    }

    /** The branch's place, beneath the table's root. */
    static String under(UUID refId) {
        return "branches/" + refId;
    }

    /** The table properties a load answers a writer on the branch only with; none for any other. */
    static Map<String, String> of(OnRef on, CatalogObject object, Guard.Writes writes) {
        if (writes != Guard.Writes.BRANCH) {
            return Map.of();
        }
        String place = object.locationRoot() + "/" + under(on.refId().orElseThrow());
        return Map.of(DATA_PATH, place + "/data", METADATA_PATH, place + "/metadata");
    }
}
