package keel.catalog.server.rest;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import keel.catalog.core.commit.Alerts;
import keel.catalog.iceberg.MetadataViews.TableStats;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Conditions someone must look at, logged to the {@code keel.alerts} logger for the alerting
 * pipeline, and counted, one counter per kind, for Prometheus. Large metadata is alerted once per file: a file
 * never changes, so once is enough.
 */
public final class ServerAlerts implements Alerts {

    private static final Logger LOG = LoggerFactory.getLogger("keel.alerts");
    private static final int REMEMBERED = 10_000;
    public static final String INTEGRITY = "keel.catalog.alerts.metadata.integrity";
    public static final String REGULATED_SCHEMA = "keel.catalog.alerts.regulated.schema.change";
    public static final String LARGE_METADATA = "keel.catalog.alerts.large.metadata";

    private final long maxChars;
    private final int maxSnapshots;
    private final Set<String> alerted = Collections.synchronizedSet(Collections.newSetFromMap(
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                    return size() > REMEMBERED;
                }
            }));

    private final Counter integrity;
    private final Counter regulatedSchema;
    private final Counter largeMetadata;

    ServerAlerts(long maxChars, int maxSnapshots, MeterRegistry registry) {
        this.maxChars = maxChars;
        this.maxSnapshots = maxSnapshots;
        this.integrity = Counter.builder(INTEGRITY)
                .description("Metadata files that no longer hash to what their pointer recorded")
                .register(registry);
        this.regulatedSchema = Counter.builder(REGULATED_SCHEMA)
                .description("Commits that changed the schema or partition spec of a regulated table")
                .register(registry);
        this.largeMetadata = Counter.builder(LARGE_METADATA)
                .description("Loaded metadata files over the size or snapshot limit, once per file")
                .register(registry);
    }

    @Override
    public void metadataIntegrity(UUID objectId, String location, String expectedSha256, String actualSha256) {
        LOG.error("metadata-integrity object={} location={} expected-sha256={} actual-sha256={}", objectId, location,
                expectedSha256, actualSha256);
        integrity.increment();
    }

    @Override
    public void regulatedSchemaChange(UUID objectId, UUID commitId) {
        LOG.warn("regulated-schema-change object={} commit={}", objectId, commitId);
        regulatedSchema.increment();
    }

    /** A loaded table's metadata is too large or has too many snapshots (D14). */
    void loaded(UUID tableId, String location, TableStats stats) {
        if ((stats.metadataChars() > maxChars || stats.snapshots() > maxSnapshots) && alerted.add(location)) {
            LOG.warn("large-metadata table={} location={} chars={} snapshots={} (limits {} and {})", tableId,
                    location, stats.metadataChars(), stats.snapshots(), maxChars, maxSnapshots);
            largeMetadata.increment();
        }
    }
}
