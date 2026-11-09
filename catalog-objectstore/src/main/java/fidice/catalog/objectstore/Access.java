package fidice.catalog.objectstore;

import java.util.List;

/**
 * What vended credentials may do inside their table's prefix (D21, D25). Only maintenance principals
 * get delete, so a client-side purge fails for everyone else.
 */
public enum Access {
    READ(List.of("s3:GetObject")),
    READ_WRITE(List.of("s3:GetObject", "s3:PutObject", "s3:AbortMultipartUpload", "s3:ListMultipartUploadParts")),
    READ_WRITE_DELETE(List.of("s3:GetObject", "s3:PutObject", "s3:AbortMultipartUpload",
            "s3:ListMultipartUploadParts", "s3:DeleteObject"));

    private final List<String> objectActions;

    Access(List<String> objectActions) {
        this.objectActions = objectActions;
    }

    /** The S3 actions allowed on objects beneath the prefix. Multipart upload parts count as s3:PutObject. */
    List<String> objectActions() {
        return objectActions;
    }
}
