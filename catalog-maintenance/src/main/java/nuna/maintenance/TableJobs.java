package nuna.maintenance;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.apache.iceberg.ExpireSnapshots;
import org.apache.iceberg.ManifestFile;
import org.apache.iceberg.Snapshot;
import org.apache.iceberg.Table;
import org.apache.iceberg.TableProperties;
import org.apache.iceberg.util.PropertyUtil;

/**
 * The jobs that commit through the catalog like any client (D30): snapshot expiry and manifest rewrite. Neither
 * deletes a file: each hands what it leaves unreferenced to {@code unreferenced}, where Iceberg would have deleted
 * it, and the worker records it in the catalog's pending-deletion list, which holds it until the database's PITR
 * window has passed (D30 (5)). {@code unreferenced} may be called from several threads.
 */
final class TableJobs {

    private TableJobs() {
    }

    /**
     * Expires main's snapshots older than {@code maxAge}, keeping at least {@code retainLast}, and those of every
     * other branch by the same rule unless the branch sets its own; a tag's snapshot and the ancestors a branch keeps
     * stay, as Iceberg's RemoveSnapshots decides. Schemas and partition specs no snapshot uses any more go too.
     *
     * @return how many snapshots expired; nothing is committed when none would
     */
    static int expire(Table table, Instant now, Duration maxAge, int retainLast, Consumer<String> unreferenced) {
        ExpireSnapshots expiry = table.expireSnapshots()
                .expireOlderThan(now.minus(maxAge).toEpochMilli())
                .retainLast(retainLast)
                .cleanExpiredMetadata(true)
                .cleanupLevel(ExpireSnapshots.CleanupLevel.ALL)
                .deleteWith(unreferenced);
        List<Snapshot> expired = expiry.apply();
        if (expired.isEmpty()) {
            return 0;
        }
        expiry.commit();
        return expired.size();
    }

    /**
     * Rewrites the current snapshot's small data manifests (under the table's manifest target size) into manifests of
     * that size, when there are at least {@code minManifests} of them. The old manifests stay referenced by older
     * snapshots until expiry takes those; {@code unreferenced} gets the manifests written for attempts that lost.
     *
     * @return how many manifests were rewritten; nothing is committed when too few are small
     */
    static int rewriteManifests(Table table, long minManifests, Consumer<String> unreferenced) {
        Snapshot current = table.currentSnapshot();
        if (current == null) {
            return 0;
        }
        long target = PropertyUtil.propertyAsLong(table.properties(), TableProperties.MANIFEST_TARGET_SIZE_BYTES,
                TableProperties.MANIFEST_TARGET_SIZE_BYTES_DEFAULT);
        Set<String> small = current.dataManifests(table.io()).stream().filter(manifest -> manifest.length() < target)
                .map(ManifestFile::path).collect(Collectors.toSet());
        if (small.size() < minManifests) {
            return 0;
        }
        table.rewriteManifests()
                .rewriteIf(manifest -> small.contains(manifest.path()))
                .clusterBy(file -> "all")
                .deleteWith(unreferenced)
                .commit();
        return small.size();
    }
}
