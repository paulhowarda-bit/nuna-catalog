package fidice.catalog.server.rest;

import fidice.catalog.core.commit.MetadataCache;
import fidice.catalog.core.commit.TableFiles;
import fidice.catalog.iceberg.MetadataViews;
import fidice.catalog.objectstore.MetadataFiles;
import fidice.catalog.objectstore.ProfileTableFiles;
import fidice.catalog.objectstore.StorageProfile;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;

/**
 * The REST surface's collaborators. Callers, credential vending and telemetry are seams that M5 (security),
 * M6 (object store) and M7 (audit) replace; until then requests are anonymous, no credentials are vended
 * and metrics reports are logged.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({RestSettings.class, StorageSettings.class})
class RestWiring {

    @Bean
    StorageProfile storageProfile(StorageSettings settings) {
        return settings.profile();
    }

    @Bean
    MetadataFiles metadataFiles(StorageProfile profile, StorageSettings settings) {
        AwsCredentialsProvider credentials = settings.accessKeyId() == null
                ? DefaultCredentialsProvider.builder().build()
                : StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(settings.accessKeyId(), settings.secretAccessKey()));
        return new MetadataFiles(profile, credentials);
    }

    @Bean
    TableFiles tableFiles(StorageProfile profile, MetadataFiles files) {
        return new ProfileTableFiles(profile, files);
    }

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
    CredentialVending credentialVending() {
        return CredentialVending.NONE;
    }

    @Bean
    MetricsSink metricsSink() {
        return MetricsSink.LOG;
    }
}
