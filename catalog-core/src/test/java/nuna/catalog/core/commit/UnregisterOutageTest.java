package nuna.catalog.core.commit;

import static org.assertj.core.api.Assertions.assertThat;

import nuna.catalog.core.commit.CommitReply.How;
import nuna.catalog.core.commit.CommitReply.ServerError;
import nuna.catalog.core.commit.CommitReply.Success;
import nuna.catalog.store.CatalogObject;
import nuna.catalog.store.CatalogStore;
import nuna.catalog.store.Change;
import nuna.catalog.store.CommitOutcomeUnknownException;
import nuna.catalog.store.Name;
import nuna.catalog.store.Pointer;
import nuna.catalog.store.StoreException;
import nuna.catalog.store.StoreUnavailableException;
import nuna.catalog.store.WriteResult;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.IntFunction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * An unregister whose outcome was lost, decided by reading the table back while the store is down
 * (backlog 67), against a store double that holds one table in memory: it needs no database, so it runs in
 * the fast build. {@code CommitEngineTest$UnknownOutcomes} runs the same cases on PostgreSQL.
 */
class UnregisterOutageTest {

    private static final UUID CATALOG = UUID.randomUUID();
    private static final UUID NAMESPACE = UUID.randomUUID();
    private static final String ROOT = MemoryFiles.ROOT + "/orders";
    private static final String JSON = "{\"format-version\":2,\"table-uuid\":\"orders\"}";

    private final Harness.TestClock clock = new Harness.TestClock();
    private final List<Duration> waits = new ArrayList<>();
    private final Map<String, Integer> calls = new HashMap<>();
    private final MemoryFiles files = new MemoryFiles();
    private final CatalogObject active = new CatalogObject(CATALOG, UUID.randomUUID(), NAMESPACE,
            new Name("orders", "Orders"), CatalogObject.Kind.TABLE, CatalogObject.State.ACTIVE, Optional.empty(),
            Optional.empty(), ROOT, Optional.empty());
    private final Pointer pointer;
    private CommitSettings settings = CommitSettings.DEFAULTS;

    /** The table as the store holds it. */
    private CatalogObject stored = active;
    /** What each unregisterObject call does, in order; once they run out, the store applies it. */
    private final Deque<Function<Change, WriteResult>> unregisters = new ArrayDeque<>();
    /** The failure a read-back meets, by its 1-based number; null reads the table. */
    private IntFunction<RuntimeException> readBack = ask -> null;
    private final List<UUID> changeIds = new ArrayList<>();

    UnregisterOutageTest() {
        TableFiles.Written written = files.write(ROOT, 1, JSON);
        pointer = new Pointer(written.location(), 1, written.sha256(), new byte[0]);
    }

    // ---- the store double ----

    /** The store calls an unregister makes, over the one table; any other call fails the test. */
    private CatalogStore store() {
        return (CatalogStore) Proxy.newProxyInstance(CatalogStore.class.getClassLoader(),
                new Class<?>[] {CatalogStore.class}, (proxy, method, args) -> {
                    calls.merge(method.getName(), 1, Integer::sum);
                    return switch (method.getName()) {
                        case "findObject" -> stored.state() == CatalogObject.State.ACTIVE
                                ? Optional.of(stored) : Optional.empty();
                        case "readNamespace" -> Optional.empty();
                        case "readPointer" -> Optional.of(pointer);
                        case "unregisterObject" -> {
                            Change change = (Change) args[2];
                            changeIds.add(change.id());
                            Function<Change, WriteResult> next = unregisters.poll();
                            yield next == null ? land(change) : next.apply(change);
                        }
                        case "readObject" -> {
                            RuntimeException fault = readBack.apply(calls.get("readObject"));
                            if (fault != null) {
                                throw fault;
                            }
                            yield Optional.of(stored);
                        }
                        default -> throw new UnsupportedOperationException("an unregister does not call "
                                + method.getName());
                    };
                });
    }

    private WriteResult land(Change change) {
        stored = new CatalogObject(CATALOG, active.id(), NAMESPACE, active.name(), CatalogObject.Kind.TABLE,
                CatalogObject.State.UNREGISTERED, Optional.of(change.at()), Optional.empty(), ROOT, Optional.empty());
        return WriteResult.APPLIED;
    }

    /** The unregister lands, but its answer is lost. */
    private WriteResult landsUnanswered(Change change) {
        land(change);
        throw new CommitOutcomeUnknownException(change.id(), null);
    }

    /** The answer is lost, and nothing landed. */
    private static WriteResult lostUnlanded(Change change) {
        throw new CommitOutcomeUnknownException(change.id(), null);
    }

    private static StoreUnavailableException down() {
        return new StoreUnavailableException("primary lost, standby not yet promoted", null);
    }

    // ---- the engine ----

    private CommitReply unregister() {
        CommitEngine engine = new CommitEngine(store(), files, new MetadataCache(1 << 20), List.of(),
                commit -> List.of(), new Alerts() {
                    @Override
                    public void metadataIntegrity(UUID objectId, String location, String expected, String actual) {
                        throw new AssertionError("the metadata file is intact");
                    }

                    @Override
                    public void regulatedSchemaChange(UUID objectId, UUID commitId) {
                        throw new AssertionError("an unregister changes no schema");
                    }
                }, settings, clock, wait -> {
                    waits.add(wait);
                    clock.advance(wait);
                });
        return engine.unregisterTable(new ObjectName(CATALOG, NAMESPACE, active.name()), Optional.of(active.id()),
                Harness.ALICE, Optional.empty());
    }

    private Success unregistered() {
        return new Success(active.id(), pointer.metadataLocation(), pointer.sha256(), JSON, How.COMMITTED);
    }

    private ServerError stateUnknown() {
        return new ServerError("CommitStateUnknownException", "Unregister of Orders may or may not have been applied:"
                + " outcome of commit " + changeIds.getFirst() + " is unknown");
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
    @DisplayName("an unregister whose answer was lost and which landed is decided by one read when the store is up")
    void landedStoreUp() {
        unregisters.add(this::landsUnanswered);

        assertThat(unregister()).isEqualTo(unregistered());
        assertThat(calls("unregisterObject")).isEqualTo(1);
        assertThat(calls("readObject")).isEqualTo(1);
        assertThat(waits).isEmpty();
    }

    @Test
    @DisplayName("one that landed during a short outage is decided once the store answers, within the retry budget")
    void shortOutageIsWaitedOut() {
        unregisters.add(this::landsUnanswered);
        readBack = ask -> ask <= 2 ? down() : null;

        assertThat(unregister()).isEqualTo(unregistered());
        assertThat(calls("unregisterObject")).isEqualTo(1);
        assertThat(calls("readObject")).isEqualTo(3);
        assertBackoffs(100, 200);
        assertThat(stored.state()).isEqualTo(CatalogObject.State.UNREGISTERED);
    }

    @Test
    @DisplayName("one lost at the start of a 600 ms outage, which did not land, is decided when the store is back"
            + " and sent again, landing once")
    void timedOutageThenSentAgain() {
        Instant back = clock.instant().plusMillis(600);
        unregisters.add(UnregisterOutageTest::lostUnlanded);
        readBack = ask -> clock.instant().isBefore(back) ? down() : null;

        assertThat(unregister()).isEqualTo(unregistered());
        // Read back at 0, ~100 and ~300 ms while the store was away, and at ~700 ms once it was back.
        assertThat(calls("readObject")).isEqualTo(4);
        assertThat(calls("unregisterObject")).isEqualTo(2);
        // Three waits for the store, then the unregister loop's own wait before its second attempt.
        assertBackoffs(100, 200, 400, 100);
        assertThat(waits.stream().reduce(Duration.ZERO, Duration::plus)).isLessThanOrEqualTo(settings.retryBudget());
        assertThat(stored.state()).isEqualTo(CatalogObject.State.UNREGISTERED);
    }

    @Test
    @DisplayName("an outage that outlasts the retry budget answers as before: 500 CommitStateUnknown, after 4 reads")
    void outageLongerThanTheBudget() {
        unregisters.add(UnregisterOutageTest::lostUnlanded);
        readBack = ask -> down();

        assertThat(unregister()).isEqualTo(stateUnknown());
        assertThat(calls("unregisterObject")).isEqualTo(1);
        assertThat(calls("readObject")).isEqualTo(CommitSettings.DEFAULTS.maxAttempts());
        assertBackoffs(100, 200, 400);
        assertThat(stored.state()).isEqualTo(CatalogObject.State.ACTIVE);
    }

    @Test
    @DisplayName("however many reads are allowed, none starts after the retry budget")
    void readsStopAtTheTimeBudget() {
        settings = new CommitSettings(10, Duration.ofSeconds(2), Duration.ofMillis(100), Duration.ofHours(24),
                Duration.ofSeconds(30));
        unregisters.add(UnregisterOutageTest::lostUnlanded);
        readBack = ask -> down();

        assertThat(unregister()).isEqualTo(stateUnknown());
        // Reads at 0, ~100, ~300, ~700 and ~1500 ms; the next wait, 1.6 s, would end past 2 s.
        assertThat(calls("readObject")).isEqualTo(5);
        assertBackoffs(100, 200, 400, 800);
    }

    @Test
    @DisplayName("the budget is the request's: an unregister that hung for most of it leaves no time to read again")
    void budgetCountsFromTheRequestsStart() {
        unregisters.add(change -> {
            clock.advance(Duration.ofMillis(1950));
            return lostUnlanded(change);
        });
        readBack = ask -> ask == 1 ? down() : null;

        assertThat(unregister()).isEqualTo(stateUnknown());
        assertThat(calls("readObject")).isEqualTo(1);
        assertThat(waits).isEmpty();
    }

    @Test
    @DisplayName("a store that answers but cannot say is read once: 500 CommitStateUnknown at once")
    void undecidable() {
        unregisters.add(this::landsUnanswered);
        readBack = ask -> new StoreException("standby unreachable", null);

        assertThat(unregister()).isEqualTo(stateUnknown());
        assertThat(calls("readObject")).isEqualTo(1);
        assertThat(waits).isEmpty();
    }
}
