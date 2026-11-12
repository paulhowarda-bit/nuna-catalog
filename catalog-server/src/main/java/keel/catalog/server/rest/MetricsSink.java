package keel.catalog.server.rest;

import keel.catalog.iceberg.RestRequest.MetricsSummary;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Where client metrics reports go: telemetry, never the catalog database (D13). */
@FunctionalInterface
public interface MetricsSink {

    /** Logs each report to the {@code keel.telemetry.metrics} logger, for the log pipeline to forward. */
    MetricsSink LOG = new MetricsSink() {
        private final Logger log = LoggerFactory.getLogger("keel.telemetry.metrics");

        @Override
        public void report(String catalog, List<String> namespace, String table, MetricsSummary summary) {
            if (log.isInfoEnabled()) {
                log.info("{} catalog={} namespace={} table={} snapshot={} report={}", summary.reportType(), catalog,
                        String.join(".", namespace), table, summary.snapshotId(), summary.json());
            }
        }
    };

    void report(String catalog, List<String> namespace, String table, MetricsSummary summary);
}
