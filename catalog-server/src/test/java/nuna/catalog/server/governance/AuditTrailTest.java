package nuna.catalog.server.governance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import nuna.catalog.audit.Actor;
import nuna.catalog.audit.CatalogEvent;
import nuna.catalog.audit.Commit;
import nuna.catalog.audit.CredentialIssue;
import nuna.catalog.audit.EventType;
import nuna.catalog.audit.ObjectRead;
import nuna.catalog.audit.PointerState;
import nuna.catalog.audit.Securable;
import nuna.catalog.audit.publish.AuditUnavailableException;
import nuna.catalog.audit.publish.EventPublisher;
import nuna.catalog.audit.publish.RelayTarget;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * The audit trail (D23, amended 2026-11-23): every record is written to the log {@code nuna.audit} before the call
 * returns, Kafka takes a copy where it is configured, and a Kafka that fails refuses nothing, a credential issue
 * included, and is skipped for the probe interval after.
 */
class AuditTrailTest {

    private static final URI SOURCE = URI.create("urn:nuna:test");
    private static final Duration PROBE = Duration.ofSeconds(10);
    private static final Securable ORDERS = new Securable(Securable.Kind.TABLE, UUID.randomUUID(), "lake",
            List.of("sales", "orders"));
    private static final PointerState V1 = new PointerState("s3://lake/orders/metadata/00001.json", 1, "a".repeat(64));
    private static final PointerState V2 = new PointerState("s3://lake/orders/metadata/00002.json", 2, "b".repeat(64));
    private static final Actor READER = new Actor("reader", null, null, "req-1");

    private final MovingClock clock = new MovingClock();
    private final AuditLogCapture log = new AuditLogCapture();

    @AfterEach
    void restoreLogger() {
        log.close();
    }

    static final class MovingClock extends Clock {
        Instant now = Instant.parse("2026-11-23T09:00:00Z");

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private static CredentialIssue credentials() {
        return new CredentialIssue(ORDERS, "s3", "s3://lake/orders/", CredentialIssue.Access.READ, "nuna-reader",
                Instant.parse("2026-11-23T09:30:00Z"), READER);
    }

    private static CatalogEvent commit() {
        return CatalogEvent.create(SOURCE, Instant.parse("2026-11-23T08:59:00Z"),
                new Commit(UUID.randomUUID(), ORDERS, "main", V1, V2, "[]", READER));
    }

    private static CatalogEvent read() {
        return CatalogEvent.create(SOURCE, Instant.parse("2026-11-23T08:59:01Z"),
                new ObjectRead(ORDERS, "main", V1, READER));
    }

    private static List<UUID> ids(List<CatalogEvent> events) {
        return events.stream().map(CatalogEvent::id).toList();
    }

    @Test
    void withoutKafkaEveryRecordIsWrittenToTheLogBeforeTheCallReturns() {
        AuditTrail trail = new AuditTrail(Optional.empty(), Optional.empty(), SOURCE, clock, PROBE);

        trail.record(credentials());

        assertThat(log.records()).singleElement().satisfies(record -> {
            assertThat(record.type()).isEqualTo(EventType.CREDENTIAL_ISSUE);
            assertThat(record.source()).isEqualTo(SOURCE);
            assertThat(record.data()).isEqualTo(credentials());
        });
    }

    @Test
    void aCredentialIssueIsRecordedAndNotRefusedWhenKafkaFails() {
        AtomicInteger tries = new AtomicInteger();
        EventPublisher down = event -> {
            tries.incrementAndGet();
            throw new AuditUnavailableException(event, new RuntimeException("broker down"));
        };
        AuditTrail trail = new AuditTrail(Optional.of(down), Optional.empty(), SOURCE, clock, PROBE);

        trail.record(credentials());

        assertThat(tries).hasValue(1);
        assertThat(log.records()).extracting(CatalogEvent::type).containsExactly(EventType.CREDENTIAL_ISSUE);
    }

    @Test
    void kafkaIsSkippedForTheProbeIntervalAfterItFails() {
        AtomicInteger tries = new AtomicInteger();
        List<CatalogEvent> taken = new ArrayList<>();
        EventPublisher flaky = event -> {
            if (tries.incrementAndGet() == 1) {
                throw new AuditUnavailableException(event, new RuntimeException("broker down"));
            }
            taken.add(event);
        };
        AuditTrail trail = new AuditTrail(Optional.of(flaky), Optional.empty(), SOURCE, clock, PROBE);

        trail.record(read());
        clock.now = clock.now.plus(PROBE).minusMillis(1);
        trail.record(read());
        assertThat(tries).as("the second record waits on no Kafka").hasValue(1);

        clock.now = clock.now.plusMillis(1);
        CatalogEvent third = read();
        trail.record(third);
        assertThat(tries).hasValue(2);
        assertThat(taken).containsExactly(third);
        assertThat(log.records()).as("every record in the log, Kafka or not").hasSize(3);
    }

    @Test
    void kafkaTakesACopyOfEveryRecordWhileItIsUp() {
        List<CatalogEvent> topic = new ArrayList<>();
        List<CatalogEvent> stream = new ArrayList<>();
        AuditTrail trail = new AuditTrail(Optional.of(topic::add), Optional.of(stream::addAll), SOURCE, clock, PROBE);
        CatalogEvent read = read();
        List<CatalogEvent> batch = List.of(commit(), commit());

        trail.record(read);
        trail.deliver(batch);

        assertThat(topic).containsExactly(read);
        assertThat(stream).containsExactlyElementsOf(batch);
        assertThat(ids(log.records())).containsExactly(read.id(), batch.get(0).id(), batch.get(1).id());
    }

    @Test
    void aRelayedBatchIsInTheLogInOrderAndAKafkaFailureFailsNothing() {
        AtomicInteger tries = new AtomicInteger();
        RelayTarget down = batch -> {
            tries.incrementAndGet();
            throw new AuditUnavailableException(batch.getFirst(), new RuntimeException("broker down"));
        };
        AuditTrail trail = new AuditTrail(Optional.empty(), Optional.of(down), SOURCE, clock, PROBE);
        List<CatalogEvent> batch = List.of(commit(), commit(), commit());

        trail.deliver(batch);
        trail.deliver(List.of(commit()));

        assertThat(ids(log.records()).subList(0, 3)).containsExactlyElementsOf(ids(batch));
        assertThat(log.records()).hasSize(4);
        assertThat(tries).as("the second batch skips Kafka within the probe interval").hasValue(1);
    }

    @Test
    void theServerRefusesToStartWithTheAuditLogOff() {
        org.slf4j.Logger audit = LoggerFactory.getLogger(AuditTrail.LOGGER);
        log.level(Level.INFO);
        AuditTrail.requireLogged(audit);

        log.level(Level.WARN);
        assertThatThrownBy(() -> AuditTrail.requireLogged(audit)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("logging.level.nuna.audit must be INFO");
    }
}
