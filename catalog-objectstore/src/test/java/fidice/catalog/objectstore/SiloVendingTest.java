package fidice.catalog.objectstore;

/** The vending gate against Silo, whose STS derives sessions from the calling user, as MinIO does (research F5). */
class SiloVendingTest extends VendingContract {

    @Override
    protected RunningStore start() {
        return RunningStore.silo();
    }
}
