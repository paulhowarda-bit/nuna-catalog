package nuna.catalog.audit.publish;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import nuna.catalog.audit.LogStamp;
import nuna.catalog.audit.EventCodec;
import nuna.catalog.audit.SampleEvents;
import nuna.catalog.audit.StampedEvent;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** D23 and the M7 gate: while audit is unavailable, credentials are not handed out. */
@Tag("heavy")
class BrokerOutageTest {

    private static final Duration DELIVERY = Duration.ofSeconds(4);
    private static final Duration SLACK = Duration.ofMillis(1500);

    private static final UUID RUN = UUID.randomUUID();

    private static KafkaBroker broker;

    @BeforeAll
    static void startBroker() {
        broker = new KafkaBroker();
    }

    @AfterAll
    static void stopBroker() {
        broker.close();
    }

    private static StampedEvent event(int index, int n) {
        return new StampedEvent(SampleEvents.event(SampleEvents.oneOfEachType().get(index), n), new LogStamp(RUN, n));
    }

    @Test
    void credentialIssueFailsClosedWhileTheBrokerIsDownAndPublishingResumesAfter() {
        String topic = broker.newTopic();
        StampedEvent before = event(4, 1);
        StampedEvent credentialIssue = event(7, 2);
        StampedEvent after = event(4, 3);
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
        assertThat(ids).startsWith(before.event().id()).endsWith(after.event().id());
        // The refused event may still land once, from a request the frozen broker had buffered.
        assertThat(ids.stream().filter(credentialIssue.event().id()::equals).count()).isLessThanOrEqualTo(1);
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
