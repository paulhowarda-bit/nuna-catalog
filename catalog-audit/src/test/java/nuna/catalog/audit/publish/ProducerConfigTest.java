package nuna.catalog.audit.publish;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Map;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ProducerConfigTest {

    private static PublisherSettings settings(Map<String, String> clientProperties) {
        return new PublisherSettings("broker:9092", "audit", Duration.ofSeconds(5), Duration.ofSeconds(2),
                clientProperties);
    }

    @Test
    void everyInSyncReplicaMustAcknowledge() {
        assertThat(KafkaEventPublisher.producerConfig(settings(Map.of())))
                .containsEntry(ProducerConfig.ACKS_CONFIG, "all");
    }

    @Test
    void productionIsIdempotent() {
        assertThat(KafkaEventPublisher.producerConfig(settings(Map.of())))
                .containsEntry(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, "true");
    }

    @Test
    void timeoutsComeFromTheSettings() {
        assertThat(KafkaEventPublisher.producerConfig(settings(Map.of())))
                .containsEntry(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "broker:9092")
                .containsEntry(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, "5000")
                .containsEntry(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, "2000")
                .containsEntry(ProducerConfig.MAX_BLOCK_MS_CONFIG, "5000")
                .containsEntry(ProducerConfig.LINGER_MS_CONFIG, "0");
    }

    @Test
    void clientPropertiesPassThrough() {
        assertThat(KafkaEventPublisher.producerConfig(settings(Map.of("security.protocol", "SSL"))))
                .containsEntry("security.protocol", "SSL");
    }

    @Test
    void settingsRequireServersTopicAndTimeouts() {
        Duration second = Duration.ofSeconds(1);
        assertThatThrownBy(() -> new PublisherSettings(null, "audit", second, second, Map.of()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new PublisherSettings("broker:9092", null, second, second, Map.of()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new PublisherSettings("broker:9092", "audit", null, second, Map.of()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new PublisherSettings("broker:9092", "audit", second, null, Map.of()))
                .isInstanceOf(NullPointerException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"acks", "enable.idempotence", "delivery.timeout.ms", "request.timeout.ms", "max.block.ms",
            "linger.ms", "bootstrap.servers", "key.serializer", "value.serializer"})
    void clientPropertiesCannotOverrideOwnedSettings(String key) {
        assertThatThrownBy(() -> KafkaEventPublisher.producerConfig(settings(Map.of(key, "1"))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining(key);
    }
}
