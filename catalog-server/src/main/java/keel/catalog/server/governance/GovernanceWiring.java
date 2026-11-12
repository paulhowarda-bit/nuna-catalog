package keel.catalog.server.governance;

import keel.catalog.audit.CredentialIssue;
import keel.catalog.audit.archive.ObjectLockArchive;
import keel.catalog.audit.publish.EventPublisher;
import keel.catalog.core.commit.CommitRule;
import keel.catalog.core.commit.Rules;
import keel.catalog.server.rest.AuditPublisher;
import keel.catalog.server.rest.RestSettings;
import keel.catalog.server.storage.StorageSettings;
import keel.catalog.store.CatalogStore;
import java.net.URI;
import java.time.Clock;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
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

    /** The archive where {@code keel.audit.archive.bucket} is set; its bucket must have Object Lock. */
    static Optional<ObjectLockArchive> archive(AuditSettings audit, StorageSettings storage, Clock clock) {
        String bucket = audit.archive().bucket();
        if (bucket == null || bucket.isBlank()) {
            return Optional.empty();
        }
        StorageSettings.Profile profile = storage.profiles().get(audit.archive().profile());
        if (profile == null) {
            throw new IllegalStateException("keel.audit.archive.profile must name a storage profile, not "
                    + audit.archive().profile());
        }
        AwsCredentialsProvider credentials = profile.accessKeyId() == null
                ? DefaultCredentialsProvider.builder().build()
                : StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(profile.accessKeyId(), profile.secretAccessKey()));
        S3ClientBuilder s3 = S3Client.builder()
                .region(Region.of(profile.region()))
                .credentialsProvider(credentials);
        if (profile.endpoint() != null && !profile.endpoint().isBlank()) {
            s3.endpointOverride(URI.create(profile.endpoint())).forcePathStyle(true);
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

    /**
     * The synchronous audit seam of M5 and M6 (denials, authentication failures, credential issues), on the
     * audit topic. A credential issue fails closed, so vending hands nothing out; the others never fail.
     * The trail is looked up on first use: M5's filter takes this bean while the web server starts, before
     * the audit and storage settings may be bound.
     */
    @Bean
    AuditPublisher auditPublisher(ObjectProvider<AuditTrail> trail) {
        return event -> {
            if (event.data() instanceof CredentialIssue) {
                trail.getObject().requireRecorded(event);
            } else {
                trail.getObject().record(event);
            }
        };
    }

    /** A retention class's minimum snapshot age, enforced when a commit removes snapshots (D24). */
    @Bean
    CommitRule retentionClassMinimumAge(GovernanceSettings governance) {
        return Rules.snapshotMinimumAge(governance::minimumAgeOf);
    }
}
