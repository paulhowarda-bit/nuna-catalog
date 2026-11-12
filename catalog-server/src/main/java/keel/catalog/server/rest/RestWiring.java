package keel.catalog.server.rest;

import keel.catalog.core.commit.MetadataCache;
import keel.catalog.iceberg.MetadataViews;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The REST surface's collaborators. Telemetry is a seam; until it is wired, metrics reports are logged. The
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
    ServerAlerts serverAlerts(RestSettings settings) {
        return new ServerAlerts(settings.alertMetadataChars(), settings.alertSnapshots());
    }

    @Bean
    MetricsSink metricsSink() {
        return MetricsSink.LOG;
    }
}
