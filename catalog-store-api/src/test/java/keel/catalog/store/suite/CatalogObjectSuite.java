package keel.catalog.store.suite;

import static keel.catalog.store.suite.Changes.change;
import static keel.catalog.store.suite.Changes.creation;
import static keel.catalog.store.suite.Changes.fullChange;
import static keel.catalog.store.suite.Changes.name;
import static keel.catalog.store.suite.Changes.now;
import static org.assertj.core.api.Assertions.assertThat;

import keel.catalog.store.CatalogObject;
import keel.catalog.store.CatalogStore;
import keel.catalog.store.Change;
import keel.catalog.store.CommitBatch;
import keel.catalog.store.CommitLookup;
import keel.catalog.store.CommitOutcomeUnknownException;
import keel.catalog.store.CommitResult;
import keel.catalog.store.GovernanceAttribute;
import keel.catalog.store.Grant;
import keel.catalog.store.LegalHold;
import keel.catalog.store.NamespaceRecord;
import keel.catalog.store.ObjectCreation;
import keel.catalog.store.PageRequest;
import keel.catalog.store.Pointer;
import keel.catalog.store.Securable;
import keel.catalog.store.WriteResult;
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
    @DisplayName("Objects: of a hold and a purge of one object racing, exactly one applies, every round")
    void holdRacingThePurgeExactlyOneApplies() throws Exception {
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
            Future<WriteResult> purge = background.submit(() -> {
                start.await();
                return store.purgeObject(table.id(), due);
            });
            WriteResult placed = place.get(60, TimeUnit.SECONDS);
            WriteResult purged = purge.get(60, TimeUnit.SECONDS);

            if (placed.equals(WriteResult.APPLIED)) {
                assertThat(purged).as("round %d", round).isEqualTo(new WriteResult.Refused(WriteResult.Reason.HELD));
                assertThat(store.readObject(table.id()).orElseThrow().state()).isEqualTo(CatalogObject.State.DROPPED);
            } else {
                assertThat(placed).as("round %d", round).isEqualTo(new WriteResult.Missing(WriteResult.Subject.OBJECT));
                assertThat(purged).isEqualTo(WriteResult.APPLIED);
                assertThat(store.holdsOn(Set.of(hold.target()))).isEmpty();
            }
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
                Optional.of(purgeAfter), table.locationRoot()));
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
                Optional.of(unregister.at()), Optional.empty(), table.locationRoot()));
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
        assertThat(store.purgeObject(table.id(), change())).isEqualTo(new WriteResult.Missing(WriteResult.Subject.OBJECT));
        assertThat(purgeEligible(now().plus(Duration.ofDays(36500)))).doesNotContain(table.id());
        CatalogObject again = newTable(namespace, "ORDERS");
        assertThat(store.findObject(namespace.id(), "orders")).contains(again);
    }

    @Test
    @DisplayName("Objects: unregister refuses a moved pointer, a hold above, a DR freeze above, or a dropped object, and writes nothing")
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
        assertApplied(store.releaseHold(hold.id(), change()));
        Securable top = new Securable(Securable.Kind.CATALOG, catalog);
        assertApplied(store.putAttribute(new GovernanceAttribute(top, GovernanceAttribute.Kind.DR_FREEZE, "drill",
                "suite", "admin", now()), change()));
        assertThat(store.unregisterObject(table.id(), current, refused)).isEqualTo(new WriteResult.Refused(WriteResult.Reason.FROZEN));
        assertApplied(store.removeAttribute(top, GovernanceAttribute.Kind.DR_FREEZE, change()));
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
    @DisplayName("Objects: revive brings a dropped or unregistered object back with its id, swapping its pointer in the same transaction")
    void reviveBringsTheObjectBack() {
        UUID catalog = newCatalog();
        NamespaceRecord namespace = newNamespace(catalog, "ns");
        NamespaceRecord elsewhere = newNamespace(catalog, "elsewhere");
        CatalogObject usurper = newTable(elsewhere, "taken");
        for (CatalogObject.State from : List.of(CatalogObject.State.DROPPED, CatalogObject.State.UNREGISTERED)) {
            CatalogObject table = newTable(namespace, "t-" + from);
            Pointer base = store.readPointer(table.mainKey()).orElseThrow();
            if (from == CatalogObject.State.DROPPED) {
                assertApplied(store.dropObject(table.id(), now().plus(WINDOW), change()));
            } else {
                assertApplied(store.unregisterObject(table.id(), base, change()));
            }
            CommitBatch taken = Commits.fullBatch(Commits.advance(table.mainKey(), Optional.of(base), "taken-" + table.id()));
            assertThat(store.reviveObject(table.id(), from, elsewhere.id(), name("TAKEN"), taken))
                    .isEqualTo(new WriteResult.NameTaken(usurper.id()));
            assertThat(underTest().writtenRows(taken)).isEqualTo(WrittenRows.NONE);
            CatalogObject.State other = from == CatalogObject.State.DROPPED
                    ? CatalogObject.State.UNREGISTERED : CatalogObject.State.DROPPED;
            assertThat(store.reviveObject(table.id(), other, elsewhere.id(), name("Back"), taken))
                    .isEqualTo(new WriteResult.Missing(WriteResult.Subject.OBJECT));
            CommitBatch stale = Commits.batch(Commits.swap(table.mainKey(), Optional.of(Commits.pointer("stale", 1)),
                    Commits.pointer("stale-next-" + table.id(), 2)));
            assertThat(store.reviveObject(table.id(), from, elsewhere.id(), name("Back"), stale))
                    .isEqualTo(new WriteResult.VersionMismatch(1));
            CommitBatch revive = Commits.fullBatch(Commits.advance(table.mainKey(), Optional.of(base), "revived-" + table.id()));

            assertThat(store.reviveObject(table.id(), from, elsewhere.id(), name("Back-" + from), revive))
                    .isEqualTo(WriteResult.APPLIED);

            assertThat(store.findObject(elsewhere.id(), "back-" + from.name().toLowerCase(java.util.Locale.ROOT)))
                    .contains(CatalogObject.active(catalog, table.id(), elsewhere.id(), name("Back-" + from), table.kind(),
                            table.locationRoot()));
            assertThat(store.readPointer(table.mainKey())).contains(Commits.next(revive));
            assertThat(underTest().writtenRows(revive)).isEqualTo(WrittenRows.allOf(revive));
            assertThat(store.findCommit(revive.commitId(), table.mainKey())).isEqualTo(CommitLookup.COMMITTED);
        }
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
    @DisplayName("Objects: the purge scan finds dropped objects past their purge time, not held, by purge time")
    void purgeScanFindsDueUnheldObjects() {
        NamespaceRecord namespace = newNamespace(newCatalog(), "ns");
        Instant base = Instant.parse("2001-01-01T00:00:00Z").plus(Duration.ofMinutes(UUID.randomUUID().hashCode() & 0xffff));
        CatalogObject late = newTable(namespace, "late");
        CatalogObject early = newTable(namespace, "early");
        CatalogObject held = newTable(namespace, "held");
        CatalogObject future = newTable(namespace, "future");
        CatalogObject active = newTable(namespace, "active");
        assertThat(store.dropObject(late.id(), base.plusSeconds(20), change())).isEqualTo(WriteResult.APPLIED);
        assertThat(store.dropObject(early.id(), base.plusSeconds(10), change())).isEqualTo(WriteResult.APPLIED);
        assertThat(store.dropObject(held.id(), base.plusSeconds(5), change())).isEqualTo(WriteResult.APPLIED);
        assertThat(store.dropObject(future.id(), base.plusSeconds(100), change())).isEqualTo(WriteResult.APPLIED);
        assertThat(store.placeHold(new LegalHold(UUID.randomUUID(), held.securable(), "matter-1", "legal", now()),
                change())).isEqualTo(WriteResult.APPLIED);

        List<UUID> eligible = allPages(1, page -> store.listPurgeEligible(base.plusSeconds(30), page)).stream()
                .filter(o -> o.namespaceId().equals(namespace.id())).map(CatalogObject::id).toList();

        assertThat(eligible).containsExactly(early.id(), late.id());
        assertThat(active.state()).isEqualTo(CatalogObject.State.ACTIVE);
    }

    @Test
    @DisplayName("Objects: purge refuses an object before its purge time or under a hold, then marks it purged")
    void purgeRefusesNotDueAndHeld() {
        UUID catalog = newCatalog();
        NamespaceRecord namespace = newNamespace(catalog, "ns");
        CatalogObject table = newTable(namespace, "t");
        Securable securable = table.securable();
        assertThat(store.putGrant(new Grant(catalog, securable, "readers", "select"), "admin", change())).isEqualTo(WriteResult.APPLIED);
        Change drop = change();
        assertThat(store.purgeObject(table.id(), change())).isEqualTo(new WriteResult.Missing(WriteResult.Subject.OBJECT));
        assertThat(store.dropObject(table.id(), drop.at().plusSeconds(60), drop)).isEqualTo(WriteResult.APPLIED);

        assertThat(store.purgeObject(table.id(), change())).isEqualTo(new WriteResult.Refused(WriteResult.Reason.NOT_DUE));
        LegalHold hold = new LegalHold(UUID.randomUUID(), new Securable(Securable.Kind.NAMESPACE, namespace.id()),
                "matter-2", "legal", now());
        assertThat(store.placeHold(hold, change())).isEqualTo(WriteResult.APPLIED);
        Change late = new Change(UUID.randomUUID(), drop.at().plusSeconds(61), change().outbox(), Optional.empty());
        assertThat(store.purgeObject(table.id(), late)).as("a hold on the namespace holds its objects")
                .isEqualTo(new WriteResult.Refused(WriteResult.Reason.HELD));
        assertThat(store.releaseHold(hold.id(), change())).isEqualTo(WriteResult.APPLIED);
        Change purge = new Change(UUID.randomUUID(), drop.at().plusSeconds(61), change().outbox(), Optional.empty());

        assertThat(store.purgeObject(table.id(), purge)).isEqualTo(WriteResult.APPLIED);

        assertThat(store.readObject(table.id()).orElseThrow().state()).isEqualTo(CatalogObject.State.PURGED);
        assertThat(store.grantsFor(Set.of(securable), Set.of("readers"))).isEmpty();
        assertThat(store.undropObject(table.id(), namespace.id(), name("t"), change()))
                .isEqualTo(new WriteResult.Missing(WriteResult.Subject.OBJECT));
        assertThat(store.listPurgeEligible(drop.at().plusSeconds(3600), PageRequest.first(1000)).items())
                .extracting(CatalogObject::id).doesNotContain(table.id());
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
            assertThat(store.releaseHold(hold.id(), change())).isEqualTo(WriteResult.APPLIED);
        }
        assertThat(store.dropObject(table.id(), now().plus(WINDOW), change())).isEqualTo(WriteResult.APPLIED);
    }

    @Test
    @DisplayName("Objects: the purge scan skips an object held through its namespace, an ancestor or its catalog")
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

            assertApplied(store.releaseHold(hold.id(), change()));
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
        assertApplied(store.releaseHold(onNew.id(), change()));
        LegalHold onOld = holdOn(oldParent);
        assertThat(store.dropObject(table.id(), now().plus(WINDOW), change())).as("no longer under its old namespace")
                .isEqualTo(WriteResult.APPLIED);

        assertApplied(store.undropObject(table.id(), oldHome.id(), name("t"), change()));
        assertThat(store.dropObject(table.id(), now().plus(WINDOW), change())).as("held above the namespace it was undropped into")
                .isEqualTo(new WriteResult.Refused(WriteResult.Reason.HELD));
        assertApplied(store.releaseHold(onOld.id(), change()));
        holdOn(newParent);
        assertThat(store.dropObject(table.id(), now().plus(WINDOW), change())).as("no longer under the namespace it was renamed into")
                .isEqualTo(WriteResult.APPLIED);
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
