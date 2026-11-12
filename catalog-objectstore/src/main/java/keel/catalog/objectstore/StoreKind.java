package keel.catalog.objectstore;

/** The object stores a storage profile can point at (D21, D36a). */
public enum StoreKind {
    S3,
    MINIO,
    CEPH;

    /**
     * On-prem stores are addressed by path ({@code endpoint/bucket/key}), since their endpoints
     * rarely carry a wildcard DNS name per bucket. AWS S3 is addressed by virtual host.
     */
    public boolean pathStyle() {
        return this != S3;
    }
}
