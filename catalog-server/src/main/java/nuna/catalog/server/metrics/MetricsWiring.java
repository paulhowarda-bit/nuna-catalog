package nuna.catalog.server.metrics;

import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import nuna.catalog.core.commit.MetadataCache;
import nuna.catalog.server.governance.RefSettings;
import nuna.catalog.server.storage.StorageSettings;
import nuna.catalog.store.CatalogStore;
import java.time.Clock;
import java.util.List;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/** The catalog's own meters (docs/observability/catalog-metrics.md), beside Boot's. */
@Configuration(proxyBeanMethods = false)
class MetricsWiring {

    static final String CACHE = "nuna.catalog.metadata.cache.lookups";

    @Bean
    CatalogMetrics catalogMetrics(MeterRegistry registry) {
        return new CatalogMetrics(registry);
    }

    @Bean
    FilterRegistrationBean<RequestMetrics> requestMetrics(MeterRegistry registry) {
        FilterRegistrationBean<RequestMetrics> registration = new FilterRegistrationBean<>(new RequestMetrics(registry));
        // Right after the trace starts and the request id is set (TelemetryWiring), before authentication.
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 3);
        return registration;
    }

    /** The metadata cache's lookups (D14), found or not. */
    @Bean
    MeterBinder metadataCacheMeters(MetadataCache cache) {
        return registry -> {
            FunctionCounter.builder(CACHE, cache, MetadataCache::hits)
                    .description("Lookups in the metadata cache, by whether they found the file")
                    .tag(CatalogMetrics.OUTCOME, "hit")
                    .register(registry);
            FunctionCounter.builder(CACHE, cache, MetadataCache::misses)
                    .description("Lookups in the metadata cache, by whether they found the file")
                    .tag(CatalogMetrics.OUTCOME, "miss")
                    .register(registry);
        };
    }

    /** Each declared catalog's open branches, tags and branches near their expiry (D33). */
    @Bean
    MeterBinder refMeters(CatalogStore store, StorageSettings storage, RefSettings refs, Clock clock) {
        return new RefGauges(store, List.copyOf(storage.catalogs().keySet()), refs, clock);
    }
}
