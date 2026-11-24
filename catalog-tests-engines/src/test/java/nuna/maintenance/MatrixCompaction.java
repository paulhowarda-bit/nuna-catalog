package nuna.maintenance;

import java.net.URI;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The engine matrix's way into the maintenance worker's compaction (D30 (3), amended 2026-11-15): the worker's own
 * {@link KubernetesJobs}, package-private, launching the Job the worker would for one table that needs compaction, in a
 * cleanup window that opens now and closes in half an hour.
 */
public final class MatrixCompaction {

    /** The policy's built-in target file size, which the worker passes on: 512 MiB. */
    private static final long TARGET_FILE_SIZE = 536870912L;

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
        WorkerSettings.Compaction settings = new WorkerSettings.Compaction(image, "Never", jobs, "nuna-compaction",
                apiServer, serviceAccount, catalogUri, "2Gi", null, "1g", 1, 185, Map.of());
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
        Instant now = Instant.now();
        CleanupWindow.Night night = new CleanupWindow.Night(LocalDate.ofInstant(now, ZoneOffset.UTC), now,
                now.plus(Duration.ofMinutes(30)));
        // rewrite_data_files' own thresholds, as the worker passes them for a table whose policy sets none.
        CompactionNeed need = new CompactionNeed(0, 0, 0, 0, 5, 0.3, TARGET_FILE_SIZE);
        Compactor.Launch launch = new KubernetesJobs(settings, catalogUri, tokens, Map.of(), Clock.systemUTC())
                .launch(table, need, night);
        if (launch != Compactor.Launch.STARTED) {
            throw new IllegalStateException("compaction of " + id + " did not start: " + launch);
        }
        return KubernetesJobs.jobName(table, night);
    }
}
