package keel.catalog.audit.publish;

import eu.rekawek.toxiproxy.Proxy;
import eu.rekawek.toxiproxy.ToxiproxyClient;
import eu.rekawek.toxiproxy.model.ToxicDirection;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.testcontainers.containers.Network;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.toxiproxy.ToxiproxyContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * One KRaft broker in a container, reachable directly and through a Toxiproxy link. The broker
 * advertises the link's address on the listener the link reaches, so a client that bootstraps
 * through the link stays on it.
 */
public final class KafkaBroker implements AutoCloseable {

    private static final int LINK_PORT = 8666;
    private static final String LINK_LISTENER = "kafka:19092";

    private final Network network = Network.newNetwork();
    private final ToxiproxyContainer toxiproxy;
    private final ToxiproxyClient toxiproxyClient;
    private final Proxy link;
    private final KafkaContainer kafka;
    private final Admin admin;

    public KafkaBroker() {
        toxiproxy = new ToxiproxyContainer(DockerImageName.parse(System.getProperty("toxiproxy.image")))
                .withNetwork(network);
        toxiproxy.start();
        toxiproxyClient = new ToxiproxyClient(toxiproxy.getHost(), toxiproxy.getControlPort());
        link = toxi(() -> toxiproxyClient.createProxy("kafka", "0.0.0.0:" + LINK_PORT, LINK_LISTENER));
        kafka = new KafkaContainer(DockerImageName.parse(System.getProperty("kafka.image")))
                .withNetwork(network)
                .withListener(LINK_LISTENER, this::linkAddress)
                .withEnv("KAFKA_HEAP_OPTS", "-Xms384m -Xmx384m");
        kafka.start();
        admin = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, direct()));
    }

    public String direct() {
        return kafka.getBootstrapServers();
    }

    String linkAddress() {
        return toxiproxy.getHost() + ":" + toxiproxy.getMappedPort(LINK_PORT);
    }

    /** A new single-partition topic, so the order of its records is the order they were written. */
    public String newTopic() {
        return newTopic(1);
    }

    /** A new topic with this many partitions. */
    public String newTopic(int partitions) {
        String topic = "audit-" + UUID.randomUUID();
        try {
            admin.createTopics(List.of(new NewTopic(topic, partitions, (short) 1))).all().get();
        } catch (InterruptedException | ExecutionException e) {
            throw new IllegalStateException(e);
        }
        return topic;
    }

    /** Every record in the topic, in order. */
    List<ConsumerRecord<byte[], byte[]>> readAll(String topic) {
        TopicPartition partition = new TopicPartition(topic, 0);
        try (KafkaConsumer<byte[], byte[]> consumer = new KafkaConsumer<>(
                Map.of(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, direct()),
                new ByteArrayDeserializer(), new ByteArrayDeserializer())) {
            consumer.assign(List.of(partition));
            consumer.seekToBeginning(List.of(partition));
            long end = consumer.endOffsets(List.of(partition)).get(partition);
            List<ConsumerRecord<byte[], byte[]>> records = new ArrayList<>();
            long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
            while (consumer.position(partition) < end) {
                if (System.nanoTime() > deadline) {
                    throw new IllegalStateException("could not read " + topic + " to offset " + end);
                }
                consumer.poll(Duration.ofMillis(200)).forEach(records::add);
            }
            return records;
        }
    }

    /** The link stops carrying broker responses; requests still reach the broker. */
    void dropResponses() {
        toxi(() -> link.toxics().timeout("drop-responses", ToxicDirection.DOWNSTREAM, 0));
    }

    /** Every broker response on the link is held back this long. */
    void delayResponses(Duration delay) {
        toxi(() -> link.toxics().latency("delay-responses", ToxicDirection.DOWNSTREAM, delay.toMillis()));
    }

    void healLink() {
        toxi(() -> {
            toxiproxyClient.reset();
            return null;
        });
    }

    /** Freezes the broker process: connections stay open and nothing answers. */
    public void pause() {
        kafka.getDockerClient().pauseContainerCmd(kafka.getContainerId()).exec();
    }

    public void unpause() {
        kafka.getDockerClient().unpauseContainerCmd(kafka.getContainerId()).exec();
    }

    @Override
    public void close() {
        admin.close();
        kafka.stop();
        toxiproxy.stop();
        network.close();
    }

    private interface ToxiproxyCall<T> {
        T call() throws IOException;
    }

    private static <T> T toxi(ToxiproxyCall<T> call) {
        try {
            return call.call();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
