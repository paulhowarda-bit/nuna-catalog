package keel.catalog.server.rest;

import io.micrometer.core.instrument.MeterRegistry;
import keel.catalog.core.commit.MetadataCache;
import keel.catalog.iceberg.MetadataViews;
import keel.catalog.server.metrics.CatalogMetrics;
import java.time.Clock;
import org.apache.tomcat.util.buf.EncodedSolidusHandling;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.tomcat.TomcatConnectorCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The REST surface's collaborators. Metrics reports are logged and their results measured. The
 * synchronous audit publisher is M7's, in {@code keel.catalog.server.governance}. Callers come from the security package
 * (M5); storage and credential vending are wired per catalog in {@code keel.catalog.server.storage} (M6).
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RestSettings.class)
class RestWiring {

    @Bean
    MetadataCache metadataCache(RestSettings settings) {
        return new MetadataCache(settings.metadataCacheChars());
    }

    @Bean
    MetadataViews metadataViews(RestSettings settings) {
        return new MetadataViews(settings.metadataCacheChars());
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    ServerAlerts serverAlerts(RestSettings settings, MeterRegistry registry) {
        return new ServerAlerts(settings.alertMetadataChars(), settings.alertSnapshots(), registry);
    }

    /** Each report to its log line, whole, and its results to the catalog's meters, aggregated. */
    @Bean
    MetricsSink metricsSink(CatalogMetrics metrics) {
        return (catalog, namespace, table, summary) -> {
            MetricsSink.LOG.report(catalog, namespace, table, summary);
            metrics.report(catalog, summary);
        };
    }

    /**
     * A name may hold {@code /}, which a client sends as {@code %2F} in the path. Tomcat refuses an encoded slash
     * by default, before any route runs; passed through, it stays inside its path segment and the route decodes
     * it into the name.
     */
    @Bean
    TomcatConnectorCustomizer encodedSlashesInNames() {
        return connector -> connector.setEncodedSolidusHandling(EncodedSolidusHandling.PASS_THROUGH.getValue());
    }
}
