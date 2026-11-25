package nuna.maintenance;

import nuna.catalog.objectstore.StoreKind;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
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
 * @param storage        its own storage identity, one per store, by a name of the deployment's choosing: where on each
 *                       store it lists and deletes, the catalog's storage profiles say at each scan (item 256)
 * @param compaction     compaction as Kubernetes Jobs; off unless an image is named
 * @param requestDeadline how long a job run on request (backlog 114) may run: a requested compaction Job's deadline,
 *                        and how long a requested orphan scan lists before it stops, recording nothing
 */
@ConfigurationProperties("nuna.maintenance")
public record WorkerSettings(
        URI catalogUri,
        @DefaultValue("PT10M") Duration scanInterval,
        CleanupWindow cleanupWindow,
        @DefaultValue("P1D") Duration orphanInterval,
        @DefaultValue("100") int pageSize,
        Token token,
        @DefaultValue Map<String, Store> storage,
        @DefaultValue Compaction compaction,
        @DefaultValue("PT2H") Duration requestDeadline) {

    public WorkerSettings {
        Objects.requireNonNull(catalogUri, "nuna.maintenance.catalog-uri names no catalog");
        Objects.requireNonNull(cleanupWindow, "nuna.maintenance.cleanup-window: set start, end and zone, the nightly"
                + " window in which compaction and orphan scans run");
        Objects.requireNonNull(token, "nuna.maintenance.token: set file, or endpoint, client-id and client-secret");
        if (storage.isEmpty()) {
            throw new IllegalArgumentException("nuna.maintenance.storage names no store: the worker lists and deletes"
                    + " table files with its own storage identity");
        }
        storage.forEach((name, store) -> Objects.requireNonNull(store.endpoint(), "nuna.maintenance.storage." + name
                + ".endpoint: name the store's endpoint as the catalog's storage profiles name it, the regional"
                + " endpoint on AWS S3: the worker finds a profile's store by it"));
        if (scanInterval.isNegative() || scanInterval.isZero()) {
            throw new IllegalArgumentException("nuna.maintenance.scan-interval must be positive");
        }
        if (requestDeadline.isNegative() || requestDeadline.isZero()) {
            throw new IllegalArgumentException("nuna.maintenance.request-deadline must be positive");
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
     * The worker's own identity on one store (item 256). The catalog's storage profiles give the prefixes, and the
     * worker serves a profile with the identity whose kind and endpoint are the profile's; everything else about the
     * store comes from here alone, never from the catalog.
     *
     * @param kind            the store's kind as the catalog's profiles name it: s3 (AWS S3), minio or ceph; MinIO and
     *                        Ceph buckets are addressed by path
     * @param endpoint        the S3 endpoint as the catalog's profiles write it, the regional endpoint on AWS S3:
     *                        required for every store in {@code nuna.maintenance.storage}; a DR replica on AWS S3 may
     *                        name none
     * @param region          the region
     * @param accessKeyId     its access key; none for the AWS SDK's default chain (IRSA, Pod Identity)
     * @param secretAccessKey its secret key
     * @param caBundle        a PEM file of one or more CA certificates, for a store whose certificate a private CA
     *                        signed: every S3 client the worker opens to this endpoint trusts them beside the JVM's CAs,
     *                        its own and those reading with the credentials the catalog vends it, which name the
     *                        catalog's endpoint for the store (backlog 21)
     */
    public record Store(StoreKind kind, URI endpoint, @DefaultValue("us-east-1") String region, String accessKeyId,
                        String secretAccessKey, Path caBundle) {

        public Store {
            Objects.requireNonNull(kind, "a nuna.maintenance.storage store needs its kind: s3, minio or ceph, as the"
                    + " catalog's storage profiles name it");
            if ((accessKeyId == null) != (secretAccessKey == null)) {
                throw new IllegalArgumentException("a nuna.maintenance.storage store needs both keys or neither");
            }
            if (caBundle != null && endpoint == null) {
                throw new IllegalArgumentException("a nuna.maintenance.storage store with a ca-bundle needs an"
                        + " endpoint: the bundle is trusted for that endpoint alone");
            }
        }
    }

    /**
     * The DR replication-watermark verifier ({@code nuna.maintenance.dr.*}; D37, amended 2026-11-20;
     * docs/observability/dr-watermark.md): off unless a replica is named. Every {@code verify-interval} it reads the
     * catalog's DR work list and checks each table's and view's newer versions in the DR store, the read-only replica
     * of a store: the same buckets and paths on another endpoint, as on-prem site replication keeps them.
     *
     * @param verifyInterval how often the verifier reads the DR work list
     * @param replicas       a read-only replica per store, by the store's name in {@code nuna.maintenance.storage}
     */
    @ConfigurationProperties("nuna.maintenance.dr")
    public record Dr(@DefaultValue("PT1M") Duration verifyInterval, @DefaultValue Map<String, Replica> replicas) {

        public Dr {
            if (verifyInterval.isNegative() || verifyInterval.isZero()) {
                throw new IllegalArgumentException("nuna.maintenance.dr.verify-interval must be positive");
            }
            replicas = Map.copyOf(replicas);
        }

        /** Whether the verifier runs at all. */
        public boolean enabled() {
            return !replicas.isEmpty();
        }
    }

    /**
     * A store's read-only replica in the DR region: the prefixes the catalog gives for the store, on another endpoint
     * of the same kind, read with keys that may only read there.
     *
     * @param endpoint        the replica's S3 endpoint; none for AWS S3 (renamed buckets wait on PLT-4)
     * @param region          its region
     * @param accessKeyId     its access key; none for the AWS SDK's default chain
     * @param secretAccessKey its secret key
     * @param caBundle        a PEM file of CA certificates the replica's endpoint is trusted with, as for a store
     */
    public record Replica(URI endpoint, @DefaultValue("us-east-1") String region, String accessKeyId,
                          String secretAccessKey, Path caBundle) {
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
     * @param memoryRequest   the memory the Job container asks the node for; by default {@code memory}. Lower, a Job
     *                        schedules beside pods that leave less than its limit unasked for, and is the first pod the
     *                        node stops when its memory runs short: the demo's node is 94% asked for (backlog 230)
     * @param driverMemory    Spark's driver memory, within that limit
     * @param maxJobs         how many Jobs run at once; the neediest tables go first
     * @param runAsUser       the Spark image's user id (185 in the apache/spark images)
     * @param sparkConf       more Spark settings for every Job
     */
    public record Compaction(String image, @DefaultValue("IfNotPresent") String imagePullPolicy, String namespace,
                             String jobServiceAccount, @DefaultValue("https://kubernetes.default.svc") URI apiServer,
                             @DefaultValue("/var/run/secrets/kubernetes.io/serviceaccount") Path serviceAccount,
                             URI catalogUri, @DefaultValue("2Gi") String memory, String memoryRequest,
                             @DefaultValue("1g") String driverMemory,
                             @DefaultValue("2") int maxJobs, @DefaultValue("185") long runAsUser,
                             @DefaultValue Map<String, String> sparkConf) {

        public Compaction {
            sparkConf = Map.copyOf(sparkConf);
        }

        /** The memory the Job container asks for: {@code memory-request}, else its limit. */
        public String request() {
            return memoryRequest == null || memoryRequest.isBlank() ? memory : memoryRequest;
        }

        /** Whether compaction runs at all. */
        public boolean enabled() {
            return image != null && !image.isBlank();
        }
    }
}
