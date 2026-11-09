package fidice.catalog.objectstore;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * The vending gate against real AWS S3 and STS (research F6: emulators ignore session policies). Runs
 * only when {@code -Dfidice.aws.profile} names a CLI profile that has been signed in with short-lived
 * credentials; it creates two buckets and one role, and deletes them at the end. Heavy, since it waits
 * for IAM to accept the new role:
 *
 * <pre>./mvnw test -pl catalog-objectstore -Pheavy -Dtest=AwsVendingTest -Dfidice.aws.profile=fidice-sandbox</pre>
 */
@Tag("heavy")
@EnabledIfSystemProperty(named = "fidice.aws.profile", matches = ".+")
class AwsVendingTest extends VendingContract {

    @Override
    protected RunningStore start() {
        return RunningStore.aws(System.getProperty("fidice.aws.profile"));
    }
}
