package nuna.catalog.core.commit;

import static nuna.catalog.core.commit.Requests.assertUuid;
import static nuna.catalog.core.commit.Requests.setProperties;
import static nuna.catalog.core.commit.Requests.update;
import static org.assertj.core.api.Assertions.assertThat;

import nuna.catalog.core.commit.CommitReply.Success;
import nuna.catalog.core.principal.Caller;
import nuna.catalog.core.principal.Principal;
import nuna.catalog.iceberg.Metadata;
import nuna.catalog.iceberg.Outcome;
import nuna.catalog.store.GovernanceAttribute;
import nuna.catalog.store.JournalRecord;
import nuna.catalog.store.OutboxEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The DR reconcile (D37): the database is behind storage, so files past the pointer were written by commits it
 * never received. Each test writes such files itself, as a primary-region server would have, and lost-swap
 * attempts as files at a version another file also holds.
 */
@Tag("heavy")
class ReconcileTest {

    private static final Caller OPERATOR = new Caller(new Principal("operator", Set.of("platform-operators")),
            "dr-runbook", false, Optional.empty());

    private final Harness h = new Harness();
    private final Reconcile reconcile = new Reconcile(h.store, h.files, h.clock);
    private final List<CommitEvents.Commit> published = Collections.synchronizedList(new ArrayList<>());
    private final CommitEvents events = commit -> {
        published.add(commit);
        return List.of(new OutboxEvent("test.commit", "{}"));
    };
    private String v1;
    private int written;

    @BeforeEach
    void table() {
        Success created = h.createTable("Orders");
        v1 = created.metadataLocation();
    }

    /** A file built on {@code base}, as a commit on the primary wrote it before its swap. */
    private String after(String base) {
        String json = h.files.read(base, "").json();
        Outcome outcome = Metadata.commitTable(update(assertUuid(h.object("Orders").id().toString()),
                setProperties(Map.of("written", String.valueOf(++written)))), json, base);
        return h.files.write(h.object("Orders").locationRoot(), Reconcile.version(base) + 1,
                ((Outcome.NewMetadata) outcome).json()).location();
    }

    private void freeze() {
        h.attribute(h.namespace(), GovernanceAttribute.Kind.DR_FREEZE, "DR drill");
    }

    @Test
    void movesThroughProvenFilesAndLeavesTheTipToTheOperator() {
        String a2 = after(v1);
        String lost2 = after(v1);
        String a3 = after(a2);
        String a4 = after(a3);
        freeze();

        Reconcile.Plan plan = reconcile.plan(h.object("Orders"), Set.of());

        assertThat(plan.steps()).extracting(Reconcile.Step::location).containsExactly(a2, a3);
        assertThat(plan.steps()).extracting(Reconcile.Step::proof).containsOnly(Reconcile.Proof.SUCCESSOR);
        assertThat(plan.tips()).containsExactly(a4);
        assertThat(plan.problem()).isEmpty();
        assertThat(plan.steps()).extracting(Reconcile.Step::location).doesNotContain(lost2);

        Reconcile.Applied applied = reconcile.apply(plan, OPERATOR, events);

        assertThat(applied.stepsApplied()).isEqualTo(2);
        assertThat(applied.problem()).isEmpty();
        assertThat(h.pointer("Orders").metadataLocation()).isEqualTo(a3);
        assertThat(h.pointer("Orders").version()).isEqualTo(3);
        List<JournalRecord> journal = h.journal("Orders");
        assertThat(journal).hasSize(3);
        assertThat(journal.subList(1, 3)).extracting(JournalRecord::newLocation).containsExactly(a2, a3);
        assertThat(journal.get(2).entry().principal()).isEqualTo("operator");
        assertThat(journal.get(2).entry().updatesJson()).contains("dr-fast-forward").contains("successor");
        assertThat(published).extracting(c -> c.after().metadataLocation()).containsExactly(a2, a3);

        Reconcile.Plan accepted = reconcile.plan(h.object("Orders"), Set.of(a4));
        assertThat(accepted.steps()).extracting(Reconcile.Step::proof).containsExactly(Reconcile.Proof.ACCEPTED);
        assertThat(reconcile.apply(accepted, OPERATOR, events).stepsApplied()).isEqualTo(1);
        assertThat(h.pointer("Orders").metadataLocation()).isEqualTo(a4);
        assertThat(h.journal("Orders").getLast().entry().updatesJson()).contains("accepted");
    }

    @Test
    void twoTipsAtOneVersionAreNotGuessedBetween() {
        String c2 = after(v1);
        String d2 = after(v1);
        freeze();

        Reconcile.Plan plan = reconcile.plan(h.object("Orders"), Set.of());

        assertThat(plan.steps()).isEmpty();
        assertThat(plan.tips()).containsExactlyInAnyOrder(c2, d2);
        assertThat(reconcile.plan(h.object("Orders"), Set.of(c2, d2)).problem()).get().asString()
                .contains("more than one");
    }

    @Test
    void twoProvenFilesAtOneVersionStopTheWalk() {
        String e2 = after(v1);
        String f2 = after(v1);
        after(e2);
        after(f2);
        freeze();

        Reconcile.Plan plan = reconcile.plan(h.object("Orders"), Set.of());

        assertThat(plan.steps()).isEmpty();
        assertThat(plan.problem()).get().asString().contains(e2).contains(f2);
    }

    @Test
    void anUnfrozenTableDoesNotMove() {
        String a2 = after(v1);
        after(a2);

        Reconcile.Applied applied = reconcile.apply(reconcile.plan(h.object("Orders"), Set.of()), OPERATOR, events);

        assertThat(applied.stepsApplied()).isZero();
        assertThat(applied.problem()).get().asString().contains("not frozen");
        assertThat(h.pointer("Orders").metadataLocation()).isEqualTo(v1);
    }

    @Test
    void storageBehindTheDatabaseIsReported() {
        h.files.remove(v1);
        freeze();

        Reconcile.Plan plan = reconcile.plan(h.object("Orders"), Set.of());

        assertThat(plan.steps()).isEmpty();
        assertThat(plan.problem()).get().asString().contains("storage is behind the database");
    }

    @Test
    void aPointerThatMovedSincePlanningStopsTheApply() {
        String a2 = after(v1);
        after(a2);
        Reconcile.Plan plan = reconcile.plan(h.object("Orders"), Set.of());
        assertThat(h.engine().commitTable(h.name("Orders"), update(assertUuid(h.object("Orders").id().toString()),
                setProperties(Map.of("moved", "yes"))), Harness.ALICE, Optional.empty())).isInstanceOf(Success.class);
        freeze();

        Reconcile.Applied applied = reconcile.apply(plan, OPERATOR, events);

        assertThat(applied.stepsApplied()).isZero();
        assertThat(applied.problem()).get().asString().contains("plan again");
        assertThat(h.pointer("Orders").metadataLocation()).isNotEqualTo(a2);
    }

    @Test
    void aTableStorageHasNothingNewerForIsUpToDate() {
        freeze();

        Reconcile.Plan plan = reconcile.plan(h.object("Orders"), Set.of());

        assertThat(plan.steps()).isEmpty();
        assertThat(plan.tips()).isEmpty();
        assertThat(plan.problem()).isEmpty();
    }
}
