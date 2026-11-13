package keel.catalog.server.governance;

import keel.catalog.server.IssuerForTests;
import keel.catalog.server.PostgresForTests;
import keel.catalog.server.S3ForTests;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * The server every M7 server test shares (one Spring context): PostgreSQL, the run's S3 store and a Kafka broker,
 * M5's test issuer, M6's credential vending on that store, and a clock tests can move forward. {@link Api}
 * signs each request in as the caller its {@code X-Test-Principal} pseudo-header names.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "keel.audit.delivery-timeout=3s",
        "keel.audit.request-timeout=1s",
        "keel.audit.probe-interval=1s",
        "keel.audit.relay-interval=100ms",
        "keel.security.maintenance-principals=maintenance,maintenance-2",
        "keel.governance.purges-per-minute=3",
        "keel.governance.retention-classes.trade-records.minimum-snapshot-age=30d",
        "keel.security.grant-admin-groups=" + GovernanceServer.GRANT_ADMINS,
        "keel.security.platform-operator-groups=" + GovernanceServer.PLATFORM_OPS,
})
@ContextConfiguration(initializers = IssuerForTests.class)
@Import({PostgresForTests.class, S3ForTests.class, GovernanceServer.Beans.class})
public @interface GovernanceServer {

    /** A pseudo-header {@link Api} turns into a token for this principal; the default is {@code tester}. */
    String PRINCIPAL = "X-Test-Principal";
    /** A pseudo-header {@link Api} turns into a token from the registered trusted engine. */
    String ENGINE = "X-Test-Engine";
    /** A pseudo-header naming the token's groups, comma-separated; the default is the test group. */
    String GROUPS = "X-Test-Groups";
    String GRANT_ADMINS = "grant-admins";
    String PLATFORM_OPS = "platform-ops";
    /** Tag stewards: {@code manage-tags} on the test catalog, nothing else. */
    String STEWARDS = "stewards";

    /** The test beans; the clock is a {@link TestClock}, the broker a {@link Broker}. */
    @TestConfiguration(proxyBeanMethods = false)
    class Beans {

        @Bean(destroyMethod = "close")
        Broker broker() {
            return new Broker();
        }

        @Bean
        DynamicPropertyRegistrar auditProperties(Broker broker) {
            return registry -> registry.add("keel.audit.kafka-bootstrap-servers", broker::bootstrapServers);
        }

        @Bean
        @Primary
        TestClock testClock() {
            return new TestClock();
        }
    }

    /** One Kafka broker whose container the outage test pauses. */
    final class Broker implements AutoCloseable {

        private final KafkaContainer kafka = new KafkaContainer(DockerImageName.parse(System.getProperty("kafka.image")))
                .withEnv("KAFKA_HEAP_OPTS", "-Xms256m -Xmx256m");

        Broker() {
            kafka.start();
        }

        String bootstrapServers() {
            return kafka.getBootstrapServers();
        }

        void pause() {
            DockerClientFactory.instance().client().pauseContainerCmd(kafka.getContainerId()).exec();
        }

        void unpause() {
            DockerClientFactory.instance().client().unpauseContainerCmd(kafka.getContainerId()).exec();
        }

        @Override
        public void close() {
            kafka.stop();
        }
    }

    /** The wall clock, moved forward by what a test adds. */
    final class TestClock extends Clock {

        private volatile Duration offset = Duration.ZERO;

        void advance(Duration duration) {
            offset = offset.plus(duration);
        }

        @Override
        public Instant instant() {
            return Instant.now().plus(offset);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}
