package fidice.catalog.server.rest;

import fidice.catalog.core.commit.MetadataCache;
import fidice.catalog.iceberg.MetadataViews;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The REST surface's collaborators. The synchronous audit publisher and telemetry are seams that M7 (audit)
 * replaces; until then audit events and metrics reports are logged. Callers come from the security package
 * (M5); storage and credential vending are wired per catalog in {@code fidice.catalog.server.storage} (M6).
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
    AuditPublisher auditPublisher() {
        return AuditPublisher.LOG;
    }

    @Bean
    MetricsSink metricsSink() {
        return MetricsSink.LOG;
    }
}
