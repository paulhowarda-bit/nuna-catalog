package nuna.catalog.core.commit;

import static nuna.catalog.core.commit.Requests.assertUuid;
import static nuna.catalog.core.commit.Requests.setProperties;
import static nuna.catalog.core.commit.Requests.update;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import nuna.catalog.core.principal.Caller;
import nuna.catalog.core.principal.Principal;
import nuna.catalog.iceberg.Metadata;
import nuna.catalog.iceberg.Outcome;
import nuna.catalog.store.CatalogObject;
import nuna.catalog.store.CatalogStore;
import nuna.catalog.store.CommitBatch;
import nuna.catalog.store.CommitLookup;
import nuna.catalog.store.CommitOutcomeUnknownException;
import nuna.catalog.store.CommitResult;
import nuna.catalog.store.GovernanceAttribute;
import nuna.catalog.store.Name;
import nuna.catalog.store.OutboxEvent;
import nuna.catalog.store.Pointer;
import nuna.catalog.store.PointerSwap;
import nuna.catalog.store.Securable;
import nuna.catalog.store.StoreException;
import nuna.catalog.store.StoreUnavailableException;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.IntFunction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A DR reconcile step whose outcome was lost, decided while the store is down (backlog 69): the apply asks
 * {@code findCommit} within the commit engine's retry bounds (P10), counted from the step's start, as the engine
 * decides a commit. Against a store double that holds one frozen table's pointer in memory, with a clock only the
 * waits move: it needs no database, so it runs in the fast build. {@code ReconcileTest} runs the walk itself on
 * PostgreSQL.
 */
class ReconcileOutageTest {

    private static final UUID CATALOG = UUID.randomUUID();
    private static final UUID NAMESPACE = UUID.randomUUID();
    private static final Caller OPERATOR = new Caller(new Principal("operator", Set.of("platform-operators")),
            "dr-runbook", false, Optional.empty());

    private final Harness.TestClock clock = new Harness.TestClock();
    private final List<Duration> waits = new ArrayList<>();
    private final Map<String, Integer> calls = new HashMap<>();
    private final MemoryFiles files = new MemoryFiles();
    private final UUID tableId = UUID.randomUUID();
    private final String root = MemoryFiles.ROOT + "/" + tableId;
    private final CatalogObject table = new CatalogObject(CATALOG, tableId, NAMESPACE, new Name("orders", "Orders"),
            CatalogObject.Kind.TABLE, CatalogObject.State.ACTIVE, Optional.empty(), Optional.empty(), root,
            Optional.empty());
    private final List<String> chain = new ArrayList<>();
    private CommitSettings settings = CommitSettings.DEFAULTS;

    /** The table's pointer as the store holds it. */
    private Pointer pointer;
    /** What each commit call does, in order; once they run out, the store swaps as asked. */
    private final Deque<Function<CommitBatch, CommitResult>> commits = new ArrayDeque<>();
    /** The failure a findCommit meets, by its 1-based number; null answers from what landed. */
    private IntFunction<RuntimeException> lookups = ask -> null;
    private final Set<UUID> landed = new HashSet<>();
    private final List<UUID> commitIds = new ArrayList<>();

    /** Version 1, which the pointer names, then versions 2 to 4, each built on the one before. */
    ReconcileOutageTest() {
        String json = ((Outcome.NewMetadata) Metadata.createTable(Requests.createTable("orders", Map.of()),
                tableId.toString(), root)).json();
        TableFiles.Written first = files.write(root, 1, json);
        pointer = new Pointer(first.location(), 1, first.sha256(), new byte[0]);
        chain.add(first.location());
        for (int version = 2; version <= 4; version++) {
            json = ((Outcome.NewMetadata) Metadata.commitTable(update(assertUuid(tableId.toString()),
                    setProperties(Map.of("written", String.valueOf(version)))), json, chain.getLast())).json();
            chain.add(files.write(root, version, json).location());
        }
    }

    // ---- the store double ----

    /** The store calls a reconcile makes, over the one table under a DR freeze; any other call fails the test. */
    private CatalogStore store() {
        return (CatalogStore) Proxy.newProxyInstance(CatalogStore.class.getClassLoader(),
                new Class<?>[] {CatalogStore.class}, (proxy, method, args) -> {
                    calls.merge(method.getName(), 1, Integer::sum);
                    return switch (method.getName()) {
                        case "readPointer" -> Optional.of(pointer);
                        case "readNamespace" -> Optional.empty();
                        case "attributesOf" -> List.of(new GovernanceAttribute(
                                new Securable(Securable.Kind.NAMESPACE, NAMESPACE), GovernanceAttribute.Kind.DR_FREEZE,
                                "DR drill", "operator", "operator", clock.instant()));
                        case "commit" -> {
                            CommitBatch batch = (CommitBatch) args[0];
                            commitIds.add(batch.commitId());
                            Function<CommitBatch, CommitResult> next = commits.poll();
                            yield next == null ? swap(batch) : next.apply(batch);
                        }
                        case "findCommit" -> {
                            RuntimeException fault = lookups.apply(calls.get("findCommit"));
                            if (fault != null) {
                                throw fault;
                            }
                            yield landed.contains((UUID) args[0]) ? CommitLookup.COMMITTED : CommitLookup.NOT_COMMITTED;
                        }
                        default -> throw new UnsupportedOperationException("a reconcile does not call "
                                + method.getName());
                    };
                });
    }

    /** Swaps the pointer if it is where the batch expects it. */
    private CommitResult swap(CommitBatch batch) {
        PointerSwap swap = batch.swaps().getFirst();
        if (!swap.expected().equals(Optional.of(pointer))) {
            return new CommitResult.Conflict(swap.key(), Optional.of(pointer));
        }
        pointer = swap.next();
        landed.add(batch.commitId());
        return new CommitResult.Committed(batch.commitId());
    }

    /** The swap lands, but its answer is lost. */
    private CommitResult landsUnanswered(CommitBatch batch) {
        swap(batch);
        throw new CommitOutcomeUnknownException(batch.commitId(), null);
    }

    /** The answer is lost, and nothing landed. */
    private static CommitResult lostUnlanded(CommitBatch batch) {
        throw new CommitOutcomeUnknownException(batch.commitId(), null);
    }

    private static StoreUnavailableException down() {
        return new StoreUnavailableException("primary lost, standby not yet promoted", null);
    }

    // ---- the reconcile ----

    private Reconcile reconcile() {
        return new Reconcile(store(), files, settings, clock, wait -> {
            waits.add(wait);
            clock.advance(wait);
        });
    }

    /** Plans with the newest file accepted, so the plan has three steps, versions 2 to 4, then applies it. */
    private Reconcile.Applied apply() {
        Reconcile reconcile = reconcile();
        Reconcile.Plan plan = reconcile.plan(table, Set.of(chain.getLast()));
        assertThat(plan.steps()).extracting(Reconcile.Step::location).containsExactlyElementsOf(chain.subList(1, 4));
        return reconcile.apply(plan, OPERATOR, commit -> List.of(new OutboxEvent("test.commit", "{}")));
    }

    private int calls(String method) {
        return calls.getOrDefault(method, 0);
    }

    private void assertBackoffs(long... bases) {
        assertThat(waits).hasSize(bases.length);
        for (int i = 0; i < bases.length; i++) {
            assertThat(waits.get(i)).isBetween(Duration.ofMillis(bases[i]), Duration.ofMillis(bases[i] + bases[i] / 10));
        }
    }

    // ---- the cases ----

    @Test
    @DisplayName("with no answer lost, every step swaps and nothing is looked up")
    void allAnswered() {
        Reconcile.Applied applied = apply();

        assertThat(applied.stepsApplied()).isEqualTo(3);
        assertThat(applied.problem()).isEmpty();
        assertThat(pointer.metadataLocation()).isEqualTo(chain.getLast());
        assertThat(calls("findCommit")).isZero();
        assertThat(waits).isEmpty();
    }

    @Test
    @DisplayName("a step whose answer was lost and which landed is decided by one lookup when the store is up")
    void landedStoreUp() {
        commits.add(this::landsUnanswered);

        Reconcile.Applied applied = apply();

        assertThat(applied.stepsApplied()).isEqualTo(3);
        assertThat(applied.problem()).isEmpty();
        assertThat(calls("findCommit")).isEqualTo(1);
        assertThat(waits).isEmpty();
    }

    @Test
    @DisplayName("one that landed during a short outage is decided once the store answers, and the walk goes on")
    void shortOutageIsWaitedOut() {
        commits.add(this::swap);
        commits.add(this::landsUnanswered);
        lookups = ask -> ask <= 2 ? down() : null;

        Reconcile.Applied applied = apply();

        assertThat(applied.stepsApplied()).isEqualTo(3);
        assertThat(applied.problem()).isEmpty();
        assertThat(pointer.metadataLocation()).isEqualTo(chain.getLast());
        assertThat(calls("commit")).isEqualTo(3);
        assertThat(calls("findCommit")).isEqualTo(3);
        assertBackoffs(100, 200);
    }

    @Test
    @DisplayName("one lost at the start of a 600 ms outage, which did not land, is decided when the store is back:"
            + " the apply stops there and counts the steps before it")
    void timedOutageNotLanded() {
        Instant back = clock.instant().plusMillis(600);
        commits.add(this::swap);
        commits.add(ReconcileOutageTest::lostUnlanded);
        lookups = ask -> clock.instant().isBefore(back) ? down() : null;

        Reconcile.Applied applied = apply();

        assertThat(applied.stepsApplied()).isEqualTo(1);
        assertThat(applied.problem()).get().asString().contains("plan again");
        assertThat(pointer.metadataLocation()).isEqualTo(chain.get(1));
        // Asked at 0, ~100 and ~300 ms while the store was away, and at ~700 ms once it was back.
        assertThat(calls("findCommit")).isEqualTo(4);
        assertBackoffs(100, 200, 400);
        assertThat(waits.stream().reduce(Duration.ZERO, Duration::plus)).isLessThanOrEqualTo(settings.retryBudget());
    }

    @Test
    @DisplayName("an outage that outlasts the retry budget answers as before: the store's failure, after 4 lookups")
    void outageLongerThanTheBudget() {
        commits.add(this::swap);
        commits.add(ReconcileOutageTest::lostUnlanded);
        lookups = ask -> down();

        assertThatThrownBy(this::apply).isInstanceOf(StoreUnavailableException.class);
        assertThat(calls("commit")).isEqualTo(2);
        assertThat(calls("findCommit")).isEqualTo(CommitSettings.DEFAULTS.maxAttempts());
        assertBackoffs(100, 200, 400);
        assertThat(pointer.metadataLocation()).isEqualTo(chain.get(1));
    }

    @Test
    @DisplayName("however many lookups are allowed, none starts after the retry budget")
    void lookupsStopAtTheTimeBudget() {
        settings = new CommitSettings(10, Duration.ofSeconds(2), Duration.ofMillis(100), Duration.ofHours(24),
                Duration.ofSeconds(30));
        commits.add(ReconcileOutageTest::lostUnlanded);
        lookups = ask -> down();

        assertThatThrownBy(this::apply).isInstanceOf(StoreUnavailableException.class);
        // Lookups at 0, ~100, ~300, ~700 and ~1500 ms; the next wait, 1.6 s, would end past 2 s.
        assertThat(calls("findCommit")).isEqualTo(5);
        assertBackoffs(100, 200, 400, 800);
    }

    @Test
    @DisplayName("the budget is the step's: a swap that hung for most of it leaves no time to look again")
    void budgetCountsFromTheStepsStart() {
        commits.add(batch -> {
            clock.advance(Duration.ofMillis(1950));
            return lostUnlanded(batch);
        });
        lookups = ask -> ask == 1 ? down() : null;

        assertThatThrownBy(this::apply).isInstanceOf(StoreUnavailableException.class);
        assertThat(calls("findCommit")).isEqualTo(1);
        assertThat(waits).isEmpty();
    }

    @Test
    @DisplayName("each step has a budget of its own: earlier slow steps do not spend a later step's")
    void eachStepHasItsOwnBudget() {
        commits.add(batch -> {
            clock.advance(Duration.ofMillis(1950));
            return swap(batch);
        });
        commits.add(batch -> {
            clock.advance(Duration.ofMillis(1950));
            return swap(batch);
        });
        commits.add(this::landsUnanswered);
        lookups = ask -> ask <= 2 ? down() : null;

        Reconcile.Applied applied = apply();

        assertThat(applied.stepsApplied()).isEqualTo(3);
        assertThat(applied.problem()).isEmpty();
        assertThat(calls("findCommit")).isEqualTo(3);
        assertBackoffs(100, 200);
    }

    @Test
    @DisplayName("a store that answers but cannot say is asked once: its failure is thrown at once")
    void undecidable() {
        commits.add(this::landsUnanswered);
        lookups = ask -> new StoreException("standby unreachable", null);

        assertThatThrownBy(this::apply).isInstanceOf(StoreException.class)
                .isNotInstanceOf(StoreUnavailableException.class);
        assertThat(calls("findCommit")).isEqualTo(1);
        assertThat(waits).isEmpty();
    }
}
