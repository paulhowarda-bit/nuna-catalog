package nuna.catalog.server.governance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import nuna.catalog.audit.Actor;
import nuna.catalog.audit.CatalogEvent;
import nuna.catalog.audit.Commit;
import nuna.catalog.audit.EventCodec;
import nuna.catalog.audit.PointerState;
import nuna.catalog.audit.RefChange;
import nuna.catalog.audit.Securable;
import nuna.catalog.store.OutboxBatch;
import nuna.catalog.store.OutboxEntry;
import nuna.catalog.store.OutboxStore;
import nuna.catalog.store.WriteResult;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Audit with no Kafka configured (D23, amended 2026-11-23): the server's audit runtime starts, its outbox relay drains
 * every change to the log {@code nuna.audit} instead of leaving it in the outbox, and a notice goes to the log before
 * the route answers. With the audit log switched off the server does not start.
 */
class AuditRuntimeWithoutKafkaTest {

    private static final URI SOURCE = URI.create("urn:nuna:test");
    private static final Securable ORDERS = new Securable(Securable.Kind.TABLE, UUID.randomUUID(), "lake",
            List.of("sales", "orders"));
    private static final Actor WRITER = new Actor("writer", null, null, "req-1");

    private final AuditLogCapture log = new AuditLogCapture();
    private final MemoryOutbox outbox = new MemoryOutbox();

    @AfterEach
    void restoreLogger() {
        log.close();
    }

    /** The outbox contract in memory, with one relay: lowest entries first, removed once marked. */
    private static final class MemoryOutbox implements OutboxStore {
        final List<OutboxEntry> entries = new ArrayList<>();

        synchronized void add(CatalogEvent event) {
            entries.add(new OutboxEntry(String.valueOf(entries.size() + 1000), UUID.randomUUID(), event.time(),
                    event.type().typeName(), new String(EventCodec.encode(event), StandardCharsets.UTF_8)));
        }

        synchronized int size() {
            return entries.size();
        }

        @Override
        public synchronized Optional<OutboxBatch> claimOutbox(UUID relayId, Instant now, Duration lease, int limit) {
            return Optional.of(new OutboxBatch(relayId, List.copyOf(entries.subList(0, Math.min(limit,
                    entries.size())))));
        }

        @Override
        public synchronized WriteResult markRelayed(UUID relayId, List<String> entryIds, Instant now) {
            entries.removeIf(e -> entryIds.contains(e.id()));
            return WriteResult.APPLIED;
        }
    }

    private static AuditSettings noKafka() {
        return new AuditSettings(null, "nuna.catalog.audit", "nuna.catalog.changes", Duration.ofSeconds(5),
                Duration.ofSeconds(2), Duration.ofSeconds(10), Duration.ofMillis(20), Duration.ofSeconds(30), 2,
                new AuditSettings.Archive(null, null, "audit", Duration.ofDays(2557), 10_000, Duration.ofMinutes(5),
                        "nuna-audit-archive"));
    }

    private AuditRuntime runtime() {
        return new AuditRuntime(noKafka(), outbox, Optional.empty(), SOURCE, UnaryOperator.identity(),
                Clock.systemUTC());
    }

    private static CatalogEvent commit() {
        PointerState v1 = new PointerState("s3://lake/orders/metadata/00001.json", 1, "a".repeat(64));
        PointerState v2 = new PointerState("s3://lake/orders/metadata/00002.json", 2, "b".repeat(64));
        return CatalogEvent.create(SOURCE, Instant.now(), new Commit(UUID.randomUUID(), ORDERS, "main", v1, v2, "[]",
                WRITER));
    }

    @Test
    void theRelayDrainsEveryChangeToTheLog() throws InterruptedException {
        List<UUID> changes = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            CatalogEvent change = commit();
            outbox.add(change);
            changes.add(change.id());
        }

        try (AuditRuntime runtime = runtime()) {
            runtime.start();
            long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (outbox.size() > 0 && System.nanoTime() < deadline) {
                Thread.sleep(20);
            }
        }

        assertThat(outbox.size()).as("nothing left in the outbox").isZero();
        assertThat(log.records()).extracting(CatalogEvent::id).containsExactlyElementsOf(changes);
    }

    @Test
    void aNoticeGoesToTheLogBeforeTheRouteAnswers() {
        CatalogEvent warning = CatalogEvent.create(SOURCE, Instant.now(), new RefChange(RefChange.Action.EXPIRING,
                UUID.randomUUID(), "audit-2026", RefChange.Kind.BRANCH,
                new Securable(Securable.Kind.CATALOG, UUID.randomUUID(), "lake", List.of()), "main",
                Instant.now().plus(Duration.ofDays(7)), List.of(), WRITER));

        try (AuditRuntime runtime = runtime()) {
            runtime.notices().deliver(List.of(warning));
        }

        assertThat(log.records()).extracting(CatalogEvent::id).containsExactly(warning.id());
    }

    @Test
    void theServerDoesNotStartWithTheAuditLogOff() {
        log.level(Level.WARN);

        assertThatThrownBy(this::runtime).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("logging.level.nuna.audit");
    }
}
