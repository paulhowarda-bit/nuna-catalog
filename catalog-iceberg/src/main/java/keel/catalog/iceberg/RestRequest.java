package keel.catalog.iceberg;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.exceptions.UnprocessableEntityException;
import org.apache.iceberg.expressions.ExpressionUtil;
import org.apache.iceberg.expressions.Expressions;
import org.apache.iceberg.metrics.CommitMetrics;
import org.apache.iceberg.metrics.CommitMetricsResult;
import org.apache.iceberg.metrics.CommitReport;
import org.apache.iceberg.metrics.DefaultMetricsContext;
import org.apache.iceberg.metrics.ImmutableCommitReport;
import org.apache.iceberg.metrics.ImmutableScanReport;
import org.apache.iceberg.metrics.MetricsReport;
import org.apache.iceberg.metrics.ScanMetrics;
import org.apache.iceberg.metrics.ScanMetricsResult;
import org.apache.iceberg.metrics.ScanReport;
import org.apache.iceberg.rest.requests.CommitTransactionRequest;
import org.apache.iceberg.rest.requests.CommitTransactionRequestParser;
import org.apache.iceberg.rest.requests.CreateNamespaceRequest;
import org.apache.iceberg.rest.requests.CreateTableRequest;
import org.apache.iceberg.rest.requests.CreateViewRequest;
import org.apache.iceberg.rest.requests.RegisterTableRequest;
import org.apache.iceberg.rest.requests.RegisterTableRequestParser;
import org.apache.iceberg.rest.requests.RegisterViewRequest;
import org.apache.iceberg.rest.requests.RegisterViewRequestParser;
import org.apache.iceberg.rest.requests.RenameTableRequest;
import org.apache.iceberg.rest.requests.ReportMetricsRequest;
import org.apache.iceberg.rest.requests.ReportMetricsRequestParser;
import org.apache.iceberg.rest.requests.UpdateNamespacePropertiesRequest;
import org.apache.iceberg.rest.requests.UpdateTableRequestParser;
import org.apache.iceberg.util.JsonUtil;

/**
 * Reads the Iceberg REST request bodies the routes need, with iceberg-core's own request classes, and
 * hands back plain values. A body that is not a valid request throws {@link InvalidRequest}.
 */
public final class RestRequest {

    private RestRequest() {
    }

    /** A namespace or object name: namespace levels, then (for an object) its name. */
    public record Identifier(List<String> namespace, String name) {
        public Identifier {
            namespace = List.copyOf(namespace);
            Objects.requireNonNull(name, "name");
        }
    }

    public record NewNamespace(List<String> levels, Map<String, String> properties) {
        public NewNamespace {
            levels = List.copyOf(levels);
            properties = Map.copyOf(properties);
        }
    }

    public record PropertyChanges(Set<String> removals, Map<String, String> updates) {
        public PropertyChanges {
            removals = Set.copyOf(removals);
            updates = Map.copyOf(updates);
        }
    }

    public record Rename(Identifier source, Identifier destination) {
    }

    public record Registration(String name, String metadataLocation, boolean overwrite) {
    }

    /** The parts of a create-table request a route needs before the commit engine sees it. */
    public record NewTable(String name, boolean stageCreate) {
    }

    /** One table's part of a transaction: the table it names, and its commit request as a commit route takes it. */
    public record TableCommit(Identifier identifier, String json) {
    }

    /**
     * What the server forwards to telemetry from a metrics report (D13). {@code bytes} is the body's size in UTF-8.
     * For a scan or commit report, {@code json} is the whole report, a scan's filter sanitized. For a type this server
     * does not know, {@code reportType} and {@code tableName} ("" when none was given) are the client's own strings,
     * and {@code json} holds those two alone: nothing else of the body leaves the catalog.
     *
     * @param operation  a commit report's operation as the client sent it ({@code append} and so on), "" otherwise
     * @param engineName the {@code engine-name} in the report's metadata as the client sent it, "" when none
     * @param results    a scan or commit report's results that the spec names; none for any other type
     */
    public record MetricsSummary(String reportType, String tableName, long snapshotId, long bytes, String json,
                                 String operation, String engineName, List<Result> results) {
        public static final String SCAN = "scan-report";
        public static final String COMMIT = "commit-report";

        public MetricsSummary {
            results = List.copyOf(results);
        }

        /** A scan or commit report, whose type name is the spec's, not one the client chose. */
        public boolean known() {
            return SCAN.equals(reportType) || COMMIT.equals(reportType);
        }
    }

    /**
     * One result of a scan or commit report, by the name the spec gives it: {@code total-duration}, {@code attempts},
     * {@code added-data-files}, {@code skipped-data-manifests} and so on. A result the spec does not name, and a
     * counter in a unit other than the one the spec gives it, are left out.
     */
    public sealed interface Result permits Timed, Counted {
        String name();
    }

    /** A timer result: the time it measured in all, converted by the time unit the report states, over count events. */
    public record Timed(String name, Duration total, long count) implements Result {
    }

    /** A counter result, in the unit the report states and the spec gives it: {@code bytes} or {@code count}. */
    public record Counted(String name, long value, String unit) implements Result {
    }

    /** A request the server cannot accept: {@code status} is 400 or 422, {@code type} the Iceberg error class. */
    public static final class InvalidRequest extends RuntimeException {
        private final int status;
        private final String type;

        InvalidRequest(int status, String type, String message) {
            super(message);
            this.status = status;
            this.type = type;
        }

        public int status() {
            return status;
        }

        public String type() {
            return type;
        }
    }

    public static NewNamespace createNamespace(String json) {
        CreateNamespaceRequest request = read(json, CreateNamespaceRequest.class);
        Namespace namespace = request.namespace();
        if (namespace == null) {
            throw badRequest("Invalid namespace: null");
        }
        return new NewNamespace(levels(namespace), properties(request.properties()));
    }

    /** Keys both set and removed are a 422, as the spec and iceberg-core's own request say. */
    public static PropertyChanges updateNamespaceProperties(String json) {
        UpdateNamespacePropertiesRequest request = read(json, UpdateNamespacePropertiesRequest.class);
        Map<String, String> updates = properties(request.updates());
        Set<String> removals = new LinkedHashSet<>();
        for (String key : request.removals() == null ? List.<String>of() : request.removals()) {
            if (key == null) {
                throw badRequest("Invalid key: null");
            }
            removals.add(key);
        }
        Set<String> both = new LinkedHashSet<>(removals);
        both.retainAll(updates.keySet());
        if (!both.isEmpty()) {
            throw new InvalidRequest(422, "UnprocessableEntityException",
                    "Invalid namespace update, cannot simultaneously set and remove keys: " + both);
        }
        return new PropertyChanges(removals, updates);
    }

    public static Rename rename(String json) {
        RenameTableRequest request = read(json, RenameTableRequest.class);
        if (request.source() == null || request.destination() == null) {
            throw badRequest("Invalid rename: source and destination are required");
        }
        return new Rename(identifier(request.source()), identifier(request.destination()));
    }

    public static Registration register(String json) {
        RegisterTableRequest request = guarded(() -> RegisterTableRequestParser.fromJson(json));
        return new Registration(request.name(), request.metadataLocation(), request.overwrite());
    }

    /** {@code POST .../register-view}: the name and metadata file of a view that already exists. */
    public static Registration registerView(String json) {
        RegisterViewRequest request = guarded(() -> RegisterViewRequestParser.fromJson(json));
        return new Registration(request.name(), request.metadataLocation(), false);
    }

    public static NewTable createTable(String json) {
        CreateTableRequest request = read(json, CreateTableRequest.class);
        guarded(() -> {
            request.validate();
            return request;
        });
        return new NewTable(request.name(), request.stageCreate());
    }

    public static String createViewName(String json) {
        CreateViewRequest request = read(json, CreateViewRequest.class);
        if (request.name() == null || request.name().isEmpty()) {
            throw badRequest("Invalid view name: " + request.name());
        }
        return request.name();
    }

    /**
     * A commit-transaction request's table changes, in request order (D15). Each must name its table, and
     * each is parsed, so an unknown requirement or update anywhere refuses the whole request with 400.
     */
    public static List<TableCommit> commitTransaction(String json) {
        CommitTransactionRequest request = guarded(() -> {
            CommitTransactionRequest parsed = CommitTransactionRequestParser.fromJson(json);
            parsed.validate();
            return parsed;
        });
        return request.tableChanges().stream()
                .map(change -> new TableCommit(identifier(change.identifier()), UpdateTableRequestParser.toJson(change)))
                .toList();
    }

    /**
     * A metrics report as it may leave the catalog (D13). A scan report's filter is sanitized here, whatever the
     * client sent: the stock Java client sanitizes it before sending, but nothing obliges another client to. A type
     * the parser does not know keeps only the type and table name it was sent with; its values cannot be sanitized.
     */
    public static MetricsSummary reportMetrics(String json) {
        long bytes = json.getBytes(StandardCharsets.UTF_8).length;
        return guarded(() -> JsonUtil.parse(json, node -> {
            ReportMetricsRequest request = ReportMetricsRequestParser.fromJson(node);
            return switch (request.report()) {
                case ScanReport scan -> {
                    String compact = compact(ImmutableScanReport.copyOf(scan)
                            .withFilter(ExpressionUtil.sanitize(scan.filter())));
                    yield new MetricsSummary(MetricsSummary.SCAN, scan.tableName(), scan.snapshotId(), bytes, compact,
                            "", engineName(scan.metadata()), results(compact, SCAN_RESULTS));
                }
                case CommitReport commit -> {
                    String compact = compact(commit);
                    yield new MetricsSummary(MetricsSummary.COMMIT, commit.tableName(), commit.snapshotId(), bytes,
                            compact, commit.operation() == null ? "" : commit.operation(),
                            engineName(commit.metadata()), results(compact, COMMIT_RESULTS));
                }
                case null, default -> unknownReport(node, bytes);
            };
        }));
    }

    private static final String TIMER = "timer";

    /**
     * The results the spec gives each report type, as "timer" or a counter's unit, read from iceberg-core's own
     * reports with every result present: a scan's from fresh scan metrics, a commit's from a snapshot summary that
     * holds every key.
     */
    private static final Map<String, String> SCAN_RESULTS = specResults(ImmutableScanReport.builder()
            .tableName("spec").snapshotId(0).filter(Expressions.alwaysTrue()).schemaId(0)
            .scanMetrics(ScanMetricsResult.fromScanMetrics(ScanMetrics.of(new DefaultMetricsContext())))
            .build());
    private static final Map<String, String> COMMIT_RESULTS = specResults(ImmutableCommitReport.builder()
            .tableName("spec").snapshotId(0).sequenceNumber(0).operation("append")
            .commitMetrics(CommitMetricsResult.from(CommitMetrics.of(new DefaultMetricsContext()),
                    new AbstractMap<String, String>() {
                        @Override
                        public Set<Map.Entry<String, String>> entrySet() {
                            return Set.of();
                        }

                        @Override
                        public boolean containsKey(Object key) {
                            return true;
                        }

                        @Override
                        public String get(Object key) {
                            return "0";
                        }
                    }))
            .build());

    /**
     * The results the spec gives a scan or commit report, by name: {@code timer}, or the unit of a counter
     * ({@code bytes} or {@code count}). Empty for any other report type.
     */
    public static Map<String, String> reportResults(String reportType) {
        return switch (reportType) {
            case MetricsSummary.SCAN -> SCAN_RESULTS;
            case MetricsSummary.COMMIT -> COMMIT_RESULTS;
            default -> Map.of();
        };
    }

    private static Map<String, String> specResults(MetricsReport report) {
        Map<String, String> results = new LinkedHashMap<>();
        metrics(compact(report)).properties().forEach(result -> results.put(result.getKey(),
                result.getValue().has("time-unit") ? TIMER : result.getValue().get("unit").asText()));
        return Map.copyOf(results);
    }

    /**
     * The results of a report as the parser wrote it again, so only names it knows: a timer's total converted by the
     * time unit it states, a counter kept only in the unit the spec gives it.
     */
    private static List<Result> results(String compact, Map<String, String> spec) {
        List<Result> results = new ArrayList<>();
        metrics(compact).properties().forEach(entry -> {
            String name = entry.getKey();
            JsonNode result = entry.getValue();
            String unit = spec.get(name);
            if (TIMER.equals(unit)) {
                TimeUnit timeUnit = TimeUnit.valueOf(result.get("time-unit").asText().toUpperCase(Locale.ROOT));
                results.add(new Timed(name, Duration.of(result.get("total-duration").asLong(), timeUnit.toChronoUnit()),
                        result.get("count").asLong()));
            } else if (unit != null && unit.equals(result.get("unit").asText())) {
                results.add(new Counted(name, result.get("value").asLong(), unit));
            }
        });
        return results;
    }

    private static JsonNode metrics(String compact) {
        try {
            return Metadata.REQUESTS.readTree(compact).path("metrics");
        } catch (JsonProcessingException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private static String engineName(Map<String, String> metadata) {
        String name = metadata == null ? null : metadata.get("engine-name");
        return name == null ? "" : name;
    }

    /** Written again by the parser: one compact line, whatever whitespace the client sent. */
    private static String compact(MetricsReport report) {
        return ReportMetricsRequestParser.toJson(ReportMetricsRequest.of(report));
    }

    private static MetricsSummary unknownReport(JsonNode node, long bytes) {
        String type = JsonUtil.getString("report-type", node);
        String table = JsonUtil.getStringOrNull("table-name", node);
        String kept = JsonUtil.generate(gen -> {
            gen.writeStartObject();
            gen.writeStringField("report-type", type);
            if (table != null) {
                gen.writeStringField("table-name", table);
            }
            gen.writeEndObject();
        }, false);
        return new MetricsSummary(type, table == null ? "" : table, -1, bytes, kept, "", "", List.of());
    }

    /**
     * A namespace from its path segment: levels joined by the unit separator 0x1F, the default the spec
     * names and the only one this server advertises.
     */
    public static List<String> namespaceLevels(String encoded) {
        if (encoded == null || encoded.isEmpty()) {
            throw badRequest("Invalid namespace: empty");
        }
        List<String> levels = Arrays.asList(encoded.split("\u001f", -1));
        for (String level : levels) {
            if (level.isEmpty()) {
                throw badRequest("Invalid namespace: " + String.join(".", levels));
            }
        }
        return levels;
    }

    private static List<String> levels(Namespace namespace) {
        if (namespace.isEmpty()) {
            throw badRequest("Invalid namespace: empty");
        }
        for (String level : namespace.levels()) {
            if (level == null || level.isEmpty() || level.indexOf('\u001f') >= 0) {
                throw badRequest("Invalid namespace: " + namespace);
            }
        }
        return List.of(namespace.levels());
    }

    private static Identifier identifier(TableIdentifier identifier) {
        if (identifier.name() == null || identifier.name().isEmpty()) {
            throw badRequest("Invalid name: " + identifier);
        }
        return new Identifier(levels(identifier.namespace()), identifier.name());
    }

    private static Map<String, String> properties(Map<String, String> given) {
        if (given == null) {
            return Map.of();
        }
        Map<String, String> copy = new HashMap<>();
        given.forEach((key, value) -> {
            if (key == null) {
                throw badRequest("Invalid key: null");
            }
            if (value == null) {
                throw badRequest("Invalid value: null");
            }
            copy.put(key, value);
        });
        return copy;
    }

    private static <T> T read(String json, Class<T> type) {
        return guarded(() -> {
            try {
                T value = Metadata.REQUESTS.readValue(json, type);
                if (value == null) {
                    throw badRequest("Missing request body");
                }
                return value;
            } catch (JsonProcessingException e) {
                throw badRequest("Malformed request: " + e.getOriginalMessage());
            }
        });
    }

    /**
     * iceberg-core reports a bad request as IllegalArgumentException (UnsupportedOperationException for an
     * unknown update action), or 422 for clashing property keys.
     */
    private static <T> T guarded(Supplier<T> work) {
        try {
            return work.get();
        } catch (InvalidRequest e) {
            throw e;
        } catch (UnprocessableEntityException e) {
            throw new InvalidRequest(422, "UnprocessableEntityException", e.getMessage());
        } catch (IllegalArgumentException | IllegalStateException | NullPointerException
                 | UnsupportedOperationException | java.io.UncheckedIOException e) {
            throw badRequest(e.getMessage());
        } catch (RuntimeException e) {
            if (e.getCause() instanceof UnprocessableEntityException unprocessable) {
                throw new InvalidRequest(422, "UnprocessableEntityException", unprocessable.getMessage());
            }
            throw e;
        }
    }

    static InvalidRequest badRequest(String message) {
        return new InvalidRequest(400, "BadRequestException", message);
    }
}
