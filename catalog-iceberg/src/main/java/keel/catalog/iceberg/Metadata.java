package keel.catalog.iceberg;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import keel.catalog.iceberg.Facts.TableFacts;
import keel.catalog.iceberg.Facts.ViewFacts;
import keel.catalog.iceberg.Outcome.BadRequest;
import keel.catalog.iceberg.Outcome.NewMetadata;
import keel.catalog.iceberg.Outcome.NoChange;
import keel.catalog.iceberg.Outcome.NotFound;
import keel.catalog.iceberg.Outcome.RequirementFailed;
import java.io.UncheckedIOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.apache.iceberg.EnvironmentContext;
import org.apache.iceberg.MetadataUpdate.UpgradeFormatVersion;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.RetryableValidationException;
import org.apache.iceberg.SortOrder;
import org.apache.iceberg.TableMetadata;
import org.apache.iceberg.TableMetadataParser;
import org.apache.iceberg.TableProperties;
import org.apache.iceberg.UpdateRequirement;
import org.apache.iceberg.UpdateRequirement.AssertTableDoesNotExist;
import org.apache.iceberg.UpdateRequirementParser;
import org.apache.iceberg.exceptions.CommitFailedException;
import org.apache.iceberg.exceptions.ValidationException;
import org.apache.iceberg.rest.RESTSerializers;
import org.apache.iceberg.rest.requests.CreateTableRequest;
import org.apache.iceberg.rest.requests.CreateViewRequest;
import org.apache.iceberg.rest.requests.UpdateTableRequest;
import org.apache.iceberg.rest.requests.UpdateTableRequestParser;
import org.apache.iceberg.view.ImmutableViewVersion;
import org.apache.iceberg.view.SQLViewRepresentation;
import org.apache.iceberg.view.ViewMetadata;
import org.apache.iceberg.view.ViewMetadataParser;
import org.apache.iceberg.view.ViewVersion;

/**
 * Applies Iceberg REST create and commit requests to table and view metadata with iceberg-core's own
 * builders, following the 1.12.0 reference handlers step for step (D3). Pure: it reads nothing and
 * writes nothing. The caller supplies the current metadata, writes the new file and swaps the pointer,
 * so the commit loop can apply the same request again after losing the swap, and the rules layer can
 * judge the result before anything is written.
 *
 * <p>Request faults come back as an {@link Outcome}. Current metadata that will not parse is the
 * caller's fault and throws.
 */
public final class Metadata {

    /** New tables are v3 unless the request names a format version (D12). */
    public static final int DEFAULT_TABLE_FORMAT_VERSION = 3;

    /** No table is created at or upgraded to a higher version until v4 is ratified (D12). */
    public static final int MAX_TABLE_FORMAT_VERSION = 3;

    // Configured as iceberg-core's own REST object mapper is, so request bodies parse as they do there.
    static final ObjectMapper REQUESTS = new ObjectMapper()
            .setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .setPropertyNamingStrategy(new PropertyNamingStrategies.KebabCaseStrategy());

    static {
        RESTSerializers.registerAll(REQUESTS);
    }

    private Metadata() {
    }

    /**
     * {@code POST .../tables}: a new table from a create-table request.
     *
     * @param tableUuid the catalog-assigned table UUID
     * @param location  the catalog-assigned table location; a location named in the request is ignored, so
     *                  the response shows where the table really is (D28)
     */
    public static Outcome createTable(String requestJson, String tableUuid, String location) {
        return guarded(() -> {
            CreateTableRequest request = read(requestJson, CreateTableRequest.class);
            request.validate();
            Map<String, String> properties = new HashMap<>();
            properties.put(TableProperties.FORMAT_VERSION, String.valueOf(DEFAULT_TABLE_FORMAT_VERSION));
            properties.putAll(request.properties());
            TableMetadata created = TableMetadata.newTableMetadata(
                    request.schema(),
                    request.spec() != null ? request.spec() : PartitionSpec.unpartitioned(),
                    request.writeOrder() != null ? request.writeOrder() : SortOrder.unsorted(),
                    location,
                    properties);
            return table(null, TableMetadata.buildFrom(created).assignUUID(tableUuid).build());
        });
    }

    /**
     * {@code POST .../register}: a table whose metadata file already exists (D34). Nothing is applied: the
     * file is read so the rules can judge it and the new object can take its UUID and location.
     */
    public static Outcome registerTable(String metadataJson, String metadataLocation) {
        return guarded(() -> {
            TableMetadata metadata = TableMetadataParser.fromJson(metadataLocation, metadataJson);
            if (metadata.formatVersion() > MAX_TABLE_FORMAT_VERSION) {
                return new BadRequest("Table format version " + metadata.formatVersion()
                        + " is not supported; the highest is " + MAX_TABLE_FORMAT_VERSION);
            }
            return new NewMetadata(metadataJson, null, facts(metadata));
        });
    }

    /**
     * {@code POST .../register-view}: an existing view metadata file, taken as it is. Where it may live is
     * the commit rules' concern (D28), as for a registered table.
     */
    public static Outcome registerView(String metadataJson, String metadataLocation) {
        return guarded(() -> {
            ViewMetadata metadata = ViewMetadataParser.fromJson(metadataLocation, metadataJson);
            return new NewMetadata(metadataJson, null, facts(metadata));
        });
    }

    /**
     * {@code POST .../tables/{table}}: an update-table request. A request whose requirements include
     * {@code assert-create} creates the table (a staged create being committed).
     *
     * @param currentJson     the current metadata, or null when the table does not exist
     * @param currentLocation where {@code currentJson} is stored; it enters the new metadata log
     */
    public static Outcome commitTable(String requestJson, String currentJson, String currentLocation) {
        TableMetadata base = currentJson == null ? null : TableMetadataParser.fromJson(currentLocation, currentJson);
        return guarded(() -> {
            UpdateTableRequest request = UpdateTableRequestParser.fromJson(requestJson);
            List<UpdateRequirement> requirements = request.requirements();
            if (requirements.stream().anyMatch(AssertTableDoesNotExist.class::isInstance)) {
                List<UpdateRequirement> others = requirements.stream()
                        .filter(r -> !(r instanceof AssertTableDoesNotExist)).toList();
                if (!others.isEmpty()) {
                    return new BadRequest("Invalid create requirements: " + others);
                }
                require(requirements, r -> r.validate(base));
                int version = request.updates().stream()
                        .filter(UpgradeFormatVersion.class::isInstance)
                        .map(u -> ((UpgradeFormatVersion) u).formatVersion())
                        .findFirst().orElse(DEFAULT_TABLE_FORMAT_VERSION);
                TableMetadata.Builder builder = TableMetadata.buildFromEmpty(version);
                request.updates().forEach(u -> u.applyTo(builder));
                return table(null, builder.build());
            }
            if (base == null) {
                return new NotFound();
            }
            require(requirements, r -> r.validate(base));
            TableMetadata.Builder builder = TableMetadata.buildFrom(base);
            try {
                request.updates().forEach(u -> u.applyTo(builder));
            } catch (RetryableValidationException e) {
                throw new CommitFailedException(e, "Validation failed, please retry: %s", e.getMessage());
            }
            TableMetadata updated = builder.build();
            return updated.changes().isEmpty() ? new NoChange() : table(base, updated);
        });
    }

    /**
     * {@code POST .../views}: a new view from a create-view request. As in the reference handler, the new
     * version takes the request's schema, SQL representations and defaults, and is version 1 with the
     * server's environment as its summary.
     *
     * @param viewUuid        the catalog-assigned view UUID
     * @param location        the catalog-assigned view location; a location named in the request is ignored
     * @param timestampMillis the new version's timestamp
     */
    public static Outcome createView(String requestJson, String viewUuid, String location,
                                     long timestampMillis) {
        return guarded(() -> {
            CreateViewRequest request = read(requestJson, CreateViewRequest.class);
            request.validate();
            ViewVersion requested = request.viewVersion();
            List<String> unsupported = requested.representations().stream()
                    .filter(r -> !(r instanceof SQLViewRepresentation)).map(r -> r.type()).toList();
            if (!unsupported.isEmpty()) {
                return new BadRequest("Found unsupported view representations: " + unsupported);
            }
            if (requested.representations().isEmpty()) {
                return new BadRequest("Cannot create view without specifying a query");
            }
            ViewVersion version = ImmutableViewVersion.builder()
                    .versionId(1)
                    .schemaId(request.schema().schemaId())
                    .addAllRepresentations(requested.representations())
                    .defaultNamespace(requested.defaultNamespace())
                    .defaultCatalog(requested.defaultCatalog())
                    .timestampMillis(timestampMillis)
                    .putAllSummary(EnvironmentContext.get())
                    .build();
            ViewMetadata created = ViewMetadata.builder()
                    .assignUUID(viewUuid)
                    .setProperties(new HashMap<>(request.properties()))
                    .setLocation(location)
                    .setCurrentVersion(version, request.schema())
                    .build();
            return new NewMetadata(ViewMetadataParser.toJson(created), null, facts(created));
        });
    }

    /** {@code POST .../views/{view}}: a commit request, which is how clients replace a view. */
    public static Outcome commitView(String requestJson, String currentJson) {
        ViewMetadata base = ViewMetadataParser.fromJson(currentJson);
        return guarded(() -> {
            UpdateTableRequest request = UpdateTableRequestParser.fromJson(requestJson);
            require(request.requirements(), r -> r.validate(base));
            ViewMetadata.Builder builder = ViewMetadata.buildFrom(base);
            request.updates().forEach(u -> u.applyTo(builder));
            ViewMetadata updated = builder.build();
            return updated.changes().isEmpty()
                    ? new NoChange()
                    : new NewMetadata(ViewMetadataParser.toJson(updated), facts(base), facts(updated));
        });
    }

    private static Outcome table(TableMetadata before, TableMetadata after) {
        if (after.formatVersion() > MAX_TABLE_FORMAT_VERSION) {
            return new BadRequest("Table format version " + after.formatVersion()
                    + " is not supported; the highest is " + MAX_TABLE_FORMAT_VERSION);
        }
        Optional<String> problem = Backstops.check(before, after);
        if (problem.isPresent()) {
            return new BadRequest(problem.get());
        }
        return new NewMetadata(TableMetadataParser.toJson(after), before == null ? null : facts(before),
                facts(after));
    }

    /** A commit request's requirements and updates, as the JSON arrays the client sent, for the journal. */
    public record Journal(String requirementsJson, String updatesJson) {
    }

    /** Splits an update request that a commit method has already accepted into its journal parts. */
    public static Journal journal(String updateRequestJson) {
        try {
            JsonNode request = REQUESTS.readTree(updateRequestJson);
            return new Journal(arrayOrEmpty(request.get("requirements")), arrayOrEmpty(request.get("updates")));
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * One table metadata file as a link of its metadata-log chain, for the DR reconcile (D37).
     *
     * @param previous the file it replaced: the newest entry of its metadata log, empty for a first version
     */
    public record TableLink(String uuid, TableFacts facts, Optional<String> previous) {
    }

    /** Reads a table metadata file's UUID, facts and predecessor; throws on anything that is not table metadata. */
    public static TableLink tableLink(String metadataJson, String metadataLocation) {
        TableMetadata metadata = TableMetadataParser.fromJson(metadataLocation, metadataJson);
        List<TableMetadata.MetadataLogEntry> log = metadata.previousFiles();
        return new TableLink(metadata.uuid(), facts(metadata),
                log.isEmpty() ? Optional.empty() : Optional.of(log.getLast().file()));
    }

    private static String arrayOrEmpty(JsonNode node) {
        return node == null || node.isNull() ? "[]" : node.toString();
    }

    private static TableFacts facts(TableMetadata metadata) {
        Map<Long, Long> snapshots = new LinkedHashMap<>();
        metadata.snapshots().forEach(s -> snapshots.put(s.snapshotId(), s.timestampMillis()));
        return new TableFacts(metadata.uuid(), metadata.formatVersion(), metadata.location(),
                Map.copyOf(metadata.properties()), metadata.currentSchemaId(), metadata.defaultSpecId(),
                Collections.unmodifiableMap(snapshots));
    }

    private static ViewFacts facts(ViewMetadata metadata) {
        return new ViewFacts(metadata.uuid(), metadata.formatVersion(), metadata.location(),
                Map.copyOf(metadata.properties()), metadata.currentSchemaId(), metadata.currentVersionId());
    }

    private static <T> T read(String json, Class<T> type) {
        try {
            return REQUESTS.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Maps iceberg-core's failures to outcomes as its reference adapter maps them to statuses, except that
     * a dropped view dialect (IllegalStateException) and an update of the wrong kind
     * (UnsupportedOperationException) are 400 here, where the reference answers 500 and 406. The 400s the
     * reference also sends keep its error type, the exception's class name.
     */
    private static Outcome guarded(Supplier<Outcome> work) {
        try {
            return work.get();
        } catch (Unmet e) {
            return new RequirementFailed(e.getMessage(), Optional.of(e.type));
        } catch (CommitFailedException e) {
            return new RequirementFailed(e.getMessage());
        } catch (ValidationException | IllegalArgumentException | IllegalStateException
                 | UnsupportedOperationException | UncheckedIOException e) {
            boolean referenceType = e instanceof IllegalArgumentException || e instanceof ValidationException;
            return new BadRequest(referenceType ? e.getClass().getSimpleName() : "BadRequestException", e.getMessage());
        }
    }

    /** Checks each requirement in turn; the first that does not hold is named in the outcome. */
    private static void require(List<UpdateRequirement> requirements, Consumer<UpdateRequirement> check) {
        for (UpdateRequirement requirement : requirements) {
            try {
                check.accept(requirement);
            } catch (CommitFailedException e) {
                throw new Unmet(requirement, e);
            }
        }
    }

    /** A requirement that does not hold, with its type as the spec names it ({@code assert-ref-snapshot-id}...). */
    private static final class Unmet extends RuntimeException {
        private final String type;

        Unmet(UpdateRequirement requirement, CommitFailedException cause) {
            super(cause.getMessage(), cause);
            try {
                this.type = REQUESTS.readTree(UpdateRequirementParser.toJson(requirement)).get("type").asText();
            } catch (JsonProcessingException e) {
                throw new UncheckedIOException(e);
            }
        }
    }
}
