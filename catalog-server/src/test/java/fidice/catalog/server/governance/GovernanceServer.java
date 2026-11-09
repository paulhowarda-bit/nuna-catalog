package fidice.catalog.server.governance;

import fidice.catalog.core.principal.Caller;
import fidice.catalog.core.principal.Principal;
import fidice.catalog.iceberg.RestResponse.StorageCredential;
import fidice.catalog.server.PostgresForTests;
import fidice.catalog.server.SiloForTests;
import fidice.catalog.server.rest.Callers;
import fidice.catalog.server.rest.CredentialVending;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * The server every M7 server test shares (one Spring context): PostgreSQL, Silo and a Kafka broker,
 * a caller named by the {@code X-Test-Principal} header, a clock tests can move forward, and
 * credential vending that hands out one credential when the delegation header asks for it.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "fidice.audit.delivery-timeout=3s",
        "fidice.audit.request-timeout=1s",
        "fidice.audit.probe-interval=1s",
        "fidice.audit.relay-interval=100ms",
        "fidice.governance.maintenance-principals=maintenance",
        "fidice.governance.purges-per-minute=3",
        "fidice.governance.retention-classes.trade-records.minimum-snapshot-age=30d",
})
@Import({PostgresForTests.class, SiloForTests.class, GovernanceServer.Beans.class})
public @interface GovernanceServer {

    String PRINCIPAL = "X-Test-Principal";
    String VEND = "vended-credentials";

    /** The test beans; the clock is a {@link TestClock}, the broker a {@link Broker}. */
    @TestConfiguration(proxyBeanMethods = false)
    class Beans {

        @Bean(destroyMethod = "close")
        Broker broker() {
            return new Broker();
        }

        @Bean
        DynamicPropertyRegistrar auditProperties(Broker broker) {
            return registry -> registry.add("fidice.audit.kafka-bootstrap-servers", broker::bootstrapServers);
        }

        @Bean
        @Primary
        TestClock testClock() {
            return new TestClock();
        }

        @Bean
        @Primary
        Callers testCallers() {
            return request -> {
                String principal = request.getHeader(PRINCIPAL);
                return principal == null ? Callers.ANONYMOUS_CALLER
                        : new Caller(new Principal(principal, Set.of()), "test", false, Optional.empty());
            };
        }

        @Bean
        @Primary
        CredentialVending testVending() {
            return (catalogId, tableId, location, delegation) -> delegation.filter(VEND::equals).isPresent()
                    ? new CredentialVending.Vended(Map.of(), List.of(new StorageCredential(location,
                    Map.of("s3.session-token", "test"))), "test-epoch", Optional.of(Instant.now().plusSeconds(3600)))
                    : new CredentialVending.Vended(Map.of(), List.of(), "none", Optional.empty());
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
