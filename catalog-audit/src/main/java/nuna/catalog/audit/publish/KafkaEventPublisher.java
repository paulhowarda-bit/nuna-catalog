package nuna.catalog.audit.publish;

import static java.nio.charset.StandardCharsets.UTF_8;

import nuna.catalog.audit.EventCodec;
import nuna.catalog.audit.StampedEvent;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.serialization.ByteArraySerializer;

/**
 * Publishes events to one Kafka topic and waits for every in-sync replica to acknowledge each
 * one ({@code acks=all}). Production is idempotent, so a retried send is written once. A publish
 * that is not acknowledged within the delivery timeout throws; the catalog's audit trail then has the record in its
 * log only (D23, amended 2026-11-23).
 *
 * <p>Each record is a CloudEvents structured-mode message: the record as the log {@code nuna.audit} holds it, its
 * run and number included ({@link StampedEvent#encode}, item 276), as the value, its subject as the key (so one
 * object's events keep their order), and the CloudEvents media type as the {@code content-type} header. Safe for
 * concurrent use.
 */
public final class KafkaEventPublisher implements EventPublisher, StampedTarget, AutoCloseable {

    static final String CONTENT_TYPE_HEADER = "content-type";

    /** Producer settings this class owns; client properties may not set them. */
    static final Set<String> OWNED_SETTINGS = Set.of(
            ProducerConfig.BOOTSTRAP_SERVERS_CONFIG,
            ProducerConfig.ACKS_CONFIG,
            ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG,
            ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG,
            ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG,
            ProducerConfig.MAX_BLOCK_MS_CONFIG,
            ProducerConfig.LINGER_MS_CONFIG,
            ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,
            ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG);

    private final Producer<byte[], byte[]> producer;
    private final String topic;
    private final long deliveryTimeoutNanos;

    public KafkaEventPublisher(PublisherSettings settings) {
        this.topic = settings.topic();
        this.deliveryTimeoutNanos = settings.deliveryTimeout().toNanos();
        this.producer = new KafkaProducer<>(producerConfig(settings), new ByteArraySerializer(),
                new ByteArraySerializer());
    }

    static Map<String, Object> producerConfig(PublisherSettings settings) {
        Set<String> overridden = new TreeSet<>(settings.clientProperties().keySet());
        overridden.retainAll(OWNED_SETTINGS);
        if (!overridden.isEmpty()) {
            throw new IllegalArgumentException("client properties may not set " + overridden);
        }
        Map<String, Object> config = new HashMap<>(settings.clientProperties());
        config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, settings.bootstrapServers());
        config.put(ProducerConfig.ACKS_CONFIG, "all");
        config.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, "true");
        config.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, String.valueOf(settings.deliveryTimeout().toMillis()));
        config.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, String.valueOf(settings.requestTimeout().toMillis()));
        // send() waits for metadata up to this long; the publish deadline bounds the total.
        config.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, String.valueOf(settings.deliveryTimeout().toMillis()));
        // Every caller waits for its own acknowledgement, so batching delay is pure latency.
        config.put(ProducerConfig.LINGER_MS_CONFIG, "0");
        return config;
    }

    /**
     * Returns once Kafka has acknowledged the record.
     *
     * @throws AuditUnavailableException if it was not acknowledged within the delivery timeout
     */
    @Override
    public void publish(StampedEvent stamped) {
        long deadline = System.nanoTime() + deliveryTimeoutNanos;
        try {
            producer.send(record(topic, stamped)).get(deadline - System.nanoTime(), TimeUnit.NANOSECONDS);
        } catch (ExecutionException e) {
            throw new AuditUnavailableException(stamped.event(), e.getCause());
        } catch (TimeoutException | KafkaException e) {
            throw new AuditUnavailableException(stamped.event(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AuditUnavailableException(stamped.event(), e);
        }
    }

    /** Publishes one record at a time, so nothing after a failed record is sent. */
    @Override
    public void deliver(List<StampedEvent> orderedBatch) {
        for (StampedEvent stamped : orderedBatch) {
            publish(stamped);
        }
    }

    /** The Kafka record for one audit record on {@code topic}: the log's line as the value, keyed by its subject. */
    static ProducerRecord<byte[], byte[]> record(String topic, StampedEvent stamped) {
        String subject = stamped.event().subject();
        byte[] key = subject == null ? null : subject.getBytes(UTF_8);
        ProducerRecord<byte[], byte[]> record = new ProducerRecord<>(topic, key, stamped.encode());
        record.headers().add(CONTENT_TYPE_HEADER, EventCodec.MEDIA_TYPE.getBytes(UTF_8));
        return record;
    }

    @Override
    public void close() {
        producer.close(Duration.ofNanos(deliveryTimeoutNanos));
    }
}
