package fidice.catalog.core.commit;

import java.util.UUID;

/** Conditions the commit engine lets through but someone must look at. */
public interface Alerts {

    /**
     * A metadata file no longer hashes to the SHA-256 its pointer recorded (D5, D26). For a regulated
     * object the engine then fails closed; otherwise it goes on with the file as read.
     */
    void metadataIntegrity(UUID objectId, String location, String expectedSha256, String actualSha256);

    /** A commit changed the schema or partition spec of a table in a regulated namespace (D11a). */
    void regulatedSchemaChange(UUID objectId, UUID commitId);
}
