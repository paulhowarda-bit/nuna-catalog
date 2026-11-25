package nuna.catalog.server.tracing;

import io.micrometer.common.KeyValues;
import io.micrometer.observation.ObservationRegistry;
import java.time.Duration;
import java.util.List;
import nuna.catalog.audit.StampedEvent;
import nuna.catalog.audit.publish.EventPublisher;

/**
 * The synchronous audit records (D23) with each publish observed as {@value #NAME} (docs/observability/spans.md),
 * from the send to Kafka's acknowledgement: a span named {@code audit publish} and a timer, tagged with the topic,
 * the event type and the outcome, {@code acknowledged} or {@code error}. The timer has histogram buckets at
 * {@link #BUCKETS} ({@link TracingWiring}).
 */
public final class ObservedPublisher implements EventPublisher {

    public static final String NAME = "nuna.audit.publish";
    /** The timer's histogram bucket bounds, the console health page's: from 5 ms to 5 s. */
    public static final List<Duration> BUCKETS = List.of(Duration.ofMillis(5), Duration.ofMillis(10),
            Duration.ofMillis(25), Duration.ofMillis(50), Duration.ofMillis(100), Duration.ofMillis(250),
            Duration.ofMillis(500), Duration.ofSeconds(1), Duration.ofMillis(2500), Duration.ofSeconds(5));

    private final EventPublisher publisher;
    private final KeyValues topic;
    private final ObservationRegistry registry;

    public ObservedPublisher(EventPublisher publisher, String topic, ObservationRegistry registry) {
        this.publisher = publisher;
        this.topic = KeyValues.of("topic", topic);
        this.registry = registry;
    }

    @Override
    public void publish(StampedEvent stamped) {
        Observed.call(registry, NAME, "audit publish", topic.and("event", stamped.event().type().typeName()), () -> {
            publisher.publish(stamped);
            return null;
        }, acknowledged -> "acknowledged");
    }
}
