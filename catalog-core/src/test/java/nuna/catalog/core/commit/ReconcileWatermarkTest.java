package nuna.catalog.core.commit;

import static nuna.catalog.core.commit.Requests.assertUuid;
import static nuna.catalog.core.commit.Requests.assertViewUuid;
import static nuna.catalog.core.commit.Requests.setProperties;
import static nuna.catalog.core.commit.Requests.update;
import static org.assertj.core.api.Assertions.assertThat;

import nuna.catalog.core.principal.Caller;
import nuna.catalog.core.principal.Principal;
import nuna.catalog.iceberg.Metadata;
import nuna.catalog.iceberg.Outcome;
import nuna.catalog.store.CatalogObject;
import nuna.catalog.store.CatalogStore;
import nuna.catalog.store.CommitBatch;
import nuna.catalog.store.CommitResult;
import nuna.catalog.store.DrWatermark;
import nuna.catalog.store.GovernanceAttribute;
import nuna.catalog.store.JournalEntry;
import nuna.catalog.store.JournalRecord;
import nuna.catalog.store.Name;
import nuna.catalog.store.OutboxEvent;
import nuna.catalog.store.Page;
import nuna.catalog.store.PageRequest;
import nuna.catalog.store.PageToken;
import nuna.catalog.store.Pointer;
import nuna.catalog.store.PointerSwap;
import nuna.catalog.store.Securable;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The reconcile and the DR watermark (D37, amended 2026-11-20; item 83 folded into item 28). The verifier found every
 * file of version 2 in the DR store and none past it. The DR database's pointer names version 4, and storage holds
 * proven files past it. The walk does not stop at the watermark ("Walk with a file check": each step's files are
 * checked instead, ReconcileFileCheckTest), and the rewind moves a table, or a view ("Rewind views too"), back to the
 * watermark in one commit, giving up versions 3 and 4 by name. Against a store double holding one object's pointer,
 * journal and watermark, so it needs no database.
 */
class ReconcileWatermarkTest {

    private static final UUID CATALOG = UUID.randomUUID();
    private static final UUID NAMESPACE = UUID.randomUUID();
    private static final Caller OPERATOR = new Caller(new Principal("operator", Set.of("platform-operators")),
            "dr-runbook", false, Optional.empty());
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-20T03:00:00Z"), ZoneOffset.UTC);

    private final MemoryFiles files = new MemoryFiles();
    private final UUID tableId = UUID.randomUUID();
    private final String root = MemoryFiles.ROOT + "/" + tableId;
    private final CatalogObject table = new CatalogObject(CATALOG, tableId, NAMESPACE, new Name("orders", "Orders"),
            CatalogObject.Kind.TABLE, CatalogObject.State.ACTIVE, Optional.empty(), Optional.empty(), root,
            Optional.empty());
    private final List<PointerSwap> swapped = new ArrayList<>();
    private final List<CommitEvents.Commit> published = new ArrayList<>();
    private final List<JournalRecord> journal = new ArrayList<>();
    private final Reconcile reconcile = new Reconcile(store(), files, CommitSettings.DEFAULTS, CLOCK, wait -> {
        throw new AssertionError("nothing waits here");
    });

    private final TableFiles.Written v1;
    private final TableFiles.Written v2;
    private final TableFiles.Written v3;
    private final TableFiles.Written v4;
    private final TableFiles.Written v5;
    private Pointer pointer;
    private Optional<DrWatermark> watermark;
    private boolean frozen = true;

    ReconcileWatermarkTest() {
        String json = ((Outcome.NewMetadata) Metadata.createTable(Requests.createTable("orders", Map.of()),
                tableId.toString(), root)).json();
        v1 = files.write(root, 1, json);
        v2 = after(v1, "a");
        v3 = after(v2, "b");
        v4 = after(v3, "c");
        v5 = after(v4, "d");
        after(v5, "e");
        for (TableFiles.Written version : List.of(v1, v2, v3, v4)) {
            long n = Reconcile.version(version.location());
            journal.add(new JournalRecord(UUID.randomUUID(), table.mainKey(), CLOCK.instant().minusSeconds(600 - n),
                    new JournalEntry("writer-" + n, null, "spark", "[]", "[]"),
                    n == 1 ? Optional.empty() : Optional.of(journal.getLast().newLocation()), version.location(), n));
        }
        pointer = new Pointer(v4.location(), 4, v4.sha256(), new byte[0]);
        watermark = Optional.of(new DrWatermark(2, v2.location(), CLOCK.instant()));
    }

    /** A file built on {@code base}, as a commit on the primary wrote it before its swap. */
    private TableFiles.Written after(TableFiles.Written base, String value) {
        String json = ((Outcome.NewMetadata) Metadata.commitTable(update(assertUuid(tableId.toString()),
                setProperties(Map.of("written", value))), files.read(base.location(), "").json(), base.location())).json();
        return files.write(root, Reconcile.version(base.location()) + 1, json);
    }

    // ---- the walk does not stop at the watermark ----

    @Test
    @DisplayName("a table at its watermark walks forward through the proven files past it, each checked")
    void aTableAtItsWatermarkWalksForward() {
        pointer = new Pointer(v2.location(), 2, v2.sha256(), new byte[0]);

        Reconcile.Plan plan = reconcile.plan(table, Set.of());

        assertThat(plan.steps()).extracting(Reconcile.Step::location)
                .containsExactly(v3.location(), v4.location(), v5.location());
        assertThat(plan.problem()).isEmpty();
        Reconcile.Applied applied = reconcile.apply(plan, OPERATOR, events());
        assertThat(applied.stepsApplied()).isEqualTo(3);
        assertThat(pointer.metadataLocation()).isEqualTo(v5.location());
    }

    @Test
    @DisplayName("the walk is the same with a watermark below the pointer, at it, or none, and an accepted tip or an"
            + " archived swap is taken as without one")
    void theWatermarkDoesNotChangeTheWalk() {
        String tip = files.metadataFiles(root).stream().filter(location -> Reconcile.version(location) == 6)
                .findFirst().orElseThrow();
        for (Optional<DrWatermark> kept : List.of(Optional.of(new DrWatermark(2, v2.location(), CLOCK.instant())),
                Optional.of(new DrWatermark(4, v4.location(), CLOCK.instant())), Optional.<DrWatermark>empty())) {
            watermark = kept;

            Reconcile.Plan plan = reconcile.plan(table, Set.of());
            Reconcile.Plan accepted = reconcile.plan(table, Set.of(tip));
            Reconcile.Plan archived = reconcile.plan(table, Set.of(), Optional.of(List.of(
                    new Reconcile.ArchivedCommit(v5.location(), tip, 6, files.read(tip, "").sha256()))));

            assertThat(plan.steps()).as("watermark %s", kept).extracting(Reconcile.Step::location)
                    .containsExactly(v5.location());
            assertThat(plan.tips()).as("watermark %s", kept).containsExactly(tip);
            for (Reconcile.Plan taken : List.of(accepted, archived)) {
                assertThat(taken.steps()).as("watermark %s", kept).extracting(Reconcile.Step::location)
                        .containsExactly(v5.location(), tip);
                assertThat(taken.problem()).isEmpty();
            }
        }
    }

    // ---- the rewind ----

    @Test
    @DisplayName("the rewind lists the commits it gives up, then writes the watermark's metadata again as the next"
            + " version and swaps to it in one journaled, published commit")
    void theRewindMovesBackToTheWatermark() {
        Reconcile.Rewind plan = reconcile.planRewind(table);

        assertThat(plan.problem()).isEmpty();
        assertThat(plan.to()).isEqualTo(watermark);
        assertThat(plan.lost()).extracting(Reconcile.Lost::version).containsExactly(3L, 4L);
        assertThat(plan.lost()).extracting(Reconcile.Lost::metadataLocation)
                .containsExactly(v3.location(), v4.location());
        assertThat(plan.lost()).extracting(Reconcile.Lost::commitId)
                .containsExactly(journal.get(2).commitId(), journal.get(3).commitId());
        assertThat(plan.lost()).extracting(Reconcile.Lost::committedAt)
                .containsExactly(journal.get(2).committedAt(), journal.get(3).committedAt());
        assertThat(plan.lost()).extracting(Reconcile.Lost::principal).containsExactly("writer-3", "writer-4");

        Reconcile.Rewound rewound = reconcile.rewind(plan, OPERATOR, events());

        assertThat(rewound.problem()).isEmpty();
        assertThat(pointer.version()).isEqualTo(5);
        assertThat(rewound.to()).contains(pointer);
        assertThat(Reconcile.version(pointer.metadataLocation())).isEqualTo(5);
        assertThat(files.read(pointer.metadataLocation(), pointer.sha256()).intact()).isTrue();
        assertThat(files.read(pointer.metadataLocation(), "").json()).as("the watermark's metadata, again")
                .isEqualTo(files.read(v2.location(), "").json());
        assertThat(swapped).hasSize(1);
        assertThat(swapped.getFirst().expected()).contains(new Pointer(v4.location(), 4, v4.sha256(), new byte[0]));
        assertThat(swapped.getFirst().journal().updatesJson()).contains("\"dr-rewind\"")
                .contains("\"watermark-version\":2").contains(v2.location()).contains("\"versions-given-up\":2");
        assertThat(swapped.getFirst().journal().principal()).isEqualTo("operator");
        assertThat(published).hasSize(1);
        assertThat(published.getFirst().after()).isEqualTo(pointer);
        Reconcile.Rewind again = reconcile.planRewind(table);
        assertThat(again.problem()).as("until the verifier passes version 5").get().asString()
                .contains("already holds the DR watermark's metadata");
        assertThat(again.lost()).isEmpty();
    }

    @Test
    @DisplayName("only a table under a DR freeze is rewound, and nothing is written for one that is not")
    void onlyAFrozenTableIsRewound() {
        frozen = false;
        int written = files.writes.get();

        Reconcile.Rewound rewound = reconcile.rewind(reconcile.planRewind(table), OPERATOR, events());

        assertThat(rewound.problem()).get().asString().contains("not frozen for DR reconcile");
        assertThat(rewound.to()).isEmpty();
        assertThat(pointer.version()).isEqualTo(4);
        assertThat(swapped).isEmpty();
        assertThat(files.writes.get()).isEqualTo(written);
    }

    @Test
    @DisplayName("there is nothing to rewind without a watermark or at it, and no rewind when storage lacks the"
            + " watermark's file")
    void nothingToRewind() {
        watermark = Optional.empty();
        assertThat(reconcile.planRewind(table).problem()).get().asString().contains("has no DR watermark");

        watermark = Optional.of(new DrWatermark(4, v4.location(), CLOCK.instant()));
        assertThat(reconcile.planRewind(table).problem()).get().asString().contains("at the DR watermark, version 4");

        watermark = Optional.of(new DrWatermark(2, v2.location(), CLOCK.instant()));
        files.remove(v2.location());
        Reconcile.Rewind missing = reconcile.planRewind(table);
        assertThat(missing.problem()).get().asString().contains("does not hold the DR watermark's file");
        assertThat(missing.lost()).extracting(Reconcile.Lost::version).containsExactly(3L, 4L);

        for (Reconcile.Rewind plan : List.of(missing, reconcile.planRewind(table))) {
            assertThat(reconcile.rewind(plan, OPERATOR, events()).to()).isEmpty();
        }
        assertThat(pointer.version()).isEqualTo(4);
        assertThat(swapped).isEmpty();
    }

    @Test
    @DisplayName("a rewind planned before the pointer moved swaps nothing and says to plan again")
    void aMovedPointerStopsTheRewind() {
        Reconcile.Rewind plan = reconcile.planRewind(table);
        pointer = new Pointer(v5.location(), 5, v5.sha256(), new byte[0]);

        Reconcile.Rewound rewound = reconcile.rewind(plan, OPERATOR, events());

        assertThat(rewound.problem()).get().asString().contains("no longer at version 4; plan again");
        assertThat(pointer.metadataLocation()).isEqualTo(v5.location());
    }

    // ---- views ----

    @Test
    @DisplayName("a view above its watermark is rewound as a table is: its lost commits listed, and only under a DR"
            + " freeze its watermark's metadata written again as the next version in one journaled, published commit")
    void aFrozenViewIsRewoundToItsWatermark() {
        UUID viewId = UUID.randomUUID();
        String viewRoot = MemoryFiles.ROOT + "/" + viewId;
        CatalogObject view = new CatalogObject(CATALOG, viewId, NAMESPACE, new Name("recent", "Recent"),
                CatalogObject.Kind.VIEW, CatalogObject.State.ACTIVE, Optional.empty(), Optional.empty(), viewRoot,
                Optional.empty());
        List<TableFiles.Written> versions = new ArrayList<>();
        versions.add(files.write(viewRoot, 1, ((Outcome.NewMetadata) Metadata.createView(Requests.createView("recent"),
                viewId.toString(), viewRoot, CLOCK.millis())).json()));
        for (String value : List.of("a", "b", "c")) {
            String json = ((Outcome.NewMetadata) Metadata.commitView(update(assertViewUuid(viewId.toString()),
                    setProperties(Map.of("written", value))), files.read(versions.getLast().location(), "").json()))
                    .json();
            versions.add(files.write(viewRoot, versions.size() + 1, json));
        }
        journal.clear();
        for (TableFiles.Written version : versions) {
            long n = Reconcile.version(version.location());
            journal.add(new JournalRecord(UUID.randomUUID(), view.mainKey(), CLOCK.instant().minusSeconds(600 - n),
                    new JournalEntry("writer-" + n, null, "trino", "[]", "[]"),
                    n == 1 ? Optional.empty() : Optional.of(journal.getLast().newLocation()), version.location(), n));
        }
        TableFiles.Written w2 = versions.get(1);
        TableFiles.Written w4 = versions.get(3);
        pointer = new Pointer(w4.location(), 4, w4.sha256(), new byte[0]);

        watermark = Optional.empty();
        assertThat(reconcile.planRewind(view).problem()).get().asString().contains("the view has no DR watermark");

        watermark = Optional.of(new DrWatermark(2, w2.location(), CLOCK.instant()));
        Reconcile.Rewind plan = reconcile.planRewind(view);

        assertThat(plan.problem()).isEmpty();
        assertThat(plan.object()).isEqualTo(view);
        assertThat(plan.to()).isEqualTo(watermark);
        assertThat(plan.lost()).extracting(Reconcile.Lost::version).containsExactly(3L, 4L);
        assertThat(plan.lost()).extracting(Reconcile.Lost::metadataLocation)
                .containsExactly(versions.get(2).location(), w4.location());
        assertThat(plan.lost()).extracting(Reconcile.Lost::principal).containsExactly("writer-3", "writer-4");

        frozen = false;
        assertThat(reconcile.rewind(plan, OPERATOR, events()).problem()).get().asString()
                .contains("not frozen for DR reconcile");
        assertThat(swapped).isEmpty();

        frozen = true;
        Reconcile.Rewound rewound = reconcile.rewind(plan, OPERATOR, events());

        assertThat(rewound.problem()).isEmpty();
        assertThat(rewound.to()).contains(pointer);
        assertThat(pointer.version()).isEqualTo(5);
        assertThat(Reconcile.version(pointer.metadataLocation())).isEqualTo(5);
        assertThat(pointer.metadataLocation()).startsWith(viewRoot + "/metadata/");
        assertThat(files.read(pointer.metadataLocation(), "").json()).as("version 2's metadata, again")
                .isEqualTo(files.read(w2.location(), "").json());
        assertThat(new String(pointer.projection(), StandardCharsets.UTF_8)).contains("\"kind\":\"view\"")
                .contains("\"uuid\":\"" + viewId + "\"");
        assertThat(swapped).singleElement().satisfies(swap -> {
            assertThat(swap.key()).isEqualTo(view.mainKey());
            assertThat(swap.journal().updatesJson()).contains("\"dr-rewind\"").contains("\"watermark-version\":2")
                    .contains(w2.location()).contains("\"versions-given-up\":2");
        });
        assertThat(published).singleElement().satisfies(commit -> assertThat(commit.object()).isEqualTo(view));
    }

    // ---- the store double ----

    private CommitEvents events() {
        return commit -> {
            published.add(commit);
            return List.of(new OutboxEvent("test.commit", "{}"));
        };
    }

    /** The store calls a reconcile makes, over the one table; any other call fails the test. */
    private CatalogStore store() {
        return (CatalogStore) Proxy.newProxyInstance(CatalogStore.class.getClassLoader(),
                new Class<?>[] {CatalogStore.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "readPointer" -> Optional.of(pointer);
                    case "readDrWatermark" -> watermark;
                    case "readJournal" -> journal((PageRequest) args[3]);
                    case "readNamespace" -> Optional.empty();
                    case "attributesOf" -> frozen ? List.of(new GovernanceAttribute(
                            new Securable(Securable.Kind.NAMESPACE, NAMESPACE), GovernanceAttribute.Kind.DR_FREEZE,
                            "DR drill", "operator", "operator", CLOCK.instant())) : List.of();
                    case "commit" -> {
                        CommitBatch batch = (CommitBatch) args[0];
                        PointerSwap swap = batch.swaps().getFirst();
                        if (!swap.expected().equals(Optional.of(pointer))) {
                            yield new CommitResult.Conflict(swap.key(), Optional.of(pointer));
                        }
                        pointer = swap.next();
                        swapped.add(swap);
                        yield new CommitResult.Committed(batch.commitId());
                    }
                    default -> throw new UnsupportedOperationException("a reconcile does not call " + method.getName());
                });
    }

    /** One record a page, so the rewind reads the journal across pages. */
    private Page<JournalRecord> journal(PageRequest page) {
        long after = page.token().map(token -> Long.parseLong(PageToken.decode(token, 1).getFirst())).orElse(0L);
        List<JournalRecord> above = journal.stream().filter(record -> record.newVersion() > after).toList();
        return above.isEmpty() ? new Page<>(List.of(), Optional.empty()) : new Page<>(List.of(above.getFirst()),
                above.size() > 1 ? Optional.of(PageToken.encode(Long.toString(above.getFirst().newVersion())))
                        : Optional.empty());
    }
}
