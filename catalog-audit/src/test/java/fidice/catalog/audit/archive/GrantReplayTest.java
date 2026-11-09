package fidice.catalog.audit.archive;

import static org.assertj.core.api.Assertions.assertThat;

import fidice.catalog.audit.Actor;
import fidice.catalog.audit.CatalogEvent;
import fidice.catalog.audit.GrantChange;
import fidice.catalog.audit.SampleEvents;
import fidice.catalog.audit.Securable;
import fidice.catalog.store.Change;
import fidice.catalog.store.Grant;
import fidice.catalog.store.GrantRecord;
import fidice.catalog.store.GrantStore;
import fidice.catalog.store.OutboxEvent;
import fidice.catalog.store.Page;
import fidice.catalog.store.PageRequest;
import fidice.catalog.store.WriteResult;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** D37: grant changes replayed from the archive bring a restored store back to the last grants. */
class GrantReplayTest {

    private static final UUID CATALOG = UUID.randomUUID();
    private static final Actor ADMIN = new Actor("grant-admin", null, "console", "req-1");
    private static final Securable ORDERS = new Securable(Securable.Kind.TABLE, UUID.randomUUID(), "Trading",
            List.of("eq", "orders"));
    private static final Securable TRADES = new Securable(Securable.Kind.TABLE, UUID.randomUUID(), "Trading",
            List.of("eq", "trades"));

    /** Grants as a set, with the answers the store contract gives. */
    private static final class MemoryGrants implements GrantStore {
        final Set<Grant> grants = new HashSet<>();
        final List<Change> changes = new ArrayList<>();
        final Set<UUID> missing = new HashSet<>();

        @Override
        public WriteResult putGrant(Grant grant, String grantedBy, Change change) {
            if (missing.contains(grant.securable().id())) {
                return new WriteResult.Missing(WriteResult.Subject.OBJECT);
            }
            if (!grants.add(grant)) {
                return WriteResult.UNCHANGED;
            }
            changes.add(change);
            return WriteResult.APPLIED;
        }

        @Override
        public WriteResult revokeGrant(Grant grant, Change change) {
            if (!grants.remove(grant)) {
                return new WriteResult.Missing(WriteResult.Subject.GRANT);
            }
            changes.add(change);
            return WriteResult.APPLIED;
        }

        @Override
        public List<GrantRecord> grantsFor(Set<fidice.catalog.store.Securable> securables, Set<String> groups) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Page<GrantRecord> listGrantsOfGroups(UUID catalogId, Set<String> groups, PageRequest page) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Page<GrantRecord> listGrantsOn(fidice.catalog.store.Securable securable, PageRequest page) {
            throw new UnsupportedOperationException();
        }
    }

    private static CatalogEvent change(GrantChange.Action action, Securable on, String group, int n) {
        return SampleEvents.event(new GrantChange(action, on, group, "select", ADMIN), n);
    }

    private static Grant grant(Securable on, String group) {
        return new Grant(CATALOG, new fidice.catalog.store.Securable(fidice.catalog.store.Securable.Kind.TABLE, on.id()),
                group, "select");
    }

    private final List<CatalogEvent> archived = List.of(
            change(GrantChange.Action.GRANTED, ORDERS, "readers", 1),
            SampleEvents.event(SampleEvents.oneOfEachType().getFirst(), 2),
            change(GrantChange.Action.GRANTED, TRADES, "readers", 3),
            change(GrantChange.Action.REVOKED, ORDERS, "readers", 4),
            change(GrantChange.Action.GRANTED, ORDERS, "auditors", 5),
            change(GrantChange.Action.GRANTED, ORDERS, "readers", 6),
            change(GrantChange.Action.REVOKED, TRADES, "readers", 7));

    private static Change replayChange(GrantChange grantChange) {
        return new Change(UUID.randomUUID(), Instant.now(), List.of(new OutboxEvent("fidice.catalog.grant", "{}")),
                Optional.empty());
    }

    @Test
    @DisplayName("net keeps each grant's last change, in the order those changes were made")
    void netKeepsTheLastChange() {
        assertThat(GrantReplay.net(archived)).extracting(GrantChange::action, c -> c.securable().id(), GrantChange::group)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(GrantChange.Action.GRANTED, ORDERS.id(), "auditors"),
                        org.assertj.core.groups.Tuple.tuple(GrantChange.Action.GRANTED, ORDERS.id(), "readers"),
                        org.assertj.core.groups.Tuple.tuple(GrantChange.Action.REVOKED, TRADES.id(), "readers"));
    }

    @Test
    @DisplayName("a store restored from before the newest changes ends with exactly the archive's grants, and a second replay changes nothing")
    void restoredStoreCatchesUp() {
        MemoryGrants restored = new MemoryGrants();
        restored.grants.add(grant(TRADES, "readers"));
        List<GrantChange> net = GrantReplay.net(archived);

        GrantReplay.Report report = GrantReplay.apply(restored, net, name -> Optional.of(CATALOG),
                GrantReplayTest::replayChange);

        assertThat(restored.grants).containsExactlyInAnyOrder(grant(ORDERS, "auditors"), grant(ORDERS, "readers"));
        assertThat(report).isEqualTo(new GrantReplay.Report(2, 1, 0, List.of()));
        assertThat(restored.changes).as("each replayed change is written with its own audit event").hasSize(3);

        assertThat(GrantReplay.apply(restored, net, name -> Optional.of(CATALOG), GrantReplayTest::replayChange))
                .isEqualTo(new GrantReplay.Report(0, 0, 3, List.of()));
    }

    @Test
    @DisplayName("a change whose catalog or securable is gone is reported, not applied")
    void unresolved() {
        MemoryGrants restored = new MemoryGrants();
        restored.missing.add(ORDERS.id());
        List<GrantChange> net = GrantReplay.net(archived);

        GrantReplay.Report report = GrantReplay.apply(restored, net, name -> Optional.of(CATALOG),
                GrantReplayTest::replayChange);
        assertThat(report.unresolved()).hasSize(2).allMatch(c -> c.securable().id().equals(ORDERS.id()));

        assertThat(GrantReplay.apply(new MemoryGrants(), net, name -> Optional.empty(), GrantReplayTest::replayChange)
                .unresolved()).hasSize(3);
    }
}
