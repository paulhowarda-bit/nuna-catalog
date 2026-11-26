package nuna.catalog.server.governance;

import nuna.catalog.audit.archive.ObjectLockArchive;
import nuna.catalog.core.commit.CommitRule;
import nuna.catalog.core.commit.Rules;
import nuna.catalog.objectstore.CaBundle;
import nuna.catalog.server.rest.AuditPublisher;
import nuna.catalog.server.rest.RestSettings;
import nuna.catalog.server.storage.StorageSettings;
import nuna.catalog.server.tracing.ObservedPublisher;
import nuna.catalog.store.CatalogStore;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.binder.MeterBinder;
import io.micrometer.observation.ObservationRegistry;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.SmartLifecycle;
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
@EnableConfigurationProperties({AuditSettings.class, GovernanceSettings.class, RefSettings.class})
class GovernanceWiring {

    /**
     * The archive where {@code nuna.audit.archive.bucket} is set; its bucket must have Object Lock. Its S3 client trusts
     * the profile's CA bundle beside the JVM's CAs, as the profile's other clients do (backlog 87), and a bundle it
     * cannot read stops startup, naming the setting and the file.
     */
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
        if (profile.caBundle() != null) {
            CaBundle bundle;
            try {
                bundle = CaBundle.read(profile.caBundle());
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException("nuna.storage.profiles." + audit.archive().profile() + ".ca-bundle: "
                        + e.getMessage(), e);
            }
            bundle.applyTo(s3);
        }
        // The server writes nothing but the check's state: the retention is the writer's, and the floor stands in.
        ObjectLockArchive archive = new ObjectLockArchive(s3.build(), bucket, audit.archive().prefix(),
                ObjectLockArchive.MINIMUM_RETENTION, clock);
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

    /**
     * Audit delivery. Each synchronous record's publish to Kafka, up to its acknowledgement, is observed
     * (docs/observability/spans.md).
     */
    @Bean(destroyMethod = "close")
    AuditRuntime auditRuntime(AuditSettings audit, StorageSettings storage, CatalogStore store,
                              GovernanceSettings governance, RestSettings rest, Clock clock,
                              ObservationRegistry observations) {
        MaintenancePolicy.canonical(governance.maintenanceDefaults(), governance);
        requireDropWindowBeyondPitr(rest.dropRetention(), governance.pitrWindow());
        return new AuditRuntime(audit, store, archive(audit, storage, clock), rest.auditSource(),
                publisher -> new ObservedPublisher(publisher, audit.auditTopic(), observations), clock);
    }

    /** Every audit record goes through it: to the log, and to Kafka where it is configured, best effort (D23). */
    @Bean
    AuditTrail auditTrail(AuditRuntime runtime) {
        return runtime.trail();
    }

    /**
     * The records this server has logged in its run, as a gauge tagged with the run (backlog 272): beside the archive's
     * highest number for the run ({@link AuditArchiveCheck#ARCHIVED}), it shows a tail that has not reached the
     * archive while the server still runs.
     */
    @Bean
    MeterBinder auditLoggedMeter(AuditTrail trail) {
        return registry -> Gauge.builder(AuditArchiveCheck.LOGGED, trail, AuditTrail::logged)
                .description("The audit records this server has logged in its run, the number of the last")
                .tag(AuditArchiveCheck.RUN, trail.run().toString())
                .strongReference(true)
                .register(registry);
    }

    /**
     * Logs the run's run-end record as the server stops (D23, amended 2026-11-25), in the last phase, after the web
     * server, the relay and every other part that records have stopped.
     */
    @Bean
    SmartLifecycle auditRunEnd(AuditTrail trail) {
        return new SmartLifecycle() {
            private volatile boolean running;

            @Override
            public void start() {
                running = true;
            }

            @Override
            public void stop() {
                running = false;
                trail.end(host());
            }

            @Override
            public boolean isRunning() {
                return running;
            }

            @Override
            public int getPhase() {
                return Integer.MIN_VALUE;
            }
        };
    }

    /** The pod's name where Kubernetes sets it, else the machine's. */
    static String host() {
        String name = System.getenv("HOSTNAME");
        if (name != null && !name.isBlank()) {
            return name;
        }
        try {
            return java.net.InetAddress.getLocalHost().getHostName();
        } catch (java.net.UnknownHostException e) {
            return "unknown";
        }
    }

    /**
     * The synchronous audit seam of M5 and M6 (denials, authentication failures, reads, credential issues). None of
     * them fails for want of Kafka: a credential issue is recorded as the others are (D23, amended 2026-11-23). The
     * trail is looked up on first use: M5's filter takes this bean while the web server starts, before the audit and
     * storage settings may be bound.
     */
    @Bean
    AuditPublisher auditPublisher(ObjectProvider<AuditTrail> trail) {
        return event -> trail.getObject().record(event);
    }

    /**
     * The deployment's minimum snapshot age and a retention class's, enforced when a commit removes snapshots
     * (D24, D30).
     */
    @Bean
    CommitRule snapshotMinimumAge(GovernanceSettings governance) {
        return Rules.snapshotMinimumAge(governance.minimumSnapshotAge(), governance::minimumAgeOf);
    }

    /**
     * A branch's expiry warning (D33, choice 11), which changes nothing in the store, goes out as the relay sends a
     * batch: to the log, then, where Kafka is configured, the audit topic and the change stream.
     */
    @Bean
    RefRoutes.Notices refNotices(AuditRuntime runtime) {
        return event -> runtime.notices().deliver(List.of(event));
    }

    /** Who dropped a table or view, from the audit table, for the refs that keep it (backlog 170). */
    @Bean
    RefRoutes.Drops refDrops(AuditFeed feed, nuna.catalog.security.Authorizer authorizer, AuditTrail trail) {
        return new AuditDrops(feed, authorizer, trail);
    }
}
