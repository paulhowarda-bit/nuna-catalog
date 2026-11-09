package fidice.catalog.server.rest;

import fidice.catalog.core.commit.Alerts;
import fidice.catalog.iceberg.MetadataViews.TableStats;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Conditions someone must look at, logged to the {@code fidice.alerts} logger for the alerting
 * pipeline. Large metadata is alerted once per file: a file never changes, so once is enough.
 */
public final class ServerAlerts implements Alerts {

    private static final Logger LOG = LoggerFactory.getLogger("fidice.alerts");
    private static final int REMEMBERED = 10_000;

    private final long maxChars;
    private final int maxSnapshots;
    private final Set<String> alerted = Collections.synchronizedSet(Collections.newSetFromMap(
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                    return size() > REMEMBERED;
                }
            }));

    ServerAlerts(long maxChars, int maxSnapshots) {
        this.maxChars = maxChars;
        this.maxSnapshots = maxSnapshots;
    }

    @Override
    public void metadataIntegrity(UUID objectId, String location, String expectedSha256, String actualSha256) {
        LOG.error("metadata-integrity object={} location={} expected-sha256={} actual-sha256={}", objectId, location,
                expectedSha256, actualSha256);
    }

    @Override
    public void regulatedSchemaChange(UUID objectId, UUID commitId) {
        LOG.warn("regulated-schema-change object={} commit={}", objectId, commitId);
    }

    /** A loaded table's metadata is too large or has too many snapshots (D14). */
    void loaded(UUID tableId, String location, TableStats stats) {
        if ((stats.metadataChars() > maxChars || stats.snapshots() > maxSnapshots) && alerted.add(location)) {
            LOG.warn("large-metadata table={} location={} chars={} snapshots={} (limits {} and {})", tableId,
                    location, stats.metadataChars(), stats.snapshots(), maxChars, maxSnapshots);
        }
    }
}
