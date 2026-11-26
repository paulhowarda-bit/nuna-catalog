package nuna.catalog.core.commit;

import static org.assertj.core.api.Assertions.assertThat;

import nuna.catalog.core.Names;
import nuna.catalog.core.commit.CommitReply.Applied;
import nuna.catalog.core.commit.CommitReply.Failure;
import nuna.catalog.core.commit.CommitReply.How;
import nuna.catalog.core.commit.CommitReply.Success;
import nuna.catalog.core.principal.Caller;
import nuna.catalog.core.principal.Principal;
import nuna.catalog.iceberg.Metadata;
import nuna.catalog.iceberg.Outcome;
import nuna.catalog.store.CatalogObject;
import nuna.catalog.store.CatalogStore;
import nuna.catalog.store.CommitBatch;
import nuna.catalog.store.CommitLookup;
import nuna.catalog.store.CommitResult;
import nuna.catalog.store.GovernanceAttribute;
import nuna.catalog.store.IdempotencyClaim;
import nuna.catalog.store.IdempotencyResult;
import nuna.catalog.store.KeyState;
import nuna.catalog.store.LegalHold;
import nuna.catalog.store.Name;
import nuna.catalog.store.NamespaceRecord;
import nuna.catalog.store.ObjectCreation;
import nuna.catalog.store.OutboxEvent;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.iceberg.TableMetadataParser;
import org.apache.iceberg.view.ViewMetadataParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Commits on a branch (D33, item 105): each swaps the branch's own pointer of the table or view, which the branch's
 * first commit to it creates at version 1 from the pointer the branch's cut took, under a hold on the branch; main's
 * pointer never moves. Against a store double holding main's objects and pointers, the branches' own pointers, the
 * branches and the idempotency keys, judging a batch as the stores do, with metadata files in memory, so it needs no
 * database. The stores' own side of a branch's commit (the hold on the ref, its expiry at commit time) is
 * {@code RefSuite}'s, on every database.
 */
class BranchCommitTest {

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

    private final Map<UUID, CatalogObject> objects = new ConcurrentHashMap<>();
    private final Map<PointerKey, Pointer> pointers = new ConcurrentHashMap<>();
    private final Map<UUID, Ref> refs = new ConcurrentHashMap<>();
    private final Map<UUID, Map<ObjectName, RefEntry>> cuts = new HashMap<>();
    private final Set<UUID> commits = ConcurrentHashMap.newKeySet();
    private final List<CommitBatch> landed = new CopyOnWriteArrayList<>();
    private final Map<String, IdempotencyClaim> claims = new ConcurrentHashMap<>();
    private final Map<String, IdempotencyResult> done = new ConcurrentHashMap<>();
    private final List<GovernanceAttribute> attributes = new CopyOnWriteArrayList<>();
    private final List<LegalHold> holds = new CopyOnWriteArrayList<>();
    private final List<CommitEvents.Commit> events = new CopyOnWriteArrayList<>();
    /** Runs once, before the store judges the next batch: what lands between a commit's read and its swap. */
    private final AtomicReference<Runnable> beforeNextCommit = new AtomicReference<>();
    private final CatalogStore store = store();
    /** As the server has them: the standard rules, and the minimum snapshot age with retention class ledger's. */
    private final List<CommitRule> rules = new ArrayList<>(Rules.standard(files, Duration.ofSeconds(60)));

    {
        rules.add(Rules.snapshotMinimumAge(Duration.ofHours(1), retentionClass -> retentionClass.equals("ledger")
                ? Optional.of(Duration.ofDays(30)) : Optional.empty()));
    }

    // ---- the pointer a commit swaps ----

    @Test
    @DisplayName("a branch's first commit to a table creates its own pointer at version 1 from the cut's version, its"
            + " second swaps it; main's pointer does not move, and every file the branch writes carries its mark")
    void firstAndSecondCommit() {
        CatalogObject orders = table("orders");
        Pointer cut = pointers.get(orders.mainKey());
        Ref dev = branch("dev", orders);
        success(engine().commitTable(name(orders), update(Map.of("on", "main")), ALICE, Optional.empty()));
        Pointer main = pointers.get(orders.mainKey());
        assertThat(main.version()).isEqualTo(2);
        assertThat(TableFiles.onBranch(main.metadataLocation())).as("main's own commit").isFalse();

        Success first = success(on(dev).commitTable(name(orders), Requests.update(
                Requests.assertUuid(orders.id().toString()), Requests.setProperties(Map.of("a", "1"))), ALICE,
                Optional.empty()));
        Pointer own = pointers.get(dev.key(orders.id()));
        assertThat(own.version()).isEqualTo(1);
        assertThat(own.metadataLocation()).isEqualTo(first.metadataLocation())
                .startsWith(orders.locationRoot() + "/metadata/00001-")
                .endsWith(TableFiles.BRANCH_MARK + dev.id() + ".metadata.json");
        assertThat(properties(first)).as("applied to the cut's version, not main's").containsEntry("a", "1")
                .doesNotContainKey("on");
        CommitBatch created = landed.getLast();
        assertThat(created.swaps()).singleElement().satisfies(swap -> {
            assertThat(swap.key()).isEqualTo(dev.key(orders.id()));
            assertThat(swap.expected()).as("created, not swapped").isEmpty();
        });
        assertThat(created.ref()).contains(new RefLock.Hold(dev.id()));
        assertThat(events.getLast().branch()).isEqualTo("dev");
        assertThat(events.getLast().before()).map(Pointer::metadataLocation).contains(cut.metadataLocation());

        Success second = success(on(dev).commitTable(name(orders), update(Map.of("b", "2")), BOB, Optional.empty()));
        Pointer moved = pointers.get(dev.key(orders.id()));
        assertThat(moved.version()).isEqualTo(2);
        assertThat(moved.metadataLocation()).isEqualTo(second.metadataLocation())
                .startsWith(orders.locationRoot() + "/metadata/00002-").contains(TableFiles.BRANCH_MARK + dev.id());
        assertThat(properties(second)).containsEntry("a", "1").containsEntry("b", "2").doesNotContainKey("on");
        assertThat(landed.getLast().swaps().getFirst().expected()).map(Pointer::version).contains(1L);
        assertThat(events.getLast().before()).map(Pointer::metadataLocation).contains(own.metadataLocation());

        assertThat(pointers.get(orders.mainKey()).metadataLocation()).isEqualTo(main.metadataLocation());
        assertThat(files.metadataFiles(orders.locationRoot())).filteredOn(TableFiles::onBranch)
                .containsExactlyInAnyOrder(own.metadataLocation(), moved.metadataLocation());
        assertThat(files.metadataFiles(orders.locationRoot())).filteredOn(location -> !TableFiles.onBranch(location))
                .containsExactlyInAnyOrder(cut.metadataLocation(), main.metadataLocation());
    }

    @Test
    @DisplayName("a view's replace on a branch goes to the branch's own pointer as a table's commit does")
    void aViewOnABranch() {
        CatalogObject daily = view("daily");
        Pointer main = pointers.get(daily.mainKey());
        Ref dev = branch("dev", daily);

        Success replaced = success(on(dev).commitView(name(daily), update(Map.of("comment", "weekly")), ALICE,
                Optional.empty()));
        assertThat(pointers.get(dev.key(daily.id())).metadataLocation()).isEqualTo(replaced.metadataLocation())
                .contains(TableFiles.BRANCH_MARK + dev.id());
        assertThat(ViewMetadataParser.fromJson(replaced.metadataJson()).properties())
                .containsEntry("comment", "weekly");
        assertThat(pointers.get(daily.mainKey())).isSameAs(main);
    }

    @Test
    @DisplayName("two first commits to one table on a branch race: one creates the branch's pointer, the other loses"
            + " the swap, reads it and lands on top")
    void twoFirstCommitsRace() {
        CatalogObject orders = table("orders");
        Ref dev = branch("dev", orders);
        beforeNextCommit.set(() -> success(on(dev).commitTable(name(orders), update(Map.of("b", "2")), BOB,
                Optional.empty())));

        Success alice = success(on(dev).commitTable(name(orders), update(Map.of("a", "1")), ALICE, Optional.empty()));

        Pointer own = pointers.get(dev.key(orders.id()));
        assertThat(own.version()).isEqualTo(2);
        assertThat(own.metadataLocation()).isEqualTo(alice.metadataLocation());
        assertThat(properties(alice)).containsEntry("a", "1").containsEntry("b", "2");
        assertThat(landed).extracting(batch -> batch.swaps().getFirst().expected().map(Pointer::version))
                .containsExactly(Optional.empty(), Optional.of(1L));
        assertThat(store.countOwnPointers(dev.id())).isEqualTo(1);
        assertThat(pointers.get(orders.mainKey()).version()).isEqualTo(1);
    }

    @Test
    @DisplayName("a transaction on a branch swaps each table's own pointer in one batch holding the branch, creating"
            + " those the branch has not changed yet; main's pointers do not move")
    void aTransactionAcrossTwoTables() {
        CatalogObject orders = table("orders");
        CatalogObject fills = table("fills");
        Ref dev = branch("dev", orders, fills);
        success(on(dev).commitTable(name(orders), update(Map.of("a", "1")), ALICE, Optional.empty()));

        CommitReply reply = on(dev).commitTransaction(List.of(
                new CommitEngine.TableChange(name(orders), update(Map.of("t", "1"))),
                new CommitEngine.TableChange(name(fills), update(Map.of("t", "1")))), "{}", ALICE, Optional.empty());

        assertThat(reply).isEqualTo(new Applied(How.COMMITTED));
        CommitBatch batch = landed.getLast();
        assertThat(batch.swaps()).extracting(swap -> Map.entry(swap.key(), swap.expected().map(Pointer::version)))
                .containsExactlyInAnyOrder(Map.entry(dev.key(orders.id()), Optional.of(1L)),
                        Map.entry(dev.key(fills.id()), Optional.empty()));
        assertThat(batch.ref()).contains(new RefLock.Hold(dev.id()));
        assertThat(pointers.get(dev.key(orders.id())).version()).isEqualTo(2);
        assertThat(pointers.get(dev.key(fills.id())).version()).isEqualTo(1);
        assertThat(pointers.get(orders.mainKey()).version()).isEqualTo(1);
        assertThat(pointers.get(fills.mainKey()).version()).isEqualTo(1);
        assertThat(events.subList(events.size() - 2, events.size())).extracting(CommitEvents.Commit::branch)
                .containsOnly("dev");
    }

    @Test
    @DisplayName("a transaction on a branch holds a table it only checks by the branch's own pointer, or by its"
            + " absence when the branch has not committed to it (backlog 278)")
    void aTransactionHoldsWhatItOnlyChecks() {
        CatalogObject orders = table("orders");
        CatalogObject fills = table("fills");
        CatalogObject quotes = table("quotes");
        Ref dev = branch("dev", orders, fills, quotes);
        success(on(dev).commitTable(name(quotes), update(Map.of("q", "1")), ALICE, Optional.empty()));
        Pointer ownQuotes = pointers.get(dev.key(quotes.id()));

        CommitReply reply = on(dev).commitTransaction(List.of(
                new CommitEngine.TableChange(name(orders), update(Map.of("t", "1"))),
                new CommitEngine.TableChange(name(fills), Requests.update(Requests.assertSchema(0))),
                new CommitEngine.TableChange(name(quotes), Requests.update(Requests.assertSchema(0)))), "{}", ALICE,
                Optional.empty());

        assertThat(reply).isEqualTo(new Applied(How.COMMITTED));
        assertThat(landed.getLast().holds()).containsExactlyInAnyOrder(
                new PointerHold(dev.key(fills.id()), Optional.empty()), new PointerHold(dev.key(quotes.id()), ownQuotes));
        assertThat(pointers).as("a hold creates no pointer").doesNotContainKey(dev.key(fills.id()));
    }

    @Test
    @DisplayName("a first commit on the branch to a table a transaction only checks, landing between the transaction's"
            + " read and its swap, fails the transaction's requirement: 409, and nothing of it lands (backlog 278)")
    void aFirstCommitToAHeldTableLandsMeanwhile() {
        CatalogObject orders = table("orders");
        CatalogObject fills = table("fills");
        Ref dev = branch("dev", orders, fills);
        beforeNextCommit.set(() -> success(on(dev).commitTable(name(fills),
                Requests.update("", Requests.addColumn(1, "venue")), BOB, Optional.empty())));

        CommitReply reply = on(dev).commitTransaction(List.of(
                new CommitEngine.TableChange(name(orders), update(Map.of("t", "1"))),
                new CommitEngine.TableChange(name(fills), Requests.update(Requests.assertSchema(0)))), "{}", ALICE,
                Optional.empty());

        assertThat(reply).isInstanceOfSatisfying(Failure.class, failure -> {
            assertThat(failure.status()).isEqualTo(409);
            assertThat(failure.type()).isEqualTo("CommitFailedException");
            assertThat(failure.message()).startsWith(name(fills).qualified() + ": ");
        });
        assertThat(pointers.get(dev.key(fills.id())).version()).as("Bob's first commit").isEqualTo(1);
        assertThat(pointers).as("nothing of the transaction lands").doesNotContainKey(dev.key(orders.id()));
        assertThat(landed).singleElement().satisfies(batch -> assertThat(batch.swaps()).extracting(PointerSwap::key)
                .containsExactly(dev.key(fills.id())));
    }

    // ---- the cap ----

    @Test
    @DisplayName("a branch changes at most nuna.refs.max-merge-tables tables and views: a commit or transaction that"
            + " would change one more is refused with 400 and writes nothing; one to a table it changed already passes")
    void theCap() {
        CatalogObject orders = table("orders");
        CatalogObject fills = table("fills");
        CatalogObject quotes = table("quotes");
        Ref dev = branch("dev", orders, fills, quotes);
        CommitEngine capped = on(dev, 2);
        success(capped.commitTable(name(orders), update(Map.of("a", "1")), ALICE, Optional.empty()));
        success(capped.commitTable(name(fills), update(Map.of("a", "1")), ALICE, Optional.empty()));

        CommitReply third = capped.commitTable(name(quotes), update(Map.of("a", "1")), ALICE, Optional.empty());
        assertThat(third).isInstanceOfSatisfying(Failure.class, failure -> {
            assertThat(failure.status()).isEqualTo(400);
            assertThat(failure.type()).isEqualTo("BadRequestException");
            assertThat(failure.message()).contains("Branch dev changes 2 tables and views already and may change at"
                    + " most 2 (nuna.refs.max-merge-tables)");
        });
        assertThat(files.metadataFiles(quotes.locationRoot())).as("nothing written").hasSize(1);
        assertThat(pointers).doesNotContainKey(dev.key(quotes.id()));

        success(capped.commitTable(name(orders), update(Map.of("b", "2")), ALICE, Optional.empty()));
        CommitReply transaction = capped.commitTransaction(List.of(
                new CommitEngine.TableChange(name(orders), update(Map.of("c", "3"))),
                new CommitEngine.TableChange(name(quotes), update(Map.of("c", "3")))), "{}", ALICE, Optional.empty());
        assertThat(transaction).isInstanceOfSatisfying(Failure.class,
                failure -> assertThat(failure.message()).contains("this commit would change 1 more"));
        assertThat(pointers.get(dev.key(orders.id())).version()).isEqualTo(2);
        assertThat(capped.commitTransaction(List.of(
                new CommitEngine.TableChange(name(orders), update(Map.of("c", "3"))),
                new CommitEngine.TableChange(name(fills), update(Map.of("c", "3")))), "{}", ALICE, Optional.empty()))
                .isEqualTo(new Applied(How.COMMITTED));
    }

    // ---- what a branch commits to ----

    @Test
    @DisplayName("a commit after the branch ended, past its expiry or merged or deleted, is answered 404"
            + " NoSuchWarehouseException; the branch keeps no pointer of it")
    void aCommitAfterTheBranchEnded() {
        CatalogObject orders = table("orders");
        CatalogObject fills = table("fills");
        Ref dev = branch("dev", orders, fills);
        Ref wip = branch("wip", orders, fills);
        now.set(START.plus(DAY.multipliedBy(31)));

        CommitReply expired = on(dev).commitTable(name(orders), update(Map.of("a", "1")), ALICE, Optional.empty());
        assertThat(expired).isEqualTo(new Failure(404, "NoSuchWarehouseException",
                "Branch dev is gone: merged, deleted or past its expiry"));

        refs.remove(wip.id());
        now.set(START);
        CommitReply deleted = on(wip).commitTransaction(List.of(
                new CommitEngine.TableChange(name(orders), update(Map.of("t", "1"))),
                new CommitEngine.TableChange(name(fills), update(Map.of("t", "1")))), "{}", ALICE, Optional.empty());
        assertThat(deleted).isEqualTo(new Failure(404, "NoSuchWarehouseException",
                "Branch wip is gone: merged, deleted or past its expiry"));
        assertThat(pointers.keySet()).containsOnly(orders.mainKey(), fills.mainKey());
    }

    @Test
    @DisplayName("a table main has dropped or unregistered since the branch was made takes no commit on it (404),"
            + " also when main drops it between the commit's read and its swap, or it is one table of a transaction")
    void aTableGoneFromMain() {
        CatalogObject orders = table("orders");
        CatalogObject fills = table("fills");
        CatalogObject quotes = table("quotes");
        Ref dev = branch("dev", orders, fills, quotes);
        objects.put(orders.id(), retired(orders, CatalogObject.State.DROPPED));
        objects.put(fills.id(), retired(fills, CatalogObject.State.UNREGISTERED));

        for (CatalogObject gone : List.of(orders, fills)) {
            assertThat(on(dev).commitTable(name(gone), update(Map.of("a", "1")), ALICE, Optional.empty()))
                    .isEqualTo(goneFromMain(gone, "dev"));
        }
        assertThat(on(dev).commitTransaction(List.of(
                new CommitEngine.TableChange(name(quotes), update(Map.of("t", "1"))),
                new CommitEngine.TableChange(name(fills), update(Map.of("t", "1")))), "{}", ALICE, Optional.empty()))
                .isEqualTo(goneFromMain(fills, "dev"));
        assertThat(files.metadataFiles(orders.locationRoot())).as("refused before anything is written").hasSize(1);
        assertThat(files.metadataFiles(fills.locationRoot())).as("refused before anything is written").hasSize(1);

        beforeNextCommit.set(() -> objects.put(quotes.id(), retired(quotes, CatalogObject.State.DROPPED)));
        assertThat(on(dev).commitTable(name(quotes), update(Map.of("a", "1")), ALICE, Optional.empty()))
                .isEqualTo(goneFromMain(quotes, "dev"));
        assertThat(pointers.keySet()).containsOnly(orders.mainKey(), fills.mainKey(), quotes.mainKey());
    }

    // ---- a register never puts a branch's file on main (backlog 177) ----

    @Test
    @DisplayName("a register refuses a metadata file a branch wrote with 400, on main and through a branch alike, for a"
            + " table or view no object holds, and creates nothing")
    void aRegisterRefusesABranchsFile() {
        CatalogObject orders = table("orders");
        Ref dev = branch("dev", orders);
        UUID id = UUID.randomUUID();
        String root = files.newLocation(id);
        String fills = files.write(root, 2, ((Outcome.NewMetadata) Metadata.createTable(
                Requests.createTable("fills", Map.of()), id.toString(), root)).json(), Optional.of(dev.id())).location();
        UUID viewId = UUID.randomUUID();
        String viewRoot = files.newLocation(viewId);
        String daily = files.write(viewRoot, 2, ((Outcome.NewMetadata) Metadata.createView(Requests.createView("daily"),
                viewId.toString(), viewRoot, 1)).json(), Optional.of(dev.id())).location();

        for (CommitEngine engine : List.of(engine(), on(dev))) {
            assertBranchFileRefused(engine.registerTable(registered("fills"), register("fills", fills), ALICE,
                    Optional.empty(), CommitEngine.Undrop.NEVER), fills, "table");
            assertBranchFileRefused(engine.registerView(registered("daily"), register("daily", daily), ALICE,
                    Optional.empty(), CommitEngine.Undrop.NEVER), daily, "view");
        }
        assertThat(objects.keySet()).containsOnly(orders.id());
        assertThat(pointers.keySet()).containsOnly(orders.mainKey());
        assertThat(events).isEmpty();
    }

    @Test
    @DisplayName("a register that would bring an unregistered table back by a file its branch wrote is refused with"
            + " 400, on main and through a branch alike, and the table stays unregistered at main's file")
    void aReviveRefusesABranchsFile() {
        CatalogObject orders = table("orders");
        Ref dev = branch("dev", orders);
        String onDev = success(on(dev).commitTable(name(orders), update(Map.of("a", "1")), ALICE, Optional.empty()))
                .metadataLocation();
        objects.put(orders.id(), retired(orders, CatalogObject.State.UNREGISTERED));
        Pointer main = pointers.get(orders.mainKey());
        events.clear();

        for (CommitEngine engine : List.of(engine(), on(dev))) {
            assertBranchFileRefused(engine.registerTable(name(orders), register("orders", onDev), ALICE,
                    Optional.empty(), CommitEngine.Undrop.NEVER), onDev, "table");
        }
        assertThat(objects.get(orders.id()).state()).isEqualTo(CatalogObject.State.UNREGISTERED);
        assertThat(pointers.get(orders.mainKey())).isEqualTo(main);
        assertThat(events).isEmpty();
    }

    private static void assertBranchFileRefused(CommitReply reply, String location, String kind) {
        assertThat(reply).isEqualTo(new Failure(400, "BadRequestException", "Metadata file " + location
                + " was written by a commit on a branch, and a " + kind + " is registered only from a file of main's;"
                + " merge the branch, or register a file main's commits wrote"));
    }

    private ObjectName registered(String display) {
        return new ObjectName(catalogId, sales.id(), name(display));
    }

    private static String register(String name, String location) {
        return "{\"name\":\"" + name + "\",\"metadata-location\":\"" + location + "\"}";
    }

    // ---- governance (security finding 4; Paul, 2026-11-22) ----

    @Test
    @DisplayName("a DR freeze does not refuse a commit on a branch, only the merge: the branch's commit to a frozen"
            + " table lands and main is unchanged, while main's own commit is refused")
    void aFreezeDoesNotHoldABranch() {
        CatalogObject orders = table("orders");
        Pointer main = pointers.get(orders.mainKey());
        Ref dev = branch("dev", orders);
        attributes.add(attribute(GovernanceAttribute.Kind.DR_FREEZE, "DR drill"));

        assertThat(engine().commitTable(name(orders), update(Map.of("a", "1")), ALICE, Optional.empty()))
                .isInstanceOfSatisfying(Failure.class, failure -> assertThat(failure.refusal())
                        .map(CommitReply.Refusal::reason).contains("dr_freeze"));
        success(on(dev).commitTable(name(orders), update(Map.of("a", "1")), ALICE, Optional.empty()));

        assertThat(pointers.get(dev.key(orders.id())).version()).isEqualTo(1);
        assertThat(pointers.get(orders.mainKey())).isSameAs(main);
    }

    @Test
    @DisplayName("a legal hold refuses a commit on a branch that removes a snapshot, as on main (D24)")
    void aLegalHoldHoldsABranch() {
        CatalogObject orders = withSnapshots(table("orders"), DAY.multipliedBy(40), DAY.multipliedBy(35));
        Ref dev = branch("dev", orders);
        holds.add(new LegalHold(UUID.randomUUID(), orders.securable(), "litigation", "legal", START));

        assertThat(on(dev).commitTable(name(orders), Requests.update("", Requests.removeSnapshots(1)), ALICE,
                Optional.empty())).isInstanceOfSatisfying(Failure.class, failure -> {
                    assertThat(failure.status()).isEqualTo(403);
                    assertThat(failure.refusal()).map(CommitReply.Refusal::reason).contains("legal_hold");
                });
        assertThat(pointers).doesNotContainKey(dev.key(orders.id()));
    }

    @Test
    @DisplayName("a retention class's minimum snapshot age refuses a commit on a branch that removes a younger"
            + " snapshot, as on main (D24, D30); without the class the same commit lands")
    void theMinimumSnapshotAgeHoldsABranch() {
        CatalogObject orders = withSnapshots(table("orders"), DAY.multipliedBy(10), DAY.multipliedBy(9));
        Ref dev = branch("dev", orders);
        attributes.add(attribute(GovernanceAttribute.Kind.RETENTION_CLASS, "ledger"));
        String expire = Requests.update("", Requests.removeSnapshots(1));

        assertThat(on(dev).commitTable(name(orders), expire, ALICE, Optional.empty()))
                .isInstanceOfSatisfying(Failure.class, failure -> assertThat(failure.refusal())
                        .map(CommitReply.Refusal::reason).contains("snapshot_min_age"));
        attributes.clear();
        success(on(dev).commitTable(name(orders), expire, ALICE, Optional.empty()));
    }

    // ---- idempotency ----

    @Test
    @DisplayName("an Idempotency-Key is bound to the branch: sent again on the branch it replays, sent to main with"
            + " the same body it was used for a different request")
    void aKeyIsBoundToTheBranch() {
        CatalogObject orders = table("orders");
        Ref dev = branch("dev", orders);
        String body = update(Map.of("a", "1"));

        Success first = success(on(dev).commitTable(name(orders), body, ALICE, Optional.of("key-1")));
        CommitReply again = on(dev).commitTable(name(orders), body, ALICE, Optional.of("key-1"));
        assertThat(again).isInstanceOfSatisfying(Success.class, replayed -> {
            assertThat(replayed.how()).isEqualTo(How.REPLAYED);
            assertThat(replayed.metadataLocation()).isEqualTo(first.metadataLocation());
        });
        assertThat(landed).hasSize(1);

        assertThat(engine().commitTable(name(orders), body, ALICE, Optional.of("key-1")))
                .isInstanceOfSatisfying(Failure.class, failure -> assertThat(failure.message())
                        .isEqualTo("Idempotency key key-1 was used for a different request"));
        assertThat(pointers.get(orders.mainKey()).version()).isEqualTo(1);
    }

    // ---- helpers ----

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
            }
        }, CommitSettings.DEFAULTS, clock, wait -> now.updateAndGet(at -> at.plus(wait)));
    }

    private CommitEngine on(Ref branch) {
        return on(branch, 100);
    }

    private CommitEngine on(Ref branch, int maxTables) {
        return engine().on(new CommitEngine.Branch(branch, maxTables, cuts.get(branch.id())));
    }

    /** A branch made now, whose cut takes main's pointer of each object, expiring in 30 days. */
    private Ref branch(String display, CatalogObject... cut) {
        Ref ref = new Ref(catalogId, UUID.randomUUID(), name(display), Ref.Kind.BRANCH, START, "alice",
                Optional.empty(), Ref.MAIN, Optional.of(START.plus(DAY.multipliedBy(30))));
        refs.put(ref.id(), ref);
        Map<ObjectName, RefEntry> entries = new HashMap<>();
        for (CatalogObject object : cut) {
            Pointer at = pointers.get(object.mainKey());
            entries.put(name(object), new RefEntry(ref.id(), object.id(), RefEntry.of(object.kind()),
                    object.namespaceId(), object.name(),
                    Optional.of(new RefEntry.At(at.version(), at.metadataLocation(), at.sha256()))));
        }
        cuts.put(ref.id(), entries);
        return ref;
    }

    private CatalogObject table(String display) {
        UUID id = UUID.randomUUID();
        CatalogObject table = CatalogObject.active(catalogId, id, sales.id(), name(display), CatalogObject.Kind.TABLE,
                files.newLocation(id));
        return first(table, ((Outcome.NewMetadata) Metadata.createTable(Requests.createTable(display, Map.of()),
                id.toString(), table.locationRoot())).json());
    }

    private CatalogObject view(String display) {
        UUID id = UUID.randomUUID();
        CatalogObject view = CatalogObject.active(catalogId, id, sales.id(), name(display), CatalogObject.Kind.VIEW,
                files.newLocation(id));
        return first(view, ((Outcome.NewMetadata) Metadata.createView(Requests.createView(display), id.toString(),
                view.locationRoot(), 1)).json());
    }

    private CatalogObject first(CatalogObject object, String json) {
        TableFiles.Written written = files.write(object.locationRoot(), 1, json);
        objects.put(object.id(), object);
        pointers.put(object.mainKey(), new Pointer(written.location(), 1, written.sha256(), new byte[0]));
        return object;
    }

    /** Main commits two snapshots to the table, taken {@code ages} ago, the second main's. */
    private CatalogObject withSnapshots(CatalogObject table, Duration first, Duration second) {
        success(engine().commitTable(name(table), Requests.update("",
                Requests.addV3Snapshot(1, null, 1, START.minus(first).toEpochMilli(), 0),
                Requests.addV3Snapshot(2, 1L, 2, START.minus(second).toEpochMilli(), 10), Requests.setMain(2)), ALICE,
                Optional.empty()));
        return table;
    }

    private CatalogObject retired(CatalogObject object, CatalogObject.State state) {
        return new CatalogObject(object.catalogId(), object.id(), object.namespaceId(), object.name(), object.kind(),
                state, Optional.of(START), state == CatalogObject.State.DROPPED ? Optional.of(START.plus(DAY))
                        : Optional.empty(), object.locationRoot(), Optional.empty());
    }

    private GovernanceAttribute attribute(GovernanceAttribute.Kind kind, String value) {
        return new GovernanceAttribute(new Securable(Securable.Kind.NAMESPACE, sales.id()), kind, value, "test",
                "admin", START);
    }

    private static Failure goneFromMain(CatalogObject object, String branch) {
        return new Failure(404, "NoSuchTableException", "Table does not exist: " + object.name().display()
                + " (main has dropped or unregistered it since branch " + branch + " was made)");
    }

    private static String update(Map<String, String> properties) {
        return Requests.update("", Requests.setProperties(properties));
    }

    private static Success success(CommitReply reply) {
        assertThat(reply).isInstanceOf(Success.class);
        return (Success) reply;
    }

    private static Map<String, String> properties(Success success) {
        return TableMetadataParser.fromJson(success.metadataJson()).properties();
    }

    private ObjectName name(CatalogObject object) {
        return new ObjectName(catalogId, object.namespaceId(), object.name());
    }

    private static Name name(String display) {
        return new Name(Names.normalize(display), display);
    }

    /**
     * Main's objects and pointers, the branches' own pointers and the branches, governance and idempotency keys. A
     * batch is judged as the stores judge it: the branch it holds must not have ended, every object it swaps must be
     * active, and every pointer it swaps or holds must be as it expects; a branch's batch swaps only its own pointers.
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
                    case "countOwnPointers" -> pointers.keySet().stream()
                            .filter(key -> key.branch().equals(args[0].toString())).count();
                    case "commit" -> commit((CommitBatch) args[0]);
                    case "createObject" -> {
                        ObjectCreation creation = (ObjectCreation) args[0];
                        creation.firstCommit().swaps().forEach(swap -> pointers.put(swap.key(), swap.next()));
                        objects.put(creation.object().id(), creation.object());
                        yield WriteResult.APPLIED;
                    }
                    case "findCommit" -> commits.contains((UUID) args[0]) ? CommitLookup.COMMITTED
                            : CommitLookup.NOT_COMMITTED;
                    case "attributesOf" -> attributes.stream()
                            .filter(a -> ((Set<Securable>) args[0]).contains(a.target())).toList();
                    case "holdsOn" -> holds.stream().filter(h -> ((Set<Securable>) args[0]).contains(h.target()))
                            .toList();
                    case "claimKey" -> claim((IdempotencyClaim) args[0]);
                    case "completeKey" -> {
                        IdempotencyResult result = (IdempotencyResult) args[0];
                        done.put(result.key(), result);
                        yield WriteResult.APPLIED;
                    }
                    case "releaseKey" -> {
                        claims.remove((String) args[0]);
                        yield WriteResult.APPLIED;
                    }
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private CommitResult commit(CommitBatch batch) {
        Runnable between = beforeNextCommit.getAndSet(null);
        if (between != null) {
            between.run();
        }
        synchronized (this) {
            PointerKey anyKey = batch.swaps().getFirst().key();
            if (batch.ref().isPresent()) {
                RefLock lock = batch.ref().get();
                assertThat(batch.swaps()).as("a branch's batch swaps only its own pointers")
                        .allSatisfy(swap -> assertThat(lock.owns(swap.key())).isTrue());
                Ref ref = refs.get(lock.refId());
                if (ref == null || ref.expiredAt(batch.committedAt())) {
                    return new CommitResult.Inactive(anyKey, CommitResult.Subject.REF);
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
                if (!same(actual, hold.expected())) {
                    return new CommitResult.Conflict(hold.key(), actual);
                }
            }
            batch.swaps().forEach(swap -> pointers.put(swap.key(), swap.next()));
            batch.idempotency().ifPresent(result -> done.put(result.key(), result));
            commits.add(batch.commitId());
            landed.add(batch);
            return new CommitResult.Committed(batch.commitId());
        }
    }

    private static boolean same(Optional<Pointer> actual, Optional<Pointer> expected) {
        return actual.map(Pointer::metadataLocation).equals(expected.map(Pointer::metadataLocation))
                && actual.map(Pointer::version).equals(expected.map(Pointer::version));
    }

    private KeyState claim(IdempotencyClaim claim) {
        IdempotencyResult result = done.get(claim.key());
        if (result != null) {
            return result.requestHash().equals(claim.requestHash()) ? new KeyState.Done(result) : KeyState.MISMATCH;
        }
        IdempotencyClaim held = claims.putIfAbsent(claim.key(), claim);
        return held == null || held.requestHash().equals(claim.requestHash()) ? KeyState.CLAIMED : KeyState.MISMATCH;
    }
}
