package keel.catalog.audit.relay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import keel.catalog.audit.CatalogEvent;
import keel.catalog.audit.EventCodec;
import keel.catalog.audit.SampleEvents;
import keel.catalog.audit.publish.AuditUnavailableException;
import keel.catalog.audit.publish.RelayTarget;
import keel.catalog.store.OutboxBatch;
import keel.catalog.store.OutboxEntry;
import keel.catalog.store.OutboxStore;
import keel.catalog.store.WriteResult;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The relay's contract (D23, storage-design 9 b): outbox order, nothing marked unless delivered, one relay at a time. */
class OutboxRelayTest {

    private static final Duration LEASE = Duration.ofSeconds(30);

    /** A clock the test moves. */
    private static final class TestClock extends Clock {
        Instant now = Instant.parse("2026-11-08T09:00:00Z");

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    /** The outbox contract in memory: lowest entries first, one lease holder, marks only by the holder. */
    private static final class MemoryOutbox implements OutboxStore {
        final List<OutboxEntry> entries = new ArrayList<>();
        UUID holder;
        Instant leaseUntil = Instant.MIN;

        void add(CatalogEvent event) {
            entries.add(new OutboxEntry(String.valueOf(entries.size() + 1000), UUID.randomUUID(), event.time(),
                    event.type().typeName(), new String(EventCodec.encode(event), StandardCharsets.UTF_8)));
        }

        @Override
        public Optional<OutboxBatch> claimOutbox(UUID relayId, Instant now, Duration lease, int limit) {
            if (holder != null && !holder.equals(relayId) && leaseUntil.isAfter(now)) {
                return Optional.empty();
            }
            holder = relayId;
            leaseUntil = now.plus(lease);
            return Optional.of(new OutboxBatch(relayId, entries.subList(0, Math.min(limit, entries.size()))));
        }

        @Override
        public WriteResult markRelayed(UUID relayId, List<String> entryIds, Instant now) {
            if (!relayId.equals(holder) || !leaseUntil.isAfter(now)) {
                return new WriteResult.Refused(WriteResult.Reason.NOT_HOLDER);
            }
            entries.removeIf(e -> entryIds.contains(e.id()));
            return WriteResult.APPLIED;
        }
    }

    private final TestClock clock = new TestClock();
    private final MemoryOutbox outbox = new MemoryOutbox();
    private final List<CatalogEvent> delivered = new ArrayList<>();
    private final RelayTarget recording = delivered::addAll;

    private List<CatalogEvent> fill(int n) {
        List<CatalogEvent> events = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            CatalogEvent event = SampleEvents.event(SampleEvents.oneOfEachType().get(i % 3), i + 1);
            outbox.add(event);
            events.add(event);
        }
        return events;
    }

    @Test
    @DisplayName("drain relays every entry in outbox order, batch by batch, and empties the outbox")
    void drainsInOrder() {
        List<CatalogEvent> events = fill(5);

        int relayed = new OutboxRelay(outbox, recording, clock, LEASE, 2).drain();

        assertThat(relayed).isEqualTo(5);
        assertThat(delivered).containsExactlyElementsOf(events);
        assertThat(outbox.entries).isEmpty();
    }

    @Test
    @DisplayName("a failed delivery marks nothing, and the next pass delivers the same events again")
    void failedDeliveryIsRetried() {
        List<CatalogEvent> events = fill(3);
        RelayTarget down = batch -> {
            throw new AuditUnavailableException(batch.getFirst(), new RuntimeException("broker down"));
        };
        OutboxRelay failing = new OutboxRelay(outbox, down, clock, LEASE, 10);

        assertThatThrownBy(failing::relayOnce).isInstanceOf(AuditUnavailableException.class);
        assertThat(outbox.entries).hasSize(3);

        clock.now = clock.now.plus(LEASE).plusSeconds(1);
        assertThat(new OutboxRelay(outbox, recording, clock, LEASE, 10).relayOnce()).isEqualTo(3);
        assertThat(delivered).containsExactlyElementsOf(events);
    }

    @Test
    @DisplayName("while another relay holds a live lease, a relay delivers nothing")
    void oneRelayAtATime() {
        fill(2);
        OutboxRelay first = new OutboxRelay(outbox, batch -> { }, clock, LEASE, 1);
        assertThat(first.relayOnce()).isEqualTo(1);

        assertThat(new OutboxRelay(outbox, recording, clock, LEASE, 10).relayOnce()).isZero();
        assertThat(delivered).isEmpty();
        assertThat(outbox.entries).hasSize(1);
    }

    @Test
    @DisplayName("a relay whose lease lapses while it delivers marks nothing; the next holder delivers the batch again")
    void lapsedLeaseIsDeliveredAgain() {
        List<CatalogEvent> events = fill(2);
        RelayTarget slow = batch -> clock.now = clock.now.plus(LEASE).plusSeconds(1);

        assertThat(new OutboxRelay(outbox, slow, clock, LEASE, 10).relayOnce()).isZero();
        assertThat(outbox.entries).hasSize(2);

        assertThat(new OutboxRelay(outbox, recording, clock, LEASE, 10).relayOnce()).isEqualTo(2);
        assertThat(delivered).containsExactlyElementsOf(events);
    }

    @Test
    @DisplayName("an entry the codec cannot read stops the relay before anything of its batch is delivered")
    void unreadableEntryStops() {
        fill(1);
        outbox.entries.add(new OutboxEntry("bad", UUID.randomUUID(), clock.now, "keel.catalog.x", "{\"not\":1}"));

        assertThatThrownBy(() -> new OutboxRelay(outbox, recording, clock, LEASE, 10).relayOnce())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("outbox entry bad");
        assertThat(delivered).isEmpty();
        assertThat(outbox.entries).hasSize(2);
    }
}
