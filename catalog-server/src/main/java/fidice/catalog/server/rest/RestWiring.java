package fidice.catalog.server.rest;

import fidice.catalog.core.commit.MetadataCache;
import fidice.catalog.iceberg.MetadataViews;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The REST surface's collaborators. Callers and telemetry are seams that M5 (security) and M7 (audit)
 * replace; until then requests are anonymous and metrics reports are logged. Storage and credential
 * vending are wired per catalog in {@code fidice.catalog.server.storage} (M6).
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
    Callers callers() {
        return Callers.ANONYMOUS;
    }

    @Bean
    MetricsSink metricsSink() {
        return MetricsSink.LOG;
    }
}
