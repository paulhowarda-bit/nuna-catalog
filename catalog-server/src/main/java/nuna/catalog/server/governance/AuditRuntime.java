package nuna.catalog.server.governance;

import nuna.catalog.audit.archive.ArchiveConsumer;
import nuna.catalog.audit.archive.ObjectLockArchive;
import nuna.catalog.audit.publish.AuditUnavailableException;
import nuna.catalog.audit.publish.KafkaEventPublisher;
import nuna.catalog.audit.publish.PublisherSettings;
import nuna.catalog.audit.relay.ChangeStream;
import nuna.catalog.audit.relay.OutboxRelay;
import nuna.catalog.store.OutboxStore;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

/**
 * Audit delivery while the server runs (D23): the audit and change-stream publishers, the outbox
 * relay, and the archive consumer where an archive is configured. Every server runs a relay; the
 * outbox lease lets one drain at a time. Archive consumers share a consumer group, so each audit
 * partition, and so each hash chain, has one writer.
 */
public final class AuditRuntime implements SmartLifecycle, AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(AuditRuntime.class);
    private static final Duration FAILURE_BACKOFF = Duration.ofSeconds(1);
    private static final Duration ARCHIVE_POLL = Duration.ofMillis(500);

    private final AuditSettings settings;
    private final Optional<KafkaEventPublisher> audit;
    private final Optional<KafkaEventPublisher> changes;
    private final OutboxRelay relay;
    private final Optional<ObjectLockArchive> archive;
    private final Clock clock;
    private final AtomicBoolean running = new AtomicBoolean();
    private Thread relayThread;
    private Thread archiveThread;

    AuditRuntime(AuditSettings settings, OutboxStore outbox, Optional<ObjectLockArchive> archive, Clock clock) {
        this.settings = settings;
        this.clock = clock;
        this.archive = archive;
        if (settings.kafkaConfigured()) {
            audit = Optional.of(publisher(settings.auditTopic()));
            changes = Optional.of(publisher(settings.changeTopic()));
            relay = new OutboxRelay(outbox, ChangeStream.fanOut(audit.get(), changes.get()), clock,
                    settings.relayLease(), settings.relayBatch());
        } else {
            LOG.warn("nuna.audit.kafka-bootstrap-servers is not set: audit is unavailable. Changes wait in the "
                    + "outbox, reads and denials go to the fallback log, and no credential is handed out");
            audit = Optional.empty();
            changes = Optional.empty();
            relay = null;
        }
        if (archive.isPresent() && !settings.kafkaConfigured()) {
            throw new IllegalStateException("nuna.audit.archive.bucket is set but nuna.audit.kafka-bootstrap-servers "
                    + "is not: the archive reads the audit topic");
        }
    }

    private KafkaEventPublisher publisher(String topic) {
        return new KafkaEventPublisher(new PublisherSettings(settings.kafkaBootstrapServers(), topic,
                settings.deliveryTimeout(), settings.requestTimeout(), Map.of()));
    }

    /** The audit topic's publisher, for synchronous records; empty when Kafka is not configured. */
    Optional<KafkaEventPublisher> auditPublisher() {
        return audit;
    }

    @Override
    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        if (relay != null) {
            relayThread = Thread.ofVirtual().name("outbox-relay").start(this::relayLoop);
        }
        if (archive.isPresent()) {
            archiveThread = Thread.ofVirtual().name("audit-archive").start(this::archiveLoop);
        }
    }

    private void relayLoop() {
        while (running.get()) {
            try {
                relay.drain();
                sleep(settings.relayInterval());
            } catch (AuditUnavailableException e) {
                LOG.warn("outbox relay: audit unavailable; the batch stays in the outbox and is retried", e);
                sleep(FAILURE_BACKOFF);
            } catch (RuntimeException e) {
                LOG.error("outbox relay failed; retrying", e);
                sleep(FAILURE_BACKOFF);
            }
        }
    }

    private void archiveLoop() {
        while (running.get()) {
            try (ArchiveConsumer consumer = new ArchiveConsumer(Map.of(
                    ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, settings.kafkaBootstrapServers(),
                    ConsumerConfig.GROUP_ID_CONFIG, settings.archive().consumerGroup()),
                    settings.auditTopic(), archive.orElseThrow(), settings.archive().segmentEvents(),
                    settings.archive().segmentAge(), clock)) {
                while (running.get()) {
                    consumer.pollOnce(ARCHIVE_POLL);
                }
            } catch (RuntimeException e) {
                LOG.error("audit archive failed; restarting from the last written segment", e);
                sleep(FAILURE_BACKOFF);
            }
        }
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        for (Thread thread : new Thread[] {relayThread, archiveThread}) {
            if (thread != null) {
                try {
                    thread.join(settings.deliveryTimeout().plus(ARCHIVE_POLL).plusSeconds(5));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    @Override
    public void close() {
        stop();
        audit.ifPresent(KafkaEventPublisher::close);
        changes.ifPresent(KafkaEventPublisher::close);
    }
}
