package nuna.catalog.core.commit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import nuna.catalog.core.Names;
import nuna.catalog.core.commit.CommitReply.Failure;
import nuna.catalog.core.commit.CommitReply.Success;
import nuna.catalog.core.commit.Merge.Outcome;
import nuna.catalog.core.principal.Caller;
import nuna.catalog.core.principal.Principal;
import nuna.catalog.iceberg.Metadata;
import nuna.catalog.iceberg.Outcome.NewMetadata;
import nuna.catalog.store.CatalogObject;
import nuna.catalog.store.CatalogStore;
import nuna.catalog.store.Change;
import nuna.catalog.store.CommitBatch;
import nuna.catalog.store.CommitLookup;
import nuna.catalog.store.CommitResult;
import nuna.catalog.store.GovernanceAttribute;
import nuna.catalog.store.JournalRecord;
import nuna.catalog.store.LegalHold;
import nuna.catalog.store.Name;
import nuna.catalog.store.NamespaceRecord;
import nuna.catalog.store.OutboxEvent;
import nuna.catalog.store.Page;
import nuna.catalog.store.PageRequest;
import nuna.catalog.store.PageToken;
import nuna.catalog.store.Pointer;
import nuna.catalog.store.PointerHold;
import nuna.catalog.store.PointerKey;
import nuna.catalog.store.PointerSwap;
import nuna.catalog.store.Ref;
import nuna.catalog.store.RefEntry;
import nuna.catalog.store.RefLock;
import nuna.catalog.store.Securable;
import nuna.catalog.store.WriteResult;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.iceberg.TableMetadata;
import org.apache.iceberg.TableMetadataParser;
import org.apache.iceberg.view.ViewMetadataParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Merging a branch into main (D33, backlog 107): a table or view only the branch changed moves onto main as main's own
 * copy of the branch's metadata, at main's next version after main's file, in one batch that holds the branch's own
 * pointers and ends the branch; one main changed too, beyond its own maintenance, or dropped, is a conflict and
 * nothing moves; main's maintenance is rebased over; each table is judged by main's rules on the whole change from
 * main's metadata to the branch's, a DR freeze and a legal hold included; and the cap, the dry run, the race with main
 * and D11a. Against a store double that judges a merge's batch as the stores do (RefSuite checks them), with metadata
 * files in memory, so it needs only Java.
 */
class MergeTest {

    private static final Caller ALICE = new Caller(new Principal("alice", Set.of("engineers")), "spark", false,
            Optional.empty());
    private static final Caller BOB = new Caller(new Principal("bob", Set.of("engineers")), "trino", false,
            Optional.empty());
    /** The wall clock's, as Iceberg stamps the metadata it writes with it. */
    private static final Instant START = Instant.now().truncatedTo(ChronoUnit.MILLIS);
    private static final Duration DAY = Duration.ofDays(1);

    private final UUID catalogId = UUID.randomUUID();
    private final NamespaceRecord sales = new NamespaceRecord(catalogId, UUID.randomUUID(), catalogId,
            name("Sales"), Map.of(), 1);
    private final MemoryFiles files = new MemoryFiles();
    private final AtomicReference<Instant> now = new AtomicReference<>(START);
    private final Clock clock = new Clock() {
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
            return now.get();
        }
    };

    private final Map<UUID, CatalogObject> objects = new LinkedHashMap<>();
    private final Map<PointerKey, Pointer> pointers = new HashMap<>();
    private final Map<UUID, Ref> refs = new HashMap<>();
    private final Map<UUID, List<RefEntry>> cuts = new HashMap<>();
    private final List<JournalRecord> journal = new CopyOnWriteArrayList<>();
    private final List<CommitBatch> landed = new CopyOnWriteArrayList<>();
    private final List<Change> ended = new CopyOnWriteArrayList<>();
    private final List<GovernanceAttribute> attributes = new CopyOnWriteArrayList<>();
    private final List<LegalHold> holds = new CopyOnWriteArrayList<>();
    private final List<CommitEvents.Commit> events = new CopyOnWriteArrayList<>();
    private final List<UUID> alerted = new CopyOnWriteArrayList<>();
    /** Runs once, before the store judges the next batch: what lands between a merge's plan and its swap. */
    private final AtomicReference<Runnable> beforeNextCommit = new AtomicReference<>();
    private final CatalogStore store = store();
    /** As the server has them: the standard rules, and the minimum snapshot age with retention class ledger's. */
    private final List<CommitRule> rules = new ArrayList<>(Rules.standard(files, Duration.ofSeconds(60)));

    {
        rules.add(Rules.snapshotMinimumAge(Duration.ofHours(1), retentionClass -> retentionClass.equals("ledger")
                ? Optional.of(Duration.ofDays(30)) : Optional.empty()));
    }

    // ---- what moves ----

    @Test
    @DisplayName("a table only the branch changed moves: main takes its own copy of the branch's metadata, unmarked,"
            + " at its next version after main's file; one batch swaps main, holds the branch's pointers and ends it")
    void aTableChangedOnlyOnTheBranchMoves() {
        CatalogObject orders = withSnapshots(table("orders"));
        CatalogObject customers = table("customers");
        Pointer cut = pointers.get(orders.mainKey());
        Ref dev = branch("dev", orders, customers);
        Success head = success(on(dev, orders, append(3, 2L, 3, 20)));
        success(on(dev, orders, properties("stage", "dev")));
        Pointer own = pointers.get(dev.key(orders.id()));
        int written = files.writes.get();

        Outcome.Merged merged = merged(merge(dev));

        Pointer main = pointers.get(orders.mainKey());
        assertThat(main.version()).isEqualTo(cut.version() + 1);
        assertThat(TableFiles.onBranch(main.metadataLocation())).as("main's own file, never the branch's").isFalse();
        assertThat(main.metadataLocation()).startsWith(orders.locationRoot() + "/metadata/0000" + main.version() + "-");
        TableMetadata onMain = TableMetadataParser.fromJson(files.read(main.metadataLocation(), main.sha256()).json());
        assertThat(onMain.properties()).containsEntry("stage", "dev");
        assertThat(onMain.currentSnapshot().snapshotId()).isEqualTo(3L);
        assertThat(onMain.previousFiles().getLast().file()).as("its predecessor is main's file")
                .isEqualTo(cut.metadataLocation());
        assertThat(onMain.previousFiles()).noneMatch(entry -> TableFiles.onBranch(entry.file()));
        assertThat(files.writes.get() - written).as("one copy, for the one table moved").isEqualTo(1);
        assertThat(pointers.get(customers.mainKey()).version()).as("unchanged on the branch: not moved").isEqualTo(1);
        assertThat(head.metadataLocation()).contains(TableFiles.BRANCH_MARK);

        CommitBatch batch = landed.getLast();
        assertThat(batch.ref()).contains(new RefLock.End(dev.id()));
        assertThat(batch.swaps()).singleElement().satisfies(swap -> {
            assertThat(swap.key()).isEqualTo(orders.mainKey());
            assertThat(swap.expected()).map(Pointer::version).contains(cut.version());
            assertThat(swap.journal().principal()).isEqualTo("alice");
            assertThat(swap.journal().updatesJson()).contains("\"action\":\"merge-branch\"", "\"branch\":\"dev\"",
                    "\"branch-id\":\"" + dev.id() + "\"", "\"branch-version\":2", "\"cut-version\":" + cut.version(),
                    "\"branch-location\":\"" + own.metadataLocation() + "\"", "\"rebased-over\":0");
            assertThat(swap.journal().updatesJson()).as("a property set on the branch")
                    .contains("\"definition-change\":\"properties [stage] changed\"");
        });
        assertThat(batch.holds()).containsExactly(new PointerHold(dev.key(orders.id()), own));
        assertThat(batch.outbox()).extracting(OutboxEvent::type).containsExactly("test.commit", "test.ref");
        assertThat(events.getLast().branch()).isEqualTo(PointerKey.MAIN);
        assertThat(events.getLast().commitId()).isEqualTo(batch.commitId());
        assertThat(events.getLast().before()).map(Pointer::version).contains(cut.version());
        assertThat(refs).as("the merge ends the branch").doesNotContainKey(dev.id());
        assertThat(pointers.keySet()).noneMatch(key -> key.branch().equals(dev.id().toString()));
        assertThat(merged.moved()).singleElement().satisfies(moved -> {
            assertThat(moved.before().version()).isEqualTo(cut.version());
            assertThat(moved.after()).isEqualTo(main);
            assertThat(moved.table().rebased()).isFalse();
        });
    }

    @Test
    @DisplayName("a view only the branch changed moves as a table does; one main replaced since the cut conflicts")
    void aViewMovesOrConflicts() {
        CatalogObject daily = view("daily");
        CatalogObject weekly = view("weekly");
        Ref dev = branch("dev", daily, weekly);
        success(on(dev).commitView(name(daily), properties("comment", "daily"), BOB, Optional.empty()));
        success(on(dev).commitView(name(weekly), properties("comment", "weekly"), BOB, Optional.empty()));
        success(engine().commitView(name(weekly), properties("comment", "on main"), ALICE, Optional.empty()));

        Merge.Plan plan = engine().merging().plan(dev, 100);

        assertThat(plan.conflicts()).singleElement().satisfies(table -> {
            assertThat(table.entry().name().display()).isEqualTo("weekly");
            assertThat(table.conflict()).contains("main replaced the view since the cut");
        });
        Ref only = branch("only", daily);
        success(on(only).commitView(name(daily), properties("comment", "only"), BOB, Optional.empty()));

        merged(merge(only));

        Pointer main = pointers.get(daily.mainKey());
        assertThat(main.version()).isEqualTo(2);
        assertThat(TableFiles.onBranch(main.metadataLocation())).isFalse();
        assertThat(ViewMetadataParser.fromJson(files.read(main.metadataLocation(), main.sha256()).json()).properties())
                .containsEntry("comment", "only");
    }

    @Test
    @DisplayName("a branch that changed nothing merges by ending, with the merge's record and nothing swapped")
    void aBranchThatChangedNothingEnds() {
        CatalogObject orders = table("orders");
        Ref dev = branch("dev", orders);

        Outcome.Merged merged = merged(merge(dev));

        assertThat(merged.moved()).isEmpty();
        assertThat(refs).doesNotContainKey(dev.id());
        assertThat(ended).hasSize(1);
        assertThat(merges()).isEmpty();
    }

    // ---- conflicts ----

    @Test
    @DisplayName("a table changed on both sides conflicts, as does one dropped on main: the plan names every"
            + " conflict, with main's commits since the cut and who made them, and nothing moves")
    void conflictsMoveNothing() {
        CatalogObject orders = withSnapshots(table("orders"));
        CatalogObject returns = table("returns");
        CatalogObject customers = table("customers");
        Ref dev = branch("dev", orders, returns, customers);
        success(on(dev, orders, append(3, 2L, 3, 20)));
        success(on(dev, returns, properties("a", "1")));
        success(on(dev, customers, properties("b", "2")));
        success(engine().commitTable(name(orders), append(4, 2L, 3, 20), ALICE, Optional.empty()));
        success(engine().commitTable(name(orders), properties("c", "3"), BOB, Optional.empty()));
        drop(returns);
        int written = files.writes.get();

        Outcome outcome = merge(dev);

        Merge.Plan plan = refused(outcome);
        assertThat(plan.conflicts()).hasSize(2);
        assertThat(plan.conflicts().getFirst()).satisfies(table -> {
            assertThat(table.entry().name().display()).isEqualTo("orders");
            assertThat(table.conflict()).contains("properties [c] changed");
            assertThat(table.cut().version()).isEqualTo(2);
            assertThat(table.main()).map(Pointer::version).contains(4L);
            assertThat(table.branch().version()).isEqualTo(1);
            assertThat(table.since()).isEqualTo(new Merge.Since(2, List.of("alice", "bob")));
        });
        assertThat(plan.conflicts().get(1)).satisfies(table -> {
            assertThat(table.entry().name().display()).isEqualTo("returns");
            assertThat(table.conflict()).contains("main has dropped at " + START + " it");
            assertThat(table.object()).map(CatalogObject::state).contains(CatalogObject.State.DROPPED);
        });
        assertThat(plan.tables()).filteredOn(Merge.Table::moves).extracting(t -> t.entry().name().display())
                .containsExactly("customers");
        assertThat(files.writes.get()).as("nothing written").isEqualTo(written);
        assertThat(merges()).isEmpty();
        assertThat(refs).containsKey(dev.id());
        assertThat(pointers.get(customers.mainKey()).version()).isEqualTo(1);
    }

    @Test
    @DisplayName("main's own maintenance is no conflict: after compaction and snapshot expiry on main the merge"
            + " rebases over them, main taking the branch's version, and the minimum snapshot age is not asked of"
            + " the snapshots main's maintenance made")
    void aRebaseOverCompactionAndExpiry() {
        CatalogObject orders = withSnapshots(table("orders"));
        Pointer cut = pointers.get(orders.mainKey());
        Ref dev = branch("dev", orders);
        success(on(dev, orders, append(3, 2L, 3, 20)));
        // The night's compaction, then its expiry of the oldest snapshot, as the maintenance worker commits them.
        success(engine().commitTable(name(orders), Requests.update("", replace(4, 2L, 3, 0), Requests.setMain(4)),
                ALICE, Optional.empty()));
        success(engine().commitTable(name(orders), Requests.update("", Requests.removeSnapshots(1)), ALICE,
                Optional.empty()));
        // Compaction's snapshot is younger than the minimum age, and a retention class's 30 days: a merge that judged
        // its loss would be refused.
        attributes.add(attribute(GovernanceAttribute.Kind.RETENTION_CLASS, "ledger"));

        Outcome.Merged merged = merged(merge(dev));

        Pointer main = pointers.get(orders.mainKey());
        assertThat(main.version()).isEqualTo(cut.version() + 3);
        TableMetadata onMain = TableMetadataParser.fromJson(files.read(main.metadataLocation(), main.sha256()).json());
        assertThat(onMain.currentSnapshot().snapshotId()).as("the branch's version").isEqualTo(3L);
        assertThat(onMain.snapshots()).extracting(s -> s.snapshotId()).containsExactly(1L, 2L, 3L);
        assertThat(merged.moved().getFirst().table().rebased()).isTrue();
        assertThat(merged.moved().getFirst().table().since().commits()).isEqualTo(2);
        assertThat(landed.getLast().swaps().getFirst().journal().updatesJson()).contains("\"rebased-over\":2");
    }

    @Test
    @DisplayName("a merge that loses its swap to a commit on main plans again within P10's bound: after main's"
            + " maintenance it lands rebased, after main's data it conflicts, after a commit on the branch it takes it")
    void aRaceWithMain() {
        CatalogObject orders = withSnapshots(table("orders"));
        Ref dev = branch("dev", orders);
        success(on(dev, orders, append(3, 2L, 3, 20)));
        beforeNextCommit.set(() -> success(engine().commitTable(name(orders), Requests.update("",
                replace(4, 2L, 3, 0), Requests.setMain(4)), ALICE, Optional.empty())));

        merged(merge(dev));
        assertThat(landed.getLast().swaps().getFirst().expected()).map(Pointer::version).contains(3L);
        assertThat(landed.getLast().swaps().getFirst().journal().updatesJson()).contains("\"rebased-over\":1");

        CatalogObject returns = withSnapshots(table("returns"));
        Ref qa = branch("qa", returns);
        success(on(qa, returns, append(3, 2L, 3, 20)));
        beforeNextCommit.set(() -> success(engine().commitTable(name(returns), append(4, 2L, 3, 20), ALICE,
                Optional.empty())));
        Outcome lost = merge(qa);
        assertThat(refused(lost).conflicts()).singleElement()
                .satisfies(table -> assertThat(table.conflict()).contains("main committed snapshot 4 (append)"));
        assertThat(refs).containsKey(qa.id());

        CatalogObject customers = table("customers");
        CatalogObject stores = table("stores");
        Ref fix = branch("fix", customers, stores);
        success(on(fix, customers, properties("a", "1")));
        beforeNextCommit.set(() -> success(on(fix, stores, properties("b", "2"))));
        Outcome.Merged both = merged(merge(fix));
        assertThat(both.moved()).extracting(moved -> moved.table().entry().name().display())
                .containsExactlyInAnyOrder("customers", "stores");
        assertThat(pointers.get(stores.mainKey()).version()).isEqualTo(2);
    }

    @Test
    @DisplayName("a branch merged or deleted before the merge's swap answers gone")
    void aBranchGoneBeforeTheSwap() {
        CatalogObject orders = table("orders");
        Ref dev = branch("dev", orders);
        success(on(dev, orders, properties("a", "1")));
        beforeNextCommit.set(() -> refs.remove(dev.id()));

        assertThat(merge(dev)).isInstanceOf(Outcome.Gone.class);
        assertThat(pointers.get(orders.mainKey()).version()).isEqualTo(1);
    }

    // ---- the cap ----

    @Test
    @DisplayName("a branch that changed more tables than one merge moves is refused before anything is read")
    void theCap() {
        CatalogObject a = table("a");
        CatalogObject b = table("b");
        CatalogObject c = table("c");
        Ref dev = branch("dev", a, b, c);
        for (CatalogObject table : List.of(a, b, c)) {
            success(on(dev, table, properties("x", "1")));
        }
        int reads = files.reads.get();

        Merge.Plan plan = refused(engine().merging().merge(dev, 2, ALICE, over -> {
            throw new AssertionError("no rights are asked of a branch over the cap");
        }, this::change));

        assertThat(plan.overCap()).isTrue();
        assertThat(plan.changed()).isEqualTo(3);
        assertThat(plan.tables()).isEmpty();
        assertThat(files.reads.get()).isEqualTo(reads);
        assertThat(engine().merging().plan(dev, 3).overCap()).isFalse();
    }

    // ---- judged as a commit on main ----

    @Test
    @DisplayName("what the branch changed is judged on the whole change: a schema, property or partitioning change"
            + " is a definition change, which needs modify; snapshots and refs alone need write")
    void aSchemaChangeNeedsModify() {
        CatalogObject orders = withSnapshots(table("orders"));
        CatalogObject returns = table("returns");
        CatalogObject stores = table("stores");
        Ref dev = branch("dev", orders, returns, stores);
        success(on(dev, orders, append(3, 2L, 3, 20)));
        success(on(dev, orders, Requests.update("", Requests.removeSnapshots(1))));
        success(on(dev, returns, Requests.update("", Requests.addColumn(1, "note"))));
        success(on(dev, stores, properties("owner", "ops")));

        Merge.Plan plan = engine().merging().plan(dev, 100);

        Map<String, Optional<String>> definitions = new HashMap<>();
        plan.tables().forEach(table -> definitions.put(table.entry().name().display(), table.definition()));
        assertThat(definitions.get("orders")).as("data alone").isEmpty();
        assertThat(definitions.get("returns")).contains("current schema 0 became 1");
        assertThat(definitions.get("stores")).contains("properties [owner] changed");
        List<String> asked = new ArrayList<>();
        assertThatThrownBy(() -> engine().merging().merge(dev, 100, ALICE, rights -> {
            rights.tables().forEach(table -> asked.add(table.entry().name().display()));
            throw new IllegalStateException("alice holds write only");
        }, this::change)).hasMessage("alice holds write only");
        assertThat(asked).containsExactlyInAnyOrder("orders", "returns", "stores");
        assertThat(merges()).isEmpty();
        assertThat(refs).containsKey(dev.id());
    }

    @Test
    @DisplayName("a hold set after the branch removed snapshots refuses the merge as legal_hold, though one set after"
            + " a branch only added data lets it through")
    void aHoldSetAfterTheBranchCommit() {
        CatalogObject orders = withSnapshots(table("orders"));
        CatalogObject returns = withSnapshots(table("returns"));
        Ref dev = branch("dev", orders);
        Ref qa = branch("qa", returns);
        success(on(dev, orders, Requests.update("", Requests.removeSnapshots(1))));
        success(on(qa, returns, append(3, 2L, 3, 20)));
        holds.add(new LegalHold(UUID.randomUUID(), orders.securable(), "litigation", "legal", START));
        holds.add(new LegalHold(UUID.randomUUID(), returns.securable(), "litigation", "legal", START));

        Merge.Plan plan = refused(merge(dev));

        assertThat(plan.refused()).hasValueSatisfying(table -> assertThat(table.verdict()).hasValueSatisfying(f -> {
            assertThat(f.status()).isEqualTo(403);
            assertThat(f.refusal()).map(CommitReply.Refusal::reason).contains("legal_hold");
            assertThat(f.refusal()).map(r -> r.object().id()).contains(orders.id());
        }));
        assertThat(pointers.get(orders.mainKey()).version()).isEqualTo(2);
        assertThat(refs).containsKey(dev.id());
        merged(merge(qa));
        assertThat(pointers.get(returns.mainKey()).version()).isEqualTo(3);
    }

    @Test
    @DisplayName("under a legal hold a merge may not drop the snapshots main's maintenance made: the hold keeps every"
            + " snapshot main has")
    void aHoldKeepsMainsMaintenanceSnapshots() {
        CatalogObject orders = withSnapshots(table("orders"));
        Ref dev = branch("dev", orders);
        success(on(dev, orders, append(3, 2L, 3, 20)));
        success(engine().commitTable(name(orders), Requests.update("", replace(4, 2L, 3, 0), Requests.setMain(4)),
                ALICE, Optional.empty()));
        holds.add(new LegalHold(UUID.randomUUID(), orders.securable(), "litigation", "legal", START));

        Merge.Plan plan = refused(merge(dev));

        assertThat(plan.refused()).flatMap(Merge.Table::verdict).flatMap(Failure::refusal)
                .map(CommitReply.Refusal::reason).contains("legal_hold");
    }

    @Test
    @DisplayName("a DR freeze refuses the merge, though the branch's commits to the frozen table were let through")
    void aFreezeRefusesTheMerge() {
        CatalogObject orders = table("orders");
        Ref dev = branch("dev", orders);
        attributes.add(attribute(GovernanceAttribute.Kind.DR_FREEZE, "failover drill"));
        success(on(dev, orders, properties("a", "1")));

        Merge.Plan plan = refused(merge(dev));

        Failure refused = plan.refused().flatMap(Merge.Table::verdict).orElseThrow();
        assertThat(refused.refusal()).map(CommitReply.Refusal::reason).contains("dr_freeze");
        assertThat(refused.message()).contains("frozen for DR reconcile: failover drill");
        assertThat(merges()).isEmpty();
        assertThat(pointers.get(orders.mainKey()).version()).isEqualTo(1);
    }

    @Test
    @DisplayName("every rule of the catalog's profile judges the change from main's metadata to the branch's")
    void theRulesSeeTheWholeChange() {
        CatalogObject orders = table("orders");
        Ref dev = branch("dev", orders);
        success(on(dev, orders, properties("forbidden", "yes")));
        assertThat(engine().merging().plan(dev, 100).refused()).isEmpty();

        rules.add(change -> Optional.ofNullable(change.after().properties().get("forbidden"))
                .map(value -> CommitRule.Rejection.badRequest("forbidden is " + value)));

        Failure refused = refused(merge(dev)).refused().flatMap(Merge.Table::verdict).orElseThrow();
        assertThat(refused).isEqualTo(new Failure(400, "BadRequestException", "forbidden is yes"));
    }

    @Test
    @DisplayName("D11a: a schema change merged onto a regulated table is flagged on its commit event, read in its"
            + " journal entry and alerted once it lands")
    void aRegulatedSchemaChangeIsJournaledAndAlerted() {
        CatalogObject orders = table("orders");
        Ref dev = branch("dev", orders);
        attributes.add(attribute(GovernanceAttribute.Kind.REGULATED, "true"));
        success(on(dev, orders, Requests.update("", Requests.addColumn(1, "note"))));
        alerted.clear();
        events.clear();

        merged(merge(dev));

        assertThat(events).singleElement().satisfies(event -> {
            assertThat(event.regulatedSchemaChange()).isTrue();
            assertThat(event.updatesJson()).contains("\"definition-change\":\"current schema 0 became 1\"");
        });
        assertThat(landed.getLast().swaps().getFirst().journal().updatesJson())
                .contains("\"definition-change\":\"current schema 0 became 1\"");
        assertThat(alerted).containsExactly(orders.id());
    }

    // ---- the dry run ----

    @Test
    @DisplayName("the dry run plans what a merge would do, each table with main's verdict and what it needs, and"
            + " writes nothing")
    void theDryRun() {
        CatalogObject orders = withSnapshots(table("orders"));
        CatalogObject returns = table("returns");
        Ref dev = branch("dev", orders, returns);
        success(on(dev, orders, append(3, 2L, 3, 20)));
        success(on(dev, returns, Requests.update("", Requests.addColumn(1, "note"))));
        holds.add(new LegalHold(UUID.randomUUID(), orders.securable(), "litigation", "legal", START));
        int written = files.writes.get();

        Merge.Plan plan = engine().merging().plan(dev, 100);

        assertThat(plan.changed()).isEqualTo(2);
        assertThat(plan.conflicts()).isEmpty();
        assertThat(plan.refused()).isEmpty();
        assertThat(plan.tables()).extracting(t -> t.entry().name().display()).containsExactly("orders", "returns");
        assertThat(plan.tables()).allSatisfy(table -> {
            assertThat(table.namespace()).containsExactly("Sales");
            assertThat(table.json()).isPresent();
            assertThat(table.since()).isEqualTo(new Merge.Since(0, List.of()));
        });
        assertThat(files.writes.get()).isEqualTo(written);
        assertThat(merges()).isEmpty();
        assertThat(refs).containsKey(dev.id());
    }

    // ---- the estate ----

    private Outcome merge(Ref ref) {
        return engine().merging().merge(ref, 100, ALICE, plan -> {
        }, this::change);
    }

    private final Map<UUID, UUID> changeIds = new HashMap<>();

    /** The merge's own record, under one id for every attempt of one merge. */
    private Change change(Outcome.Merged merged) {
        UUID id = changeIds.computeIfAbsent(merged.plan().ref().id(), ref -> UUID.randomUUID());
        return new Change(id, clock.instant(), List.of(new OutboxEvent("test.ref", "{}")), Optional.empty());
    }

    /** The merges' batches that landed. */
    private List<CommitBatch> merges() {
        return landed.stream().filter(batch -> batch.ref().orElse(null) instanceof RefLock.End).toList();
    }

    private static Merge.Plan refused(Outcome outcome) {
        assertThat(outcome).isInstanceOf(Outcome.Refused.class);
        return ((Outcome.Refused) outcome).plan();
    }

    private static Outcome.Merged merged(Outcome outcome) {
        assertThat(outcome).isInstanceOf(Outcome.Merged.class);
        return (Outcome.Merged) outcome;
    }

    private CommitEngine engine() {
        return new CommitEngine(store, files, new MetadataCache(1 << 22), rules, commit -> {
            events.add(commit);
            return List.of(new OutboxEvent("test.commit", "{}"));
        }, new Alerts() {
            @Override
            public void metadataIntegrity(UUID objectId, String location, String expectedSha256,
                                          String actualSha256) {
                throw new AssertionError("no file is tampered with here");
            }

            @Override
            public void regulatedSchemaChange(UUID objectId, UUID commitId) {
                alerted.add(objectId);
            }
        }, CommitSettings.DEFAULTS, clock, wait -> now.updateAndGet(at -> at.plus(wait)));
    }

    private CommitEngine on(Ref branch) {
        Map<ObjectName, RefEntry> objectsOfCut = new HashMap<>();
        cuts.get(branch.id()).stream().filter(entry -> entry.kind() != RefEntry.Kind.NAMESPACE)
                .forEach(entry -> objectsOfCut.put(new ObjectName(catalogId, entry.parentId(), entry.name()), entry));
        return engine().on(new CommitEngine.Branch(branch, 100, objectsOfCut));
    }

    /** A commit on the branch, by Bob. */
    private CommitReply on(Ref branch, CatalogObject object, String request) {
        return on(branch).commitTable(name(object), request, BOB, Optional.empty());
    }

    /** A branch made now, whose cut takes the namespace and main's pointer of each object, expiring in 30 days. */
    private Ref branch(String display, CatalogObject... cut) {
        Ref ref = new Ref(catalogId, UUID.randomUUID(), name(display), Ref.Kind.BRANCH, START, "alice",
                Optional.empty(), Ref.MAIN, Optional.of(START.plus(DAY.multipliedBy(30))));
        refs.put(ref.id(), ref);
        List<RefEntry> entries = new ArrayList<>();
        entries.add(new RefEntry(ref.id(), sales.id(), RefEntry.Kind.NAMESPACE, catalogId, sales.name(),
                Optional.empty()));
        for (CatalogObject object : cut) {
            Pointer at = pointers.get(object.mainKey());
            entries.add(new RefEntry(ref.id(), object.id(), RefEntry.of(object.kind()), object.namespaceId(),
                    object.name(), Optional.of(new RefEntry.At(at.version(), at.metadataLocation(), at.sha256()))));
        }
        cuts.put(ref.id(), entries);
        return ref;
    }

    private CatalogObject table(String display) {
        UUID id = UUID.randomUUID();
        CatalogObject table = CatalogObject.active(catalogId, id, sales.id(), name(display), CatalogObject.Kind.TABLE,
                files.newLocation(id));
        return first(table, ((NewMetadata) Metadata.createTable(Requests.createTable(display,
                Map.of("format-version", "2")), id.toString(), table.locationRoot())).json());
    }

    private CatalogObject view(String display) {
        UUID id = UUID.randomUUID();
        CatalogObject view = CatalogObject.active(catalogId, id, sales.id(), name(display), CatalogObject.Kind.VIEW,
                files.newLocation(id));
        return first(view, ((NewMetadata) Metadata.createView(Requests.createView(display), id.toString(),
                view.locationRoot(), 1)).json());
    }

    private CatalogObject first(CatalogObject object, String json) {
        TableFiles.Written written = files.write(object.locationRoot(), 1, json);
        objects.put(object.id(), object);
        pointers.put(object.mainKey(), new Pointer(written.location(), 1, written.sha256(), new byte[0]));
        return object;
    }

    /** Main commits two snapshots, two and one days old, the second main's: version 2. */
    private CatalogObject withSnapshots(CatalogObject table) {
        success(engine().commitTable(name(table), Requests.update("", snapshot(1, null, 1, DAY.multipliedBy(2),
                "append"), snapshot(2, 1L, 2, DAY, "append"), Requests.setMain(2)), ALICE, Optional.empty()));
        return table;
    }

    private CatalogObject drop(CatalogObject object) {
        CatalogObject dropped = new CatalogObject(object.catalogId(), object.id(), object.namespaceId(),
                object.name(), object.kind(), CatalogObject.State.DROPPED, Optional.of(START),
                Optional.of(START.plus(DAY)), object.locationRoot(), Optional.empty());
        objects.put(object.id(), dropped);
        return dropped;
    }

    private GovernanceAttribute attribute(GovernanceAttribute.Kind kind, String value) {
        return new GovernanceAttribute(new Securable(Securable.Kind.NAMESPACE, sales.id()), kind, value, "test",
                "admin", START);
    }

    /** An append of snapshot {@code id}, made main, stamped {@code hoursAgo} hours before the start. */
    private static String append(long id, Long parent, long sequence, int hoursAgo) {
        return Requests.update("", snapshot(id, parent, sequence, Duration.ofHours(hoursAgo), "append"),
                Requests.setMain(id));
    }

    /** A compaction's snapshot, as the maintenance worker commits one. */
    private static String replace(long id, Long parent, long sequence, int hoursAgo) {
        return snapshot(id, parent, sequence, Duration.ofHours(hoursAgo), "replace");
    }

    private static String snapshot(long id, Long parent, long sequence, Duration ago, String operation) {
        long at = START.minus(ago).toEpochMilli();
        return "{\"action\":\"add-snapshot\",\"snapshot\":{\"snapshot-id\":" + id
                + (parent == null ? "" : ",\"parent-snapshot-id\":" + parent)
                + ",\"sequence-number\":" + sequence + ",\"timestamp-ms\":" + at
                + ",\"manifest-list\":\"s3://lake/warehouse/manifests/snap-" + id + ".avro\""
                + ",\"summary\":{\"operation\":\"" + operation + "\"},\"schema-id\":0}}";
    }

    private static String properties(String key, String value) {
        return Requests.update("", Requests.setProperties(Map.of(key, value)));
    }

    private static Success success(CommitReply reply) {
        assertThat(reply).isInstanceOf(Success.class);
        return (Success) reply;
    }

    private ObjectName name(CatalogObject object) {
        return new ObjectName(catalogId, object.namespaceId(), object.name());
    }

    private static Name name(String display) {
        return new Name(Names.normalize(display), display);
    }

    /**
     * Main's objects and pointers, the branches with their cuts and own pointers, the journal and governance. A batch
     * is judged as the stores judge it: the branch it holds or ends must not have ended; a merge's must hold every own
     * pointer of the branch; every object it swaps must be active; every pointer it swaps or holds must be as it
     * expects. A merge's batch then ends the branch.
     */
    @SuppressWarnings("unchecked")
    private CatalogStore store() {
        return (CatalogStore) Proxy.newProxyInstance(CatalogStore.class.getClassLoader(),
                new Class<?>[] {CatalogStore.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "toString" -> "the test's store";
                    case "readObject" -> Optional.ofNullable(objects.get((UUID) args[0]));
                    case "findObject" -> objects.values().stream().filter(o -> o.state() == CatalogObject.State.ACTIVE
                            && o.namespaceId().equals(args[0]) && o.name().normalized().equals(args[1])).findFirst();
                    case "readNamespace" -> Optional.of(sales).filter(ns -> ns.id().equals(args[0]));
                    case "readPointer" -> Optional.ofNullable(pointers.get((PointerKey) args[0]));
                    case "readRef" -> Optional.ofNullable(refs.get((UUID) args[0]));
                    case "countOwnPointers" -> pointers.keySet().stream()
                            .filter(key -> key.branch().equals(args[0].toString())).count();
                    case "listRefNamespaces" -> page(cuts.getOrDefault((UUID) args[0], List.of()).stream()
                            .filter(e -> e.kind() == RefEntry.Kind.NAMESPACE && e.parentId().equals(args[1])).toList());
                    case "listRefObjects" -> page(cuts.getOrDefault((UUID) args[0], List.of()).stream()
                            .filter(e -> e.kind() == RefEntry.of((CatalogObject.Kind) args[2])
                                    && e.parentId().equals(args[1])).toList());
                    case "readJournal" -> {
                        long after = Long.parseLong(PageToken.decode(((PageRequest) args[3]).token().orElseThrow(), 1)
                                .getFirst());
                        yield new Page<>(journal.stream().filter(r -> r.key().equals(args[0])
                                && r.newVersion() > after).limit(((PageRequest) args[3]).size()).toList(),
                                Optional.empty());
                    }
                    case "commit" -> commit((CommitBatch) args[0]);
                    case "findCommit" -> landed.stream().anyMatch(b -> b.commitId().equals(args[0]))
                            ? CommitLookup.COMMITTED : CommitLookup.NOT_COMMITTED;
                    case "deleteRef" -> {
                        if (refs.remove((UUID) args[0]) == null) {
                            yield new WriteResult.Missing(WriteResult.Subject.REF);
                        }
                        end((UUID) args[0]);
                        ended.add((Change) args[1]);
                        yield WriteResult.APPLIED;
                    }
                    case "attributesOf" -> attributes.stream()
                            .filter(a -> ((Set<Securable>) args[0]).contains(a.target())).toList();
                    case "holdsOn" -> holds.stream().filter(h -> ((Set<Securable>) args[0]).contains(h.target()))
                            .toList();
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private static Page<RefEntry> page(List<RefEntry> entries) {
        return new Page<>(entries, Optional.empty());
    }

    private synchronized CommitResult commit(CommitBatch batch) {
        Runnable between = beforeNextCommit.getAndSet(null);
        if (between != null) {
            between.run();
        }
        PointerKey anyKey = batch.swaps().getFirst().key();
        if (batch.ref().isPresent()) {
            RefLock lock = batch.ref().get();
            Ref ref = refs.get(lock.refId());
            if (ref == null || ref.expiredAt(batch.committedAt())) {
                return new CommitResult.Inactive(anyKey, CommitResult.Subject.REF);
            }
            if (lock instanceof RefLock.End) {
                Set<PointerKey> held = new java.util.HashSet<>();
                batch.holds().forEach(hold -> held.add(hold.key()));
                Optional<PointerKey> unheld = pointers.keySet().stream().filter(lock::owns)
                        .filter(key -> !held.contains(key)).findFirst();
                if (unheld.isPresent()) {
                    return new CommitResult.Conflict(unheld.get(), Optional.of(pointers.get(unheld.get())));
                }
            }
        }
        for (PointerSwap swap : batch.swaps()) {
            CatalogObject object = objects.get(swap.key().objectId());
            if (object == null || object.state() != CatalogObject.State.ACTIVE) {
                return new CommitResult.Inactive(swap.key(), CommitResult.Subject.OBJECT);
            }
            Optional<Pointer> actual = Optional.ofNullable(pointers.get(swap.key()));
            if (!same(actual, swap.expected())) {
                return new CommitResult.Conflict(swap.key(), actual);
            }
        }
        for (PointerHold hold : batch.holds()) {
            Optional<Pointer> actual = Optional.ofNullable(pointers.get(hold.key()));
            if (!same(actual, Optional.of(hold.expected()))) {
                return new CommitResult.Conflict(hold.key(), actual);
            }
        }
        for (PointerSwap swap : batch.swaps()) {
            Optional<Pointer> before = Optional.ofNullable(pointers.get(swap.key()));
            pointers.put(swap.key(), swap.next());
            journal.add(new JournalRecord(batch.commitId(), swap.key(), batch.committedAt(), swap.journal(),
                    before.map(Pointer::metadataLocation), swap.next().metadataLocation(), swap.next().version()));
        }
        if (batch.ref().orElse(null) instanceof RefLock.End end) {
            refs.remove(end.refId());
            end(end.refId());
        }
        landed.add(batch);
        return new CommitResult.Committed(batch.commitId());
    }

    private void end(UUID refId) {
        cuts.remove(refId);
        pointers.keySet().removeIf(key -> key.branch().equals(refId.toString()));
    }

    private static boolean same(Optional<Pointer> actual, Optional<Pointer> expected) {
        return actual.map(Pointer::metadataLocation).equals(expected.map(Pointer::metadataLocation))
                && actual.map(Pointer::version).equals(expected.map(Pointer::version));
    }
}
