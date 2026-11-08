package fidice.catalog.objectstore;

/** Metadata file I/O against Ceph RGW, the open on-prem store (research F7). */
class CephMetadataFilesTest extends MetadataFilesContract {

    @Override
    protected RunningStore start() {
        return RunningStore.ceph();
    }
}
