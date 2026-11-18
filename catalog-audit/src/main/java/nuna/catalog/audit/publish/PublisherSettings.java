package nuna.catalog.audit.publish;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;

/**
 * Where and how a {@link KafkaEventPublisher} publishes.
 *
 * @param bootstrapServers the Kafka bootstrap list
 * @param topic            the topic every event goes to
 * @param deliveryTimeout  the longest one publish may take before it fails closed
 * @param requestTimeout   the longest one produce request may take before the client retries it
 *                         within the delivery timeout
 * @param clientProperties further Kafka client settings (TLS, SASL); they may not override the
 *                         settings the publisher owns
 */
public record PublisherSettings(String bootstrapServers, String topic, Duration deliveryTimeout,
                                Duration requestTimeout, Map<String, String> clientProperties) {

    public PublisherSettings {
        Objects.requireNonNull(bootstrapServers, "bootstrapServers");
        Objects.requireNonNull(topic, "topic");
        Objects.requireNonNull(deliveryTimeout, "deliveryTimeout");
        Objects.requireNonNull(requestTimeout, "requestTimeout");
        clientProperties = Map.copyOf(clientProperties);
    }
}
