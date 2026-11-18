package nuna.maintenance;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The maintenance worker's settings ({@code nuna.maintenance.*}, D30). The worker signs in to the catalog as a
 * maintenance principal (the catalog's {@code nuna.security.maintenance-principals}) and reaches storage with its own
 * identity, the only one a deployment's delete policy lets delete (D21).
 *
 * @param catalogUri     the catalog's base URI, e.g. {@code http://nuna-catalog:8181/}
 * @param scanInterval   how often the worker reads the work list and does what is due (D30 (2))
 * @param cleanupWindow  the nightly window, the only time compaction and orphan scans start (D30, amended
 *                       2026-11-15); required, since only the deployment knows its night
 * @param orphanInterval the least time between two orphan scans of one table, rounded up to whole nights: a scan lists
 *                       the table's whole prefix
 * @param pageSize       work list items per page
 * @param token          how the worker gets its bearer token
 * @param storage        its own storage identity, per store: every table location the catalog lists must lie under one
 *                       profile's prefixes
 * @param compaction     compaction as Kubernetes Jobs; off unless an image is named
 */
@ConfigurationProperties("nuna.maintenance")
public record WorkerSettings(
        URI catalogUri,
        @DefaultValue("PT10M") Duration scanInterval,
        CleanupWindow cleanupWindow,
        @DefaultValue("P1D") Duration orphanInterval,
        @DefaultValue("100") int pageSize,
        Token token,
        @DefaultValue Map<String, StorageProfile> storage,
        @DefaultValue Compaction compaction) {

    public WorkerSettings {
        Objects.requireNonNull(catalogUri, "nuna.maintenance.catalog-uri names no catalog");
        Objects.requireNonNull(cleanupWindow, "nuna.maintenance.cleanup-window: set start, end and zone, the nightly"
                + " window in which compaction and orphan scans run");
        Objects.requireNonNull(token, "nuna.maintenance.token: set file, or endpoint, client-id and client-secret");
        if (storage.isEmpty()) {
            throw new IllegalArgumentException("nuna.maintenance.storage names no storage profile: the worker reads"
                    + " and deletes table files with its own storage identity");
        }
        if (scanInterval.isNegative() || scanInterval.isZero()) {
            throw new IllegalArgumentException("nuna.maintenance.scan-interval must be positive");
        }
        if (compaction.enabled()) {
            if (token.file() != null) {
                throw new IllegalArgumentException("nuna.maintenance.compaction needs the client-credentials grant"
                        + " (nuna.maintenance.token.endpoint): a compaction Job runs up to its deadline and renews its"
                        + " own token, where a token file's token would expire under it");
            }
            if (token.scope() == null) {
                throw new IllegalArgumentException("nuna.maintenance.token.scope must be set when compaction runs:"
                        + " the Job's Iceberg client always asks for a scope (\"catalog\" when none is set), so the"
                        + " worker's own token request names the same one");
            }
            if (compaction.namespace() == null || compaction.namespace().isBlank()) {
                throw new IllegalArgumentException("nuna.maintenance.compaction.namespace must name a namespace for"
                        + " the Jobs alone, never the catalog's or the worker's: a pod may run as any service"
                        + " account and mount any Secret of its namespace");
            }
            if (compaction.maxJobs() < 1) {
                throw new IllegalArgumentException("nuna.maintenance.compaction.max-jobs must be at least 1");
            }
        }
        storage = Map.copyOf(storage);
    }

    /**
     * Where the bearer token comes from: a file read again whenever it changes (a mounted Secret kept fresh by
     * something else), or the OAuth 2 client-credentials grant against the identity provider's token endpoint.
     *
     * @param file         a file holding the token
     * @param endpoint     the identity provider's token endpoint
     * @param clientId     the worker's client id there
     * @param clientSecret its secret
     * @param scope        a scope to ask for; required when compaction runs, since the Job's client always asks for
     *                     one
     * @param audience     an audience to ask for, if the provider needs one (the catalog checks {@code aud})
     */
    public record Token(Path file, URI endpoint, String clientId, String clientSecret, String scope, String audience) {

        public Token {
            if ((file == null) == (endpoint == null)) {
                throw new IllegalArgumentException("nuna.maintenance.token: set exactly one of file and endpoint");
            }
            if (endpoint != null && (clientId == null || clientSecret == null)) {
                throw new IllegalArgumentException("nuna.maintenance.token: the client-credentials grant needs"
                        + " client-id and client-secret");
            }
        }
    }

    /**
     * The worker's own identity on one store.
     *
     * @param prefixes        the locations it covers, e.g. {@code s3://lake/tables}
     * @param endpoint        the S3 endpoint; none for AWS S3
     * @param region          the region
     * @param pathStyleAccess address buckets by path (MinIO, Ceph)
     * @param accessKeyId     its access key; none for the AWS SDK's default chain (IRSA, Pod Identity)
     * @param secretAccessKey its secret key
     */
    public record StorageProfile(List<String> prefixes, URI endpoint, @DefaultValue("us-east-1") String region,
                                 @DefaultValue("false") boolean pathStyleAccess, String accessKeyId,
                                 String secretAccessKey) {

        public StorageProfile {
            if (prefixes == null || prefixes.isEmpty()) {
                throw new IllegalArgumentException("a nuna.maintenance.storage profile covers no prefix");
            }
            prefixes = List.copyOf(prefixes);
            if ((accessKeyId == null) != (secretAccessKey == null)) {
                throw new IllegalArgumentException("a nuna.maintenance.storage profile needs both keys or neither");
            }
        }
    }

    /**
     * Compaction (D30 (3), amended 2026-11-15): in the cleanup window, one Kubernetes Job per table that needs it,
     * running the repo's Spark image ({@code catalog-tests-engines/images/spark}) with rewrite_data_files, signed in as
     * the worker, and stopped at the window's end.
     *
     * @param image           the Spark image; compaction is off without one
     * @param imagePullPolicy its pull policy
     * @param namespace       where Jobs run: a namespace of their own, required, never the catalog's or the worker's
     * @param jobServiceAccount the service account the Jobs' pods run as, one with no rights; by default the
     *                        namespace's {@code default}
     * @param apiServer       the Kubernetes API server
     * @param serviceAccount  the folder holding the worker's service account token, CA and namespace
     * @param catalogUri      the catalog as the Job's pods reach it; by default the worker's {@code catalog-uri}
     * @param memory          the Job container's memory limit
     * @param driverMemory    Spark's driver memory, within that limit
     * @param maxJobs         how many Jobs run at once; the neediest tables go first
     * @param runAsUser       the Spark image's user id (185 in the apache/spark images)
     * @param sparkConf       more Spark settings for every Job
     */
    public record Compaction(String image, @DefaultValue("IfNotPresent") String imagePullPolicy, String namespace,
                             String jobServiceAccount, @DefaultValue("https://kubernetes.default.svc") URI apiServer,
                             @DefaultValue("/var/run/secrets/kubernetes.io/serviceaccount") Path serviceAccount,
                             URI catalogUri, @DefaultValue("2Gi") String memory, @DefaultValue("1g") String driverMemory,
                             @DefaultValue("2") int maxJobs, @DefaultValue("185") long runAsUser,
                             @DefaultValue Map<String, String> sparkConf) {

        public Compaction {
            sparkConf = Map.copyOf(sparkConf);
        }

        /** Whether compaction runs at all. */
        public boolean enabled() {
            return image != null && !image.isBlank();
        }
    }
}
