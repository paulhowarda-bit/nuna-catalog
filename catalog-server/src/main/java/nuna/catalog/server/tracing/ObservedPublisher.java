package nuna.catalog.server.tracing;

import io.micrometer.common.KeyValues;
import io.micrometer.observation.ObservationRegistry;
import nuna.catalog.audit.CatalogEvent;
import nuna.catalog.audit.publish.EventPublisher;

/**
 * The synchronous audit records (D23) with each publish observed as {@value #NAME} (docs/observability/spans.md),
 * from the send to Kafka's acknowledgement: a span named {@code audit publish} and a timer, tagged with the topic,
 * the event type and the outcome, {@code acknowledged} or {@code error}.
 */
public final class ObservedPublisher implements EventPublisher {

    public static final String NAME = "nuna.audit.publish";

    private final EventPublisher publisher;
    private final KeyValues topic;
    private final ObservationRegistry registry;

    public ObservedPublisher(EventPublisher publisher, String topic, ObservationRegistry registry) {
        this.publisher = publisher;
        this.topic = KeyValues.of("topic", topic);
        this.registry = registry;
    }

    @Override
    public void publish(CatalogEvent event) {
        Observed.call(registry, NAME, "audit publish", topic.and("event", event.type().typeName()), () -> {
            publisher.publish(event);
            return null;
        }, acknowledged -> "acknowledged");
    }
}
