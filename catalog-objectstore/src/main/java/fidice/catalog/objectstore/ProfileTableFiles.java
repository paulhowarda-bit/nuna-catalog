package fidice.catalog.objectstore;

import fidice.catalog.core.commit.TableFiles;
import java.util.UUID;

/**
 * The commit engine's metadata files for one storage profile. Files that fail their hash come back
 * flagged, not refused: the engine decides from the object's governance whether to fail closed (D26).
 */
public final class ProfileTableFiles implements TableFiles {

    private final StorageProfile profile;
    private final MetadataFiles files;

    /** {@code files} must be open on {@code profile}; the caller closes it. */
    public ProfileTableFiles(StorageProfile profile, MetadataFiles files) {
        this.profile = profile;
        this.files = files;
    }

    @Override
    public String newLocation(UUID objectId) {
        return profile.newTableLocation(objectId);
    }

    @Override
    public Written write(String locationRoot, long version, String json) {
        StoredMetadata stored = files.write(locationRoot, version, json);
        return new Written(stored.location(), stored.sha256());
    }

    @Override
    public Read read(String location, String expectedSha256) {
        LoadedMetadata loaded = files.read(location, expectedSha256, OnMismatch.FLAG);
        return new Read(loaded.json(), loaded.sha256(), loaded.intact());
    }

    /** False for anything that is not a canonical {@code s3://} location, which can lie within nothing. */
    @Override
    public boolean isWithin(String location, String root) {
        try {
            return ObjectLocation.parse(location).isBeneath(ObjectLocation.parse(root));
        } catch (LocationRejectedException e) {
            return false;
        }
    }
}
