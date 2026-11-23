package nuna.catalog.server.governance;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import nuna.catalog.store.CatalogRecord;
import nuna.catalog.store.CatalogStore;
import nuna.catalog.store.GovernanceLift;
import nuna.catalog.store.Securable;
import org.junit.jupiter.api.Test;

/**
 * The holds released and freezes lifted in a catalog (items 128 and 133): read from the store, which keeps every
 * release and lift, over the last 30 days to the millisecond, for that catalog only. A store double answers, so this
 * needs only Java.
 */
class HoldHistoryTest {

    private static final Instant NOW = Instant.parse("2026-11-22T12:00:00.123456789Z");

    private final CatalogRecord catalog = new CatalogRecord(UUID.randomUUID(), "gate", "gate");
    /** What the history asked the store: the catalog and the window's start, per read. */
    private final List<Object[]> asked = new ArrayList<>();

    @Test
    void theWindowIsTheLastThirtyDaysAndEveryLiftTheStoreKeepsInItIsListedAsKept() {
        Securable table = new Securable(Securable.Kind.TABLE, UUID.randomUUID());
        Securable namespace = new Securable(Securable.Kind.NAMESPACE, UUID.randomUUID());
        List<GovernanceLift> kept = List.of(
                new GovernanceLift(GovernanceLift.Kind.HOLD, Optional.of(UUID.randomUUID()), table, "matter 9",
                        "stella", NOW.minusSeconds(86_400 * 40), "sam", NOW.minusSeconds(60)),
                new GovernanceLift(GovernanceLift.Kind.DR_FREEZE, Optional.empty(), namespace, "drill 1", "olga",
                        NOW.minusSeconds(86_400 * 2), "oscar", NOW.minusSeconds(86_400)));

        HoldHistory.Result result = new HoldHistory(store(kept), Clock.fixed(NOW, ZoneOffset.UTC)).lifted(catalog);

        Instant since = Instant.parse("2026-10-23T12:00:00.123Z");
        assertThat(result.since()).as("30 days back, to the millisecond the store keeps").isEqualTo(since);
        assertThat(result.lifted()).isEqualTo(kept);
        assertThat(asked).hasSize(1);
        assertThat(asked.getFirst()).containsExactly(catalog.id(), since);
    }

    /** Answers {@code liftedIn} with {@code kept}, noting what was asked; any other call fails the test. */
    private CatalogStore store(List<GovernanceLift> kept) {
        return (CatalogStore) Proxy.newProxyInstance(CatalogStore.class.getClassLoader(),
                new Class<?>[] {CatalogStore.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "liftedIn" -> {
                        asked.add(args);
                        yield kept;
                    }
                    case "toString" -> "the test's store";
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }
}
