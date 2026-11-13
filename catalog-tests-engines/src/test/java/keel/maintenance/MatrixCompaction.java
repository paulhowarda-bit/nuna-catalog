package keel.maintenance;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The engine matrix's way into the maintenance worker's compaction (D30 (3)): the worker's own {@link KubernetesJobs},
 * package-private, launching the Job the worker would for one table whose policy turns compaction on.
 */
public final class MatrixCompaction {

    private MatrixCompaction() {
    }

    /**
     * Launches the compaction Job for one table and returns its name.
     *
     * @param image          the Spark image, already on the cluster's nodes
     * @param jobs           the Jobs' namespace, where the worker's service account holds the chart's Role
     * @param apiServer      the Kubernetes API server as the caller reaches it
     * @param serviceAccount a folder holding the worker's service account token, the cluster's CA as
     *                       {@code ca.crt} and the worker's own namespace
     * @param catalogUri     the catalog as the Job's pods reach it
     * @param catalog        the catalog (Iceberg warehouse) holding the table
     * @param id             the table's id
     * @param namespace      its namespace levels
     * @param name           its name
     * @param location       its location
     * @param token          the worker's bearer token, which the Job signs in with
     */
    public static String launch(String image, String jobs, URI apiServer, Path serviceAccount, URI catalogUri,
                                String catalog, UUID id, List<String> namespace, String name, String location,
                                String token) {
        WorkerSettings.Compaction settings = new WorkerSettings.Compaction(image, "Never", jobs, "keel-compaction",
                apiServer, serviceAccount, catalogUri, "2Gi", "1g", Duration.ofMinutes(30), 185, Map.of());
        TokenSource tokens = new TokenSource() {
            @Override
            public String token() {
                return token;
            }

            @Override
            public Map<String, String> clientProperties() {
                return Map.of("token", token);
            }
        };
        Management.TableWork table = new Management.TableWork(catalog, id, namespace, name, location,
                Map.of("compaction.enabled", "true"));
        Compactor.Launch launch = new KubernetesJobs(settings, Duration.ofHours(1), catalogUri, tokens).launch(table);
        if (launch != Compactor.Launch.STARTED) {
            throw new IllegalStateException("compaction of " + id + " did not start: " + launch);
        }
        return KubernetesJobs.jobName(table);
    }
}
