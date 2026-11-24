package nuna.datagen.live;

import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ExecutionException;
import nuna.datagen.world.Event;
import nuna.datagen.world.Feed;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.TopicExistsException;
import org.apache.kafka.common.serialization.StringSerializer;

/**
 * The Kafka feeds' topics: one partition each, so a topic's end offset is the number of records the world has sent to
 * it, which is how a restart knows where to resume ({@link Publisher}). Records carry no key, and their timestamp is the
 * wall clock's: the world's business time runs ahead of it, and Kafka 4 refuses a timestamp more than an hour ahead.
 */
public final class KafkaTopics implements Publisher.Topics, AutoCloseable {

    /** A day of the stream; the demo is rebuilt every night. */
    private static final String RETENTION_MS = String.valueOf(Duration.ofDays(1).toMillis());

    private final KafkaProducer<String, String> producer;
    private volatile Exception failure;

    public KafkaTopics(String bootstrap) {
        Properties p = new Properties();
        p.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        p.put(ProducerConfig.CLIENT_ID_CONFIG, "brokerage-world");
        p.put(ProducerConfig.ACKS_CONFIG, "all");
        p.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        p.put(ProducerConfig.LINGER_MS_CONFIG, 100);
        p.put(ProducerConfig.BUFFER_MEMORY_CONFIG, 8L * 1024 * 1024);
        p.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        p.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        producer = new KafkaProducer<>(p);
    }

    @Override
    public void send(Event event) {
        check();
        producer.send(new ProducerRecord<>(event.feed().topic, Encoding.json(event)), (metadata, e) -> {
            if (e != null) {
                failure = e;
            }
        });
    }

    @Override
    public void flush() {
        producer.flush();
        check();
    }

    private void check() {
        if (failure != null) {
            throw new IllegalStateException("a record was not sent", failure);
        }
    }

    @Override
    public void close() {
        producer.close(Duration.ofSeconds(10));
    }

    static Admin admin(String bootstrap) {
        return Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap,
            AdminClientConfig.CLIENT_ID_CONFIG, "brokerage-world-admin"));
    }

    /** Creates each feed's topic (the orders' too, for when SingleStore is off) and the sink's control topic, where missing. */
    public static void create(String bootstrap, String controlTopic) throws InterruptedException, ExecutionException {
        try (Admin admin = admin(bootstrap)) {
            List<NewTopic> topics = new ArrayList<>();
            for (Feed f : Feed.values()) {
                topics.add(new NewTopic(f.topic, 1, (short) 1).configs(Map.of("retention.ms", RETENTION_MS)));
            }
            topics.add(new NewTopic(controlTopic, 1, (short) 1));
            for (var created : admin.createTopics(topics).values().entrySet()) {
                try {
                    created.getValue().get();
                    System.out.println("created topic " + created.getKey());
                } catch (ExecutionException e) {
                    if (!(e.getCause() instanceof TopicExistsException)) {
                        throw e;
                    }
                }
            }
        }
    }

    /** The records each feed's topic holds. */
    public static Map<Feed, Long> endOffsets(String bootstrap) throws InterruptedException, ExecutionException {
        try (Admin admin = admin(bootstrap)) {
            Map<TopicPartition, OffsetSpec> latest = new HashMap<>();
            Map<TopicPartition, Feed> feeds = new HashMap<>();
            for (Feed f : Feed.values()) {
                TopicPartition tp = new TopicPartition(f.topic, 0);
                latest.put(tp, OffsetSpec.latest());
                feeds.put(tp, f);
            }
            Map<Feed, Long> offsets = new EnumMap<>(Feed.class);
            admin.listOffsets(latest).all().get().forEach((tp, info) -> offsets.put(feeds.get(tp), info.offset()));
            return offsets;
        }
    }
}
