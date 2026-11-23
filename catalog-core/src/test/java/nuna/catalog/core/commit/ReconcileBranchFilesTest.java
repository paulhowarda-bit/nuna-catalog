package nuna.catalog.core.commit;

import static nuna.catalog.core.commit.Requests.assertUuid;
import static nuna.catalog.core.commit.Requests.setProperties;
import static nuna.catalog.core.commit.Requests.update;
import static org.assertj.core.api.Assertions.assertThat;

import nuna.catalog.iceberg.Metadata;
import nuna.catalog.iceberg.Outcome;
import nuna.catalog.store.CatalogObject;
import nuna.catalog.store.CatalogStore;
import nuna.catalog.store.Name;
import nuna.catalog.store.Pointer;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The DR reconcile's walk never takes a branch's file as main's (security finding 12; item 111). A commit on a branch
 * writes its file beside main's, built on the file it read, and its first builds on main's file at the branch's cut
 * (D33, item 105), so without its mark a branch's chain would pass for main's. The walk judges each file that follows
 * by its name ({@link TableFiles#onBranch}), and never the pointer it starts from, which a register may start on a
 * branch's file (a merge never does: it writes main's own unmarked copy at main's next version, item 107).
 *
 * <p>A branch numbers its own pointer from 1, so today its first file lies below main's pointer and its later ones
 * build on its own; the walk meets a branch's files only after a register names one. The first two tests name a
 * branch's files at main's next versions all the same: the mark, not the numbering, keeps them out of main's chain.
 * Against a store double holding one table's main pointer, so it needs no database.
 */
class ReconcileBranchFilesTest {

    private static final UUID CATALOG = UUID.randomUUID();
    private static final UUID NAMESPACE = UUID.randomUUID();
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-22T02:00:00Z"), ZoneOffset.UTC);
    private static final Optional<UUID> MAIN = Optional.empty();

    private final MemoryFiles files = new MemoryFiles();
    private final UUID tableId = UUID.randomUUID();
    private final Optional<UUID> dev = Optional.of(UUID.randomUUID());
    private final String root = MemoryFiles.ROOT + "/" + tableId;
    private final CatalogObject table = new CatalogObject(CATALOG, tableId, NAMESPACE, new Name("orders", "Orders"),
            CatalogObject.Kind.TABLE, CatalogObject.State.ACTIVE, Optional.empty(), Optional.empty(), root,
            Optional.empty());
    private final Reconcile reconcile = new Reconcile(store(), files, CommitSettings.DEFAULTS, CLOCK, wait -> {
        throw new AssertionError("nothing waits here");
    });

    /** Main's pointer as the DR database holds it: version 1. */
    private final TableFiles.Written v1;
    private Pointer pointer;
    private int written;

    ReconcileBranchFilesTest() {
        String json = ((Outcome.NewMetadata) Metadata.createTable(Requests.createTable("orders", Map.of()),
                tableId.toString(), root)).json();
        v1 = files.write(root, 1, json);
        pointer = new Pointer(v1.location(), 1, v1.sha256(), new byte[0]);
    }

    /** A file built on {@code base} at the next version, by a commit on main or, with an id, on that branch. */
    private TableFiles.Written after(TableFiles.Written base, Optional<UUID> branch) {
        String json = ((Outcome.NewMetadata) Metadata.commitTable(update(assertUuid(tableId.toString()),
                setProperties(Map.of("written", String.valueOf(++written)))), files.read(base.location(), "").json(),
                base.location())).json();
        return files.write(root, Reconcile.version(base.location()) + 1, json, branch);
    }

    @Test
    @DisplayName("a branch's two commits past main's head are not taken, and main's own successor still is")
    void aBranchsCommitsPastMainsHeadAreNotTaken() {
        TableFiles.Written onDev2 = after(v1, dev);
        TableFiles.Written onDev3 = after(onDev2, dev);
        // Main's own commit after v1, its last on the primary: no later file proves it, the archive does.
        TableFiles.Written main2 = after(v1, MAIN);

        Reconcile.Plan plan = reconcile.plan(table, Set.of(), Optional.of(List.of(new Reconcile.ArchivedCommit(
                v1.location(), main2.location(), 2, main2.sha256()))));

        assertThat(TableFiles.onBranch(onDev2.location())).as("dev's files are marked").isTrue();
        assertThat(plan.steps()).extracting(Reconcile.Step::location).containsExactly(main2.location());
        assertThat(plan.steps()).extracting(Reconcile.Step::proof).containsExactly(Reconcile.Proof.ARCHIVED);
        assertThat(plan.tips()).isEmpty();
        assertThat(plan.problem()).isEmpty();
        assertThat(plan.steps()).extracting(Reconcile.Step::location)
                .doesNotContain(onDev2.location(), onDev3.location());
    }

    @Test
    @DisplayName("a branch's file and main's at one predecessor, each with a file built on it, are not two proven files")
    void aBranchsFileAndMainsAreNotTwoProvenFiles() {
        TableFiles.Written onDev2 = after(v1, dev);
        after(onDev2, dev);
        TableFiles.Written main2 = after(v1, MAIN);
        TableFiles.Written main3 = after(main2, MAIN);

        Reconcile.Plan plan = reconcile.plan(table, Set.of());

        assertThat(plan.problem()).isEmpty();
        assertThat(plan.steps()).extracting(Reconcile.Step::location).containsExactly(main2.location());
        assertThat(plan.steps()).extracting(Reconcile.Step::proof).containsExactly(Reconcile.Proof.SUCCESSOR);
        assertThat(plan.tips()).containsExactly(main3.location());
    }

    @Test
    @DisplayName("a pointer that names a branch's file, as a register can start it, still walks main's files after it")
    void aPointerOnABranchsFileWalksMainsFilesAfterIt() {
        TableFiles.Written registered = after(v1, dev);
        pointer = new Pointer(registered.location(), 2, registered.sha256(), new byte[0]);
        TableFiles.Written main3 = after(registered, MAIN);
        TableFiles.Written main4 = after(main3, MAIN);

        Reconcile.Plan plan = reconcile.plan(table, Set.of());

        assertThat(plan.from().metadataLocation()).isEqualTo(registered.location());
        assertThat(plan.problem()).isEmpty();
        assertThat(plan.steps()).extracting(Reconcile.Step::location).containsExactly(main3.location());
        assertThat(plan.steps()).extracting(Reconcile.Step::proof).containsExactly(Reconcile.Proof.SUCCESSOR);
        assertThat(plan.tips()).containsExactly(main4.location());
    }

    /** The store calls a plan makes, over the one table; any other call fails the test. */
    private CatalogStore store() {
        return (CatalogStore) Proxy.newProxyInstance(CatalogStore.class.getClassLoader(),
                new Class<?>[] {CatalogStore.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "readPointer" -> Optional.of(pointer);
                    case "readDrWatermark" -> Optional.empty();
                    default -> throw new UnsupportedOperationException("a plan does not call " + method.getName());
                });
    }
}
