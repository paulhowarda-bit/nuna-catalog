package fidice.catalog.store.suite;

import static fidice.catalog.store.suite.Changes.change;
import static fidice.catalog.store.suite.Changes.fullChange;
import static fidice.catalog.store.suite.Changes.name;
import static fidice.catalog.store.suite.Changes.now;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import fidice.catalog.store.Change;
import fidice.catalog.store.CatalogObject;
import fidice.catalog.store.CatalogStore;
import fidice.catalog.store.Grant;
import fidice.catalog.store.LegalHold;
import fidice.catalog.store.NamespaceRecord;
import fidice.catalog.store.PageRequest;
import fidice.catalog.store.Securable;
import fidice.catalog.store.WriteResult;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Namespaces, and suite test 8 (names) for them. */
public abstract class NamespaceSuite extends GroupSuite {

    @Test
    @DisplayName("Namespaces: a created namespace loads by id and by path, at version 1, with its change records")
    void createdNamespaceLoads() {
        UUID catalog = newCatalog();
        NamespaceRecord namespace = new NamespaceRecord(catalog, UUID.randomUUID(), catalog, name("Trading"),
                Map.of("owner", "desk-7", "comment", "ünïcode ✓"), 1);
        Change change = fullChange();

        assertThat(store.createNamespace(namespace, change)).isEqualTo(WriteResult.APPLIED);

        assertThat(store.readNamespace(namespace.id())).contains(namespace);
        assertThat(store.findNamespace(catalog, List.of("trading"))).contains(namespace);
        assertThat(store.findNamespace(catalog, List.of("Trading"))).as("lookup is by normalized name").isEmpty();
        assertThat(underTest().changeRows(change)).isEqualTo(WrittenRows.allOf(change));
    }

    @Test
    @DisplayName("Namespaces: creating under a missing parent namespace or catalog writes nothing")
    void createUnderAMissingParentWritesNothing() {
        UUID catalog = newCatalog();
        Change orphan = fullChange();
        Change noCatalog = fullChange();
        UUID missingCatalog = UUID.randomUUID();

        assertThat(store.createNamespace(new NamespaceRecord(catalog, UUID.randomUUID(), UUID.randomUUID(),
                name("orphan"), Map.of(), 1), orphan)).isEqualTo(new WriteResult.Missing(WriteResult.Subject.NAMESPACE));
        assertThat(store.createNamespace(new NamespaceRecord(missingCatalog, UUID.randomUUID(), missingCatalog,
                name("nowhere"), Map.of(), 1), noCatalog)).isEqualTo(new WriteResult.Missing(WriteResult.Subject.CATALOG));

        assertThat(underTest().changeRows(orphan)).isEqualTo(WrittenRows.NONE);
        assertThat(underTest().changeRows(noCatalog)).isEqualTo(WrittenRows.NONE);
    }

    @Test
    @DisplayName("8 Names: a sibling whose name differs only in case is NameTaken, and the first keeps its display case")
    void caseOnlyCollisionIsNameTaken() {
        UUID catalog = newCatalog();
        NamespaceRecord first = newNamespace(catalog, "Orders");
        Change second = fullChange();

        assertThat(store.createNamespace(new NamespaceRecord(catalog, UUID.randomUUID(), catalog, name("ORDERS"),
                Map.of(), 1), second)).isEqualTo(new WriteResult.NameTaken(first.id()));

        assertThat(underTest().changeRows(second)).isEqualTo(WrittenRows.NONE);
        assertThat(store.findNamespace(catalog, List.of("orders"))).contains(first);
        assertThat(store.readNamespace(first.id()).orElseThrow().name().display()).isEqualTo("Orders");
        NamespaceRecord elsewhere = newNamespace(newNamespace(catalog, "Other"), "ORDERS");
        assertThat(elsewhere.name().display()).as("the same name under another parent is fine").isEqualTo("ORDERS");
    }

    @Test
    @DisplayName("8 Names: names keep any characters, and a namespace 64 levels deep is found by its path")
    void deepPathsAndAnyCharacters() {
        UUID catalog = newCatalog();
        List<String> displays = List.of("a.b", "with space", "slash/inside", "quote'\"", "Ünïcödé", "日本語", "emoji-🧊",
                "percent%2F", "tab\tin", "Ελληνικά");
        List<NamespaceRecord> chain = new ArrayList<>();
        List<String> path = new ArrayList<>();
        for (int level = 0; level < 64; level++) {
            String display = displays.get(level % displays.size()) + "-" + level;
            NamespaceRecord namespace = level == 0 ? newNamespace(catalog, display) : newNamespace(chain.getLast(), display);
            chain.add(namespace);
            path.add(namespace.name().normalized());
        }

        assertThat(store.findNamespace(catalog, path)).contains(chain.getLast());
        assertThat(store.findNamespace(catalog, path.subList(0, 10))).contains(chain.get(9));
        List<String> wrong = new ArrayList<>(path);
        wrong.set(30, "nope");
        assertThat(store.findNamespace(catalog, wrong)).isEmpty();
        for (int level = 0; level < 63; level++) {
            assertThat(store.listNamespaces(chain.get(level).id(), PageRequest.first(10)).items())
                    .containsExactly(chain.get(level + 1));
        }
    }

    @Test
    @DisplayName("Namespaces: listing pages through every child once, in normalized-name order")
    void listingPagesThroughEveryChildOnce() {
        UUID catalog = newCatalog();
        NamespaceRecord parent = newNamespace(catalog, "parent");
        List<Integer> order = new ArrayList<>(IntStream.range(0, 25).boxed().toList());
        Collections.shuffle(order);
        List<NamespaceRecord> children = new ArrayList<>();
        for (int i : order) {
            children.add(newNamespace(parent, "Child-%02d".formatted(i)));
        }

        List<NamespaceRecord> listed = allPages(7, page -> store.listNamespaces(parent.id(), page));

        assertThat(listed).extracting(namespace -> namespace.name().normalized())
                .containsExactlyElementsOf(IntStream.range(0, 25).mapToObj("child-%02d"::formatted).toList());
        assertThat(listed).containsExactlyInAnyOrderElementsOf(children);
        assertThat(store.listNamespaces(catalog, PageRequest.first(100)).items()).containsExactly(parent);
    }

    @Test
    @DisplayName("Namespaces: a page token stays valid while children are added before and after it")
    void pageTokenIsStableUnderConcurrentInserts() {
        UUID catalog = newCatalog();
        NamespaceRecord parent = newNamespace(catalog, "parent");
        for (int i = 0; i < 20; i++) {
            newNamespace(parent, "child-%02d".formatted(i * 2));
        }
        var first = store.listNamespaces(parent.id(), PageRequest.first(7));
        assertThat(first.items()).extracting(namespace -> namespace.name().normalized()).last().isEqualTo("child-12");

        newNamespace(parent, "child-05");
        newNamespace(parent, "child-13");
        newNamespace(parent, "child-99");
        List<String> rest = allPages(7, page -> store.listNamespaces(parent.id(),
                page.token().isEmpty() ? PageRequest.first(7).after(first) : page)).stream()
                .map(namespace -> namespace.name().normalized()).toList();

        List<String> expected = new ArrayList<>(IntStream.range(7, 20).mapToObj(i -> "child-%02d".formatted(i * 2)).toList());
        expected.add("child-13");
        expected.add("child-99");
        assertThat(rest).doesNotHaveDuplicates().containsExactlyElementsOf(expected.stream().sorted().toList());
    }

    @Test
    @DisplayName("Namespaces: a page token that is not the store's is rejected")
    void foreignPageTokenIsRejected() {
        UUID catalog = newCatalog();
        assertThatThrownBy(() -> store.listNamespaces(catalog, new PageRequest(Optional.of("not a token"), 5)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Namespaces: a properties update needs the current version, replaces the properties and advances the version")
    void propertiesUpdateIsACompareAndSwap() {
        UUID catalog = newCatalog();
        NamespaceRecord namespace = new NamespaceRecord(catalog, UUID.randomUUID(), catalog, name("props"),
                Map.of("a", "1", "b", "2"), 1);
        assertThat(store.createNamespace(namespace, change())).isEqualTo(WriteResult.APPLIED);
        Change stale = fullChange();
        Change good = fullChange();

        assertThat(store.updateNamespaceProperties(namespace.id(), 2, Map.of("x", "y"), stale))
                .isEqualTo(new WriteResult.VersionMismatch(1));
        assertThat(store.updateNamespaceProperties(namespace.id(), 1, Map.of("b", "3", "c", "4"), good))
                .isEqualTo(WriteResult.APPLIED);
        assertThat(store.updateNamespaceProperties(namespace.id(), 1, Map.of(), change()))
                .isEqualTo(new WriteResult.VersionMismatch(2));
        assertThat(store.updateNamespaceProperties(UUID.randomUUID(), 1, Map.of(), change()))
                .isEqualTo(new WriteResult.Missing(WriteResult.Subject.NAMESPACE));

        assertThat(store.readNamespace(namespace.id())).contains(new NamespaceRecord(catalog, namespace.id(), catalog,
                namespace.name(), Map.of("b", "3", "c", "4"), 2));
        assertThat(underTest().changeRows(stale)).isEqualTo(WrittenRows.NONE);
        assertThat(underTest().changeRows(good)).isEqualTo(WrittenRows.allOf(good));
    }

    @Test
    @DisplayName("Namespaces: of 8 concurrent property updates from one version exactly one applies, every round")
    void exactlyOnePropertiesUpdateWins() throws Exception {
        UUID catalog = newCatalog();
        NamespaceRecord namespace = newNamespace(catalog, "contended");
        for (int round = 1; round <= 20; round++) {
            CyclicBarrier start = new CyclicBarrier(8);
            List<Future<WriteResult>> results = new ArrayList<>();
            for (int writer = 0; writer < 8; writer++) {
                long expected = round;
                Map<String, String> properties = Map.of("writer", round + "-" + writer);
                results.add(background.submit(() -> {
                    start.await();
                    return store.updateNamespaceProperties(namespace.id(), expected, properties, change());
                }));
            }
            List<WriteResult> outcomes = new ArrayList<>();
            for (Future<WriteResult> result : results) {
                outcomes.add(result.get(60, TimeUnit.SECONDS));
            }
            assertThat(outcomes).as("round %d", round).filteredOn(WriteResult.APPLIED::equals).hasSize(1);
            assertThat(outcomes).filteredOn(outcome -> !outcome.equals(WriteResult.APPLIED))
                    .containsOnly(new WriteResult.VersionMismatch(round + 1));
            assertThat(store.readNamespace(namespace.id()).orElseThrow().version()).isEqualTo(round + 1);
        }
    }

    @Test
    @DisplayName("Namespaces: drop refuses a namespace with a child or an active object, not one with only dropped objects")
    void dropRefusesANonEmptyNamespace() {
        UUID catalog = newCatalog();
        NamespaceRecord parent = newNamespace(catalog, "parent");
        NamespaceRecord child = newNamespace(parent, "child");
        Change refused = fullChange();

        assertThat(store.dropNamespace(parent.id(), refused)).as("a child alone")
                .isEqualTo(new WriteResult.Refused(WriteResult.Reason.NOT_EMPTY));
        assertThat(underTest().changeRows(refused)).isEqualTo(WrittenRows.NONE);
        CatalogObject table = newTable(parent, "t");
        assertThat(store.dropNamespace(child.id(), change())).isEqualTo(WriteResult.APPLIED);
        assertThat(store.dropNamespace(parent.id(), change())).as("an active object alone")
                .isEqualTo(new WriteResult.Refused(WriteResult.Reason.NOT_EMPTY));
        assertThat(store.dropObject(table.id(), Instant.now(), change())).isEqualTo(WriteResult.APPLIED);
        Change drop = fullChange();

        assertThat(store.dropNamespace(parent.id(), drop)).isEqualTo(WriteResult.APPLIED);

        assertThat(underTest().changeRows(drop)).isEqualTo(WrittenRows.allOf(drop));
        assertThat(store.readNamespace(parent.id())).isEmpty();
        assertThat(store.dropNamespace(parent.id(), change())).isEqualTo(new WriteResult.Missing(WriteResult.Subject.NAMESPACE));
        assertThat(store.readObject(table.id()).orElseThrow().namespaceId()).as("a dropped object keeps its namespace id")
                .isEqualTo(parent.id());
    }

    @Test
    @DisplayName("Namespaces: a drop deletes the grants on the namespace and frees its name")
    void dropDeletesGrantsAndFreesTheName() {
        UUID catalog = newCatalog();
        NamespaceRecord namespace = new NamespaceRecord(catalog, UUID.randomUUID(), catalog, name("Gone"), Map.of("k", "v"), 1);
        assertThat(store.createNamespace(namespace, change())).isEqualTo(WriteResult.APPLIED);
        Securable securable = new Securable(Securable.Kind.NAMESPACE, namespace.id());
        assertThat(store.putGrant(new Grant(catalog, securable, "analysts", "select"), "admin", change()))
                .isEqualTo(WriteResult.APPLIED);

        assertThat(store.dropNamespace(namespace.id(), change())).isEqualTo(WriteResult.APPLIED);

        assertThat(store.grantsFor(Set.of(securable), Set.of("analysts"))).isEmpty();
        NamespaceRecord again = new NamespaceRecord(catalog, UUID.randomUUID(), catalog, name("GONE"), Map.of(), 1);
        assertThat(store.createNamespace(again, change())).isEqualTo(WriteResult.APPLIED);
        assertThat(store.findNamespace(catalog, List.of("gone"))).contains(again);
    }

    @Test
    @DisplayName("Namespaces: a child create racing its parent's drop never leaves an orphan, every round")
    void childCreateRacingParentDropLeavesNoOrphan() throws Exception {
        UUID catalog = newCatalog();
        for (int round = 0; round < 30; round++) {
            NamespaceRecord parent = newNamespace(catalog, "parent-" + round);
            NamespaceRecord child = new NamespaceRecord(catalog, UUID.randomUUID(), parent.id(), name("child"), Map.of(), 1);
            CyclicBarrier start = new CyclicBarrier(2);
            Future<WriteResult> create = background.submit(() -> {
                start.await();
                return store.createNamespace(child, change());
            });
            Future<WriteResult> drop = background.submit(() -> {
                start.await();
                return store.dropNamespace(parent.id(), change());
            });
            WriteResult created = create.get(60, TimeUnit.SECONDS);
            WriteResult dropped = drop.get(60, TimeUnit.SECONDS);

            if (created.equals(WriteResult.APPLIED)) {
                assertThat(dropped).as("round %d", round).isEqualTo(new WriteResult.Refused(WriteResult.Reason.NOT_EMPTY));
                assertThat(store.readNamespace(parent.id())).isPresent();
            } else {
                assertThat(created).as("round %d", round).isEqualTo(new WriteResult.Missing(WriteResult.Subject.NAMESPACE));
                assertThat(dropped).isEqualTo(WriteResult.APPLIED);
                assertThat(store.readNamespace(child.id())).isEmpty();
            }
        }
    }

    @Test
    @DisplayName("Namespaces: a hold on the namespace, an ancestor or the catalog refuses its drop and writes nothing")
    void holdsRefuseANamespaceDrop() {
        UUID catalog = newCatalog();
        NamespaceRecord parent = newNamespace(catalog, "parent");
        NamespaceRecord namespace = newNamespace(parent, "ns");
        for (Securable target : List.of(new Securable(Securable.Kind.NAMESPACE, namespace.id()),
                new Securable(Securable.Kind.NAMESPACE, parent.id()), new Securable(Securable.Kind.CATALOG, catalog))) {
            LegalHold hold = new LegalHold(UUID.randomUUID(), target, "matter", "legal", now());
            assertApplied(store.placeHold(hold, change()));
            Change refused = fullChange();

            assertThat(store.dropNamespace(namespace.id(), refused)).as("held by %s", target)
                    .isEqualTo(new WriteResult.Refused(WriteResult.Reason.HELD));

            assertThat(underTest().changeRows(refused)).isEqualTo(WrittenRows.NONE);
            assertApplied(store.releaseHold(hold.id(), change()));
        }
        assertApplied(store.dropNamespace(namespace.id(), change()));
    }

    @Test
    @DisplayName("Namespaces: a grant on a namespace whose drop is open is Missing once the drop commits, and leaves no grant")
    void grantDuringAnOpenDropIsMissing() throws Exception {
        NamespaceRecord namespace = newNamespace(newCatalog(), "ns");
        Grant grant = new Grant(namespace.catalogId(), new Securable(Securable.Kind.NAMESPACE, namespace.id()),
                "racers", "select");

        assertThat(duringAnOpenDrop(namespace, () -> store.putGrant(grant, "admin", change())))
                .isEqualTo(new WriteResult.Missing(WriteResult.Subject.NAMESPACE));

        assertThat(store.grantsFor(Set.of(grant.securable()), Set.of("racers"))).isEmpty();
    }

    @Test
    @DisplayName("Namespaces: a hold on a namespace whose drop is open is Missing once the drop commits, and leaves no hold")
    void holdDuringAnOpenDropIsMissing() throws Exception {
        NamespaceRecord namespace = newNamespace(newCatalog(), "ns");
        LegalHold hold = new LegalHold(UUID.randomUUID(), new Securable(Securable.Kind.NAMESPACE, namespace.id()),
                "matter", "legal", now());

        assertThat(duringAnOpenDrop(namespace, () -> store.placeHold(hold, change())))
                .isEqualTo(new WriteResult.Missing(WriteResult.Subject.NAMESPACE));

        assertThat(store.holdsOn(Set.of(hold.target()))).isEmpty();
    }

    /**
     * Drops the namespace through a store that holds the drop open just before its COMMIT, runs
     * {@code write} meanwhile, then lets the drop commit; returns what {@code write} returned. A
     * write that does not wait for the drop finishes inside the 1.5 s the drop is held.
     */
    private WriteResult duringAnOpenDrop(NamespaceRecord namespace, Callable<WriteResult> write) throws Exception {
        Change drop = change();
        CountDownLatch atCommit = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CatalogStore held = underTest().store(changeId -> {
            if (changeId.equals(drop.id())) {
                atCommit.countDown();
                CommitSuite.awaitLatch(release);
            }
        });
        CompletableFuture<WriteResult> dropping = CompletableFuture.supplyAsync(
                () -> held.dropNamespace(namespace.id(), drop), background);
        assertThat(atCommit.await(60, TimeUnit.SECONDS)).isTrue();

        Future<WriteResult> writing = background.submit(write);
        Thread.sleep(1_500);
        release.countDown();

        assertThat(dropping.get(60, TimeUnit.SECONDS)).isEqualTo(WriteResult.APPLIED);
        return writing.get(60, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("Namespaces: a create waits for an open create of the same name, then reports NameTaken")
    void createWaitsForAnOpenCreateOfTheSameName() throws Exception {
        UUID catalog = newCatalog();
        NamespaceRecord first = new NamespaceRecord(catalog, UUID.randomUUID(), catalog, name("InFlight"), Map.of(), 1);
        Change firstChange = change();
        CountDownLatch atCommit = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CatalogStore held = underTest().store(changeId -> {
            if (changeId.equals(firstChange.id())) {
                atCommit.countDown();
                CommitSuite.awaitLatch(release);
            }
        });
        CompletableFuture<WriteResult> creating = CompletableFuture.supplyAsync(
                () -> held.createNamespace(first, firstChange), background);
        assertThat(atCommit.await(60, TimeUnit.SECONDS)).isTrue();

        CompletableFuture<WriteResult> second = CompletableFuture.supplyAsync(() -> store.createNamespace(
                new NamespaceRecord(catalog, UUID.randomUUID(), catalog, name("INFLIGHT"), Map.of(), 1), change()), background);
        Thread.sleep(1_500);
        assertThat(second).as("the second create must wait for the open one").isNotDone();
        release.countDown();

        assertThat(creating.get(60, TimeUnit.SECONDS)).isEqualTo(WriteResult.APPLIED);
        assertThat(second.get(60, TimeUnit.SECONDS)).isEqualTo(new WriteResult.NameTaken(first.id()));
    }
}
