package nuna.catalog.store.suite;

import static nuna.catalog.store.suite.Changes.change;
import static nuna.catalog.store.suite.Changes.creation;
import static nuna.catalog.store.suite.Changes.fullChange;
import static nuna.catalog.store.suite.Changes.name;
import static nuna.catalog.store.suite.Changes.now;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import nuna.catalog.store.CatalogObject;
import nuna.catalog.store.CatalogStore;
import nuna.catalog.store.Change;
import nuna.catalog.store.CommitBatch;
import nuna.catalog.store.CommitLookup;
import nuna.catalog.store.CommitOutcomeUnknownException;
import nuna.catalog.store.CommitResult;
import nuna.catalog.store.GovernanceAttribute;
import nuna.catalog.store.Grant;
import nuna.catalog.store.LegalHold;
import nuna.catalog.store.NamespaceRecord;
import nuna.catalog.store.ObjectCreation;
import nuna.catalog.store.PageRequest;
import nuna.catalog.store.PendingDeletion;
import nuna.catalog.store.Pointer;
import nuna.catalog.store.Securable;
import nuna.catalog.store.WriteResult;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Tables and views, and suite test 8 (names) for them. */
public abstract class CatalogObjectSuite extends GroupSuite {

    private static final Duration WINDOW = Duration.ofDays(30);

    @Test
    @DisplayName("Objects: a create writes the object, its first pointer, journal entry, outbox events and idempotency result")
    void createWritesEveryRecord() {
        NamespaceRecord namespace = newNamespace(newCatalog(), "ns");
        CatalogObject table = CatalogObject.active(namespace.catalogId(), UUID.randomUUID(), namespace.id(),
                name("Orders"), CatalogObject.Kind.TABLE, "s3://suite-warehouse/t1");
        ObjectCreation creation = creation(table);

        assertThat(store.createObject(creation)).isEqualTo(WriteResult.APPLIED);

        assertThat(store.readObject(table.id())).contains(table);
        assertThat(store.findObject(namespace.id(), "orders")).contains(table);
        assertThat(store.readPointer(table.mainKey())).contains(Commits.next(creation.firstCommit()));
        assertThat(underTest().writtenRows(creation.firstCommit())).isEqualTo(WrittenRows.allOf(creation.firstCommit()));
        assertThat(store.findCommit(creation.firstCommit().commitId(), table.mainKey())).isEqualTo(CommitLookup.COMMITTED);
    }

    @Test
    @DisplayName("8 Names: a table or view whose name differs only in case is NameTaken and writes nothing")
    void caseOnlyCollisionAcrossKindsIsNameTaken() {
        NamespaceRecord namespace = newNamespace(newCatalog(), "ns");
        CatalogObject view = newObject(namespace, "Daily", CatalogObject.Kind.VIEW);
        ObjectCreation table = creation(CatalogObject.active(namespace.catalogId(), UUID.randomUUID(), namespace.id(),
                name("DAILY"), CatalogObject.Kind.TABLE, "s3://suite-warehouse/x"));

        assertThat(store.createObject(table)).isEqualTo(new WriteResult.NameTaken(view.id()));

        assertThat(underTest().writtenRows(table.firstCommit())).isEqualTo(WrittenRows.NONE);
        assertThat(store.readObject(table.object().id())).isEmpty();
        assertThat(store.readPointer(table.object().mainKey())).isEmpty();
        assertThat(store.findObject(namespace.id(), "daily").orElseThrow().name().display()).isEqualTo("Daily");
    }

    @Test
    @DisplayName("Objects: a create in a missing namespace writes nothing")
    void createInAMissingNamespaceWritesNothing() {
        UUID catalog = newCatalog();
        ObjectCreation creation = creation(CatalogObject.active(catalog, UUID.randomUUID(), UUID.randomUUID(),
                name("t"), CatalogObject.Kind.TABLE, "s3://suite-warehouse/x"));

        assertThat(store.createObject(creation)).isEqualTo(new WriteResult.Missing(WriteResult.Subject.NAMESPACE));

        assertThat(underTest().writtenRows(creation.firstCommit())).isEqualTo(WrittenRows.NONE);
        assertThat(store.readPointer(creation.object().mainKey())).isEmpty();
    }

    @Test
    @DisplayName("Objects: of 8 concurrent creates of one name exactly one applies")
    void exactlyOneConcurrentCreateWins() throws Exception {
        NamespaceRecord namespace = newNamespace(newCatalog(), "ns");
        for (int round = 0; round < 10; round++) {
            CyclicBarrier start = new CyclicBarrier(8);
            List<ObjectCreation> creations = new ArrayList<>();
            List<Future<WriteResult>> results = new ArrayList<>();
            for (int writer = 0; writer < 8; writer++) {
                ObjectCreation creation = creation(CatalogObject.active(namespace.catalogId(), UUID.randomUUID(),
                        namespace.id(), name(writer % 2 == 0 ? "Race-" + round : "RACE-" + round),
                        CatalogObject.Kind.TABLE, "s3://suite-warehouse/" + UUID.randomUUID()));
                creations.add(creation);
                results.add(background.submit(() -> {
                    start.await();
                    return store.createObject(creation);
                }));
            }
            List<WriteResult> outcomes = new ArrayList<>();
            for (Future<WriteResult> result : results) {
                outcomes.add(result.get(60, TimeUnit.SECONDS));
            }
            int winner = outcomes.indexOf(WriteResult.APPLIED);
            assertThat(outcomes).as("round %d", round).filteredOn(WriteResult.APPLIED::equals).hasSize(1);
            UUID holder = creations.get(winner).object().id();
            assertThat(outcomes).filteredOn(outcome -> !outcome.equals(WriteResult.APPLIED))
                    .containsOnly(new WriteResult.NameTaken(holder));
            for (int i = 0; i < 8; i++) {
                assertThat(store.readPointer(creations.get(i).object().mainKey()).isPresent()).isEqualTo(i == winner);
            }
        }
    }

    @Test
    @DisplayName("Objects: a create racing its namespace's drop never leaves an orphan, every round")
    void createRacingTheNamespaceDropLeavesNoOrphan() throws Exception {
        UUID catalog = newCatalog();
        for (int round = 0; round < 30; round++) {
            NamespaceRecord namespace = newNamespace(catalog, "ns-" + round);
            ObjectCreation creation = creation(CatalogObject.active(catalog, UUID.randomUUID(), namespace.id(),
                    name("t"), CatalogObject.Kind.TABLE, "s3://suite-warehouse/" + UUID.randomUUID()));
            CyclicBarrier start = new CyclicBarrier(2);
            Future<WriteResult> create = background.submit(() -> {
                start.await();
                return store.createObject(creation);
            });
            Future<WriteResult> drop = background.submit(() -> {
                start.await();
                return store.dropNamespace(namespace.id(), change());
            });
            WriteResult created = create.get(60, TimeUnit.SECONDS);
            WriteResult dropped = drop.get(60, TimeUnit.SECONDS);

            if (created.equals(WriteResult.APPLIED)) {
                assertThat(dropped).as("round %d", round).isEqualTo(new WriteResult.Refused(WriteResult.Reason.NOT_EMPTY));
                assertThat(store.readNamespace(namespace.id())).isPresent();
            } else {
                assertThat(created).as("round %d", round).isEqualTo(new WriteResult.Missing(WriteResult.Subject.NAMESPACE));
                assertThat(dropped).isEqualTo(WriteResult.APPLIED);
                assertThat(store.readObject(creation.object().id())).isEmpty();
                assertThat(store.readPointer(creation.object().mainKey())).isEmpty();
            }
        }
    }

    @Test
    @DisplayName("Objects: of a hold and a purge claim racing, a hold first refuses the claim, and a hold after the claim does not stop the purge")
    void holdRacingThePurgeClaim() throws Exception {
        NamespaceRecord namespace = newNamespace(newCatalog(), "ns");
        for (int round = 0; round < 30; round++) {
            CatalogObject table = newTable(namespace, "t-" + round);
            Change drop = change();
            assertApplied(store.dropObject(table.id(), drop.at(), drop));
            LegalHold hold = new LegalHold(UUID.randomUUID(), table.securable(), "matter", "legal", now());
            Change due = new Change(UUID.randomUUID(), drop.at().plusSeconds(1), change().outbox(), Optional.empty());
            CyclicBarrier start = new CyclicBarrier(2);
            Future<WriteResult> place = background.submit(() -> {
                start.await();
                return store.placeHold(hold, change());
            });
            Future<WriteResult> claim = background.submit(() -> {
                start.await();
                return store.claimPurge(table.id(), "worker", due);
            });
            WriteResult placed = place.get(60, TimeUnit.SECONDS);
            WriteResult claimed = claim.get(60, TimeUnit.SECONDS);

            assertThat(placed).as("round %d: a claimed object is not purged yet, so the hold applies", round)
                    .isEqualTo(WriteResult.APPLIED);
            if (claimed.equals(WriteResult.APPLIED)) {
                assertThat(store.readObject(table.id()).orElseThrow().state()).isEqualTo(CatalogObject.State.PURGING);
                assertThat(store.completePurge(table.id(), "worker", change())).as("round %d", round)
                        .isEqualTo(WriteResult.APPLIED);
            } else {
                assertThat(claimed).as("round %d", round).isEqualTo(new WriteResult.Refused(WriteResult.Reason.HELD));
                assertThat(store.readObject(table.id()).orElseThrow().state()).isEqualTo(CatalogObject.State.DROPPED);
            }
        }
    }

    @Test
    @DisplayName("Objects: a purge claim waits for a hold being placed on a namespace above the object, or on its"
            + " catalog, or a DR freeze on that namespace, and then is refused (backlog 171)")
    void aPurgeClaimWaitsForAnOpenHoldAboveTheObject() throws Exception {
        UUID catalog = newCatalog();
        NamespaceRecord outer = newNamespace(catalog, "outer");
        NamespaceRecord inner = newNamespace(outer, "inner");
        Securable namespace = new Securable(Securable.Kind.NAMESPACE, outer.id());
        record Placement(Securable level, boolean freeze) {
        }
        for (Placement placement : List.of(new Placement(namespace, false),
                new Placement(new Securable(Securable.Kind.CATALOG, catalog), false), new Placement(namespace, true))) {
            Securable level = placement.level();
            String what = (placement.freeze() ? "a DR freeze" : "a hold") + " on the " + level.kind();
            CatalogObject table = newTable(inner, "t-" + level.kind() + (placement.freeze() ? "-frozen" : ""));
            Change drop = change();
            assertApplied(store.dropObject(table.id(), drop.at(), drop));
            LegalHold hold = new LegalHold(UUID.randomUUID(), level, "matter", "legal", now());
            GovernanceAttribute freeze = new GovernanceAttribute(level, GovernanceAttribute.Kind.DR_FREEZE, "drill",
                    "suite", "admin", now());
            Change place = change();
            Change due = new Change(UUID.randomUUID(), drop.at().plusSeconds(1), change().outbox(), Optional.empty());

            Overlap raced = whileOpen(place, placing -> placement.freeze() ? placing.putAttribute(freeze, place)
                    : placing.placeHold(hold, place), () -> store.claimPurge(table.id(), "worker", due));

            assertThat(raced.open()).isEqualTo(WriteResult.APPLIED);
            assertThat(raced.meanwhile()).as(what).isEqualTo(new WriteResult.Refused(placement.freeze()
                    ? WriteResult.Reason.FROZEN : WriteResult.Reason.HELD));
            assertThat(store.readObject(table.id()).orElseThrow().state()).isEqualTo(CatalogObject.State.DROPPED);
            assertThat(raced.waited()).as("the claim waited for %s", what).isTrue();
            assertApplied(placement.freeze()
                    ? store.removeAttribute(level, GovernanceAttribute.Kind.DR_FREEZE, "steward", change())
                    : store.releaseHold(hold.id(), "counsel", change()));
        }
    }

    @Tag("heavy")
    @Test
    @DisplayName("Objects: a create whose answer is lost is unknown, and findCommit resolves it to COMMITTED")
    void lostCreateAnswerResolvesToCommitted() {
        NamespaceRecord namespace = newNamespace(newCatalog(), "ns");
        ObjectCreation creation = creation(CatalogObject.active(namespace.catalogId(), UUID.randomUUID(), namespace.id(),
                name("lost-answer"), CatalogObject.Kind.TABLE, "s3://suite-warehouse/x"));
        CatalogStore dropping = underTest().store(Commits.before(creation.firstCommit(), faults::dropResponses));

        Assertions.assertThatThrownBy(() -> dropping.createObject(creation))
                .isInstanceOfSatisfying(CommitOutcomeUnknownException.class,
                        unknown -> assertThat(unknown.commitId()).isEqualTo(creation.firstCommit().commitId()));
        faults.healLink();

        assertThat(store.findCommit(creation.firstCommit().commitId(), creation.object().mainKey()))
                .isEqualTo(CommitLookup.COMMITTED);
        assertThat(store.readObject(creation.object().id())).contains(creation.object());
    }

    @Tag("heavy")
    @Test
    @DisplayName("Objects: a create whose COMMIT is lost is unknown, and findCommit resolves it to NOT_COMMITTED")
    void lostCreateCommitResolvesToNotCommitted() {
        NamespaceRecord namespace = newNamespace(newCatalog(), "ns");
        ObjectCreation creation = creation(CatalogObject.active(namespace.catalogId(), UUID.randomUUID(), namespace.id(),
                name("lost-commit"), CatalogObject.Kind.TABLE, "s3://suite-warehouse/x"));
        CatalogStore dropping = underTest().store(Commits.before(creation.firstCommit(), faults::dropRequests));

        Assertions.assertThatThrownBy(() -> dropping.createObject(creation))
                .isInstanceOf(CommitOutcomeUnknownException.class);
        faults.healLink();

        assertThat(store.findCommit(creation.firstCommit().commitId(), creation.object().mainKey()))
                .isEqualTo(CommitLookup.NOT_COMMITTED);
        assertThat(store.readObject(creation.object().id())).isEmpty();
        assertThat(store.findObject(namespace.id(), "lost-commit")).isEmpty();
    }

    @Test
    @DisplayName("Objects: findCommit in another process waits for a create held open before COMMIT")
    void findCommitWaitsForAnOpenCreate() throws Exception {
        NamespaceRecord namespace = newNamespace(newCatalog(), "ns");
        ObjectCreation creation = creation(CatalogObject.active(namespace.catalogId(), UUID.randomUUID(), namespace.id(),
                name("held"), CatalogObject.Kind.TABLE, "s3://suite-warehouse/x"));
        CountDownLatch atCommit = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CatalogStore held = underTest().store(Commits.before(creation.firstCommit(), () -> {
            atCommit.countDown();
            CommitSuite.awaitLatch(release);
        }));
        CompletableFuture<WriteResult> create = CompletableFuture.supplyAsync(() -> held.createObject(creation), background);
        assertThat(atCommit.await(60, TimeUnit.SECONDS)).isTrue();

        CompletableFuture<CommitLookup> lookup = CompletableFuture.supplyAsync(() -> underTest().storeOfAnotherProcess()
                .findCommit(creation.firstCommit().commitId(), creation.object().mainKey()), background);
        Thread.sleep(1_500);
        assertThat(lookup).as("findCommit must wait while the create can still commit").isNotDone();
        release.countDown();

        assertThat(create.get(60, TimeUnit.SECONDS)).isEqualTo(WriteResult.APPLIED);
        assertThat(lookup.get(60, TimeUnit.SECONDS)).isEqualTo(CommitLookup.COMMITTED);
    }

    @Test
    @DisplayName("Objects: a soft drop fixes the purge time, frees the name at once and keeps the id and pointer")
    void softDropFreesTheNameAndKeepsTheRest() {
        NamespaceRecord namespace = newNamespace(newCatalog(), "ns");
        CatalogObject table = newTable(namespace, "Orders");
        Change drop = fullChange();
        Instant purgeAfter = drop.at().plus(WINDOW);

        assertThat(store.dropObject(table.id(), purgeAfter, drop)).isEqualTo(WriteResult.APPLIED);

        assertThat(store.readObject(table.id())).contains(new CatalogObject(table.catalogId(), table.id(),
                namespace.id(), table.name(), table.kind(), CatalogObject.State.DROPPED, Optional.of(drop.at()),
                Optional.of(purgeAfter), table.locationRoot(), Optional.empty()));
        assertThat(underTest().changeRows(drop)).isEqualTo(WrittenRows.allOf(drop));
        assertThat(store.findObject(namespace.id(), "orders")).isEmpty();
        assertThat(store.readPointer(table.mainKey())).isPresent();
        assertThat(store.dropObject(table.id(), purgeAfter, change())).isEqualTo(new WriteResult.Missing(WriteResult.Subject.OBJECT));
        CatalogObject again = newTable(namespace, "ORDERS");
        assertThat(store.findObject(namespace.id(), "orders")).contains(again);
    }

    @Test
    @DisplayName("Objects: a commit built before its object was dropped is refused and writes nothing")
    void aCommitAfterTheDropIsRefused() {
        NamespaceRecord namespace = newNamespace(newCatalog(), "ns");
        CatalogObject table = newTable(namespace, "t");
        Pointer base = store.readPointer(table.mainKey()).orElseThrow();
        CommitBatch late = Commits.fullBatch(Commits.advance(table.mainKey(), Optional.of(base), "late-" + table.id()));
        assertApplied(store.dropObject(table.id(), now().plus(WINDOW), change()));

        assertThat(store.commit(late)).isEqualTo(new CommitResult.Inactive(table.mainKey()));

        assertThat(store.readPointer(table.mainKey())).contains(base);
        assertThat(underTest().writtenRows(late)).isEqualTo(WrittenRows.NONE);
    }

    @Test
    @DisplayName("Objects: unregister is final: it frees the name, keeps the id and pointer, and refuses every later commit")
    void unregisterIsFinal() {
        NamespaceRecord namespace = newNamespace(newCatalog(), "ns");
        CatalogObject table = newTable(namespace, "Orders");
        Pointer base = store.readPointer(table.mainKey()).orElseThrow();
        Change unregister = fullChange();

        assertThat(store.unregisterObject(table.id(), base, unregister)).isEqualTo(WriteResult.APPLIED);

        assertThat(store.readObject(table.id())).contains(new CatalogObject(table.catalogId(), table.id(),
                namespace.id(), table.name(), table.kind(), CatalogObject.State.UNREGISTERED,
                Optional.of(unregister.at()), Optional.empty(), table.locationRoot(), Optional.empty()));
        assertThat(underTest().changeRows(unregister)).isEqualTo(WrittenRows.allOf(unregister));
        assertThat(store.findObject(namespace.id(), "orders")).isEmpty();
        assertThat(store.readPointer(table.mainKey())).contains(base);
        assertThat(allPages(10, page -> store.listObjects(namespace.id(), CatalogObject.Kind.TABLE,
                CatalogObject.State.UNREGISTERED, page))).extracting(CatalogObject::id).containsExactly(table.id());
        CommitBatch late = Commits.fullBatch(Commits.advance(table.mainKey(), Optional.of(base), "late-" + table.id()));
        assertThat(store.commit(late)).isEqualTo(new CommitResult.Inactive(table.mainKey()));
        assertThat(underTest().writtenRows(late)).isEqualTo(WrittenRows.NONE);
        assertThat(store.unregisterObject(table.id(), base, change())).isEqualTo(new WriteResult.Missing(WriteResult.Subject.OBJECT));
        assertThat(store.dropObject(table.id(), now().plus(WINDOW), change())).isEqualTo(new WriteResult.Missing(WriteResult.Subject.OBJECT));
        assertThat(store.undropObject(table.id(), namespace.id(), name("Orders"), change()))
                .as("an unregistered object is not undropped").isEqualTo(new WriteResult.Missing(WriteResult.Subject.OBJECT));
        assertThat(store.claimPurge(table.id(), "worker", change())).isEqualTo(new WriteResult.Missing(WriteResult.Subject.OBJECT));
        assertThat(purgeEligible(now().plus(Duration.ofDays(36500)))).doesNotContain(table.id());
        assertThat(store.recordDeletions(table.id(), List.of(table.locationRoot() + "/data/f.parquet"), now(), change()))
                .as("the files of an unregistered table are not the catalog's to delete")
                .isEqualTo(new WriteResult.Missing(WriteResult.Subject.OBJECT));
        CatalogObject again = newTable(namespace, "ORDERS");
        assertThat(store.findObject(namespace.id(), "orders")).contains(again);
    }

    @Test
    @DisplayName("Objects: unregister refuses a moved pointer, a hold above, a DR freeze above, a regulated flag above, a row/column flag, or a dropped object, and writes nothing")
    void unregisterRefusals() {
        UUID catalog = newCatalog();
        NamespaceRecord namespace = newNamespace(catalog, "ns");
        CatalogObject table = newTable(namespace, "t");
        Pointer base = store.readPointer(table.mainKey()).orElseThrow();
        CommitBatch moved = Commits.batch(Commits.advance(table.mainKey(), Optional.of(base), "moved-" + table.id()));
        assertThat(store.commit(moved)).isInstanceOf(CommitResult.Committed.class);
        Change refused = fullChange();

        assertThat(store.unregisterObject(table.id(), base, refused)).isEqualTo(new WriteResult.VersionMismatch(2));
        assertThat(underTest().changeRows(refused)).isEqualTo(WrittenRows.NONE);

        Pointer current = Commits.next(moved);
        LegalHold hold = holdOn(namespace);
        assertThat(store.unregisterObject(table.id(), current, refused)).isEqualTo(new WriteResult.Refused(WriteResult.Reason.HELD));
        assertApplied(store.releaseHold(hold.id(), "counsel", change()));
        Securable top = new Securable(Securable.Kind.CATALOG, catalog);
        assertApplied(store.putAttribute(new GovernanceAttribute(top, GovernanceAttribute.Kind.DR_FREEZE, "drill",
                "suite", "admin", now()), change()));
        assertThat(store.unregisterObject(table.id(), current, refused)).isEqualTo(new WriteResult.Refused(WriteResult.Reason.FROZEN));
        assertApplied(store.removeAttribute(top, GovernanceAttribute.Kind.DR_FREEZE, "steward", change()));
        Securable area = new Securable(Securable.Kind.NAMESPACE, namespace.id());
        assertApplied(store.putAttribute(new GovernanceAttribute(area, GovernanceAttribute.Kind.REGULATED, "true",
                "suite", "steward", now()), change()));
        assertThat(store.unregisterObject(table.id(), current, refused)).isEqualTo(new WriteResult.Refused(WriteResult.Reason.FLAGGED));
        assertApplied(store.putAttribute(new GovernanceAttribute(area, GovernanceAttribute.Kind.REGULATED, "false",
                "suite", "steward", now()), change()));
        assertApplied(store.putAttribute(new GovernanceAttribute(table.securable(),
                GovernanceAttribute.Kind.ROW_COLUMN_POLICY, "true", "suite", "steward", now()), change()));
        assertThat(store.unregisterObject(table.id(), current, refused)).isEqualTo(new WriteResult.Refused(WriteResult.Reason.FLAGGED));
        assertApplied(store.removeAttribute(table.securable(), GovernanceAttribute.Kind.ROW_COLUMN_POLICY, "steward", change()));
        assertThat(underTest().changeRows(refused)).isEqualTo(WrittenRows.NONE);

        assertApplied(store.dropObject(table.id(), now().plus(WINDOW), change()));
        assertThat(store.unregisterObject(table.id(), current, refused)).isEqualTo(new WriteResult.Missing(WriteResult.Subject.OBJECT));
        assertThat(store.readObject(table.id()).orElseThrow().state()).isEqualTo(CatalogObject.State.DROPPED);
    }

    @Tag("heavy")
    @Test
    @DisplayName("Objects: of a commit and an unregister of one table racing, exactly one applies, every round")
    void commitRacingTheUnregisterExactlyOneApplies() throws Exception {
        NamespaceRecord namespace = newNamespace(newCatalog(), "ns");
        for (int round = 0; round < 30; round++) {
            CatalogObject table = newTable(namespace, "t-" + round);
            Pointer base = store.readPointer(table.mainKey()).orElseThrow();
            CommitBatch batch = Commits.batch(Commits.advance(table.mainKey(), Optional.of(base), "raced-" + table.id()));
            CyclicBarrier start = new CyclicBarrier(2);
            Future<CommitResult> commit = background.submit(() -> {
                start.await();
                return store.commit(batch);
            });
            Future<WriteResult> unregister = background.submit(() -> {
                start.await();
                return store.unregisterObject(table.id(), base, change());
            });
            CommitResult committed = commit.get(60, TimeUnit.SECONDS);
            WriteResult unregistered = unregister.get(60, TimeUnit.SECONDS);

            if (committed instanceof CommitResult.Committed) {
                assertThat(unregistered).as("round %d", round).isEqualTo(new WriteResult.VersionMismatch(2));
                assertThat(store.readObject(table.id()).orElseThrow().state()).isEqualTo(CatalogObject.State.ACTIVE);
            } else {
                assertThat(committed).as("round %d", round).isEqualTo(new CommitResult.Inactive(table.mainKey()));
                assertThat(unregistered).isEqualTo(WriteResult.APPLIED);
                assertThat(store.readPointer(table.mainKey())).contains(base);
            }
        }
    }

    @Test
    @DisplayName("Objects: revive brings a dropped or unregistered object back with its id in its own namespace, swapping its pointer in the same transaction")
    void reviveBringsTheObjectBack() {
        UUID catalog = newCatalog();
        NamespaceRecord namespace = newNamespace(catalog, "ns");
        NamespaceRecord elsewhere = newNamespace(catalog, "elsewhere");
        CatalogObject usurper = newTable(namespace, "taken");
        for (CatalogObject.State from : List.of(CatalogObject.State.DROPPED, CatalogObject.State.UNREGISTERED)) {
            CatalogObject table = newTable(namespace, "t-" + from);
            Pointer base = store.readPointer(table.mainKey()).orElseThrow();
            if (from == CatalogObject.State.DROPPED) {
                assertApplied(store.dropObject(table.id(), now().plus(WINDOW), change()));
            } else {
                assertApplied(store.unregisterObject(table.id(), base, change()));
            }
            CommitBatch taken = Commits.fullBatch(Commits.advance(table.mainKey(), Optional.of(base), "taken-" + table.id()));
            assertThat(store.reviveObject(table.id(), from, namespace.id(), name("TAKEN"), taken))
                    .isEqualTo(new WriteResult.NameTaken(usurper.id()));
            assertThat(underTest().writtenRows(taken)).isEqualTo(WrittenRows.NONE);
            CatalogObject.State other = from == CatalogObject.State.DROPPED
                    ? CatalogObject.State.UNREGISTERED : CatalogObject.State.DROPPED;
            assertThat(store.reviveObject(table.id(), other, namespace.id(), name("Back"), taken))
                    .isEqualTo(new WriteResult.Missing(WriteResult.Subject.OBJECT));
            assertThat(store.reviveObject(table.id(), from, elsewhere.id(), name("Back"), taken))
                    .as("another namespace").isEqualTo(new WriteResult.Missing(WriteResult.Subject.OBJECT));
            assertThat(underTest().writtenRows(taken)).isEqualTo(WrittenRows.NONE);
            CommitBatch stale = Commits.batch(Commits.swap(table.mainKey(), Optional.of(Commits.pointer("stale", 1)),
                    Commits.pointer("stale-next-" + table.id(), 2)));
            assertThat(store.reviveObject(table.id(), from, namespace.id(), name("Back"), stale))
                    .isEqualTo(new WriteResult.VersionMismatch(1));
            CommitBatch revive = Commits.fullBatch(Commits.advance(table.mainKey(), Optional.of(base), "revived-" + table.id()));

            assertThat(store.reviveObject(table.id(), from, namespace.id(), name("Back-" + from), revive))
                    .isEqualTo(WriteResult.APPLIED);

            assertThat(store.findObject(namespace.id(), "back-" + from.name().toLowerCase(java.util.Locale.ROOT)))
                    .contains(CatalogObject.active(catalog, table.id(), namespace.id(), name("Back-" + from), table.kind(),
                            table.locationRoot()));
            assertThat(store.readPointer(table.mainKey())).contains(Commits.next(revive));
            assertThat(underTest().writtenRows(revive)).isEqualTo(WrittenRows.allOf(revive));
            assertThat(store.findCommit(revive.commitId(), table.mainKey())).isEqualTo(CommitLookup.COMMITTED);
        }
    }

    @Test
    @DisplayName("Objects: an object undropped, renamed into another namespace and dropped again after a revive read it is not brought back in the namespace it first left, and writes nothing")
    void reviveComparesTheNamespace() {
        UUID catalog = newCatalog();
        NamespaceRecord home = newNamespace(catalog, "home");
        NamespaceRecord away = newNamespace(catalog, "away");
        CatalogObject table = newTable(home, "t");
        Pointer base = store.readPointer(table.mainKey()).orElseThrow();
        assertApplied(store.dropObject(table.id(), now().plus(WINDOW), change()));
        CatalogObject read = store.readObject(table.id()).orElseThrow();
        CommitBatch revive = Commits.fullBatch(Commits.advance(table.mainKey(), Optional.of(base), "revived-" + table.id()));
        assertApplied(store.undropObject(table.id(), home.id(), name("t"), change()));
        assertApplied(store.renameObject(table.id(), away.id(), name("moved"), change()));
        assertApplied(store.dropObject(table.id(), now().plus(WINDOW), change()));
        assertThat(store.readPointer(table.mainKey())).as("undrop, rename and drop leave the pointer").contains(base);

        assertThat(store.reviveObject(table.id(), read.state(), read.namespaceId(), read.name(), revive))
                .isEqualTo(new WriteResult.Missing(WriteResult.Subject.OBJECT));

        assertThat(underTest().writtenRows(revive)).isEqualTo(WrittenRows.NONE);
        assertThat(store.findObject(home.id(), "t")).isEmpty();
        CatalogObject current = store.readObject(table.id()).orElseThrow();
        assertThat(current.state()).isEqualTo(CatalogObject.State.DROPPED);
        assertThat(current.namespaceId()).isEqualTo(away.id());
        assertThat(store.readPointer(table.mainKey())).contains(base);

        assertThat(store.reviveObject(table.id(), CatalogObject.State.DROPPED, away.id(), name("moved"), revive))
                .as("where it now is").isEqualTo(WriteResult.APPLIED);
        assertThat(store.findObject(away.id(), "moved")).contains(CatalogObject.active(catalog, table.id(), away.id(),
                name("moved"), table.kind(), table.locationRoot()));
    }

    @Tag("heavy")
    @Test
    @DisplayName("Objects: a revive whose answer is lost is unknown, and findCommit resolves it to COMMITTED")
    void lostReviveAnswerResolvesToCommitted() {
        NamespaceRecord namespace = newNamespace(newCatalog(), "ns");
        CatalogObject table = newTable(namespace, "t");
        Pointer base = store.readPointer(table.mainKey()).orElseThrow();
        assertApplied(store.unregisterObject(table.id(), base, change()));
        CommitBatch revive = Commits.fullBatch(Commits.advance(table.mainKey(), Optional.of(base), "lost-" + table.id()));
        CatalogStore dropping = underTest().store(Commits.before(revive, faults::dropResponses));

        Assertions.assertThatThrownBy(() -> dropping.reviveObject(table.id(), CatalogObject.State.UNREGISTERED,
                        namespace.id(), name("t"), revive))
                .isInstanceOfSatisfying(CommitOutcomeUnknownException.class,
                        unknown -> assertThat(unknown.commitId()).isEqualTo(revive.commitId()));
        faults.healLink();

        assertThat(store.findCommit(revive.commitId(), table.mainKey())).isEqualTo(CommitLookup.COMMITTED);
        assertThat(store.readObject(table.id()).orElseThrow().state()).isEqualTo(CatalogObject.State.ACTIVE);
        assertThat(underTest().writtenRows(revive)).isEqualTo(WrittenRows.allOf(revive));
    }

    @Test
    @DisplayName("Objects: undrop restores the same id under a free name, and refuses a taken name or a missing namespace")
    void undropRestoresTheSameObject() {
        UUID catalog = newCatalog();
        NamespaceRecord namespace = newNamespace(catalog, "ns");
        CatalogObject table = newTable(namespace, "Orders");
        assertThat(store.dropObject(table.id(), now().plus(WINDOW), change())).isEqualTo(WriteResult.APPLIED);
        CatalogObject usurper = newTable(namespace, "orders");
        Change taken = fullChange();

        assertThat(store.undropObject(table.id(), namespace.id(), name("Orders"), taken))
                .isEqualTo(new WriteResult.NameTaken(usurper.id()));
        assertThat(underTest().changeRows(taken)).isEqualTo(WrittenRows.NONE);
        assertThat(store.undropObject(table.id(), UUID.randomUUID(), name("Orders"), change()))
                .isEqualTo(new WriteResult.Missing(WriteResult.Subject.NAMESPACE));
        assertThat(store.undropObject(usurper.id(), namespace.id(), name("x"), change()))
                .as("an active object is not undropped").isEqualTo(new WriteResult.Missing(WriteResult.Subject.OBJECT));

        NamespaceRecord elsewhere = newNamespace(catalog, "elsewhere");
        assertThat(store.undropObject(table.id(), elsewhere.id(), name("Orders-Restored"), change())).isEqualTo(WriteResult.APPLIED);

        assertThat(store.findObject(elsewhere.id(), "orders-restored")).contains(CatalogObject.active(catalog, table.id(),
                elsewhere.id(), name("Orders-Restored"), table.kind(), table.locationRoot()));
        assertThat(store.readPointer(table.mainKey())).isPresent();
    }

    @Test
    @DisplayName("8 Names: rename moves an object across namespaces; a case-only rename changes the display name")
    void renameAndCaseOnlyRename() {
        UUID catalog = newCatalog();
        NamespaceRecord from = newNamespace(catalog, "from");
        NamespaceRecord to = newNamespace(catalog, "to");
        CatalogObject table = newTable(from, "orders");
        CatalogObject other = newTable(to, "Taken");
        Change refused = fullChange();

        assertThat(store.renameObject(table.id(), to.id(), name("TAKEN"), refused)).isEqualTo(new WriteResult.NameTaken(other.id()));
        assertThat(underTest().changeRows(refused)).isEqualTo(WrittenRows.NONE);
        assertThat(store.renameObject(table.id(), UUID.randomUUID(), name("x"), change()))
                .isEqualTo(new WriteResult.Missing(WriteResult.Subject.NAMESPACE));
        assertThat(store.renameObject(UUID.randomUUID(), to.id(), name("x"), change()))
                .isEqualTo(new WriteResult.Missing(WriteResult.Subject.OBJECT));

        assertThat(store.renameObject(table.id(), to.id(), name("Orders"), change())).isEqualTo(WriteResult.APPLIED);
        assertThat(store.findObject(from.id(), "orders")).isEmpty();
        assertThat(store.renameObject(table.id(), to.id(), name("ORDERS"), change())).isEqualTo(WriteResult.APPLIED);

        assertThat(store.findObject(to.id(), "orders")).contains(CatalogObject.active(catalog, table.id(), to.id(),
                name("ORDERS"), table.kind(), table.locationRoot()));
        assertThat(store.readPointer(table.mainKey())).as("a rename keeps the pointer").isPresent();
    }

    @Test
    @DisplayName("8 Names: two objects can swap names through a third")
    void namesSwapThroughATemporaryName() {
        NamespaceRecord namespace = newNamespace(newCatalog(), "ns");
        CatalogObject a = newTable(namespace, "a");
        CatalogObject b = newTable(namespace, "b");

        assertThat(store.renameObject(a.id(), namespace.id(), name("tmp"), change())).isEqualTo(WriteResult.APPLIED);
        assertThat(store.renameObject(b.id(), namespace.id(), name("a"), change())).isEqualTo(WriteResult.APPLIED);
        assertThat(store.renameObject(a.id(), namespace.id(), name("b"), change())).isEqualTo(WriteResult.APPLIED);

        assertThat(store.findObject(namespace.id(), "a").orElseThrow().id()).isEqualTo(b.id());
        assertThat(store.findObject(namespace.id(), "b").orElseThrow().id()).isEqualTo(a.id());
    }

    @Test
    @DisplayName("Objects: listing pages by kind and state, every object once, stable while objects are added")
    void listingByKindAndState() {
        NamespaceRecord namespace = newNamespace(newCatalog(), "ns");
        List<CatalogObject> tables = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            tables.add(newTable(namespace, "t-%02d".formatted(i * 2)));
        }
        CatalogObject view = newObject(namespace, "v-01", CatalogObject.Kind.VIEW);
        assertThat(store.dropObject(tables.get(3).id(), now().plus(WINDOW), change())).isEqualTo(WriteResult.APPLIED);
        CatalogObject recreated = newTable(namespace, "t-06");
        assertThat(store.dropObject(recreated.id(), now().plus(WINDOW), change())).isEqualTo(WriteResult.APPLIED);

        var first = store.listObjects(namespace.id(), CatalogObject.Kind.TABLE, CatalogObject.State.ACTIVE, PageRequest.first(5));
        newTable(namespace, "t-01");
        newTable(namespace, "t-99");
        List<String> active = new ArrayList<>(first.items().stream().map(o -> o.name().normalized()).toList());
        PageRequest rest = PageRequest.first(5).after(first);
        active.addAll(allPages(5, page -> store.listObjects(namespace.id(), CatalogObject.Kind.TABLE,
                CatalogObject.State.ACTIVE, page.token().isEmpty() ? rest : page)).stream()
                .map(o -> o.name().normalized()).toList());

        List<String> expected = new ArrayList<>(IntStream.range(0, 12).filter(i -> i != 3)
                .mapToObj(i -> "t-%02d".formatted(i * 2)).toList());
        expected.add("t-99");
        assertThat(active).doesNotHaveDuplicates().containsExactlyElementsOf(expected);
        assertThat(allPages(1, page -> store.listObjects(namespace.id(), CatalogObject.Kind.VIEW,
                CatalogObject.State.ACTIVE, page))).containsExactly(view);
        assertThat(allPages(1, page -> store.listObjects(namespace.id(), CatalogObject.Kind.TABLE,
                CatalogObject.State.DROPPED, page))).extracting(CatalogObject::id)
                .as("two dropped objects may share a name").containsExactlyInAnyOrder(tables.get(3).id(), recreated.id());
    }

    @Test
    @DisplayName("Objects: the purge scan finds dropped objects past their purge time, neither held nor frozen, and every claimed object, by purge time")
    void purgeScanFindsDueUncoveredAndClaimedObjects() {
        NamespaceRecord namespace = newNamespace(newCatalog(), "ns");
        Instant base = Instant.parse("2001-01-01T00:00:00Z").plus(Duration.ofMinutes(UUID.randomUUID().hashCode() & 0xffff));
        CatalogObject late = newTable(namespace, "late");
        CatalogObject early = newTable(namespace, "early");
        CatalogObject held = newTable(namespace, "held");
        CatalogObject frozen = newTable(namespace, "frozen");
        CatalogObject claimed = newTable(namespace, "claimed");
        CatalogObject future = newTable(namespace, "future");
        CatalogObject active = newTable(namespace, "active");
        assertThat(store.dropObject(late.id(), base.plusSeconds(20), change())).isEqualTo(WriteResult.APPLIED);
        assertThat(store.dropObject(early.id(), base.plusSeconds(10), change())).isEqualTo(WriteResult.APPLIED);
        assertThat(store.dropObject(held.id(), base.plusSeconds(5), change())).isEqualTo(WriteResult.APPLIED);
        assertThat(store.dropObject(frozen.id(), base.plusSeconds(7), change())).isEqualTo(WriteResult.APPLIED);
        assertThat(store.dropObject(claimed.id(), base.plusSeconds(15), change())).isEqualTo(WriteResult.APPLIED);
        assertThat(store.dropObject(future.id(), base.plusSeconds(100), change())).isEqualTo(WriteResult.APPLIED);
        assertThat(store.placeHold(new LegalHold(UUID.randomUUID(), held.securable(), "matter-1", "legal", now()),
                change())).isEqualTo(WriteResult.APPLIED);
        assertApplied(store.putAttribute(new GovernanceAttribute(frozen.securable(), GovernanceAttribute.Kind.DR_FREEZE,
                "drill", "suite", "admin", now()), change()));
        assertApplied(store.claimPurge(claimed.id(), "worker", change()));
        assertApplied(store.placeHold(new LegalHold(UUID.randomUUID(), claimed.securable(), "matter-2", "legal", now()),
                change()));

        List<UUID> eligible = allPages(1, page -> store.listPurgeEligible(base.plusSeconds(30), page)).stream()
                .filter(o -> o.namespaceId().equals(namespace.id())).map(CatalogObject::id).toList();

        assertThat(eligible).as("a claim is finished whatever came after it")
                .containsExactly(early.id(), claimed.id(), late.id());
        assertThat(active.state()).isEqualTo(CatalogObject.State.ACTIVE);
    }

    @Test
    @DisplayName("Objects: a purge claim refuses an object not due, held or frozen; its claimant resumes and completes it, nobody else, and a hold after the claim does not stop it")
    void purgeIsClaimedThenCompleted() {
        UUID catalog = newCatalog();
        NamespaceRecord namespace = newNamespace(catalog, "ns");
        CatalogObject table = newTable(namespace, "t");
        Securable securable = table.securable();
        assertThat(store.putGrant(new Grant(catalog, securable, "readers", "select"), "admin", change())).isEqualTo(WriteResult.APPLIED);
        Change drop = change();
        assertThat(store.claimPurge(table.id(), "worker-a", change())).as("an active object")
                .isEqualTo(new WriteResult.Missing(WriteResult.Subject.OBJECT));
        assertApplied(store.recordDeletions(table.id(), List.of(table.locationRoot() + "/metadata/old.avro"),
                drop.at().minusSeconds(1), change()));
        assertThat(store.dropObject(table.id(), drop.at().plusSeconds(60), drop)).isEqualTo(WriteResult.APPLIED);
        Change refused = fullChange();

        assertThat(store.claimPurge(table.id(), "worker-a", refused)).isEqualTo(new WriteResult.Refused(WriteResult.Reason.NOT_DUE));
        LegalHold hold = new LegalHold(UUID.randomUUID(), new Securable(Securable.Kind.NAMESPACE, namespace.id()),
                "matter-2", "legal", now());
        assertThat(store.placeHold(hold, change())).isEqualTo(WriteResult.APPLIED);
        Change late = new Change(refused.id(), drop.at().plusSeconds(61), refused.outbox(), refused.idempotency());
        assertThat(store.claimPurge(table.id(), "worker-a", late)).as("a hold on the namespace holds its objects")
                .isEqualTo(new WriteResult.Refused(WriteResult.Reason.HELD));
        assertThat(store.releaseHold(hold.id(), "counsel", change())).isEqualTo(WriteResult.APPLIED);
        Securable top = new Securable(Securable.Kind.CATALOG, catalog);
        assertApplied(store.putAttribute(new GovernanceAttribute(top, GovernanceAttribute.Kind.DR_FREEZE, "drill",
                "suite", "admin", now()), change()));
        assertThat(store.claimPurge(table.id(), "worker-a", late)).as("a DR freeze on the catalog freezes its objects")
                .isEqualTo(new WriteResult.Refused(WriteResult.Reason.FROZEN));
        assertApplied(store.removeAttribute(top, GovernanceAttribute.Kind.DR_FREEZE, "steward", change()));
        assertThat(store.completePurge(table.id(), "worker-a", late)).as("an unclaimed object")
                .isEqualTo(new WriteResult.Missing(WriteResult.Subject.OBJECT));
        assertThat(underTest().changeRows(refused)).isEqualTo(WrittenRows.NONE);
        Change full = fullChange();
        Change claim = new Change(full.id(), drop.at().plusSeconds(61), full.outbox(), full.idempotency());

        assertThat(store.claimPurge(table.id(), "worker-a", claim)).isEqualTo(WriteResult.APPLIED);

        assertThat(underTest().changeRows(claim)).isEqualTo(WrittenRows.allOf(claim));
        CatalogObject claimed = store.readObject(table.id()).orElseThrow();
        assertThat(claimed.state()).isEqualTo(CatalogObject.State.PURGING);
        assertThat(claimed.purgeClaimant()).contains("worker-a");
        assertThat(claimed.purgeAfter()).contains(drop.at().plusSeconds(60));
        assertThat(store.claimPurge(table.id(), "worker-a", change())).as("the claimant resumes its claim")
                .isEqualTo(WriteResult.UNCHANGED);
        assertThat(store.claimPurge(table.id(), "worker-b", change())).isEqualTo(new WriteResult.Refused(WriteResult.Reason.NOT_HOLDER));
        assertThat(store.completePurge(table.id(), "worker-b", change())).isEqualTo(new WriteResult.Refused(WriteResult.Reason.NOT_HOLDER));
        assertThat(store.undropObject(table.id(), namespace.id(), name("t"), change())).as("a claimed object is not undropped")
                .isEqualTo(new WriteResult.Missing(WriteResult.Subject.OBJECT));
        assertThat(store.recordDeletions(table.id(), List.of(table.locationRoot() + "/data/f.parquet"), now(), change()))
                .as("a claimed object's files go with the purge").isEqualTo(new WriteResult.Missing(WriteResult.Subject.OBJECT));
        assertApplied(store.placeHold(new LegalHold(UUID.randomUUID(), securable, "matter-3", "legal", now()), change()));
        Change complete = fullChange();

        assertThat(store.completePurge(table.id(), "worker-a", complete)).as("a hold after the claim does not stop it")
                .isEqualTo(WriteResult.APPLIED);

        assertThat(underTest().changeRows(complete)).isEqualTo(WrittenRows.allOf(complete));
        CatalogObject purged = store.readObject(table.id()).orElseThrow();
        assertThat(purged.state()).isEqualTo(CatalogObject.State.PURGED);
        assertThat(purged.purgeClaimant()).isEmpty();
        assertThat(store.grantsFor(Set.of(securable), Set.of("readers"))).isEmpty();
        assertThat(dueDeletions(now())).extracting(PendingDeletion::objectId).doesNotContain(table.id());
        assertThat(store.claimPurge(table.id(), "worker-a", change())).isEqualTo(new WriteResult.Missing(WriteResult.Subject.OBJECT));
        assertThat(store.completePurge(table.id(), "worker-a", change())).isEqualTo(new WriteResult.Missing(WriteResult.Subject.OBJECT));
        assertThat(store.undropObject(table.id(), namespace.id(), name("t"), change()))
                .isEqualTo(new WriteResult.Missing(WriteResult.Subject.OBJECT));
        assertThat(purgeEligible(drop.at().plusSeconds(3600))).doesNotContain(table.id());
    }

    @Test
    @DisplayName("Objects: a hold on the object, its namespace or its catalog refuses the drop")
    void holdsRefuseTheDrop() {
        UUID catalog = newCatalog();
        NamespaceRecord parent = newNamespace(catalog, "parent");
        NamespaceRecord namespace = newNamespace(parent, "ns");
        CatalogObject table = newTable(namespace, "t");
        for (Securable target : List.of(table.securable(), new Securable(Securable.Kind.NAMESPACE, parent.id()),
                new Securable(Securable.Kind.CATALOG, catalog))) {
            LegalHold hold = new LegalHold(UUID.randomUUID(), target, "matter", "legal", now());
            assertThat(store.placeHold(hold, change())).isEqualTo(WriteResult.APPLIED);
            Change refused = fullChange();

            assertThat(store.dropObject(table.id(), now().plus(WINDOW), refused)).as("held by %s", target)
                    .isEqualTo(new WriteResult.Refused(WriteResult.Reason.HELD));

            assertThat(underTest().changeRows(refused)).isEqualTo(WrittenRows.NONE);
            assertThat(store.releaseHold(hold.id(), "counsel", change())).isEqualTo(WriteResult.APPLIED);
        }
        assertThat(store.dropObject(table.id(), now().plus(WINDOW), change())).isEqualTo(WriteResult.APPLIED);
    }

    @Test
    @DisplayName("Objects: the purge scan skips an object held or frozen through its namespace, an ancestor or its catalog")
    void purgeScanSkipsObjectsHeldAbove() {
        UUID catalog = newCatalog();
        NamespaceRecord parent = newNamespace(catalog, "parent");
        NamespaceRecord namespace = newNamespace(parent, "ns");
        CatalogObject table = newTable(namespace, "t");
        Instant due = Instant.parse("2001-01-01T00:00:00Z").plus(Duration.ofMinutes(UUID.randomUUID().hashCode() & 0xffff));
        assertApplied(store.dropObject(table.id(), due, change()));
        for (Securable target : List.of(new Securable(Securable.Kind.NAMESPACE, namespace.id()),
                new Securable(Securable.Kind.NAMESPACE, parent.id()), new Securable(Securable.Kind.CATALOG, catalog))) {
            LegalHold hold = new LegalHold(UUID.randomUUID(), target, "matter", "legal", now());
            assertApplied(store.placeHold(hold, change()));

            assertThat(purgeEligible(due)).as("held by %s", target).doesNotContain(table.id());

            assertApplied(store.releaseHold(hold.id(), "counsel", change()));
            assertApplied(store.putAttribute(new GovernanceAttribute(target, GovernanceAttribute.Kind.DR_FREEZE, "drill",
                    "suite", "admin", now()), change()));

            assertThat(purgeEligible(due)).as("frozen by %s", target).doesNotContain(table.id());

            assertApplied(store.removeAttribute(target, GovernanceAttribute.Kind.DR_FREEZE, "steward", change()));
        }
        assertThat(purgeEligible(due)).contains(table.id());
    }

    @Test
    @DisplayName("Objects: after a rename or undrop into another namespace, the holds above the new namespace apply")
    void holdsFollowARenameOrUndrop() {
        UUID catalog = newCatalog();
        NamespaceRecord oldParent = newNamespace(catalog, "old-parent");
        NamespaceRecord newParent = newNamespace(catalog, "new-parent");
        NamespaceRecord oldHome = newNamespace(oldParent, "old");
        NamespaceRecord newHome = newNamespace(newParent, "new");
        CatalogObject table = newTable(oldHome, "t");

        assertApplied(store.renameObject(table.id(), newHome.id(), name("t"), change()));
        LegalHold onNew = holdOn(newParent);
        assertThat(store.dropObject(table.id(), now().plus(WINDOW), change())).as("held above its new namespace")
                .isEqualTo(new WriteResult.Refused(WriteResult.Reason.HELD));
        assertApplied(store.releaseHold(onNew.id(), "counsel", change()));
        LegalHold onOld = holdOn(oldParent);
        assertThat(store.dropObject(table.id(), now().plus(WINDOW), change())).as("no longer under its old namespace")
                .isEqualTo(WriteResult.APPLIED);

        assertApplied(store.undropObject(table.id(), oldHome.id(), name("t"), change()));
        assertThat(store.dropObject(table.id(), now().plus(WINDOW), change())).as("held above the namespace it was undropped into")
                .isEqualTo(new WriteResult.Refused(WriteResult.Reason.HELD));
        assertApplied(store.releaseHold(onOld.id(), "counsel", change()));
        holdOn(newParent);
        assertThat(store.dropObject(table.id(), now().plus(WINDOW), change())).as("no longer under the namespace it was renamed into")
                .isEqualTo(WriteResult.APPLIED);
    }

    @Test
    @DisplayName("Maintenance: pending deletions come due at their time, in order, page by page, and each is completed once")
    void pendingDeletionsComeDueInOrder() {
        NamespaceRecord namespace = newNamespace(newCatalog(), "ns");
        CatalogObject active = newTable(namespace, "active");
        CatalogObject dropped = newTable(namespace, "dropped");
        assertApplied(store.dropObject(dropped.id(), now().plus(WINDOW), change()));
        Instant base = Instant.parse("2001-01-01T00:00:00Z").plus(Duration.ofMinutes(UUID.randomUUID().hashCode() & 0xffff));
        Change record = fullChange();
        String first = active.locationRoot() + "/data/a.parquet";
        String second = active.locationRoot() + "/metadata/b.avro";

        assertThat(store.recordDeletions(active.id(), List.of(first, second), base.plusSeconds(10), record))
                .isEqualTo(WriteResult.APPLIED);

        assertThat(underTest().changeRows(record)).isEqualTo(WrittenRows.allOf(record));
        assertApplied(store.recordDeletions(dropped.id(), List.of(dropped.locationRoot() + "/data/c.parquet"),
                base.plusSeconds(5), change()));
        assertThat(store.recordDeletions(UUID.randomUUID(), List.of("s3://suite-warehouse/x"), base, change()))
                .isEqualTo(new WriteResult.Missing(WriteResult.Subject.OBJECT));
        assertThat(ours(dueDeletions(base.plusSeconds(9)), namespace)).extracting(PendingDeletion::location)
                .containsExactly(dropped.locationRoot() + "/data/c.parquet");
        List<PendingDeletion> due = ours(allPages(1, page -> store.listDueDeletions(base.plusSeconds(10), page)), namespace);
        assertThat(due).extracting(PendingDeletion::location).as("by time, every deletion once")
                .containsExactlyInAnyOrder(first, second, dropped.locationRoot() + "/data/c.parquet");
        assertThat(due.getFirst().objectId()).isEqualTo(dropped.id());
        PendingDeletion done = due.get(1);
        assertThat(done.objectId()).isEqualTo(active.id());
        assertThat(done.catalogId()).isEqualTo(active.catalogId());
        assertThat(done.notBefore()).isEqualTo(base.plusSeconds(10));
        assertThat(done.recordedAt()).isEqualTo(record.at());
        Change complete = fullChange();

        assertThat(store.completeDeletions(active.id(), List.of(done.id()), complete)).isEqualTo(WriteResult.APPLIED);

        assertThat(underTest().changeRows(complete)).isEqualTo(WrittenRows.allOf(complete));
        assertThat(ours(dueDeletions(base.plusSeconds(10)), namespace)).extracting(PendingDeletion::id)
                .doesNotContain(done.id()).hasSize(2);
        Change again = fullChange();
        assertThat(store.completeDeletions(active.id(), List.of(done.id()), again)).isEqualTo(WriteResult.UNCHANGED);
        assertThat(store.completeDeletions(dropped.id(), List.of(due.get(2).id()), again)).as("another object's deletion")
                .isEqualTo(WriteResult.UNCHANGED);
        assertThat(underTest().changeRows(again)).isEqualTo(WrittenRows.NONE);
    }

    @Test
    @DisplayName("Maintenance: a file is pending once per object; recording it again keeps its time, and only files already pending write nothing")
    void pendingDeletionsAreRecordedOncePerFile() {
        NamespaceRecord namespace = newNamespace(newCatalog(), "ns");
        CatalogObject table = newTable(namespace, "t");
        CatalogObject other = newTable(namespace, "other");
        Instant base = Instant.parse("2001-01-01T00:00:00Z").plus(Duration.ofMinutes(UUID.randomUUID().hashCode() & 0xffff));
        String first = table.locationRoot() + "/data/a.parquet";
        String second = table.locationRoot() + "/data/b.parquet";
        assertApplied(store.recordDeletions(table.id(), List.of(first, first), base, change()));
        Change again = fullChange();

        assertThat(store.recordDeletions(table.id(), List.of(first), base.plusSeconds(60), again))
                .isEqualTo(WriteResult.UNCHANGED);

        assertThat(underTest().changeRows(again)).isEqualTo(WrittenRows.NONE);
        assertApplied(store.recordDeletions(table.id(), List.of(first, second), base.plusSeconds(60), change()));
        assertApplied(store.recordDeletions(other.id(), List.of(first), base.plusSeconds(60), change()));
        List<PendingDeletion> due = ours(dueDeletions(base.plusSeconds(60)), namespace);
        assertThat(due).filteredOn(d -> d.objectId().equals(table.id())).as("each file once, the first time kept")
                .extracting(PendingDeletion::location, PendingDeletion::notBefore)
                .containsExactlyInAnyOrder(tuple(first, base), tuple(second, base.plusSeconds(60)));
        assertThat(due).filteredOn(d -> d.objectId().equals(other.id())).as("per object")
                .extracting(PendingDeletion::location).containsExactly(first);
    }

    @Test
    @DisplayName("Maintenance: a hold or a DR freeze on an object, a namespace above it or its catalog keeps its pending deletions back")
    void pendingDeletionsWaitOutHoldsAndFreezes() {
        UUID catalog = newCatalog();
        NamespaceRecord parent = newNamespace(catalog, "parent");
        NamespaceRecord namespace = newNamespace(parent, "ns");
        CatalogObject table = newTable(namespace, "t");
        Instant due = Instant.parse("2001-01-01T00:00:00Z").plus(Duration.ofMinutes(UUID.randomUUID().hashCode() & 0xffff));
        assertApplied(store.recordDeletions(table.id(), List.of(table.locationRoot() + "/data/a.parquet"), due, change()));
        for (Securable target : List.of(table.securable(), new Securable(Securable.Kind.NAMESPACE, parent.id()),
                new Securable(Securable.Kind.CATALOG, catalog))) {
            LegalHold hold = new LegalHold(UUID.randomUUID(), target, "matter", "legal", now());
            assertApplied(store.placeHold(hold, change()));

            assertThat(ours(dueDeletions(due), namespace)).as("held by %s", target).isEmpty();

            assertApplied(store.releaseHold(hold.id(), "counsel", change()));
            assertApplied(store.putAttribute(new GovernanceAttribute(target, GovernanceAttribute.Kind.DR_FREEZE, "drill",
                    "suite", "admin", now()), change()));

            assertThat(ours(dueDeletions(due), namespace)).as("frozen by %s", target).isEmpty();

            assertApplied(store.removeAttribute(target, GovernanceAttribute.Kind.DR_FREEZE, "steward", change()));
        }
        assertThat(ours(dueDeletions(due), namespace)).hasSize(1);
    }

    @Test
    @DisplayName("Maintenance: deletions recorded while a table was active never come due once it is unregistered, since its files are no longer the catalog's to delete")
    void anUnregisteredTablesPendingDeletionsNeverComeDue() {
        NamespaceRecord namespace = newNamespace(newCatalog(), "ns");
        CatalogObject table = newTable(namespace, "t");
        Instant due = Instant.parse("2001-01-01T00:00:00Z").plus(Duration.ofMinutes(UUID.randomUUID().hashCode() & 0xffff));
        assertApplied(store.recordDeletions(table.id(), List.of(table.locationRoot() + "/data/a.parquet"), due, change()));
        assertThat(ours(dueDeletions(due), namespace)).as("due while the table is active").hasSize(1);

        assertApplied(store.unregisterObject(table.id(), store.readPointer(table.mainKey()).orElseThrow(), change()));

        assertThat(ours(dueDeletions(due), namespace)).isEmpty();
        assertThat(ours(dueDeletions(due.plus(Duration.ofDays(3650))), namespace)).isEmpty();
    }

    @Test
    @DisplayName("Maintenance: the active-object scan pages through every active object of a kind once, and nothing dropped")
    void activeObjectScanPagesByKind() {
        NamespaceRecord namespace = newNamespace(newCatalog(), "ns");
        List<UUID> tables = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            tables.add(newTable(namespace, "t-" + i).id());
        }
        CatalogObject view = newObject(namespace, "v", CatalogObject.Kind.VIEW);
        CatalogObject dropped = newTable(namespace, "dropped");
        assertApplied(store.dropObject(dropped.id(), now().plus(WINDOW), change()));

        List<CatalogObject> scanned = allPages(2, page -> store.listActiveObjects(CatalogObject.Kind.TABLE, page));

        assertThat(scanned).extracting(CatalogObject::id).doesNotHaveDuplicates().containsAll(tables)
                .doesNotContain(view.id(), dropped.id());
        assertThat(allPages(50, page -> store.listActiveObjects(CatalogObject.Kind.VIEW, page)))
                .extracting(CatalogObject::id).contains(view.id()).doesNotContainAnyElementsOf(tables);
    }

    private List<PendingDeletion> dueDeletions(Instant now) {
        return allPages(100, page -> store.listDueDeletions(now, page));
    }

    /** The deletions of objects in {@code namespace}, which other tests' deletions share the store with. */
    private List<PendingDeletion> ours(List<PendingDeletion> deletions, NamespaceRecord namespace) {
        return deletions.stream().filter(deletion -> store.readObject(deletion.objectId())
                .map(object -> object.namespaceId().equals(namespace.id())).orElse(false)).toList();
    }

    private LegalHold holdOn(NamespaceRecord namespace) {
        LegalHold hold = new LegalHold(UUID.randomUUID(), new Securable(Securable.Kind.NAMESPACE, namespace.id()),
                "matter", "legal", now());
        assertApplied(store.placeHold(hold, change()));
        return hold;
    }

    private List<UUID> purgeEligible(Instant now) {
        return allPages(100, page -> store.listPurgeEligible(now, page)).stream().map(CatalogObject::id).toList();
    }
}
