package nuna.catalog.store.suite;

import static nuna.catalog.store.suite.Changes.change;
import static nuna.catalog.store.suite.Changes.name;
import static nuna.catalog.store.suite.Changes.now;
import static nuna.catalog.store.suite.Refs.branch;
import static nuna.catalog.store.suite.Refs.changeAt;
import static nuna.catalog.store.suite.Refs.tag;
import static org.assertj.core.api.Assertions.assertThat;

import nuna.catalog.store.CatalogObject;
import nuna.catalog.store.CommitBatch;
import nuna.catalog.store.CommitResult;
import nuna.catalog.store.NamespaceRecord;
import nuna.catalog.store.PageRequest;
import nuna.catalog.store.Pointer;
import nuna.catalog.store.PointerKey;
import nuna.catalog.store.PointerSwap;
import nuna.catalog.store.Ref;
import nuna.catalog.store.RefEntry;
import nuna.catalog.store.RefLimits;
import nuna.catalog.store.WriteResult;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
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
 * concurrent creates; renewal; a deletion leaves no cut, no pointer of the ref and no piece; paging.
 */
public abstract class RefSuite extends GroupSuite {

    private static final WriteResult MISSING_REF = new WriteResult.Missing(WriteResult.Subject.REF);
    private static final WriteResult LIMIT = new WriteResult.Refused(WriteResult.Reason.REF_LIMIT);

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
                Optional.of(UUID.randomUUID()), "gone", Optional.empty());

        assertThat(store.createRef(branch(UUID.randomUUID(), "x", now), RefLimits.P11, change()))
                .isEqualTo(new WriteResult.Missing(WriteResult.Subject.CATALOG));
        assertThat(store.createRef(Refs.from(ghost, "taken", Ref.Kind.TAG, now), RefLimits.P11, change()))
                .as("a taken name before a missing source").isEqualTo(new WriteResult.NameTaken(taken.id()));
        assertThat(store.createRef(ghost, new RefLimits(0, 0), change())).as("a missing source before the limit")
                .isEqualTo(MISSING_REF);
        assertThat(store.createRef(new Ref(catalog, UUID.randomUUID(), name("y"), Ref.Kind.TAG, now, "svc-suite",
                Optional.of(other.id()), "other", Optional.empty()), RefLimits.P11, change()))
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
    @DisplayName("Refs: every table in a cut has the namespace that held it, while namespaces come and go")
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
                assertApplied(store.dropNamespace(namespace.id(), change()));
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
                }
            }
        } finally {
            stop.set(true);
        }
        writer.get(1, TimeUnit.MINUTES);
        assertThat(tableOfNamespace).as("the writer made namespaces while cutting").isNotEmpty();
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
