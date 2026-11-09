package fidice.catalog.server.governance;

import fidice.catalog.audit.archive.ObjectLockArchive;
import fidice.catalog.audit.publish.EventPublisher;
import fidice.catalog.core.commit.CommitRule;
import fidice.catalog.core.commit.Rules;
import fidice.catalog.server.rest.RestSettings;
import fidice.catalog.server.rest.StorageSettings;
import fidice.catalog.store.CatalogStore;
import java.net.URI;
import java.time.Clock;
import java.util.Optional;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;

/**
 * Governance and audit (M7): audit delivery, the archive, the synchronous audit trail and the commit
 * rule for retention classes. The server refuses to start with a maintenance default the guardrails
 * forbid, or an archive bucket without Object Lock.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({AuditSettings.class, GovernanceSettings.class})
class GovernanceWiring {

    /** The archive where {@code fidice.audit.archive.bucket} is set; its bucket must have Object Lock. */
    static Optional<ObjectLockArchive> archive(AuditSettings audit, StorageSettings storage, Clock clock) {
        String bucket = audit.archive().bucket();
        if (bucket == null || bucket.isBlank()) {
            return Optional.empty();
        }
        AwsCredentialsProvider credentials = storage.accessKeyId() == null
                ? DefaultCredentialsProvider.builder().build()
                : StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(storage.accessKeyId(), storage.secretAccessKey()));
        S3ClientBuilder s3 = S3Client.builder()
                .region(Region.of(storage.region()))
                .credentialsProvider(credentials);
        if (storage.endpoint() != null && !storage.endpoint().isBlank()) {
            s3.endpointOverride(URI.create(storage.endpoint())).forcePathStyle(true);
        }
        ObjectLockArchive archive = new ObjectLockArchive(s3.build(), bucket, audit.archive().prefix(),
                audit.archive().retention(), clock);
        archive.checkBucket();
        return Optional.of(archive);
    }

    @Bean(destroyMethod = "close")
    AuditRuntime auditRuntime(AuditSettings audit, StorageSettings storage, CatalogStore store,
                              GovernanceSettings governance, Clock clock) {
        MaintenancePolicy.canonical(governance.maintenanceDefaults(), governance);
        return new AuditRuntime(audit, store, archive(audit, storage, clock), clock);
    }

    @Bean
    AuditTrail auditTrail(AuditRuntime runtime, AuditSettings audit, RestSettings rest, Clock clock) {
        return new AuditTrail(runtime.auditPublisher().map(EventPublisher.class::cast), rest.auditSource(), clock,
                audit.probeInterval());
    }

    /** A retention class's minimum snapshot age, enforced when a commit removes snapshots (D24). */
    @Bean
    CommitRule retentionClassMinimumAge(GovernanceSettings governance) {
        return Rules.snapshotMinimumAge(governance::minimumAgeOf);
    }
}
