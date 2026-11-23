package nuna.catalog.server.metrics;

import static nuna.catalog.server.metrics.CatalogMetrics.CATALOG;
import static nuna.catalog.server.metrics.CatalogMetrics.COMMITS;
import static nuna.catalog.server.metrics.CatalogMetrics.ENGINE_NAME;
import static nuna.catalog.server.metrics.CatalogMetrics.ERROR_TYPE;
import static nuna.catalog.server.metrics.CatalogMetrics.OPERATION;
import static nuna.catalog.server.metrics.CatalogMetrics.OPERATION_ID;
import static nuna.catalog.server.metrics.CatalogMetrics.OUTCOME;
import static nuna.catalog.server.metrics.CatalogMetrics.REQUIREMENT_TYPE;
import static nuna.catalog.server.metrics.CatalogMetrics.VENDS;
import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import nuna.catalog.core.commit.CommitReply;
import nuna.catalog.core.commit.CommitReply.How;
import nuna.catalog.iceberg.RestRequest;
import nuna.catalog.iceberg.RestRequest.MetricsSummary;
import nuna.catalog.server.rest.ServerAlerts;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The commit outcome split for every reply the engine gives, including the ones a route cannot be made to give
 * without injecting a fault (an unknown outcome, a store outage), the bounds on a client's label values, and the
 * meters' doc. CatalogMetricsRoutesTest drives the routes.
 */
class CatalogMetricsTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final CatalogMetrics metrics = new CatalogMetrics(registry);

    @Test
    void everyReplyOfTheEngineHasItsOutcome() {
        UUID id = UUID.randomUUID();
        List<CommitReply> replies = List.of(
                new CommitReply.Success(id, "s3://b/t/m1.json", "sha", "{}", How.COMMITTED),
                new CommitReply.Success(id, "s3://b/t/m1.json", "sha", "{}", How.UNCHANGED),
                new CommitReply.Success(id, "s3://b/t/m1.json", "sha", "{}", How.REPLAYED),
                new CommitReply.Applied(How.COMMITTED),
                new CommitReply.Failure(409, "CommitFailedException", "Requirement failed: branch main has changed",
                        Optional.of("assert-ref-snapshot-id")),
                new CommitReply.Failure(409, "CommitFailedException", "Commit lost to concurrent commits 4 times"),
                new CommitReply.Failure(404, "NoSuchTableException", "Table does not exist: ns.t"),
                new CommitReply.ServerError("CommitStateUnknownException", "Commit may or may not have happened"),
                new CommitReply.ServerError("ServiceFailureException", "Catalog store failed"),
                new CommitReply.Unavailable("Catalog store unavailable; nothing was applied"));

        replies.forEach(reply -> metrics.commit("trading", "updateTable", reply));

        assertThat(registry.find(COMMITS).counters()).extracting(counter -> List.of(
                        counter.getId().getTag(OUTCOME), counter.getId().getTag(REQUIREMENT_TYPE),
                        counter.getId().getTag(ERROR_TYPE), counter.count()))
                .containsExactlyInAnyOrder(
                        List.of("committed", "none", "none", 2.0),
                        List.of("unchanged", "none", "none", 1.0),
                        List.of("replayed", "none", "none", 1.0),
                        List.of("requirement-failed", "assert-ref-snapshot-id", "CommitFailedException", 1.0),
                        List.of("refused", "none", "CommitFailedException", 1.0),
                        List.of("refused", "none", "NoSuchTableException", 1.0),
                        List.of("unknown", "none", "CommitStateUnknownException", 1.0),
                        List.of("failed", "none", "ServiceFailureException", 1.0),
                        List.of("failed", "none", "ServiceUnavailableException", 1.0));
        assertThat(registry.find(COMMITS).counters()).allSatisfy(counter -> {
            assertThat(counter.getId().getTag(CATALOG)).isEqualTo("trading");
            assertThat(counter.getId().getTag(OPERATION_ID)).isEqualTo("updateTable");
            assertThat(counter.getId().getTag(CatalogMetrics.REF_KIND)).isEqualTo(CatalogMetrics.MAIN);
        });
    }

    @Test
    void aCommitOnABranchIsLabelledByTheRefsKindNeverItsName() {
        UUID id = UUID.randomUUID();
        metrics.commit("trading", CatalogMetrics.BRANCH, "updateTable",
                new CommitReply.Success(id, "s3://b/t/m1.json", "sha", "{}", How.COMMITTED));
        metrics.commit("trading", CatalogMetrics.MAIN, "updateTable",
                new CommitReply.Success(id, "s3://b/t/m2.json", "sha", "{}", How.COMMITTED));

        assertThat(registry.find(COMMITS).counters()).extracting(counter -> counter.getId().getTag(
                CatalogMetrics.REF_KIND)).containsExactlyInAnyOrder(CatalogMetrics.BRANCH, CatalogMetrics.MAIN);
        assertThat(CatalogMetrics.LABELS).contains(CatalogMetrics.REF_KIND);
    }

    @Test
    void engineNamesAndOperationsStayBounded() {
        IntStream.range(0, CatalogMetrics.MAX_ENGINES + 5).forEach(i -> report("engine-" + i, "append"));
        report("not/an engine\nname", "append");
        report("", "append");
        report("engine-0", "made-up-operation");

        Set<String> engines = tagValues(ENGINE_NAME);
        assertThat(engines).hasSize(CatalogMetrics.MAX_ENGINES + 2)
                .contains("engine-0", "engine-" + (CatalogMetrics.MAX_ENGINES - 1), CatalogMetrics.OTHER,
                        CatalogMetrics.NONE)
                .doesNotContain("engine-" + CatalogMetrics.MAX_ENGINES);
        assertThat(tagValues(OPERATION)).containsExactlyInAnyOrder("append", CatalogMetrics.OTHER);
    }

    @Test
    void theDocNamesEveryMeterAndLabel() throws IOException {
        String doc = Files.readString(Path.of(System.getProperty("repo.root"), "docs", "observability",
                "catalog-metrics.md"));
        Set<String> reports = new TreeSet<>();
        Stream.of(MetricsSummary.SCAN, MetricsSummary.COMMIT).forEach(type -> RestRequest.reportResults(type)
                .keySet().forEach(result -> reports.add(CatalogMetrics.REPORT + result.replace('-', '.'))));
        List<String> meters = Stream.concat(Stream.of(RequestMetrics.REQUESTS, COMMITS, VENDS, MetricsWiring.CACHE,
                ServerAlerts.INTEGRITY, ServerAlerts.REGULATED_SCHEMA, ServerAlerts.LARGE_METADATA, RefGauges.REFS,
                RefGauges.EXPIRING), reports.stream())
                .toList();

        assertThat(meters).hasSizeGreaterThan(40).filteredOn(meter -> !doc.contains("`" + meter + "`"))
                .as("meters the doc does not name").isEmpty();
        assertThat(CatalogMetrics.LABELS).filteredOn(label -> !doc.contains("`" + label + "`"))
                .as("labels the doc does not name").isEmpty();
        assertThat(Pattern.compile("`(nuna\\.catalog\\.report\\.[a-z.]+)`").matcher(doc).results()
                .map(match -> match.group(1))).as("report meters the doc names that do not exist")
                .allMatch(reports::contains);
    }

    private void report(String engine, String operation) {
        metrics.report("trading", RestRequest.reportMetrics("""
                {"report-type":"commit-report","table-name":"trading.ns.t","snapshot-id":1,"sequence-number":1,
                 "operation":"%s","metrics":{"attempts":{"unit":"count","value":1}},
                 "metadata":{"engine-name":"%s"}}""".formatted(operation, engine.replace("\n", "\\n"))));
    }

    private Set<String> tagValues(String key) {
        return registry.getMeters().stream().map(Meter::getId).map(id -> id.getTag(key))
                .filter(value -> value != null).collect(Collectors.toSet());
    }
}
