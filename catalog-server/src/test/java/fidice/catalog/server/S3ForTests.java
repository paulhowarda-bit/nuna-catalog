package fidice.catalog.server;

import fidice.catalog.tests.s3.TestS3;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;

/**
 * The run's S3 store ({@link TestS3}, chosen by {@code -Ds3}) for server tests: storage profile
 * {@value #PROFILE}, with tables under {@code s3://<bucket()>/tables}, and the catalog {@code trading} on it.
 */
@TestConfiguration(proxyBeanMethods = false)
public class S3ForTests {

    /** The storage profile's name. */
    public static final String PROFILE = "s3";

    /** The run's store; it outlives every Spring context and is removed when the JVM exits. */
    @Bean(destroyMethod = "")
    TestS3 testS3() {
        return TestS3.get();
    }

    @Bean
    DynamicPropertyRegistrar storageProperties(TestS3 s3) {
        return registry -> {
            s3.serverProperties(PROFILE, bucket()).forEach((name, value) -> registry.add(name, () -> value));
            registry.add("fidice.storage.catalogs.trading.profile", () -> PROFILE);
            registry.add("fidice.storage.catalogs.trading.kms-key-set", () -> "test-keys");
            registry.add("fidice.storage.catalogs.trading.legal-entity", () -> "Test Entity");
        };
    }

    /** The bucket tables live in: {@code warehouse}, or on AWS this run's name for it. */
    public static String bucket() {
        return TestS3.get().bucket("warehouse");
    }
}
