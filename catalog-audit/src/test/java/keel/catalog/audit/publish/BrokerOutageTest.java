package keel.catalog.audit.publish;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import keel.catalog.audit.CatalogEvent;
import keel.catalog.audit.EventCodec;
import keel.catalog.audit.SampleEvents;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** D23 and the M7 gate: while audit is unavailable, credentials are not handed out. */
class BrokerOutageTest {

    private static final Duration DELIVERY = Duration.ofSeconds(4);
    private static final Duration SLACK = Duration.ofMillis(1500);

    private static KafkaBroker broker;

    @BeforeAll
    static void startBroker() {
        broker = new KafkaBroker();
    }

    @AfterAll
    static void stopBroker() {
        broker.close();
    }

    private static CatalogEvent event(int index, int n) {
        return SampleEvents.event(SampleEvents.oneOfEachType().get(index), n);
    }

    @Test
    void credentialIssueFailsClosedWhileTheBrokerIsDownAndPublishingResumesAfter() {
        String topic = broker.newTopic();
        CatalogEvent before = event(4, 1);
        CatalogEvent credentialIssue = event(7, 2);
        CatalogEvent after = event(4, 3);
        AtomicBoolean handedOut = new AtomicBoolean();
        try (KafkaEventPublisher publisher = new KafkaEventPublisher(new PublisherSettings(broker.direct(), topic,
                DELIVERY, Duration.ofSeconds(1), Map.of()))) {
            publisher.publish(before);
            broker.pause();
            try {
                long start = System.nanoTime();
                assertThatThrownBy(() -> {
                    publisher.publish(credentialIssue);
                    handedOut.set(true);
                }).isInstanceOf(AuditUnavailableException.class);
                assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(DELIVERY.plus(SLACK));
            } finally {
                broker.unpause();
            }
            publisher.publish(after);
        }

        assertThat(handedOut).isFalse();
        List<UUID> ids = broker.readAll(topic).stream().map(r -> EventCodec.decode(r.value()).id()).toList();
        assertThat(ids).startsWith(before.id()).endsWith(after.id());
        // The refused event may still land once, from a request the frozen broker had buffered.
        assertThat(ids.stream().filter(credentialIssue.id()::equals).count()).isLessThanOrEqualTo(1);
    }

    @Test
    void publisherThatNeverReachedABrokerFailsClosed() {
        try (KafkaEventPublisher publisher = new KafkaEventPublisher(new PublisherSettings("localhost:1", "audit",
                DELIVERY, Duration.ofSeconds(1), Map.of()))) {
            long start = System.nanoTime();
            assertThatThrownBy(() -> publisher.publish(event(7, 4))).isInstanceOf(AuditUnavailableException.class);
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(DELIVERY.plus(SLACK));
        }
    }
}
