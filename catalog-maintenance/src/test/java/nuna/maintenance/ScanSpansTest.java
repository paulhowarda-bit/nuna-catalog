package nuna.maintenance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.common.KeyValue;
import io.micrometer.common.KeyValues;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import java.net.URI;
import java.net.http.HttpRequest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * The worker's observations (backlog 49), on a registry that keeps each one as it stops: a scan is the root and each
 * part its child; each carries its outcome; a failure records the exception's type, never its message; the
 * {@code uri} of each call to the catalog is its route, naming no catalog or id. {@link WorkerTraceTest} sees the same
 * as spans, joined by the catalog's.
 */
class ScanSpansTest {

    private final List<Observation.Context> stopped = new CopyOnWriteArrayList<>();
    private final ObservationRegistry registry = ObservationRegistry.create();
    private final ScanSpans spans = new ScanSpans(registry);

    ScanSpansTest() {
        registry.observationConfig().observationHandler(new ObservationHandler<>() {
            @Override
            public void onStop(Observation.Context context) {
                stopped.add(context);
            }

            @Override
            public boolean supportsContext(Observation.Context context) {
                return true;
            }
        });
    }

    @Test
    void aScanIsTheRootAndEachPartItsChild() {
        ScanReport report = new ScanReport();
        spans.scan(report, () -> {
            spans.part(ScanSpans.Part.WORK_LIST, KeyValues.of(ScanSpans.SECTION, "tables"), () -> "page",
                    page -> ScanSpans.OK);
            spans.part(ScanSpans.Part.PURGE, KeyValues.empty(), () -> "skipped", outcome -> outcome);
        });

        Observation.Context scan = only(ScanSpans.SCAN);
        assertThat(scan.getContextualName()).isEqualTo(ScanSpans.SCAN_SPAN);
        assertThat(scan.getParentObservation()).as("the trace's root").isNull();
        assertThat(keys(scan)).isEqualTo(Map.of(ScanSpans.OUTCOME, WorkerMetrics.SUCCESS, ScanSpans.EXCEPTION,
                ScanSpans.NONE));

        List<Observation.Context> parts = named(ScanSpans.PART);
        assertThat(parts).extracting(Observation.Context::getContextualName)
                .containsExactly("maintenance work-list", "maintenance purge");
        for (Observation.Context part : parts) {
            assertThat(part.getParentObservation()).as("the scan").isNotNull();
            assertThat(part.getParentObservation().getContextView()).isSameAs(scan);
        }
        assertThat(keys(parts.get(0))).isEqualTo(Map.of(ScanSpans.PART_KEY, "work-list", ScanSpans.OUTCOME, "ok",
                ScanSpans.EXCEPTION, ScanSpans.NONE));
        assertThat(parts.get(0).getHighCardinalityKeyValues()).extracting(KeyValue::getKey)
                .as("on the span alone, so the timer keeps one set of labels").containsExactly(ScanSpans.SECTION);
        assertThat(keys(parts.get(1))).containsEntry(ScanSpans.OUTCOME, "skipped");
    }

    @Test
    void aScanThatCannotReadTheWorkListFails() {
        ScanReport report = new ScanReport();
        spans.scan(report, () -> report.failed(ScanReport.Failure.WORK_LIST, "the tables section",
                new IllegalStateException("down")));

        assertThat(keys(only(ScanSpans.SCAN))).containsEntry(ScanSpans.OUTCOME, WorkerMetrics.FAILURE);
    }

    @Test
    void aFailureRecordsItsTypeNeverItsMessage() {
        String secret = "s3://warehouse/tables/sales/orders";
        IllegalStateException failure = new IllegalStateException("cannot expire " + secret,
                new RuntimeException(secret));

        assertThatThrownBy(() -> spans.scan(new ScanReport(), () -> spans.part(ScanSpans.Part.SNAPSHOT_EXPIRY, () -> {
            throw failure;
        }))).as("the scan's caller sees the failure as it was").isSameAs(failure);

        for (Observation.Context context : stopped) {
            assertThat(keys(context)).containsEntry(ScanSpans.EXCEPTION, "IllegalStateException");
            Throwable recorded = context.getError();
            assertThat(recorded).isInstanceOf(ScanSpans.Failed.class);
            assertThat(recorded.getMessage()).isEqualTo(IllegalStateException.class.getName());
            assertThat(recorded.getCause()).isNull();
            assertThat(recorded.getStackTrace()).isEmpty();
        }
        assertThat(keys(only(ScanSpans.PART))).containsEntry(ScanSpans.OUTCOME, ScanSpans.ERROR);
        assertThat(keys(only(ScanSpans.SCAN))).containsEntry(ScanSpans.OUTCOME, WorkerMetrics.FAILURE);
    }

    @Test
    void aCallsUriIsItsRouteNamingNoCatalogOrId() {
        UUID id = UUID.randomUUID();
        assertThat(route("http://nuna-catalog:8181/management/v1/catalogs/lake/objects/" + id + "/purge-claim"))
                .isEqualTo("/management/v1/catalogs/{catalog}/objects/{id}/purge-claim");
        assertThat(route("http://nuna-catalog:8181/nuna/management/v1/maintenance/tables/" + id + "/assessment"))
                .isEqualTo("/nuna/management/v1/maintenance/tables/{id}/assessment");
        assertThat(route("http://nuna-catalog:8181/management/v1/maintenance/work?section=tables&pageToken=abc"))
                .isEqualTo("/management/v1/maintenance/work");
    }

    private static String route(String uri) {
        return Management.route(HttpRequest.newBuilder(URI.create(uri)).build());
    }

    private List<Observation.Context> named(String name) {
        return stopped.stream().filter(context -> context.getName().equals(name)).toList();
    }

    private Observation.Context only(String name) {
        List<Observation.Context> all = named(name);
        assertThat(all).hasSize(1);
        return all.getFirst();
    }

    private static Map<String, String> keys(Observation.Context context) {
        return context.getLowCardinalityKeyValues().stream()
                .collect(Collectors.toMap(KeyValue::getKey, KeyValue::getValue));
    }
}
