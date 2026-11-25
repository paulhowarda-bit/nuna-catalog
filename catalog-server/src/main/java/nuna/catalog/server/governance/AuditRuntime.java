package nuna.catalog.server.governance;

import nuna.catalog.audit.archive.ObjectLockArchive;
import nuna.catalog.audit.publish.EventPublisher;
import nuna.catalog.audit.publish.KafkaEventPublisher;
import nuna.catalog.audit.publish.PublisherSettings;
import nuna.catalog.audit.publish.RelayTarget;
import nuna.catalog.audit.relay.ChangeStream;
import nuna.catalog.audit.relay.OutboxRelay;
import nuna.catalog.store.OutboxStore;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.UnaryOperator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

/**
 * Audit delivery while the server runs (D23, amended 2026-11-23 and 2026-11-25): the audit trail, the outbox relay,
 * and, where Kafka is configured, the audit and change-stream publishers. Every server runs a relay, with or without
 * Kafka; the outbox lease lets one drain at a time. It writes each change to the log {@value AuditTrail#LOGGER} and,
 * where Kafka is configured, to the audit topic and the change stream, best effort ({@link AuditTrail#deliver}), so no
 * change waits in the outbox for Kafka. A record that changes nothing in the store, and so has no transaction to go out
 * with, is delivered at once the way the relay delivers a batch ({@link #notices}). The archive is written from the
 * log by catalog-audit-tables; the server only reads it, for its check and the DR reconcile.
 */
public final class AuditRuntime implements SmartLifecycle, AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(AuditRuntime.class);
    private static final Duration FAILURE_BACKOFF = Duration.ofSeconds(1);

    private final AuditSettings settings;
    private final Optional<KafkaEventPublisher> audit;
    private final Optional<KafkaEventPublisher> changes;
    private final AuditTrail trail;
    private final OutboxRelay relay;
    private final Optional<ObjectLockArchive> archive;
    private final AtomicBoolean running = new AtomicBoolean();
    private Thread relayThread;

    /**
     * @param observe wraps the audit topic's publisher for the synchronous records, so each publish is observed
     * @throws IllegalStateException if the audit log is switched off
     */
    AuditRuntime(AuditSettings settings, OutboxStore outbox, Optional<ObjectLockArchive> archive, URI source,
                 UnaryOperator<EventPublisher> observe, Clock clock) {
        AuditTrail.requireLogged();
        this.settings = settings;
        this.archive = archive;
        if (settings.kafkaConfigured()) {
            audit = Optional.of(publisher(settings.auditTopic()));
            changes = Optional.of(publisher(settings.changeTopic()));
            trail = new AuditTrail(Optional.of(observe.apply(audit.get())),
                    Optional.of(ChangeStream.fanOut(audit.get(), changes.get())), source, clock,
                    settings.probeInterval());
        } else {
            LOG.info("nuna.audit.kafka-bootstrap-servers is not set: audit records go to the log {} only, with no "
                    + "audit topic or change stream", AuditTrail.LOGGER);
            audit = Optional.empty();
            changes = Optional.empty();
            trail = new AuditTrail(Optional.empty(), Optional.empty(), source, clock, settings.probeInterval());
        }
        relay = new OutboxRelay(outbox, trail::deliver, clock, settings.relayLease(), settings.relayBatch());
    }

    private KafkaEventPublisher publisher(String topic) {
        return new KafkaEventPublisher(new PublisherSettings(settings.kafkaBootstrapServers(), topic,
                settings.deliveryTimeout(), settings.requestTimeout(), Map.of()));
    }

    /** The audit trail every synchronous record and every relayed batch goes through. */
    AuditTrail trail() {
        return trail;
    }

    /**
     * Where a record goes that changes nothing in the store, a branch's expiry warning (D33, choice 11): to the log
     * and, where Kafka is configured, to the audit topic and, for the change stream's types, to the change stream, as
     * the relay sends an outbox batch, before the route answers. Never throws for Kafka.
     */
    RelayTarget notices() {
        return trail::deliver;
    }

    /** The archive, where one is configured; the DR reconcile reads it (D37). */
    Optional<ObjectLockArchive> archive() {
        return archive;
    }

    @Override
    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        relayThread = Thread.ofVirtual().name("outbox-relay").start(this::relayLoop);
    }

    private void relayLoop() {
        while (running.get()) {
            try {
                relay.drain();
                sleep(settings.relayInterval());
            } catch (RuntimeException e) {
                LOG.error("outbox relay failed; retrying", e);
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
        if (relayThread != null) {
            try {
                relayThread.join(settings.deliveryTimeout().plusSeconds(5));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
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
