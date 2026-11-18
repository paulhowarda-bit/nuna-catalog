package nuna.catalog.objectstore;

import java.util.List;

/**
 * What vended credentials may do inside their table's prefix (D21). None of them deletes, a maintenance
 * principal's included: the maintenance worker deletes with its own storage identity, which the store's
 * {@link DeletePolicy} alone lets delete (D30), so a client-side purge fails for everyone.
 */
public enum Access {
    READ(List.of("s3:GetObject")),
    READ_WRITE(List.of("s3:GetObject", "s3:PutObject", "s3:AbortMultipartUpload", "s3:ListMultipartUploadParts"));

    private final List<String> objectActions;

    Access(List<String> objectActions) {
        this.objectActions = objectActions;
    }

    /** The S3 actions allowed on objects beneath the prefix. Multipart upload parts count as s3:PutObject. */
    List<String> objectActions() {
        return objectActions;
    }
}
