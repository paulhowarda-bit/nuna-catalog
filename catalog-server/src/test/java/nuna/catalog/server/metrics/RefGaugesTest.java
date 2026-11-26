package nuna.catalog.server.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import nuna.catalog.server.governance.RefSettings;
import nuna.catalog.store.CatalogRecord;
import nuna.catalog.store.CatalogStore;
import nuna.catalog.store.Name;
import nuna.catalog.store.Page;
import nuna.catalog.store.PageRequest;
import nuna.catalog.store.PageToken;
import nuna.catalog.store.Ref;
import nuna.catalog.store.StoreUnavailableException;
import org.junit.jupiter.api.Test;

/**
 * Each declared catalog's ref gauges (D33, choice 13): open branches and tags by {@code ref.kind}, branches within the
 * warning of their expiry, read from the store at most once every 30 seconds, NaN while it cannot be read.
 */
class RefGaugesTest {

    private static final Instant NOW = Instant.parse("2026-11-20T12:00:00Z");
    private static final Duration DAY = Duration.ofDays(1);
    private static final RefSettings SETTINGS = new RefSettings(50, 1000, Duration.ofDays(30), Duration.ofDays(180),
            Duration.ofDays(7));

    private final CatalogRecord gate = new CatalogRecord(UUID.randomUUID(), "gate", "gate");
    private final List<Ref> refs = new ArrayList<>();
    private final MutableClock clock = new MutableClock(NOW);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private int reads;
    private boolean down;

    RefGaugesTest() {
        branch("expired", NOW.minus(DAY));
        branch("open", NOW.plus(DAY.multipliedBy(20)));
        branch("expiring", NOW.plus(DAY.multipliedBy(3)));
        for (int i = 0; i < 3; i++) {
            refs.add(new Ref(gate.id(), UUID.randomUUID(), new Name("v" + i, "v" + i), Ref.Kind.TAG, NOW, "bea",
                    Optional.empty(), Ref.MAIN, Optional.empty(), Optional.empty()));
        }
        new RefGauges(store(), List.of("gate", "Undeclared-yet"), SETTINGS, clock).bindTo(registry);
    }

    @Test
    void eachCatalogShowsItsOpenBranchesTagsAndThoseExpiring() {
        assertThat(gauge(RefGauges.REFS, "gate", "branch")).isEqualTo(2);
        assertThat(gauge(RefGauges.REFS, "gate", "tag")).isEqualTo(3);
        assertThat(registry.get(RefGauges.EXPIRING).tag(CatalogMetrics.CATALOG, "gate").gauge().value()).isEqualTo(1);
        assertThat(gauge(RefGauges.REFS, "Undeclared-yet", "branch")).as("a catalog not made yet holds none")
                .isZero();
        assertThat(registry.getMeters()).flatExtracting(meter -> meter.getId().getTags())
                .extracting(tag -> tag.getKey()).allMatch(CatalogMetrics.LABELS::contains);
    }

    @Test
    void theStoreIsReadAtMostOnceEveryThirtySeconds() {
        gauge(RefGauges.REFS, "gate", "branch");
        int first = reads;
        gauge(RefGauges.REFS, "gate", "tag");
        clock.advance(Duration.ofSeconds(29));
        gauge(RefGauges.REFS, "gate", "branch");
        assertThat(reads).isEqualTo(first);

        clock.advance(Duration.ofSeconds(1));
        gauge(RefGauges.REFS, "gate", "branch");
        assertThat(reads).isGreaterThan(first);
    }

    @Test
    void aCatalogWhoseRefsCannotBeReadShowsNaN() {
        down = true;

        assertThat(gauge(RefGauges.REFS, "gate", "branch")).isNaN();

        down = false;
        clock.advance(RefGauges.FRESH);
        assertThat(gauge(RefGauges.REFS, "gate", "branch")).isEqualTo(2);
    }

    private double gauge(String name, String catalog, String kind) {
        return registry.get(name).tag(CatalogMetrics.CATALOG, catalog).tag(CatalogMetrics.REF_KIND, kind).gauge()
                .value();
    }

    private void branch(String name, Instant expiresAt) {
        refs.add(new Ref(gate.id(), UUID.randomUUID(), new Name(name, name), Ref.Kind.BRANCH,
                NOW.minus(DAY.multipliedBy(30)), "bea", Optional.empty(), Ref.MAIN, Optional.of(expiresAt),
                Optional.empty()));
    }

    /** The refs, listed two to a page so a count walks the pages. */
    private CatalogStore store() {
        return (CatalogStore) Proxy.newProxyInstance(CatalogStore.class.getClassLoader(),
                new Class<?>[] {CatalogStore.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "findCatalog" -> Optional.of(gate).filter(c -> c.normalizedName().equals(args[0]));
                    case "listRefs" -> {
                        reads++;
                        if (down) {
                            throw new StoreUnavailableException("the store is down", null);
                        }
                        int from = ((PageRequest) args[1]).token().map(t -> Integer.parseInt(PageToken.decode(t, 1)
                                .getFirst())).orElse(0);
                        int to = Math.min(refs.size(), from + 2);
                        yield new Page<>(refs.subList(from, to), to < refs.size()
                                ? Optional.of(PageToken.encode(Integer.toString(to))) : Optional.empty());
                    }
                    case "toString" -> "the test's store";
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    /** A clock the test moves. */
    private static final class MutableClock extends Clock {

        private volatile Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}
