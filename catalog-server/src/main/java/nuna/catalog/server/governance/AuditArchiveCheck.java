package nuna.catalog.server.governance;

import nuna.catalog.audit.archive.ArchiveCheck;
import nuna.catalog.audit.archive.ArchiveCheck.Anchor;
import nuna.catalog.audit.archive.ArchiveCheck.Checked;
import nuna.catalog.audit.archive.ArchiveConsumer;
import nuna.catalog.audit.archive.ChainHead;
import nuna.catalog.audit.archive.ObjectLockArchive;
import nuna.catalog.audit.archive.ObjectLockArchive.Placement;
import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * The archive check while the server runs (D23; backlog 125): where an archive is configured, each chain is verified
 * up to the archive consumer's anchor a minute after the server starts and every hour after that ({@link
 * ArchiveCheck}). What it verified is kept as the committed offsets of a consumer group of its own, the archive's
 * group name with {@code -check} after it, with the verified head in each offset's metadata: every server shares it,
 * and a restart reads only what was archived since. Every server checks; two checking at once read the same segments
 * and record the same heads.
 *
 * <p>The audit feed asks it how far each chain is verified, and where one record lies in its chain.
 */
@Component
class AuditArchiveCheck implements SmartLifecycle {

    private static final Logger LOG = LoggerFactory.getLogger(AuditArchiveCheck.class);
    static final Duration FIRST_RUN = Duration.ofMinutes(1);
    static final Duration INTERVAL = Duration.ofHours(1);

    private final AuditSettings settings;
    private final Optional<ObjectLockArchive> archive;
    private final Clock clock;
    private final AtomicBoolean running = new AtomicBoolean();
    private volatile Optional<ArchiveCheck.Result> last = Optional.empty();
    /** What the check group had recorded when the server started, until this server's first run. */
    private volatile Map<Integer, Checked> recorded = Map.of();
    private Thread thread;

    @Autowired
    AuditArchiveCheck(AuditSettings settings, AuditRuntime runtime, Clock clock) {
        this(settings, runtime.archive(), clock);
    }

    AuditArchiveCheck(AuditSettings settings, Optional<ObjectLockArchive> archive, Clock clock) {
        this.settings = settings;
        this.archive = archive;
        this.clock = clock;
    }

    /** Whether an archive is configured on this server. */
    boolean configured() {
        return archive.isPresent();
    }

    /** The archive's settings, for the status route. */
    AuditSettings.Archive archiveSettings() {
        return settings.archive();
    }

    /** The last run's outcome on this server; empty before its first. */
    Optional<ArchiveCheck.Result> last() {
        return last;
    }

    /** How far each partition's chain is verified, by partition: the last run's, or what was recorded before it. */
    Map<Integer, Checked> verified() {
        Optional<ArchiveCheck.Result> result = last;
        if (result.isEmpty()) {
            return recorded;
        }
        Map<Integer, Checked> verified = new HashMap<>();
        result.get().chains().forEach(c -> c.verified().ifPresent(v -> verified.put(c.partition(), v)));
        return verified;
    }

    /** Where the record at {@code offset} of {@code partition} lies in its chain; empty while it is not archived. */
    Optional<Placement> place(int partition, long offset, String eventId) {
        return archive.orElseThrow().place(ArchiveConsumer.chain(settings.auditTopic(), partition), offset, eventId,
                settings.archive().segmentEvents());
    }

    /** The archive's chain name for a partition. */
    String chain(int partition) {
        return ArchiveConsumer.chain(settings.auditTopic(), partition);
    }

    @Override
    public void start() {
        if (archive.isEmpty() || !running.compareAndSet(false, true)) {
            return;
        }
        thread = Thread.ofVirtual().name("audit-archive-check").start(this::loop);
    }

    private void loop() {
        try (KafkaHeads heads = new KafkaHeads()) {
            try {
                recorded = heads.checked();
            } catch (RuntimeException e) {
                LOG.warn("audit archive check: what earlier checks verified could not be read: {}", e.getMessage());
            }
            ArchiveCheck check = new ArchiveCheck(archive.orElseThrow(), settings.auditTopic(), heads, clock);
            Duration wait = FIRST_RUN;
            while (running.get() && sleep(wait)) {
                try {
                    ArchiveCheck.Result result = check.run();
                    last = Optional.of(result);
                    if (!result.intact()) {
                        LOG.error("audit archive check: a chain is broken or could not be checked: {}",
                                result.chains().stream().filter(c -> c.broken().isPresent() || c.failed().isPresent())
                                        .toList());
                    }
                } catch (RuntimeException e) {
                    LOG.error("audit archive check failed; retrying at the next interval", e);
                }
                wait = INTERVAL;
            }
        } catch (RuntimeException e) {
            LOG.error("audit archive check could not start", e);
        }
    }

    /** False when the wait was cut short by {@link #stop}. */
    private boolean sleep(Duration duration) {
        try {
            Thread.sleep(duration);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    @Override
    public void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        thread.interrupt();
        try {
            thread.join(Duration.ofSeconds(10));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    /**
     * The anchors, read from the archive consumers' group, and what the check verified, kept in the check's own group.
     * Neither consumer joins its group: each only reads or commits offsets.
     */
    private final class KafkaHeads implements ArchiveCheck.Heads, AutoCloseable {

        private final KafkaConsumer<byte[], byte[]> anchors = consumer(settings.archive().consumerGroup());
        private final KafkaConsumer<byte[], byte[]> checks = consumer(settings.archive().consumerGroup() + "-check");

        private KafkaConsumer<byte[], byte[]> consumer(String group) {
            return new KafkaConsumer<>(Map.of(
                    ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, settings.kafkaBootstrapServers(),
                    ConsumerConfig.GROUP_ID_CONFIG, group,
                    ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false),
                    new ByteArrayDeserializer(), new ByteArrayDeserializer());
        }

        @Override
        public List<Integer> partitions() {
            return anchors.partitionsFor(settings.auditTopic()).stream().map(p -> p.partition()).sorted().toList();
        }

        private Set<TopicPartition> topicPartitions() {
            return partitions().stream().map(p -> new TopicPartition(settings.auditTopic(), p))
                    .collect(Collectors.toSet());
        }

        @Override
        public Map<Integer, Anchor> anchors() {
            Map<Integer, Anchor> found = new HashMap<>();
            anchors.committed(topicPartitions()).forEach((partition, committed) -> {
                Optional<ChainHead> head = ArchiveConsumer.anchor(committed);
                head.ifPresent(h -> found.put(partition.partition(), new Anchor(committed.offset(), h)));
            });
            return found;
        }

        @Override
        public Map<Integer, Checked> checked() {
            Map<Integer, Checked> found = new HashMap<>();
            checks.committed(topicPartitions()).forEach((partition, committed) -> {
                if (committed != null) {
                    Checked.parse(committed.offset(), committed.metadata())
                            .ifPresent(c -> found.put(partition.partition(), c));
                }
            });
            return found;
        }

        @Override
        public void record(Map<Integer, Checked> checked) {
            Map<TopicPartition, OffsetAndMetadata> offsets = new HashMap<>();
            checked.forEach((partition, c) -> offsets.put(new TopicPartition(settings.auditTopic(), partition),
                    new OffsetAndMetadata(c.nextOffset(), c.metadata())));
            checks.commitSync(offsets);
        }

        @Override
        public void close() {
            anchors.close();
            checks.close();
        }
    }
}
