package nuna.catalog.audit.publish;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import nuna.catalog.audit.BreakGlass;
import nuna.catalog.audit.CatalogEvent;
import nuna.catalog.audit.EventCodec;
import nuna.catalog.audit.SampleEvents;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("heavy")
class KafkaPublishingTest {

    private static KafkaBroker broker;

    @BeforeAll
    static void startBroker() {
        broker = new KafkaBroker();
    }

    @AfterAll
    static void stopBroker() {
        broker.close();
    }

    @AfterEach
    void healLink() {
        broker.healLink();
    }

    private static KafkaEventPublisher publisher(String bootstrap, String topic, Duration delivery, Duration request) {
        return new KafkaEventPublisher(new PublisherSettings(bootstrap, topic, delivery, request, Map.of()));
    }

    private static List<CatalogEvent> numbered(int count) {
        return IntStream.range(0, count)
                .mapToObj(i -> SampleEvents.event(SampleEvents.oneOfEachType().get(i % 8), 1000 + i)).toList();
    }

    @Test
    void eachEventArrivesAsAStructuredCloudEventKeyedByItsSubject() {
        String topic = broker.newTopic();
        List<CatalogEvent> events = SampleEvents.events();
        try (KafkaEventPublisher publisher = publisher(broker.direct(), topic, Duration.ofSeconds(10),
                Duration.ofSeconds(3))) {
            events.forEach(publisher::publish);
        }

        List<ConsumerRecord<byte[], byte[]>> records = broker.readAll(topic);
        assertThat(records).hasSize(events.size());
        for (int i = 0; i < events.size(); i++) {
            CatalogEvent event = events.get(i);
            ConsumerRecord<byte[], byte[]> record = records.get(i);
            assertThat(record.value()).isEqualTo(EventCodec.encode(event));
            assertThat(EventCodec.decode(record.value())).isEqualTo(event);
            assertThat(record.key()).isEqualTo(event.subject() == null ? null : event.subject().getBytes(UTF_8));
            Header contentType = record.headers().lastHeader("content-type");
            assertThat(new String(contentType.value(), UTF_8)).isEqualTo("application/cloudevents+json; charset=UTF-8");
        }
    }

    @Test
    void batchIsDeliveredInOrder() {
        String topic = broker.newTopic();
        List<CatalogEvent> batch = numbered(40);
        try (KafkaEventPublisher publisher = publisher(broker.direct(), topic, Duration.ofSeconds(10),
                Duration.ofSeconds(3))) {
            publisher.deliver(batch);
        }

        assertThat(ids(broker.readAll(topic))).containsExactlyElementsOf(batch.stream().map(CatalogEvent::id).toList());
    }

    @Test
    void deliveryStopsAtTheFirstEventKafkaRefuses() {
        String topic = broker.newTopic();
        List<CatalogEvent> batch = new ArrayList<>(numbered(10));
        // Larger than the producer's default max.request.size (1 MiB): Kafka refuses it.
        batch.set(4, SampleEvents.event(new BreakGlass(SampleEvents.TABLE, "x".repeat(2 * 1024 * 1024),
                SampleEvents.ACTOR), 4));
        try (KafkaEventPublisher publisher = publisher(broker.direct(), topic, Duration.ofSeconds(10),
                Duration.ofSeconds(3))) {
            assertThatThrownBy(() -> publisher.deliver(batch)).isInstanceOf(AuditUnavailableException.class)
                    .hasMessageContaining(batch.get(4).id().toString());
        }

        assertThat(ids(broker.readAll(topic)))
                .containsExactlyElementsOf(batch.subList(0, 4).stream().map(CatalogEvent::id).toList());
    }

    @Test
    void sendRetriedAfterALostResponseIsWrittenOnce() throws InterruptedException {
        String topic = broker.newTopic();
        CatalogEvent warmUp = numbered(1).get(0);
        CatalogEvent event = SampleEvents.event(SampleEvents.oneOfEachType().get(0), 2000);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        try (KafkaEventPublisher publisher = publisher(broker.linkAddress(), topic, Duration.ofSeconds(40),
                Duration.ofMillis(1500))) {
            publisher.publish(warmUp);
            broker.dropResponses();
            Thread sender = Thread.ofVirtual().start(() -> {
                try {
                    publisher.publish(event);
                } catch (Throwable t) {
                    failure.set(t);
                }
            });
            // Long enough for the request to reach the broker, time out and be retried at least once.
            Thread.sleep(4000);
            broker.healLink();
            sender.join(Duration.ofSeconds(40));
        }

        assertThat(failure.get()).isNull();
        assertThat(ids(broker.readAll(topic))).containsExactly(warmUp.id(), event.id());
    }

    @Test
    void publishFailsClosedWithinItsDeliveryTimeoutWhenResponsesAreSlow() {
        String topic = broker.newTopic();
        Duration delivery = Duration.ofMillis(2500);
        broker.delayResponses(Duration.ofMillis(1000));
        try (KafkaEventPublisher publisher = publisher(broker.linkAddress(), topic, delivery, delivery)) {
            long start = System.nanoTime();
            assertThatThrownBy(() -> publisher.publish(numbered(1).get(0)))
                    .isInstanceOf(AuditUnavailableException.class);
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(delivery.plusMillis(700));
        }
    }

    @Test
    void interruptedPublishFailsClosedAndKeepsTheInterrupt() {
        String topic = broker.newTopic();
        try (KafkaEventPublisher publisher = publisher(broker.direct(), topic, Duration.ofSeconds(10),
                Duration.ofSeconds(3))) {
            publisher.publish(numbered(1).get(0));
            Thread.currentThread().interrupt();
            assertThatThrownBy(() -> publisher.publish(numbered(2).get(1)))
                    .isInstanceOf(AuditUnavailableException.class);
            assertThat(Thread.interrupted()).isTrue();
        }
    }

    private static List<UUID> ids(List<ConsumerRecord<byte[], byte[]>> records) {
        return records.stream().map(r -> EventCodec.decode(r.value()).id()).toList();
    }
}
