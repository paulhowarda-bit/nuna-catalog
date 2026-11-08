package fidice.catalog.store.suite;

import static fidice.catalog.store.suite.Changes.fullChange;
import static fidice.catalog.store.suite.Changes.now;
import static fidice.catalog.store.suite.Commits.advance;
import static fidice.catalog.store.suite.Commits.newKey;
import static org.assertj.core.api.Assertions.assertThat;

import fidice.catalog.store.CatalogRecord;
import fidice.catalog.store.CatalogSettings;
import fidice.catalog.store.Change;
import fidice.catalog.store.CommitBatch;
import fidice.catalog.store.CommitResult;
import fidice.catalog.store.OutboxBatch;
import fidice.catalog.store.OutboxEntry;
import fidice.catalog.store.OutboxEvent;
import fidice.catalog.store.Pointer;
import fidice.catalog.store.PointerKey;
import fidice.catalog.store.StorageProfile;
import fidice.catalog.store.WriteResult;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The outbox relay contract (docs/storage-design.md 9 b). The relay lease is judged by the
 * caller's clock, so each test moves a clock of its own an hour on, past every earlier lease.
 */
public abstract class OutboxSuite extends GroupSuite {

    private static final Duration LEASE = Duration.ofSeconds(30);
    private final UUID relay = UUID.randomUUID();
    private Instant clock = now().plus(Duration.ofDays(1));

    private Instant tick() {
        clock = clock.plus(Duration.ofHours(1));
        return clock;
    }

    /** Relays and deletes everything the earlier tests left, so this test sees only its own events. */
    private void drain(Instant now) {
        relayAll(now, 1_000);
    }

    /** Claims and relays batches of {@code limit} until none is left; returns every entry claimed, in order. */
    private List<OutboxEntry> relayAll(Instant now, int limit) {
        List<OutboxEntry> relayed = new ArrayList<>();
        for (int round = 0; round < 10_000; round++) {
            OutboxBatch batch = store.claimOutbox(relay, now, LEASE, limit).orElseThrow();
            assertThat(batch.entries()).hasSizeLessThanOrEqualTo(limit);
            if (batch.entries().isEmpty()) {
                return relayed;
            }
            relayed.addAll(batch.entries());
            assertThat(store.markRelayed(relay, ids(batch.entries()), now)).isEqualTo(WriteResult.APPLIED);
        }
        throw new AssertionError("the outbox never emptied: relayed entries are not deleted");
    }

    private static List<String> ids(List<OutboxEntry> entries) {
        return entries.stream().map(OutboxEntry::id).toList();
    }

    private static List<OutboxEntry> of(OutboxBatch batch, Set<UUID> changes) {
        return batch.entries().stream().filter(entry -> changes.contains(entry.changeId())).toList();
    }

    /** A change that writes its outbox events and nothing else that a listing would show. */
    private Change writeChange() {
        Change change = fullChange();
        StorageProfile profile = new StorageProfile(UUID.randomUUID(), "outbox", StorageProfile.Kind.S3, Optional.empty(),
                "arn:aws:iam::000000000000:role/outbox", List.of());
        assertThat(store.putStorageProfile(profile, change)).isEqualTo(WriteResult.APPLIED);
        return change;
    }

    private CommitBatch commitWithEvents(PointerKey key, Optional<Pointer> base, String... types) {
        CommitBatch batch = Commits.batch(advance(key, base, "outbox-" + UUID.randomUUID()));
        List<OutboxEvent> events = new ArrayList<>();
        for (String type : types) {
            events.add(new OutboxEvent(type, "{\"version\":" + Commits.next(batch).version() + "}"));
        }
        CommitBatch withEvents = new CommitBatch(batch.commitId(), now(), batch.swaps(), events, Optional.empty());
        assertThat(store.commit(withEvents)).isEqualTo(new CommitResult.Committed(withEvents.commitId()));
        return withEvents;
    }

    @Test
    @DisplayName("Outbox: a relay claims committed events in order with stable ids, until it marks them relayed")
    void claimedEventsStayUntilRelayed() {
        Instant now = tick();
        drain(now);
        Change change = writeChange();
        CommitBatch commit = commitWithEvents(newKey(), Optional.empty(), "table.committed", "audit.recorded");
        Set<UUID> ours = Set.of(change.id(), commit.commitId());

        List<OutboxEntry> claimed = of(store.claimOutbox(relay, now, LEASE, 100).orElseThrow(), ours);
        List<OutboxEntry> again = of(store.claimOutbox(relay, now, LEASE, 100).orElseThrow(), ours);

        assertThat(claimed).extracting(OutboxEntry::changeId)
                .containsExactly(change.id(), change.id(), commit.commitId(), commit.commitId());
        assertThat(claimed).extracting(OutboxEntry::type)
                .containsExactly("suite.changed", "audit.recorded", "table.committed", "audit.recorded");
        assertThat(claimed.getFirst().payloadJson()).isEqualTo(change.outbox().getFirst().payloadJson());
        assertThat(claimed.get(2).createdAt()).isEqualTo(commit.committedAt());
        assertThat(ids(claimed)).doesNotHaveDuplicates();
        assertThat(again).as("unrelayed entries are claimed again, with the same ids").isEqualTo(claimed);
        assertThat(store.markRelayed(relay, ids(claimed), now)).isEqualTo(WriteResult.APPLIED);
        assertThat(of(store.claimOutbox(relay, now, LEASE, 100).orElseThrow(), ours)).isEmpty();
    }

    @Test
    @DisplayName("Outbox: one relay at a time; a lapsed lease passes to another relay, which gets the unrelayed events")
    void leasePassesWhenItLapses() {
        Instant now = tick();
        drain(now);
        Change change = writeChange();
        UUID other = UUID.randomUUID();
        OutboxBatch first = store.claimOutbox(relay, now, LEASE, 100).orElseThrow();

        assertThat(store.claimOutbox(other, now.plus(LEASE).minusSeconds(1), LEASE, 100)).isEmpty();
        Instant lapsed = now.plus(LEASE).plusSeconds(1);
        OutboxBatch second = store.claimOutbox(other, lapsed, LEASE, 100).orElseThrow();

        assertThat(second.relayId()).isEqualTo(other);
        assertThat(of(second, Set.of(change.id()))).isEqualTo(of(first, Set.of(change.id()))).hasSize(2);
        assertThat(store.markRelayed(relay, ids(first.entries()), lapsed))
                .isEqualTo(new WriteResult.Refused(WriteResult.Reason.NOT_HOLDER));
        assertThat(store.markRelayed(other, ids(second.entries()), lapsed)).isEqualTo(WriteResult.APPLIED);
        clock = lapsed.plus(Duration.ofHours(1));
    }

    @Test
    @DisplayName("Outbox: claims respect the limit and resume after the entries already relayed")
    void claimsRespectTheLimit() {
        Instant now = tick();
        drain(now);
        List<UUID> changes = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            changes.add(writeChange().id());
        }
        List<UUID> seen = relayAll(now, 3).stream().map(OutboxEntry::changeId).toList();

        assertThat(seen).containsExactly(changes.get(0), changes.get(0), changes.get(1), changes.get(1),
                changes.get(2), changes.get(2), changes.get(3), changes.get(3));
    }

    @Test
    @DisplayName("Outbox: an event whose transaction commits after later events were relayed is still claimed (no gaps)")
    void lateCommittingEventIsNotSkipped() throws Exception {
        Instant now = tick();
        drain(now);
        PointerKey heldKey = newKey();
        CommitBatch held = new CommitBatch(UUID.randomUUID(), now(), List.of(advance(heldKey, Optional.empty(), "late")),
                List.of(new OutboxEvent("table.committed", "{\"late\":true}")), Optional.empty());
        CountDownLatch atCommit = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        var holding = underTest().store(Commits.before(held, () -> {
            atCommit.countDown();
            CommitSuite.awaitLatch(release);
        }));
        CompletableFuture<CommitResult> lateCommit = CompletableFuture.supplyAsync(() -> holding.commit(held), background);
        assertThat(atCommit.await(60, TimeUnit.SECONDS)).isTrue();
        CommitBatch early = commitWithEvents(newKey(), Optional.empty(), "table.committed");

        OutboxBatch first = store.claimOutbox(relay, now, LEASE, 100).orElseThrow();
        assertThat(of(first, Set.of(held.commitId(), early.commitId()))).extracting(OutboxEntry::changeId)
                .containsExactly(early.commitId());
        assertThat(store.markRelayed(relay, ids(first.entries()), now)).isEqualTo(WriteResult.APPLIED);
        release.countDown();
        assertThat(lateCommit.get(60, TimeUnit.SECONDS)).isEqualTo(new CommitResult.Committed(held.commitId()));

        OutboxBatch second = store.claimOutbox(relay, now, LEASE, 100).orElseThrow();
        assertThat(of(second, Set.of(held.commitId()))).extracting(OutboxEntry::payloadJson).containsExactly("{\"late\":true}");
    }

    @Test
    @DisplayName("Outbox: events of successive commits to one pointer are claimed in commit order")
    void eventsOfOnePointerComeInCommitOrder() {
        Instant now = tick();
        drain(now);
        PointerKey key = newKey();
        Optional<Pointer> current = Optional.empty();
        List<UUID> commits = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            CommitBatch batch = commitWithEvents(key, current, "table.committed");
            commits.add(batch.commitId());
            current = Optional.of(Commits.next(batch));
        }

        List<OutboxEntry> claimed = relayAll(now, 7).stream().filter(entry -> commits.contains(entry.changeId())).toList();

        assertThat(claimed).extracting(OutboxEntry::changeId).containsExactlyElementsOf(commits);
    }

    @Test
    @DisplayName("Outbox: a creation's events and a catalog's events are written with them")
    void creationsWriteTheirEvents() {
        Instant now = tick();
        drain(now);
        Change change = fullChange();
        StorageProfile profile = new StorageProfile(UUID.randomUUID(), "p", StorageProfile.Kind.CEPH,
                Optional.of("https://ceph.suite"), "role", List.of("s3://a/"));
        assertThat(store.putStorageProfile(profile, Changes.change())).isEqualTo(WriteResult.APPLIED);
        UUID catalog = UUID.randomUUID();
        assertThat(store.createCatalog(new CatalogRecord(catalog, "c-" + catalog, "C-" + catalog),
                new CatalogSettings(profile.id(), "kms", "entity"), change)).isEqualTo(WriteResult.APPLIED);

        assertThat(of(store.claimOutbox(relay, now, LEASE, 100).orElseThrow(), Set.of(change.id())))
                .extracting(OutboxEntry::type).containsExactly("suite.changed", "audit.recorded");
    }
}
