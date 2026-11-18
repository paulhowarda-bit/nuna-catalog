package nuna.catalog.server.governance;

import nuna.catalog.audit.CredentialIssue;
import nuna.catalog.audit.archive.ObjectLockArchive;
import nuna.catalog.audit.publish.EventPublisher;
import nuna.catalog.core.commit.CommitRule;
import nuna.catalog.core.commit.Rules;
import nuna.catalog.server.rest.AuditPublisher;
import nuna.catalog.server.rest.RestSettings;
import nuna.catalog.server.storage.StorageSettings;
import nuna.catalog.server.tracing.ObservedPublisher;
import nuna.catalog.store.CatalogStore;
import io.micrometer.observation.ObservationRegistry;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
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
 * rule for the minimum snapshot age. The server refuses to start with a maintenance default the guardrails
 * forbid, a soft-drop window no longer than the PITR window, or an archive bucket without Object Lock.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({AuditSettings.class, GovernanceSettings.class})
class GovernanceWiring {

    /** The archive where {@code nuna.audit.archive.bucket} is set; its bucket must have Object Lock. */
    static Optional<ObjectLockArchive> archive(AuditSettings audit, StorageSettings storage, Clock clock) {
        String bucket = audit.archive().bucket();
        if (bucket == null || bucket.isBlank()) {
            return Optional.empty();
        }
        StorageSettings.Profile profile = storage.profiles().get(audit.archive().profile());
        if (profile == null) {
            throw new IllegalStateException("nuna.audit.archive.profile must name a storage profile, not "
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

    /**
     * A dropped table is purged only after its soft-drop window, and that window must be longer than the
     * database's PITR window (D25, D30): a database restored to any point it still holds then never has a live
     * table whose files were purged.
     */
    static void requireDropWindowBeyondPitr(Duration dropRetention, Duration pitrWindow) {
        if (dropRetention.compareTo(pitrWindow) <= 0) {
            throw new IllegalStateException("nuna.rest.drop-retention (" + dropRetention
                    + ") must be longer than nuna.governance.pitr-window (" + pitrWindow
                    + "), so a database restored within its PITR window never holds a live table whose files"
                    + " were purged");
        }
    }

    @Bean(destroyMethod = "close")
    AuditRuntime auditRuntime(AuditSettings audit, StorageSettings storage, CatalogStore store,
                              GovernanceSettings governance, RestSettings rest, Clock clock) {
        MaintenancePolicy.canonical(governance.maintenanceDefaults(), governance);
        requireDropWindowBeyondPitr(rest.dropRetention(), governance.pitrWindow());
        return new AuditRuntime(audit, store, archive(audit, storage, clock), clock);
    }

    /** Each synchronous record's publish, up to Kafka's acknowledgement, is observed (docs/observability/spans.md). */
    @Bean
    AuditTrail auditTrail(AuditRuntime runtime, AuditSettings audit, RestSettings rest, Clock clock,
                          ObservationRegistry observations) {
        return new AuditTrail(runtime.auditPublisher().map(publisher -> (EventPublisher) new ObservedPublisher(
                publisher, audit.auditTopic(), observations)), rest.auditSource(), clock, audit.probeInterval());
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

    /**
     * The deployment's minimum snapshot age and a retention class's, enforced when a commit removes snapshots
     * (D24, D30).
     */
    @Bean
    CommitRule snapshotMinimumAge(GovernanceSettings governance) {
        return Rules.snapshotMinimumAge(governance.minimumSnapshotAge(), governance::minimumAgeOf);
    }
}
