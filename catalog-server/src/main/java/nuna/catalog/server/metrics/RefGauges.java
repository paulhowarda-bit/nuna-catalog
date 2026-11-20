package nuna.catalog.server.metrics;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import nuna.catalog.core.Names;
import nuna.catalog.server.governance.RefSettings;
import nuna.catalog.store.CatalogRecord;
import nuna.catalog.store.CatalogStore;
import nuna.catalog.store.Page;
import nuna.catalog.store.PageRequest;
import nuna.catalog.store.Ref;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.ToDoubleFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The gauges of each declared catalog's refs (D33, item 26's choice 13; docs/observability/catalog-metrics.md): its
 * open branches, its tags, and its branches within {@code nuna.refs.expiry-warning} of their expiry. They are read
 * from the store, which every server shares, so every server shows the same figures. A scrape reads the store at most
 * once every {@link #FRESH}; a catalog whose refs could not be read shows NaN until a read succeeds.
 */
public final class RefGauges implements MeterBinder {

    public static final String REFS = "nuna.catalog.refs";
    public static final String EXPIRING = "nuna.catalog.refs.expiring";

    static final Duration FRESH = Duration.ofSeconds(30);

    private static final Logger LOG = LoggerFactory.getLogger(RefGauges.class);
    private static final int PAGE = 1000;
    private static final Counts UNREAD = new Counts(Double.NaN, Double.NaN, Double.NaN);

    private final CatalogStore store;
    private final List<String> catalogs;
    private final RefSettings settings;
    private final Clock clock;
    private Instant readAt;
    private Map<String, Counts> counts = Map.of();

    private record Counts(double branches, double tags, double expiring) {
    }

    /** @param catalogs the names of the catalogs the deployment declares ({@code nuna.storage.catalogs}) */
    public RefGauges(CatalogStore store, List<String> catalogs, RefSettings settings, Clock clock) {
        this.store = store;
        this.catalogs = List.copyOf(catalogs);
        this.settings = settings;
        this.clock = clock;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        for (String catalog : catalogs) {
            refs(registry, catalog, "branch", Counts::branches);
            refs(registry, catalog, "tag", Counts::tags);
            Gauge.builder(EXPIRING, () -> counts(catalog).expiring())
                    .description("The catalog's branches within nuna.refs.expiry-warning of their expiry")
                    .tag(CatalogMetrics.CATALOG, catalog)
                    .register(registry);
        }
    }

    private void refs(MeterRegistry registry, String catalog, String kind, ToDoubleFunction<Counts> value) {
        Gauge.builder(REFS, () -> value.applyAsDouble(counts(catalog)))
                .description("The catalog's open branches and its tags")
                .tag(CatalogMetrics.CATALOG, catalog)
                .tag(CatalogMetrics.REF_KIND, kind)
                .register(registry);
    }

    private synchronized Counts counts(String catalog) {
        Instant now = clock.instant();
        if (readAt == null || !readAt.plus(FRESH).isAfter(now)) {
            Map<String, Counts> read = new HashMap<>();
            for (String name : catalogs) {
                try {
                    read.put(name, count(name, now));
                } catch (RuntimeException e) {
                    LOG.debug("reading the refs of catalog {} for their gauges failed", name, e);
                    read.put(name, UNREAD);
                }
            }
            counts = read;
            readAt = now;
        }
        return counts.getOrDefault(catalog, UNREAD);
    }

    private Counts count(String catalog, Instant now) {
        Optional<CatalogRecord> record = store.findCatalog(Names.normalize(catalog));
        if (record.isEmpty()) {
            return new Counts(0, 0, 0);
        }
        long branches = 0;
        long tags = 0;
        long expiring = 0;
        PageRequest page = PageRequest.first(PAGE);
        while (true) {
            Page<Ref> listed = store.listRefs(record.get().id(), page);
            for (Ref ref : listed.items()) {
                if (ref.kind() == Ref.Kind.TAG) {
                    tags++;
                } else if (!ref.expiredAt(now)) {
                    branches++;
                    if (settings.expiring(ref, now)) {
                        expiring++;
                    }
                }
            }
            if (listed.nextToken().isEmpty()) {
                return new Counts(branches, tags, expiring);
            }
            page = page.after(listed);
        }
    }
}
