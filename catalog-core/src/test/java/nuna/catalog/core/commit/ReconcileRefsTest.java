package nuna.catalog.core.commit;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import nuna.catalog.store.CatalogObject;
import nuna.catalog.store.CatalogStore;
import nuna.catalog.store.Name;
import nuna.catalog.store.Page;
import nuna.catalog.store.PageRequest;
import nuna.catalog.store.Pointer;
import nuna.catalog.store.PointerKey;
import nuna.catalog.store.Ref;
import nuna.catalog.store.RefEntry;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The reconcile's report on refs after a failover (D33, amended 2026-11-20: "A report only"; item 111). The catalog
 * holds the namespaces Trading and Trading.Desk, the tables Trading.Orders and Trading.Desk.Fills and the view
 * Trading.Recent; its refs are a tag, two branches, one with its own pointer for Orders, and a branch past its expiry.
 * Every ref whose cut, or a branch's own pointer, names a metadata file the DR store lacks is listed, with the tables
 * and views concerned, and no other; nothing is moved. Against a store double holding the refs, their cuts and the
 * branches' own pointers, one entry a page so every listing is read across pages, and the DR store in memory, so it
 * needs no database.
 */
class ReconcileRefsTest {

    private static final UUID CATALOG = UUID.randomUUID();
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-20T04:00:00Z"), ZoneOffset.UTC);

    /** The DR store. */
    private final MemoryFiles dr = new MemoryFiles();
    /** How often the reconcile asked the DR store whether it holds each file. */
    private final Map<String, Integer> asked = new HashMap<>();
    /** Files the DR store cannot answer for. */
    private final Set<String> unreachable = new HashSet<>();

    private final UUID trading = UUID.randomUUID();
    private final UUID desk = UUID.randomUUID();
    private final UUID orders = UUID.randomUUID();
    private final UUID fills = UUID.randomUUID();
    private final UUID recent = UUID.randomUUID();

    /** Main's files as the refs took them: Orders at version 2, Fills and Recent at version 1. */
    private final String orders2 = file(orders, 2);
    private final String fills1 = file(fills, 1);
    private final String recent1 = file(recent, 1);

    private final List<Ref> refs = new ArrayList<>();
    private final Map<UUID, List<RefEntry>> cuts = new HashMap<>();
    private final Map<PointerKey, Pointer> pointers = new HashMap<>();

    private final Ref tag = ref("eod-2026-11-19", Ref.Kind.TAG, Optional.empty());
    private final Ref dev = ref("dev", Ref.Kind.BRANCH, Optional.of(CLOCK.instant().plus(Duration.ofDays(30))));
    private final Ref qa = ref("qa", Ref.Kind.BRANCH, Optional.of(CLOCK.instant().plus(Duration.ofDays(3))));
    private final Ref stale = ref("stale", Ref.Kind.BRANCH, Optional.of(CLOCK.instant()));

    /** Dev's own pointer for Orders, its head there: its first commit on the branch, a file marked as dev's (item 105). */
    private final String devOrders = file(orders, 1, Optional.of(dev.id()));
    /** Stale's own pointer for Fills. */
    private final String staleFills = file(fills, 1, Optional.of(stale.id()));

    private final Reconcile reconcile = new Reconcile(store(), files(), CommitSettings.DEFAULTS, CLOCK, wait -> {
        throw new AssertionError("nothing waits here");
    });

    ReconcileRefsTest() {
        for (String location : List.of(orders2, fills1, recent1, devOrders, staleFills)) {
            dr.put(location, "{}".getBytes(UTF_8));
        }
        for (Ref ref : refs) {
            cut(ref);
        }
        head(dev, orders, devOrders);
        head(stale, fills, staleFills);
        // Main has moved on since: its pointers name files the DR store lacks, and the report on refs never reads them.
        for (UUID object : List.of(orders, fills, recent)) {
            pointers.put(new PointerKey(object, PointerKey.MAIN), new Pointer(file(object, 7), 7, "main", new byte[0]));
        }
    }

    // ---- what is reported ----

    @Test
    @DisplayName("a branch whose own pointer names a file the DR store lacks is reported with that table, and no other"
            + " ref, though main names files the store lacks too")
    void aBranchWhoseHeadIsMissingIsReportedAndNoOtherRef() {
        dr.remove(devOrders);

        List<Reconcile.RefCheck> checks = reconcile.checkRefs(CATALOG);

        assertThat(checks).as("every ref but the one past its expiry, by name")
                .extracting(check -> check.ref().name().display()).containsExactly("dev", "eod-2026-11-19", "qa");
        assertThat(reported(checks)).containsExactly("dev");
        Reconcile.RefCheck branch = checks.getFirst();
        assertThat(branch.problem()).isEmpty();
        assertThat(branch.missing()).containsExactly(new Reconcile.RefFile(orders, RefEntry.Kind.TABLE,
                List.of("Trading", "Orders"), Reconcile.RefPointer.HEAD, 1, devOrders));
    }

    @Test
    @DisplayName("with every file in the DR store no ref is reported")
    void withEveryFileThereNothingIsReported() {
        List<Reconcile.RefCheck> checks = reconcile.checkRefs(CATALOG);

        assertThat(checks).hasSize(3).allSatisfy(check -> {
            assertThat(check.missing()).isEmpty();
            assertThat(check.problem()).isEmpty();
        });
    }

    @Test
    @DisplayName("a file in the refs' cuts that the DR store lacks is reported for every ref naming it, from the cut,"
            + " with its namespaces' names, and the store is asked about each file once")
    void aCutFileIsReportedForEveryRefNamingIt() {
        dr.remove(fills1);

        List<Reconcile.RefCheck> checks = reconcile.checkRefs(CATALOG);

        assertThat(reported(checks)).containsExactly("dev", "eod-2026-11-19", "qa");
        Reconcile.RefFile missing = new Reconcile.RefFile(fills, RefEntry.Kind.TABLE, List.of("Trading", "Desk", "Fills"),
                Reconcile.RefPointer.CUT, 1, fills1);
        assertThat(checks).allSatisfy(check -> assertThat(check.missing()).containsExactly(missing));
        assertThat(asked).as("one question a file, however many refs name it")
                .containsOnlyKeys(orders2, fills1, recent1, devOrders).allSatisfy((file, times) ->
                        assertThat(times).as(file).isEqualTo(1));
    }

    @Test
    @DisplayName("a branch is reported for its cut's file and its own pointer's, each named as such")
    void aBranchIsCheckedAtItsCutAndItsHead() {
        dr.remove(orders2);
        dr.remove(devOrders);

        List<Reconcile.RefCheck> checks = reconcile.checkRefs(CATALOG);

        assertThat(checks.getFirst().missing()).as("dev").containsExactly(
                new Reconcile.RefFile(orders, RefEntry.Kind.TABLE, List.of("Trading", "Orders"),
                        Reconcile.RefPointer.CUT, 2, orders2),
                new Reconcile.RefFile(orders, RefEntry.Kind.TABLE, List.of("Trading", "Orders"),
                        Reconcile.RefPointer.HEAD, 1, devOrders));
        assertThat(checks.get(1).missing()).as("the tag").extracting(Reconcile.RefFile::pointer)
                .containsExactly(Reconcile.RefPointer.CUT);
    }

    @Test
    @DisplayName("a view's metadata file is checked as a table's is")
    void aViewIsCheckedAsATableIs() {
        dr.remove(recent1);

        List<Reconcile.RefCheck> checks = reconcile.checkRefs(CATALOG);

        assertThat(checks).allSatisfy(check -> assertThat(check.missing()).containsExactly(new Reconcile.RefFile(recent,
                RefEntry.Kind.VIEW, List.of("Trading", "Recent"), Reconcile.RefPointer.CUT, 1, recent1)));
    }

    @Test
    @DisplayName("a branch past its expiry is gone, so it is neither checked nor reported")
    void aBranchPastItsExpiryIsLeftOut() {
        dr.remove(staleFills);

        List<Reconcile.RefCheck> checks = reconcile.checkRefs(CATALOG);

        assertThat(checks).extracting(Reconcile.RefCheck::ref).doesNotContain(stale);
        assertThat(reported(checks)).isEmpty();
        assertThat(asked).doesNotContainKey(staleFills);
    }

    @Test
    @DisplayName("a file the DR store cannot answer for stops that ref's check with a problem, and the other refs are"
            + " checked")
    void aFileThatCannotBeCheckedStopsThatRefOnly() {
        unreachable.add(devOrders);
        dr.remove(recent1);

        List<Reconcile.RefCheck> checks = reconcile.checkRefs(CATALOG);

        Reconcile.RefCheck branch = checks.getFirst();
        assertThat(branch.ref()).isEqualTo(dev);
        assertThat(branch.problem()).get().asString().contains("storage cannot say whether it holds " + devOrders)
                .contains("connection reset");
        assertThat(checks.subList(1, 3)).allSatisfy(check -> {
            assertThat(check.problem()).isEmpty();
            assertThat(check.missing()).extracting(Reconcile.RefFile::metadataLocation).containsExactly(recent1);
        });
    }

    // ---- the catalog ----

    private static List<String> reported(List<Reconcile.RefCheck> checks) {
        return checks.stream().filter(check -> !check.missing().isEmpty() || check.problem().isPresent())
                .map(check -> check.ref().name().display()).toList();
    }

    /** A file main wrote for the object. */
    private static String file(UUID object, long version) {
        return file(object, version, Optional.empty());
    }

    /** A file a commit on {@code branch} wrote for the object, or main's when it is empty, named as the engine names it. */
    private static String file(UUID object, long version, Optional<UUID> branch) {
        return MemoryFiles.ROOT + "/" + object + "/metadata/" + TableFiles.fileName(version, branch);
    }

    private Ref ref(String name, Ref.Kind kind, Optional<Instant> expiresAt) {
        Ref ref = new Ref(CATALOG, UUID.randomUUID(), new Name(name, name), kind,
                CLOCK.instant().minus(Duration.ofDays(1)), "tester", Optional.empty(), Ref.MAIN, expiresAt,
                Optional.empty());
        refs.add(ref);
        refs.sort(Comparator.comparing(r -> r.name().normalized()));
        return ref;
    }

    /** The ref's cut, as main stood when it was made. */
    private void cut(Ref ref) {
        cuts.put(ref.id(), List.of(
                new RefEntry(ref.id(), trading, RefEntry.Kind.NAMESPACE, CATALOG, new Name("trading", "Trading"),
                        Optional.empty()),
                new RefEntry(ref.id(), desk, RefEntry.Kind.NAMESPACE, trading, new Name("desk", "Desk"),
                        Optional.empty()),
                new RefEntry(ref.id(), orders, RefEntry.Kind.TABLE, trading, new Name("orders", "Orders"),
                        Optional.of(new RefEntry.At(2, orders2, "o2"))),
                new RefEntry(ref.id(), recent, RefEntry.Kind.VIEW, trading, new Name("recent", "Recent"),
                        Optional.of(new RefEntry.At(1, recent1, "r1"))),
                new RefEntry(ref.id(), fills, RefEntry.Kind.TABLE, desk, new Name("fills", "Fills"),
                        Optional.of(new RefEntry.At(1, fills1, "f1")))));
    }

    /** A branch's own pointer for an object, as a branch's first commit to it writes it (item 105): version 1. */
    private void head(Ref branch, UUID object, String location) {
        pointers.put(branch.key(object), new Pointer(location, 1, "head", new byte[0]));
    }

    // ---- the doubles ----

    /** The DR store, counting each question whether it holds a file; one in {@link #unreachable} cannot answer. */
    private TableFiles files() {
        return (TableFiles) Proxy.newProxyInstance(TableFiles.class.getClassLoader(), new Class<?>[] {TableFiles.class},
                (proxy, method, args) -> {
                    if (!method.getName().equals("exists")) {
                        throw new UnsupportedOperationException("the report on refs does not call " + method.getName());
                    }
                    String location = (String) args[0];
                    asked.merge(location, 1, Integer::sum);
                    if (unreachable.contains(location)) {
                        throw new IllegalStateException("connection reset");
                    }
                    return dr.exists(location);
                });
    }

    /** The store calls the report on refs makes; any other call fails the test. */
    private CatalogStore store() {
        return (CatalogStore) Proxy.newProxyInstance(CatalogStore.class.getClassLoader(),
                new Class<?>[] {CatalogStore.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "listRefs" -> {
                        assertThat(args[0]).isEqualTo(CATALOG);
                        yield page(refs, (PageRequest) args[1]);
                    }
                    case "listRefNamespaces" -> page(cuts.getOrDefault((UUID) args[0], List.of()).stream()
                            .filter(entry -> entry.kind() == RefEntry.Kind.NAMESPACE && entry.parentId().equals(args[1]))
                            .sorted(Comparator.comparing(entry -> entry.name().normalized())).toList(),
                            (PageRequest) args[2]);
                    case "listRefObjects" -> page(cuts.getOrDefault((UUID) args[0], List.of()).stream()
                            .filter(entry -> entry.kind() == RefEntry.of((CatalogObject.Kind) args[2])
                                    && entry.parentId().equals(args[1]))
                            .sorted(Comparator.comparing(entry -> entry.name().normalized())).toList(),
                            (PageRequest) args[3]);
                    case "readPointer" -> {
                        PointerKey key = (PointerKey) args[0];
                        assertThat(key.branch()).as("the report reads only a branch's own pointers")
                                .isNotEqualTo(PointerKey.MAIN);
                        yield Optional.ofNullable(pointers.get(key));
                    }
                    default -> throw new UnsupportedOperationException("the report on refs does not call "
                            + method.getName());
                });
    }

    /** One item a page, so each listing is read across pages; the token is the next item's index. */
    private static <T> Page<T> page(List<T> items, PageRequest request) {
        int at = request.token().map(Integer::parseInt).orElse(0);
        if (at >= items.size()) {
            return new Page<>(List.of(), Optional.empty());
        }
        return new Page<>(List.of(items.get(at)), at + 1 < items.size() ? Optional.of(Integer.toString(at + 1))
                : Optional.empty());
    }
}
