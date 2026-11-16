package keel.catalog.server.rest;

import keel.catalog.iceberg.RestRequest.MetricsSummary;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Where client metrics reports go: telemetry, never the catalog database (D13). */
@FunctionalInterface
public interface MetricsSink {

    /**
     * Logs each report to the {@code keel.telemetry.metrics} logger, for the log pipeline to forward: a scan or commit
     * report whole (a scan's filter sanitized), a report of any other type as its type, table name and size alone.
     */
    MetricsSink LOG = new MetricsSink() {
        private final Logger log = LoggerFactory.getLogger("keel.telemetry.metrics");

        @Override
        public void report(String catalog, List<String> namespace, String table, MetricsSummary summary) {
            if (!log.isInfoEnabled()) {
                return;
            }
            if (summary.known()) {
                log.info("{} catalog={} namespace={} table={} snapshot={} report={}", summary.reportType(), catalog,
                        String.join(".", namespace), table, summary.snapshotId(), summary.json());
            } else {
                // The type and table name are the client's own strings: they go only inside the JSON, escaped, so
                // neither can break the line or pose as a known report.
                log.info("unknown-report catalog={} namespace={} table={} bytes={} report={}", catalog,
                        String.join(".", namespace), table, summary.bytes(), summary.json());
            }
        }
    };

    void report(String catalog, List<String> namespace, String table, MetricsSummary summary);
}
