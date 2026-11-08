package fidice.catalog.objectstore;

/** Metadata file I/O against Silo, which keeps MinIO's S3 behaviour (research F5, 5.14). */
class SiloMetadataFilesTest extends MetadataFilesContract {

    @Override
    protected RunningStore start() {
        return RunningStore.silo();
    }
}
