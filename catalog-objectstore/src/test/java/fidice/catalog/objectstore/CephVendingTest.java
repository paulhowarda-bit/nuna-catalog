package fidice.catalog.objectstore;

/** The vending gate against Ceph RGW, with STS and a role that allows every S3 action (research F7). */
class CephVendingTest extends VendingContract {

    @Override
    protected RunningStore start() {
        return RunningStore.ceph();
    }
}
