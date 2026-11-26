package nuna.catalog.store.suite;

import static nuna.catalog.store.suite.Changes.change;
import static nuna.catalog.store.suite.Changes.fullChange;
import static nuna.catalog.store.suite.Changes.name;
import static nuna.catalog.store.suite.Changes.now;
import static nuna.catalog.store.suite.Refs.branch;
import static nuna.catalog.store.suite.Refs.changeAt;
import static nuna.catalog.store.suite.Refs.tag;
import static org.assertj.core.api.Assertions.assertThat;

import nuna.catalog.store.CatalogObject;
import nuna.catalog.store.Change;
import nuna.catalog.store.CommitBatch;
import nuna.catalog.store.CommitResult;
import nuna.catalog.store.GovernanceAttribute;
import nuna.catalog.store.LegalHold;
import nuna.catalog.store.NamespaceRecord;
import nuna.catalog.store.PageRequest;
import nuna.catalog.store.Pointer;
import nuna.catalog.store.PointerKey;
import nuna.catalog.store.PointerSwap;
import nuna.catalog.store.Ref;
import nuna.catalog.store.RefEntry;
import nuna.catalog.store.RefLimits;
import nuna.catalog.store.RefLock;
import nuna.catalog.store.RefPointers;
import nuna.catalog.store.Securable;
import nuna.catalog.store.WriteResult;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A catalog's branches and tags (D33, backlog 102): a ref's cut is the catalog as it was, names included, read whole;
 * a ref made from a branch takes that branch's own pointers; names compare as every name does; P11's limits hold under
 * concurrent creates; renewal; a deletion leaves no cut, no pointer of the ref and no piece; a namespace stays while a
 * ref names a table or view of it (backlog 157); a table or view taken out of refs leaves them, so a dropped one can be
 * purged (backlog 156); paging.
 */
public abstract class RefSuite extends GroupSuite {

    private static final WriteResult MISSING_REF = new WriteResult.Missing(WriteResult.Subject.REF);
    private static final WriteResult LIMIT = new WriteResult.Refused(WriteResult.Reason.REF_LIMIT);
    private static final WriteResult REFERENCED = new WriteResult.Refused(WriteResult.Reason.REFERENCED);
    private static final WriteResult MISSING_OBJECT = new WriteResult.Missing(WriteResult.Subject.OBJECT);
    private static final WriteResult HELD = new WriteResult.Refused(WriteResult.Reason.HELD);
    private static final WriteResult FROZEN = new WriteResult.Refused(WriteResult.Reason.FROZEN);

    private final Random random = new Random(11);

    /** Commits {@code key} from {@code expected} to a pointer named {@code name} at the next version; returns it. */
    private Pointer commit(PointerKey key, Optional<Pointer> expected, String name) {
        PointerSwap swap = Commits.advance(key, expected, name);
        assertThat(store.commit(Commits.batch(swap))).isInstanceOf(CommitResult.Committed.class);
        return swap.next();
    }

    private static RefEntry.At at(Pointer pointer) {
        return new RefEntry.At(pointer.version(), pointer.metadataLocation(), pointer.sha256());
    }

    private Optional<RefEntry.At> pointerOnRef(Ref ref, List<String> path, String name) {
        return store.resolveOnRef(ref.id(), path, name).flatMap(RefEntry::pointer);
    }

    private Ref created(Ref ref) {
        assertApplied(store.createRef(ref, RefLimits.P11, change()));
        return ref;
    }

    @Test
    @DisplayName("Refs: a cut is the catalog as it was when the ref was made, names and pointers both")
    void aCutIsTheCatalogAsItWas() {
        UUID catalog = newCatalog();
        NamespaceRecord sales = newNamespace(catalog, "Sales");
        NamespaceRecord eu = newNamespace(sales, "EU");
        CatalogObject orders = newTable(sales, "Orders");
        CatalogObject view = newObject(eu, "Revenue", CatalogObject.Kind.VIEW);
        CatalogObject gone = newTable(sales, "Gone");
        assertApplied(store.dropObject(gone.id(), now().plus(Duration.ofDays(30)), change()));
        Pointer v2 = commit(orders.mainKey(), store.readPointer(orders.mainKey()), "orders-2");
        Pointer viewAt = store.readPointer(view.mainKey()).orElseThrow();
        newNamespace(newCatalog(), "Elsewhere");
        Ref ref = created(branch(catalog, "Release-1", now()));

        assertApplied(store.renameObject(orders.id(), sales.id(), name("Renamed"), change()));
        commit(orders.mainKey(), Optional.of(v2), "orders-3");
        assertApplied(store.dropObject(view.id(), now().plus(Duration.ofDays(30)), change()));

        assertThat(store.readRef(ref.id())).contains(ref);
        assertThat(store.findRef(catalog, "release-1")).contains(ref);
        assertThat(store.resolveOnRef(ref.id(), List.of("sales"), "orders")).contains(new RefEntry(ref.id(), orders.id(),
                RefEntry.Kind.TABLE, sales.id(), name("Orders"), Optional.of(at(v2))));
        assertThat(store.resolveOnRef(ref.id(), List.of("sales"), "renamed")).as("a name taken on main later").isEmpty();
        assertThat(store.resolveOnRef(ref.id(), List.of("sales", "eu"), "revenue")).contains(new RefEntry(ref.id(),
                view.id(), RefEntry.Kind.VIEW, eu.id(), name("Revenue"), Optional.of(at(viewAt))));
        assertThat(store.resolveOnRef(ref.id(), List.of("sales"), "gone")).as("dropped before the ref").isEmpty();
        assertThat(store.findRefNamespace(ref.id(), List.of("sales", "eu"))).contains(new RefEntry(ref.id(), eu.id(),
                RefEntry.Kind.NAMESPACE, sales.id(), name("EU"), Optional.empty()));
        assertThat(store.findRefNamespace(ref.id(), List.of("elsewhere"))).as("another catalog's").isEmpty();
        assertThat(store.listRefNamespaces(ref.id(), catalog, PageRequest.first(10)).items()).extracting(RefEntry::id)
                .containsExactly(sales.id());
        assertThat(store.listRefNamespaces(ref.id(), sales.id(), PageRequest.first(10)).items()).extracting(RefEntry::id)
                .containsExactly(eu.id());
        assertThat(store.listRefObjects(ref.id(), sales.id(), CatalogObject.Kind.TABLE, PageRequest.first(10)).items())
                .extracting(RefEntry::id).containsExactly(orders.id());
        assertThat(store.listRefObjects(ref.id(), sales.id(), CatalogObject.Kind.VIEW, PageRequest.first(10)).items())
                .isEmpty();
        assertThat(store.listRefObjects(ref.id(), eu.id(), CatalogObject.Kind.VIEW, PageRequest.first(10)).items())
                .extracting(RefEntry::id).containsExactly(view.id());
        assertThat(store.findRefNamespace(ref.id(), List.of())).isEmpty();
        assertThat(store.resolveOnRef(UUID.randomUUID(), List.of("sales"), "orders")).as("no such ref").isEmpty();
    }

    @Test
    @DisplayName("Refs: a ref made from a branch takes the branch's own pointer where it has one, else its cut")
    void aRefFromABranchTakesItsOwnPointers() {
        UUID catalog = newCatalog();
        NamespaceRecord namespace = newNamespace(catalog, "ns");
        CatalogObject a = newTable(namespace, "a");
        CatalogObject b = newTable(namespace, "b");
        Pointer bAtCut = store.readPointer(b.mainKey()).orElseThrow();
        Ref dev = created(branch(catalog, "dev", now()));
        Pointer aOnDev = commit(dev.key(a.id()), Optional.empty(), "a-on-dev");
        commit(a.mainKey(), store.readPointer(a.mainKey()), "a-on-main");
        commit(b.mainKey(), Optional.of(bAtCut), "b-on-main");

        Ref fix = Refs.from(dev, "fix", Ref.Kind.TAG, now());
        assertApplied(store.createRef(fix, RefLimits.P11, change()));

        assertThat(store.readRef(fix.id())).contains(fix);
        assertThat(fix.fromRef()).contains(dev.id());
        assertThat(pointerOnRef(fix, List.of("ns"), "a")).contains(at(aOnDev));
        assertThat(pointerOnRef(fix, List.of("ns"), "b")).as("the branch's cut").contains(at(bAtCut));
        assertThat(store.findRefNamespace(fix.id(), List.of("ns")).map(RefEntry::id)).contains(namespace.id());
    }

    @Test
    @DisplayName("Refs: names compare as every name does, are unique in their catalog, and list in normalized order")
    void namesCompareAsEveryNameDoes() {
        UUID catalog = newCatalog();
        Ref release = created(branch(catalog, "Release", now()));

        assertThat(store.createRef(tag(catalog, "RELEASE", now()), RefLimits.P11, change()))
                .isEqualTo(new WriteResult.NameTaken(release.id()));
        assertThat(store.createRef(release, RefLimits.P11, change())).as("a retried create names its own id")
                .isEqualTo(new WriteResult.NameTaken(release.id()));
        assertThat(store.findRef(catalog, "release")).contains(release);
        assertThat(store.findRef(catalog, "Release")).as("looked up by the normalized name only").isEmpty();
        assertApplied(store.createRef(branch(newCatalog(), "Release", now()), RefLimits.P11, change()));
        created(tag(catalog, "b", now()));
        created(tag(catalog, "A", now()));
        created(branch(catalog, "c", now()));

        assertThat(allPages(10, page -> store.listRefs(catalog, page))).extracting(ref -> ref.name().display())
                .containsExactly("A", "b", "c", "Release");
    }

    @Test
    @DisplayName("Refs: an unknown catalog, then a taken name, then a missing or expired source, then the limit")
    void createOutcomesComeInOrder() {
        UUID catalog = newCatalog();
        Instant now = now();
        Ref taken = created(branch(catalog, "taken", now));
        Ref other = created(branch(newCatalog(), "other", now));
        Ref none = Refs.from(taken, "x", Ref.Kind.TAG, now);
        Ref ghost = new Ref(catalog, UUID.randomUUID(), name("ghost"), Ref.Kind.TAG, now, "svc-suite",
                Optional.of(UUID.randomUUID()), "gone", Optional.empty(), Optional.empty());

        assertThat(store.createRef(branch(UUID.randomUUID(), "x", now), RefLimits.P11, change()))
                .isEqualTo(new WriteResult.Missing(WriteResult.Subject.CATALOG));
        assertThat(store.createRef(Refs.from(ghost, "taken", Ref.Kind.TAG, now), RefLimits.P11, change()))
                .as("a taken name before a missing source").isEqualTo(new WriteResult.NameTaken(taken.id()));
        assertThat(store.createRef(ghost, new RefLimits(0, 0), change())).as("a missing source before the limit")
                .isEqualTo(MISSING_REF);
        assertThat(store.createRef(new Ref(catalog, UUID.randomUUID(), name("y"), Ref.Kind.TAG, now, "svc-suite",
                Optional.of(other.id()), "other", Optional.empty(), Optional.empty()), RefLimits.P11, change()))
                .as("a ref of another catalog").isEqualTo(MISSING_REF);
        assertThat(store.createRef(none, RefLimits.P11, changeAt(now.plus(Refs.LIFETIME))))
                .as("a branch at its expiry").isEqualTo(MISSING_REF);
        assertApplied(store.deleteRef(taken.id(), change()));
        assertThat(store.createRef(Refs.from(taken, "z", Ref.Kind.TAG, now), RefLimits.P11, change()))
                .as("a deleted branch").isEqualTo(MISSING_REF);
        assertThat(store.createRef(tag(catalog, "w", now), new RefLimits(0, 0), change())).isEqualTo(LIMIT);
        assertThat(store.listRefs(catalog, PageRequest.first(10)).items()).as("nothing was made").isEmpty();
    }

    @Test
    @DisplayName("Refs: of creates racing for a catalog's last places, only as many as the limit allow are made")
    void limitsHoldUnderConcurrentCreates() throws Exception {
        UUID catalog = newCatalog();
        newTable(newNamespace(catalog, "ns"), "t");
        RefLimits limits = new RefLimits(3, 2);
        Map<Ref.Kind, List<WriteResult>> results = new ConcurrentHashMap<>();
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> creates = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            Ref.Kind kind = i % 2 == 0 ? Ref.Kind.BRANCH : Ref.Kind.TAG;
            Ref ref = kind == Ref.Kind.BRANCH ? branch(catalog, "b" + i, now()) : tag(catalog, "t" + i, now());
            creates.add(background.submit(() -> {
                start.await();
                WriteResult result = store.createRef(ref, limits, change());
                results.computeIfAbsent(kind, k -> Collections.synchronizedList(new ArrayList<>())).add(result);
                return null;
            }));
        }
        start.countDown();
        for (Future<?> create : creates) {
            create.get(2, TimeUnit.MINUTES);
        }

        assertThat(results.get(Ref.Kind.BRANCH)).filteredOn(WriteResult.APPLIED::equals).hasSize(3);
        assertThat(results.get(Ref.Kind.BRANCH)).filteredOn(LIMIT::equals).hasSize(3);
        assertThat(results.get(Ref.Kind.TAG)).filteredOn(WriteResult.APPLIED::equals).hasSize(2);
        assertThat(results.get(Ref.Kind.TAG)).filteredOn(LIMIT::equals).hasSize(4);
        assertThat(allPages(10, page -> store.listRefs(catalog, page))).hasSize(5);
    }

    @Test
    @DisplayName("Refs: a branch past its expiry no longer counts toward the limit, but holds its name until deleted")
    void anExpiredBranchFreesItsPlaceNotItsName() {
        UUID catalog = newCatalog();
        Instant now = now();
        RefLimits one = new RefLimits(1, 0);
        Ref old = branch(catalog, "old", now);
        assertApplied(store.createRef(old, one, changeAt(now)));
        Instant later = now.plus(Refs.LIFETIME);

        assertThat(store.createRef(branch(catalog, "new", now), one, changeAt(now))).isEqualTo(LIMIT);
        assertThat(store.createRef(branch(catalog, "old", later), one, changeAt(later))).as("the name is held")
                .isEqualTo(new WriteResult.NameTaken(old.id()));
        assertApplied(store.createRef(branch(catalog, "new", later), one, changeAt(later)));
        assertThat(store.readRef(old.id()).map(ref -> ref.expiredAt(later))).contains(true);
    }

    @Test
    @DisplayName("Refs: a branch is renewed until it expires; a tag never is")
    void renewal() {
        UUID catalog = newCatalog();
        Instant now = now();
        Ref dev = created(branch(catalog, "dev", now));
        Ref v1 = created(tag(catalog, "v1", now));
        Instant later = now.plus(Duration.ofDays(90));

        assertApplied(store.renewRef(dev.id(), later, change()));
        assertThat(store.readRef(dev.id()).flatMap(Ref::expiresAt)).contains(later);
        assertThat(store.renewRef(dev.id(), later, change())).isEqualTo(WriteResult.UNCHANGED);
        assertThat(store.renewRef(v1.id(), later, change()))
                .isEqualTo(new WriteResult.Refused(WriteResult.Reason.NOT_A_BRANCH));
        assertThat(store.renewRef(UUID.randomUUID(), later, change())).isEqualTo(MISSING_REF);
        assertThat(store.renewRef(dev.id(), later.plus(Duration.ofDays(1)), changeAt(later))).as("at its expiry")
                .isEqualTo(MISSING_REF);
        assertThat(store.readRef(dev.id()).flatMap(Ref::expiresAt)).contains(later);
    }

    @Test
    @DisplayName("Refs: a ref keeps the description it was made with, through every read and a renewal (backlog 167)")
    void aRefKeepsItsDescription() {
        UUID catalog = newCatalog();
        CatalogObject table = newTable(newNamespace(catalog, "ns"), "t");
        Instant now = now();
        // The route's longest: 200 characters, some outside the BMP, with the characters HTML escapes.
        String start = "Before the <b>November</b> restatement & \"Q3\" 'close' é 🧊 ";
        String purpose = start + "x".repeat(200 - start.codePointCount(0, start.length()));
        Ref dev = created(Refs.described(branch(catalog, "dev", now), purpose));
        Ref v1 = created(tag(catalog, "v1", now));
        Ref fromDev = created(Refs.from(dev, "dev-2", Ref.Kind.BRANCH, now));

        assertThat(store.readRef(dev.id())).contains(dev);
        assertThat(store.readRef(dev.id()).flatMap(Ref::description)).contains(purpose);
        assertThat(store.findRef(catalog, "dev")).contains(dev);
        assertThat(store.readRef(v1.id()).flatMap(Ref::description)).as("made without one").isEmpty();
        assertThat(store.readRef(fromDev.id()).flatMap(Ref::description)).as("not inherited from its source")
                .isEmpty();
        assertThat(store.listRefs(catalog, PageRequest.first(10)).items()).containsExactly(dev, fromDev, v1);
        assertThat(store.listRefPointers(table.id())).extracting(RefPointers::ref).containsExactly(dev, fromDev, v1);

        Instant later = now.plus(Duration.ofDays(90));
        assertApplied(store.renewRef(dev.id(), later, change()));
        assertThat(store.readRef(dev.id()).flatMap(Ref::description)).as("after a renewal").contains(purpose);
    }

    @Test
    @DisplayName("Refs: a deletion leaves no cut, no pointer of the ref and no projection piece, and frees the name")
    void aDeletionLeavesNothing() {
        UUID catalog = newCatalog();
        NamespaceRecord namespace = newNamespace(catalog, "ns");
        CatalogObject table = newTable(namespace, "t");
        Pointer main = store.readPointer(table.mainKey()).orElseThrow();
        Ref dev = created(branch(catalog, "dev", now()));
        PointerKey own = dev.key(table.id());
        byte[] projection = new byte[3 * underTest().projectionPieceBytes()];
        random.nextBytes(projection);
        Pointer large = new Pointer("s3://suite-warehouse/large-on-dev.metadata.json", 1, "0".repeat(64), projection);
        CommitBatch batch = Commits.batch(Commits.swap(own, Optional.empty(), large));
        assertThat(store.commit(batch)).isInstanceOf(CommitResult.Committed.class);
        assertThat(underTest().projectionPieces(own)).isEqualTo(3);

        assertApplied(store.deleteRef(dev.id(), change()));

        assertThat(store.readRef(dev.id())).isEmpty();
        assertThat(store.readPointer(own)).isEmpty();
        assertThat(underTest().projectionPieces(own)).isZero();
        assertThat(store.readPointer(table.mainKey())).contains(main);
        assertThat(store.listRefNamespaces(dev.id(), catalog, PageRequest.first(10)).items()).isEmpty();
        assertThat(store.listRefObjects(dev.id(), namespace.id(), CatalogObject.Kind.TABLE, PageRequest.first(10)).items())
                .isEmpty();
        assertThat(store.deleteRef(dev.id(), change())).isEqualTo(MISSING_REF);
        assertApplied(store.createRef(branch(catalog, "dev", now()), RefLimits.P11, change()));
    }

    @Test
    @DisplayName("Refs: a branch counts the tables and views it has its own pointer for, once each, and nothing of"
            + " main's or another ref's (backlog 105)")
    void aBranchCountsItsOwnPointers() {
        UUID catalog = newCatalog();
        NamespaceRecord namespace = newNamespace(catalog, "ns");
        CatalogObject a = newTable(namespace, "a");
        CatalogObject b = newTable(namespace, "b");
        CatalogObject view = newObject(namespace, "v", CatalogObject.Kind.VIEW);
        Ref dev = created(branch(catalog, "dev", now()));
        Ref other = created(branch(catalog, "other", now()));
        Ref eod = created(tag(catalog, "eod", now()));
        assertThat(store.countOwnPointers(dev.id())).isZero();

        Pointer aOnDev = commit(dev.key(a.id()), Optional.empty(), "a-on-dev");
        commit(dev.key(a.id()), Optional.of(aOnDev), "a-on-dev-2");
        commit(dev.key(view.id()), Optional.empty(), "v-on-dev");
        commit(other.key(b.id()), Optional.empty(), "b-on-other");
        commit(b.mainKey(), store.readPointer(b.mainKey()), "b-on-main");

        assertThat(store.countOwnPointers(dev.id())).isEqualTo(2);
        assertThat(store.countOwnPointers(other.id())).isEqualTo(1);
        assertThat(store.countOwnPointers(eod.id())).isZero();
        assertThat(store.countOwnPointers(UUID.randomUUID())).as("no such ref").isZero();
        assertApplied(store.deleteRef(dev.id(), change()));
        assertThat(store.countOwnPointers(dev.id())).isZero();
    }

    @Test
    @DisplayName("Refs: every ref whose cut names a table lists the pointer its cut took, and a branch its own")
    void everyRefNamingATableListsItsPointers() {
        UUID catalog = newCatalog();
        NamespaceRecord namespace = newNamespace(catalog, "ns");
        CatalogObject table = newTable(namespace, "t");
        Pointer first = store.readPointer(table.mainKey()).orElseThrow();
        Ref eod = created(tag(catalog, "eod", now()));
        Ref dev = created(branch(catalog, "Dev", now()));
        Pointer second = commit(table.mainKey(), Optional.of(first), "t-on-main");
        Ref audit = created(branch(catalog, "audit", now()));
        Pointer onDev = commit(dev.key(table.id()), Optional.empty(), "t-on-dev");
        Ref fix = created(Refs.from(dev, "fix", Ref.Kind.BRANCH, now()));
        CatalogObject later = newTable(namespace, "later");

        assertThat(store.listRefPointers(table.id())).as("by normalized name").containsExactly(
                new RefPointers(audit, at(second), Optional.empty()),
                new RefPointers(dev, at(first), Optional.of(at(onDev))),
                new RefPointers(eod, at(first), Optional.empty()),
                new RefPointers(fix, at(onDev), Optional.empty()));
        assertThat(store.listRefPointers(later.id())).as("made after every ref").isEmpty();

        assertApplied(store.deleteRef(dev.id(), change()));
        assertThat(store.listRefPointers(table.id())).extracting(RefPointers::ref).containsExactly(audit, eod, fix);
    }

    @Test
    @DisplayName("Refs: a cut never holds part of a multi-table commit made while it is read")
    void aCutHoldsEveryMultiTableCommitWhole() throws Exception {
        UUID catalog = newCatalog();
        NamespaceRecord namespace = newNamespace(catalog, "ns");
        CatalogObject table = newTable(namespace, "t");
        CatalogObject view = newObject(namespace, "v", CatalogObject.Kind.VIEW);
        AtomicBoolean stop = new AtomicBoolean();
        Future<?> writer = background.submit(() -> {
            Optional<Pointer> t = store.readPointer(table.mainKey());
            Optional<Pointer> v = store.readPointer(view.mainKey());
            while (!stop.get()) {
                PointerSwap ts = Commits.advance(table.mainKey(), t, "t-" + UUID.randomUUID());
                PointerSwap vs = Commits.advance(view.mainKey(), v, "v-" + UUID.randomUUID());
                assertThat(store.commit(Commits.batch(ts, vs))).isInstanceOf(CommitResult.Committed.class);
                t = Optional.of(ts.next());
                v = Optional.of(vs.next());
            }
            return null;
        });
        try {
            for (int i = 0; i < 25; i++) {
                Ref ref = created(tag(catalog, "cut-" + i, now()));
                long t = pointerOnRef(ref, List.of("ns"), "t").orElseThrow().version();
                long v = pointerOnRef(ref, List.of("ns"), "v").orElseThrow().version();
                assertThat(t).as("cut %d", i).isEqualTo(v);
            }
        } finally {
            stop.set(true);
        }
        writer.get(1, TimeUnit.MINUTES);
        assertThat(store.readPointer(table.mainKey()).orElseThrow().version()).as("the writer committed while cutting")
                .isGreaterThan(1);
    }

    @Test
    @DisplayName("Refs: every table in a cut has the namespace that held it, in the cut and on main, while namespaces"
            + " come and go (backlog 158)")
    void everyTableInACutHasItsNamespace() throws Exception {
        UUID catalog = newCatalog();
        Map<UUID, UUID> tableOfNamespace = new ConcurrentHashMap<>();
        AtomicBoolean stop = new AtomicBoolean();
        Future<?> writer = background.submit(() -> {
            for (int i = 0; !stop.get(); i++) {
                NamespaceRecord namespace = newNamespace(catalog, "ns-" + i);
                CatalogObject table = newTable(namespace, "t");
                tableOfNamespace.put(namespace.id(), table.id());
                assertApplied(store.dropObject(table.id(), now().plus(Duration.ofDays(30)), change()));
                assertThat(store.dropNamespace(namespace.id(), change())).as("refused while a cut names its table")
                        .isIn(WriteResult.APPLIED, REFERENCED);
            }
            return null;
        });
        try {
            for (int i = 0; i < 25; i++) {
                Ref ref = created(tag(catalog, "cut-" + i, now()));
                List<UUID> namespaces = allPages(50, page -> store.listRefNamespaces(ref.id(), catalog, page)).stream()
                        .map(RefEntry::id).toList();
                for (UUID namespaceId : List.copyOf(tableOfNamespace.keySet())) {
                    boolean tableIn = !store.listRefObjects(ref.id(), namespaceId, CatalogObject.Kind.TABLE,
                            PageRequest.first(1)).items().isEmpty();
                    assertThat(!tableIn || namespaces.contains(namespaceId)).as("cut %d, namespace %s", i, namespaceId)
                            .isTrue();
                    assertThat(!tableIn || store.readNamespace(namespaceId).isPresent())
                            .as("cut %d, namespace %s on main", i, namespaceId).isTrue();
                }
            }
        } finally {
            stop.set(true);
        }
        writer.get(1, TimeUnit.MINUTES);
        assertThat(tableOfNamespace).as("the writer made namespaces while cutting").isNotEmpty();
    }

    @Test
    @DisplayName("Refs: a namespace stays, with its governance, while a ref's cut names a table or view of it, dropped"
            + " or not, until the ref is deleted: a tag, an expired branch and a cut copied from a branch alike")
    void aRefKeepsItsObjectsNamespace() {
        UUID catalog = newCatalog();
        NamespaceRecord tables = newNamespace(catalog, "tables");
        NamespaceRecord views = newNamespace(catalog, "views");
        NamespaceRecord later = newNamespace(catalog, "later");
        CatalogObject table = newTable(tables, "t");
        CatalogObject view = newObject(views, "v", CatalogObject.Kind.VIEW);
        Securable regulated = new Securable(Securable.Kind.NAMESPACE, tables.id());
        assertApplied(store.putAttribute(new GovernanceAttribute(regulated, GovernanceAttribute.Kind.REGULATED, "true",
                "api", "steward", now()), change()));
        Ref v1 = created(tag(catalog, "v1", now()));
        Ref dev = created(branch(catalog, "dev", now()));
        CatalogObject made = newTable(later, "t");
        for (CatalogObject object : List.of(table, view, made)) {
            assertApplied(store.dropObject(object.id(), now().plus(Duration.ofDays(30)), change()));
        }
        Change refused = fullChange();

        assertThat(store.dropNamespace(tables.id(), refused)).as("a dropped table").isEqualTo(REFERENCED);
        assertThat(underTest().changeRows(refused)).isEqualTo(WrittenRows.NONE);
        assertThat(store.attributesOf(Set.of(regulated))).extracting(GovernanceAttribute::kind)
                .containsExactly(GovernanceAttribute.Kind.REGULATED);
        assertThat(store.dropNamespace(views.id(), change())).as("a dropped view").isEqualTo(REFERENCED);
        assertApplied(store.dropNamespace(later.id(), change()));
        assertApplied(store.deleteRef(v1.id(), change()));
        assertThat(store.dropNamespace(tables.id(), changeAt(dev.expiresAt().orElseThrow())))
                .as("a branch past its expiry, until it is deleted").isEqualTo(REFERENCED);
        Ref fix = created(Refs.from(dev, "fix", Ref.Kind.TAG, now()));
        assertApplied(store.deleteRef(dev.id(), change()));
        assertThat(store.dropNamespace(tables.id(), change())).as("a ref made from the branch").isEqualTo(REFERENCED);
        assertApplied(store.deleteRef(fix.id(), change()));

        assertApplied(store.dropNamespace(tables.id(), change()));
        assertApplied(store.dropNamespace(views.id(), change()));
        assertThat(store.attributesOf(Set.of(regulated))).isEmpty();
    }

    @Test
    @DisplayName("Refs: a namespace drop waits for a ref made from main that is open before its COMMIT, while a table of"
            + " the cut was dropped meanwhile, and then is refused: a ref never names a table whose namespace is gone"
            + " (backlog 158)")
    void aNamespaceDropWaitsForAnOpenRefCreate() throws Exception {
        UUID catalog = newCatalog();
        NamespaceRecord outer = newNamespace(catalog, "outer");
        NamespaceRecord inner = newNamespace(outer, "inner");
        CatalogObject table = newTable(inner, "t");
        Ref ref = tag(catalog, "v1", now());
        Change create = change();

        Overlap raced = whileOpen(create, held -> held.createRef(ref, RefLimits.P11, create), () -> {
            assertApplied(store.dropObject(table.id(), now().plus(Duration.ofDays(30)), change()));
            return store.dropNamespace(inner.id(), change());
        });

        assertThat(raced.open()).isEqualTo(WriteResult.APPLIED);
        assertThat(raced.meanwhile()).isIn(WriteResult.APPLIED, REFERENCED);
        if (raced.meanwhile().equals(WriteResult.APPLIED)) {
            assertThat(store.listRefPointers(table.id())).as("the namespace went, so no ref names its table").isEmpty();
        } else {
            assertThat(store.listRefPointers(table.id())).extracting(RefPointers::ref).containsExactly(ref);
            assertThat(store.readNamespace(inner.id())).as("a ref names its table, so the namespace stays").isPresent();
        }
        assertThat(raced.waited()).as("the drop waited for the ref create").isTrue();
    }

    @Test
    @DisplayName("Refs: taking a table out of refs deletes the named refs' entries for it and a branch's own pointer and"
            + " pieces for it; the branch's journal, its other tables, every other ref and main stay (backlog 156)")
    void takingATableOutOfRefs() {
        UUID catalog = newCatalog();
        NamespaceRecord namespace = newNamespace(catalog, "ns");
        CatalogObject table = newTable(namespace, "t");
        CatalogObject other = newTable(namespace, "other");
        Pointer main = store.readPointer(table.mainKey()).orElseThrow();
        Ref dev = created(branch(catalog, "dev", now()));
        Ref v1 = created(tag(catalog, "v1", now()));
        Ref qa = created(branch(catalog, "qa", now()));
        PointerKey own = dev.key(table.id());
        byte[] projection = new byte[3 * underTest().projectionPieceBytes()];
        random.nextBytes(projection);
        Pointer large = new Pointer("s3://suite-warehouse/large-on-dev.metadata.json", 1, "0".repeat(64), projection);
        assertThat(store.commit(Commits.batch(Commits.swap(own, Optional.empty(), large))))
                .isInstanceOf(CommitResult.Committed.class);
        Pointer otherOnDev = commit(dev.key(other.id()), Optional.empty(), "other-on-dev");
        assertThat(underTest().projectionPieces(own)).isEqualTo(3);
        Change full = fullChange();

        assertApplied(store.removeFromRefs(table.id(), Set.of(dev.id(), v1.id()), false, full));

        assertThat(underTest().changeRows(full)).isEqualTo(WrittenRows.allOf(full));
        for (Ref left : List.of(dev, v1)) {
            assertThat(store.resolveOnRef(left.id(), List.of("ns"), "t")).as("on %s", left.name().display()).isEmpty();
            assertThat(store.listRefObjects(left.id(), namespace.id(), CatalogObject.Kind.TABLE, PageRequest.first(10))
                    .items()).extracting(RefEntry::id).containsExactly(other.id());
            assertThat(store.findRefNamespace(left.id(), List.of("ns"))).as("its namespace stays").isPresent();
            assertThat(store.readRef(left.id())).contains(left);
        }
        assertThat(store.readPointer(own)).isEmpty();
        assertThat(underTest().projectionPieces(own)).isZero();
        assertThat(underTest().journalVersions(own)).as("the branch's journal stays").containsExactly(1L);
        assertThat(store.countOwnPointers(dev.id())).isEqualTo(1);
        assertThat(store.readPointer(dev.key(other.id()))).contains(otherOnDev);
        assertThat(store.listRefPointers(table.id())).extracting(RefPointers::ref).containsExactly(qa);
        assertThat(store.resolveOnRef(qa.id(), List.of("ns"), "t")).as("a ref not named keeps it").isPresent();
        assertThat(store.readPointer(table.mainKey())).contains(main);
        CommitBatch batch = Commits.fullBatch(Commits.advance(own, Optional.empty(), "t-on-dev"));
        CommitBatch onDev = new CommitBatch(batch.commitId(), now(), batch.swaps(), List.of(),
                Optional.of(new RefLock.Hold(dev.id())), batch.outbox(), batch.idempotency());
        assertThat(store.commit(onDev)).as("a commit on the branch to it").isEqualTo(new CommitResult.Inactive(own));
        assertThat(store.removeFromRefs(table.id(), Set.of(dev.id()), false, change())).as("taken out already")
                .isEqualTo(MISSING_REF);
    }

    @Test
    @DisplayName("Refs: a removal's outcomes come in order, and a refused one writes nothing: no such table, a hold, a"
            + " freeze, a ref that is not the catalog's or does not name it, and every asked with a ref left out"
            + " (backlog 156)")
    void removalOutcomesComeInOrder() {
        UUID catalog = newCatalog();
        NamespaceRecord namespace = newNamespace(catalog, "ns");
        CatalogObject table = newTable(namespace, "t");
        Ref dev = created(branch(catalog, "dev", now()));
        Ref v1 = created(tag(catalog, "v1", now()));
        CatalogObject later = newTable(namespace, "later");
        UUID otherCatalog = newCatalog();
        newTable(newNamespace(otherCatalog, "ns"), "t");
        Ref elsewhere = created(tag(otherCatalog, "v1", now()));
        Change refused = fullChange();

        assertThat(store.removeFromRefs(UUID.randomUUID(), Set.of(dev.id()), false, refused)).isEqualTo(MISSING_OBJECT);
        LegalHold hold = new LegalHold(UUID.randomUUID(), new Securable(Securable.Kind.NAMESPACE, namespace.id()),
                "matter", "legal", now());
        assertApplied(store.placeHold(hold, change()));
        assertThat(store.removeFromRefs(table.id(), Set.of(dev.id()), false, refused)).as("a hold on its namespace")
                .isEqualTo(HELD);
        Securable top = new Securable(Securable.Kind.CATALOG, catalog);
        assertApplied(store.putAttribute(new GovernanceAttribute(top, GovernanceAttribute.Kind.DR_FREEZE, "drill",
                "suite", "admin", now()), change()));
        assertThat(store.removeFromRefs(table.id(), Set.of(UUID.randomUUID()), false, refused))
                .as("held and frozen, and no such ref: the hold first").isEqualTo(HELD);
        assertApplied(store.releaseHold(hold.id(), "counsel", change()));
        assertThat(store.removeFromRefs(table.id(), Set.of(UUID.randomUUID()), false, refused))
                .as("a freeze on its catalog").isEqualTo(FROZEN);
        assertApplied(store.removeAttribute(top, GovernanceAttribute.Kind.DR_FREEZE, "steward", change()));
        assertThat(store.removeFromRefs(table.id(), Set.of(dev.id(), elsewhere.id()), false, refused))
                .as("another catalog's ref").isEqualTo(MISSING_REF);
        assertThat(store.removeFromRefs(table.id(), Set.of(dev.id(), UUID.randomUUID()), false, refused))
                .as("no such ref").isEqualTo(MISSING_REF);
        assertThat(store.removeFromRefs(later.id(), Set.of(dev.id()), false, refused))
                .as("a ref whose cut does not name it").isEqualTo(MISSING_REF);
        assertThat(store.removeFromRefs(table.id(), Set.of(namespace.id()), false, refused))
                .as("a namespace's id").isEqualTo(MISSING_REF);
        assertThat(store.removeFromRefs(table.id(), Set.of(dev.id()), true, refused)).as("every, and v1 names it too")
                .isEqualTo(REFERENCED);
        assertThat(underTest().changeRows(refused)).isEqualTo(WrittenRows.NONE);
        assertThat(store.listRefPointers(table.id())).extracting(RefPointers::ref).containsExactly(dev, v1);

        assertApplied(store.removeFromRefs(table.id(), Set.of(dev.id(), v1.id()), true, change()));
        assertThat(store.listRefPointers(table.id())).isEmpty();
    }

    @Test
    @DisplayName("Refs: a removal from refs waits for a hold being placed on a namespace above the table, or on its"
            + " catalog, and then is refused (backlog 171)")
    void aRemovalWaitsForAnOpenHoldAboveTheTable() throws Exception {
        UUID catalog = newCatalog();
        NamespaceRecord outer = newNamespace(catalog, "outer");
        NamespaceRecord inner = newNamespace(outer, "inner");
        CatalogObject table = newTable(inner, "t");
        Ref v1 = created(tag(catalog, "v1", now()));
        for (Securable level : List.of(new Securable(Securable.Kind.NAMESPACE, outer.id()),
                new Securable(Securable.Kind.CATALOG, catalog))) {
            LegalHold hold = new LegalHold(UUID.randomUUID(), level, "matter", "legal", now());
            Change place = change();

            Overlap raced = whileOpen(place, held -> held.placeHold(hold, place),
                    () -> store.removeFromRefs(table.id(), Set.of(v1.id()), false, change()));

            assertThat(raced.open()).isEqualTo(WriteResult.APPLIED);
            assertThat(raced.meanwhile()).as("a hold on the %s", level.kind()).isEqualTo(HELD);
            assertThat(store.listRefPointers(table.id())).extracting(RefPointers::ref).containsExactly(v1);
            assertThat(raced.waited()).as("the removal waited for the hold on the %s", level.kind()).isTrue();
            assertApplied(store.releaseHold(hold.id(), "counsel", change()));
        }
    }

    @Test
    @DisplayName("Refs: once no ref names a dropped table or view, its purge claim goes through and its namespace can"
            + " be dropped; an expired branch is taken out as any ref, and a ref made from a branch after a removal"
            + " does not name what it took out (backlog 156)")
    void whatNoRefNamesCanBePurged() {
        UUID catalog = newCatalog();
        NamespaceRecord tables = newNamespace(catalog, "tables");
        NamespaceRecord views = newNamespace(catalog, "views");
        CatalogObject table = newTable(tables, "t");
        CatalogObject view = newObject(views, "v", CatalogObject.Kind.VIEW);
        Ref v1 = created(tag(catalog, "v1", now()));
        Ref dev = created(branch(catalog, "dev", now()));
        Ref fix = created(Refs.from(dev, "fix", Ref.Kind.TAG, now()));
        for (CatalogObject object : List.of(table, view)) {
            assertApplied(store.dropObject(object.id(), now(), change()));
        }

        assertThat(store.claimPurge(table.id(), "worker", change())).isEqualTo(REFERENCED);
        assertApplied(store.removeFromRefs(table.id(), Set.of(v1.id(), fix.id()), false, change()));
        assertThat(store.claimPurge(table.id(), "worker", change())).as("dev names it still").isEqualTo(REFERENCED);
        assertApplied(store.removeFromRefs(table.id(), Set.of(dev.id()), true,
                changeAt(dev.expiresAt().orElseThrow())));
        Ref copy = created(Refs.from(dev, "copy", Ref.Kind.TAG, now()));
        assertThat(store.resolveOnRef(copy.id(), List.of("tables"), "t")).as("made from dev after").isEmpty();
        assertThat(store.listRefPointers(table.id())).isEmpty();
        assertApplied(store.claimPurge(table.id(), "worker", change()));

        assertThat(store.dropNamespace(views.id(), change())).isEqualTo(REFERENCED);
        assertApplied(store.removeFromRefs(view.id(), Set.of(v1.id(), dev.id(), fix.id(), copy.id()), true, change()));
        assertApplied(store.dropNamespace(views.id(), change()));
    }

    @Test
    @DisplayName("Refs: a ref made from a branch while a table is taken out of every ref is in the removal or does not"
            + " name the table: once the removal is done no ref names it (backlog 156)")
    void aRemovalFromEveryRefHoldsAgainstRefsMadeMeanwhile() throws Exception {
        UUID catalog = newCatalog();
        NamespaceRecord namespace = newNamespace(catalog, "ns");
        CatalogObject table = newTable(namespace, "t");
        newTable(namespace, "other");
        Ref dev = created(branch(catalog, "dev", now()));
        AtomicBoolean stop = new AtomicBoolean();
        CountDownLatch copying = new CountDownLatch(3);
        Future<?> writer = background.submit(() -> {
            for (int i = 0; i < 200 && !stop.get(); i++) {
                assertApplied(store.createRef(Refs.from(dev, "copy-" + i, Ref.Kind.TAG, now()), RefLimits.P11,
                        change()));
                copying.countDown();
            }
            return null;
        });
        WriteResult removed = MISSING_REF;
        try {
            assertThat(copying.await(1, TimeUnit.MINUTES)).as("the writer copies dev").isTrue();
            for (int attempt = 0; attempt < 500 && !removed.equals(WriteResult.APPLIED); attempt++) {
                Set<UUID> naming = new HashSet<>();
                store.listRefPointers(table.id()).forEach(held -> naming.add(held.ref().id()));
                removed = store.removeFromRefs(table.id(), naming, true, change());
                assertThat(removed).isIn(WriteResult.APPLIED, MISSING_REF, REFERENCED);
            }
        } finally {
            stop.set(true);
        }
        writer.get(1, TimeUnit.MINUTES);

        assertThat(removed).isEqualTo(WriteResult.APPLIED);
        assertThat(store.listRefPointers(table.id())).as("no ref names it once the removal is done").isEmpty();
        assertThat(allPages(100, page -> store.listRefs(catalog, page))).as("the writer made refs meanwhile")
                .hasSizeGreaterThan(3);
    }

    @Test
    @DisplayName("Refs: refs, a cut's namespaces and its tables page in order, each item once")
    void paging() {
        UUID catalog = newCatalog();
        List<NamespaceRecord> namespaces = new ArrayList<>();
        for (String display : List.of("e", "B", "d", "A", "c")) {
            namespaces.add(newNamespace(catalog, display));
        }
        NamespaceRecord first = namespaces.getFirst();
        for (String display : List.of("t5", "T1", "t4", "t2", "T3")) {
            newTable(first, display);
        }
        List<Ref> refs = new ArrayList<>();
        for (String display : List.of("r5", "R1", "r4", "r2", "R3")) {
            refs.add(created(tag(catalog, display, now())));
        }
        Ref ref = refs.getFirst();

        assertThat(allPages(2, page -> store.listRefs(catalog, page))).extracting(r -> r.name().normalized())
                .containsExactly("r1", "r2", "r3", "r4", "r5");
        assertThat(allPages(2, page -> store.listRefNamespaces(ref.id(), catalog, page)))
                .extracting(entry -> entry.name().normalized()).containsExactly("a", "b", "c", "d", "e");
        assertThat(allPages(2, page -> store.listRefObjects(ref.id(), first.id(), CatalogObject.Kind.TABLE, page)))
                .extracting(entry -> entry.name().normalized()).containsExactly("t1", "t2", "t3", "t4", "t5");
    }
}
