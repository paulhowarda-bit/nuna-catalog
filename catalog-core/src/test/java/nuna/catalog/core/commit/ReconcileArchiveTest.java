package nuna.catalog.core.commit;

import static nuna.catalog.core.commit.Requests.assertUuid;
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
import nuna.catalog.store.GovernanceAttribute;
import nuna.catalog.store.Name;
import nuna.catalog.store.OutboxEvent;
import nuna.catalog.store.Pointer;
import nuna.catalog.store.PointerSwap;
import nuna.catalog.store.Securable;
import java.lang.reflect.Proxy;
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
 * The DR reconcile with the audit archive's proof (D37, backlog 10): a tip no later file proves is taken when the
 * archive records its swap from the file before it, as storage holds it. The database is behind storage: its pointer
 * names version 1, while storage holds version 2, version 3 built on it (the tip, committed), and a second version 3
 * built on 2 by an attempt that lost its swap. Against a store double holding one frozen table's pointer, so it needs
 * no database; {@code ReconcileTest} runs the walk itself on PostgreSQL.
 */
class ReconcileArchiveTest {

    private static final UUID CATALOG = UUID.randomUUID();
    private static final UUID NAMESPACE = UUID.randomUUID();
    private static final Caller OPERATOR = new Caller(new Principal("operator", Set.of("platform-operators")),
            "dr-runbook", false, Optional.empty());
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-20T02:00:00Z"), ZoneOffset.UTC);

    private final MemoryFiles files = new MemoryFiles();
    private final UUID tableId = UUID.randomUUID();
    private final String root = MemoryFiles.ROOT + "/" + tableId;
    private final CatalogObject table = new CatalogObject(CATALOG, tableId, NAMESPACE, new Name("orders", "Orders"),
            CatalogObject.Kind.TABLE, CatalogObject.State.ACTIVE, Optional.empty(), Optional.empty(), root,
            Optional.empty());
    private final List<PointerSwap> swapped = new ArrayList<>();
    private final Reconcile reconcile = new Reconcile(store(), files, CommitSettings.DEFAULTS, CLOCK, wait -> {
        throw new AssertionError("nothing waits here");
    });

    private Pointer pointer;
    private final TableFiles.Written v1;
    private final TableFiles.Written v2;
    private final TableFiles.Written v3;
    private final TableFiles.Written lost3;

    ReconcileArchiveTest() {
        String json = ((Outcome.NewMetadata) Metadata.createTable(Requests.createTable("orders", Map.of()),
                tableId.toString(), root)).json();
        v1 = files.write(root, 1, json);
        pointer = new Pointer(v1.location(), 1, v1.sha256(), new byte[0]);
        v2 = after(v1, "a");
        v3 = after(v2, "b");
        lost3 = after(v2, "lost");
    }

    /** A file built on {@code base}, as a commit on the primary wrote it before its swap. */
    private TableFiles.Written after(TableFiles.Written base, String value) {
        String json = ((Outcome.NewMetadata) Metadata.commitTable(update(assertUuid(tableId.toString()),
                setProperties(Map.of("written", value))), files.read(base.location(), "").json(), base.location())).json();
        return files.write(root, Reconcile.version(base.location()) + 1, json);
    }

    /** The archive's record of the swap from {@code before} to {@code after}, as the commit wrote it. */
    private static Reconcile.ArchivedCommit swap(TableFiles.Written before, TableFiles.Written after) {
        return new Reconcile.ArchivedCommit(before.location(), after.location(), Reconcile.version(after.location()),
                after.sha256());
    }

    /** The store calls a reconcile makes, over the one table under a DR freeze; any other call fails the test. */
    private CatalogStore store() {
        return (CatalogStore) Proxy.newProxyInstance(CatalogStore.class.getClassLoader(),
                new Class<?>[] {CatalogStore.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "readPointer" -> Optional.of(pointer);
                    case "readNamespace" -> Optional.empty();
                    case "attributesOf" -> List.of(new GovernanceAttribute(
                            new Securable(Securable.Kind.NAMESPACE, NAMESPACE), GovernanceAttribute.Kind.DR_FREEZE,
                            "DR drill", "operator", "operator", CLOCK.instant()));
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

    private Reconcile.Plan plan(Set<String> accept, Reconcile.ArchivedCommit... archived) {
        return reconcile.plan(table, accept, Optional.of(List.of(archived)));
    }

    private Reconcile.Applied apply(Reconcile.Plan plan) {
        return reconcile.apply(plan, OPERATOR, commit -> List.of(new OutboxEvent("test.commit", "{}")));
    }

    private static List<String> sorted(String... locations) {
        return java.util.Arrays.stream(locations).sorted().toList();
    }

    @Test
    @DisplayName("a tip whose swap the archive records is taken as archived, and the apply journals it so")
    void aTipTheArchiveRecordsIsTaken() {
        Reconcile.Plan plan = plan(Set.of(), swap(v1, v2), swap(v2, v3));

        assertThat(plan.steps()).extracting(Reconcile.Step::location).containsExactly(v2.location(), v3.location());
        assertThat(plan.steps()).extracting(Reconcile.Step::proof)
                .containsExactly(Reconcile.Proof.SUCCESSOR, Reconcile.Proof.ARCHIVED);
        assertThat(plan.tips()).isEmpty();
        assertThat(plan.problem()).isEmpty();
        assertThat(plan.contradicted()).isEmpty();

        Reconcile.Applied applied = apply(plan);

        assertThat(applied.stepsApplied()).isEqualTo(2);
        assertThat(applied.problem()).isEmpty();
        assertThat(pointer.metadataLocation()).isEqualTo(v3.location());
        assertThat(pointer.version()).isEqualTo(3);
        assertThat(swapped.getLast().journal().updatesJson()).contains("dr-fast-forward").contains("\"archived\"");
    }

    @Test
    @DisplayName("a tip the archive does not record is left to the operator, with the other tip, and nothing past"
            + " the proven files moves")
    void aTipTheArchiveDoesNotRecordIsLeft() {
        for (Reconcile.Plan plan : List.of(plan(Set.of(), swap(v1, v2)), plan(Set.of()))) {
            assertThat(plan.steps()).extracting(Reconcile.Step::location).containsExactly(v2.location());
            assertThat(plan.tips()).containsExactlyElementsOf(sorted(v3.location(), lost3.location()));
            assertThat(plan.problem()).isEmpty();
            assertThat(plan.contradicted()).isEmpty();
        }

        Reconcile.Applied applied = apply(plan(Set.of(), swap(v1, v2)));

        assertThat(applied.stepsApplied()).isEqualTo(1);
        assertThat(pointer.metadataLocation()).isEqualTo(v2.location());
    }

    @Test
    @DisplayName("an accept the archive contradicts stops the walk before that version, and is reported")
    void aContradictedAcceptStopsTheWalk() {
        Reconcile.Plan plan = plan(Set.of(lost3.location()), swap(v1, v2), swap(v2, v3));

        assertThat(plan.steps()).extracting(Reconcile.Step::location).containsExactly(v2.location());
        assertThat(plan.tips()).containsExactlyElementsOf(sorted(v3.location(), lost3.location()));
        assertThat(plan.contradicted()).containsExactly(lost3.location());
        assertThat(plan.problem()).get().asString().contains("accept names").contains(lost3.location())
                .contains("records the swap from " + v2.location() + " to " + v3.location());

        Reconcile.Applied applied = apply(plan);

        assertThat(applied.stepsApplied()).isEqualTo(1);
        assertThat(pointer.metadataLocation()).isEqualTo(v2.location());
    }

    @Test
    @DisplayName("an accept the archive confirms is taken as archived; one it says nothing about is still the"
            + " operator's, as without the archive")
    void acceptsTheArchiveConfirmsOrDoesNotRecord() {
        Reconcile.Plan confirmed = plan(Set.of(v3.location()), swap(v1, v2), swap(v2, v3));
        assertThat(confirmed.steps()).extracting(Reconcile.Step::proof)
                .containsExactly(Reconcile.Proof.SUCCESSOR, Reconcile.Proof.ARCHIVED);
        assertThat(confirmed.contradicted()).isEmpty();

        Reconcile.Plan silent = plan(Set.of(v3.location()), swap(v1, v2));
        assertThat(silent.steps()).extracting(Reconcile.Step::location).containsExactly(v2.location(), v3.location());
        assertThat(silent.steps()).extracting(Reconcile.Step::proof)
                .containsExactly(Reconcile.Proof.SUCCESSOR, Reconcile.Proof.ACCEPTED);
        assertThat(silent.problem()).isEmpty();
        assertThat(silent.contradicted()).isEmpty();
    }

    @Test
    @DisplayName("a record whose file storage holds with other content, or not at all, proves nothing: the walk stops"
            + " there and an accept of that file is contradicted")
    void aRecordOfOtherContentProvesNothing() {
        Reconcile.ArchivedCommit otherContent = new Reconcile.ArchivedCommit(v2.location(), v3.location(), 3,
                MemoryFiles.sha256("other bytes".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        Reconcile.Plan plan = plan(Set.of(v3.location()), swap(v1, v2), otherContent);

        assertThat(plan.steps()).extracting(Reconcile.Step::location).containsExactly(v2.location());
        assertThat(plan.tips()).containsExactlyElementsOf(sorted(v3.location(), lost3.location()));
        assertThat(plan.problem()).get().asString().contains("storage holds no such file");
        assertThat(plan.contradicted()).containsExactly(v3.location());

        Reconcile.ArchivedCommit otherVersion = new Reconcile.ArchivedCommit(v2.location(), v3.location(), 4,
                v3.sha256());
        assertThat(plan(Set.of(), swap(v1, v2), otherVersion).steps()).hasSize(1);

        String missing = root + "/metadata/00003-never-arrived.metadata.json";
        Reconcile.Plan behind = plan(Set.of(),
                swap(v1, v2), new Reconcile.ArchivedCommit(v2.location(), missing, 3, v3.sha256()));
        assertThat(behind.steps()).hasSize(1);
        assertThat(behind.problem()).get().asString().contains(missing).contains("storage holds no such file");
    }

    @Test
    @DisplayName("two swaps recorded from one file stop the walk there, whatever accept names")
    void twoRecordedSwapsFromOneFileStopTheWalk() {
        Reconcile.Plan plan = plan(Set.of(v3.location()), swap(v1, v2), swap(v2, v3), swap(v2, lost3));

        assertThat(plan.steps()).extracting(Reconcile.Step::location).containsExactly(v2.location());
        assertThat(plan.problem()).get().asString().contains("records 2 swaps from " + v2.location());
    }

    @Test
    @DisplayName("a record repeated, as a relay that sent a batch twice repeats it, is one swap")
    void aRepeatedRecordIsOneSwap() {
        Reconcile.Plan plan = plan(Set.of(), swap(v1, v2), swap(v2, v3), swap(v2, v3));

        assertThat(plan.steps()).extracting(Reconcile.Step::proof)
                .containsExactly(Reconcile.Proof.SUCCESSOR, Reconcile.Proof.ARCHIVED);
        assertThat(plan.problem()).isEmpty();
    }
}
