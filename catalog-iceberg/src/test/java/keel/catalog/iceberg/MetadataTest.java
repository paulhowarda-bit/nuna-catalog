package keel.catalog.iceberg;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import keel.catalog.iceberg.Facts.TableFacts;
import keel.catalog.iceberg.Facts.ViewFacts;
import keel.catalog.iceberg.Outcome.BadRequest;
import keel.catalog.iceberg.Outcome.NewMetadata;
import keel.catalog.iceberg.Outcome.NoChange;
import keel.catalog.iceberg.Outcome.NotFound;
import keel.catalog.iceberg.Outcome.RequirementFailed;
import java.io.IOException;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** The adapter's own decisions, each pinned on its own; GoldenTest pins the metadata it writes. */
class MetadataTest {

    private static final String UUID = "c0000000-0000-4000-8000-00000000000c";
    private static final String LOCATION = "s3://warehouse/ns/positions";
    private static final String SCHEMA = """
            {"type": "struct", "schema-id": 0,
             "fields": [{"id": 1, "name": "id", "required": true, "type": "long"}]}""";
    private static final String NOTHING = """
            {"requirements": [], "updates": []}""";

    // D12: format versions

    @Test
    void newTablesAreV3() {
        NewMetadata created = (NewMetadata) Metadata.createTable(createTable("{}"), UUID, LOCATION);

        assertThat(created.after().formatVersion()).isEqualTo(Metadata.DEFAULT_TABLE_FORMAT_VERSION).isEqualTo(3);
        assertThat(created.json()).contains("\"format-version\":3");
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3})
    void createHonoursTheRequestedFormatVersion(int version) {
        Outcome outcome = Metadata.createTable(createTable("{\"format-version\": \"" + version + "\"}"), UUID, LOCATION);

        assertThat(((NewMetadata) outcome).after().formatVersion()).isEqualTo(version);
    }

    @Test
    void createAtV4IsABadRequest() {
        assertThat(Metadata.createTable(createTable("{\"format-version\": \"4\"}"), UUID, LOCATION))
                .isEqualTo(new BadRequest("Table format version 4 is not supported; the highest is 3"));
    }

    @Test
    void upgradeToV4IsABadRequest() {
        Outcome outcome = Metadata.commitTable("""
                {"requirements": [], "updates": [{"action": "upgrade-format-version", "format-version": 4}]}""",
                base("v3-append"), location("v3-append"));

        assertThat(outcome).isEqualTo(new BadRequest("Table format version 4 is not supported; the highest is 3"));
    }

    @Test
    void stagedCreateAtV4IsABadRequest() {
        assertThat(Metadata.commitTable(stagedCreate("{\"action\": \"upgrade-format-version\", \"format-version\": 4},"),
                null, null))
                .isEqualTo(new BadRequest("Table format version 4 is not supported; the highest is 3"));
    }

    /** iceberg-core's reference handler builds a staged create without a version as v2; D12 makes it v3. */
    @Test
    void stagedCreateDefaultsToV3WhereTheReferenceGivesV2() throws IOException {
        String request = stagedCreate("");
        GoldenCase staged = new GoldenCase("staged", GoldenCase.Kind.COMMIT_TABLE, null, NewMetadata.class, null);

        NewMetadata ours = (NewMetadata) Metadata.commitTable(request, null, null);
        NewMetadata reference = (NewMetadata) Reference.run(staged, request);

        assertThat(ours.after().formatVersion()).isEqualTo(3);
        assertThat(Golden.compact(reference.json())).contains("\"format-version\":2");
    }

    // D9: no-op

    @Test
    void tableCommitWithNoUpdatesChangesNothing() {
        assertThat(Metadata.commitTable(NOTHING, base("v3-append"), location("v3-append"))).isEqualTo(new NoChange());
    }

    @Test
    void tableCommitRestatingCurrentValuesChangesNothing() {
        Outcome outcome = Metadata.commitTable("""
                {"requirements": [],
                 "updates": [{"action": "set-current-schema", "schema-id": 0},
                             {"action": "set-snapshot-ref", "ref-name": "main", "snapshot-id": 3001, "type": "branch"}]}""",
                base("v3-append"), location("v3-append"));

        assertThat(outcome).isEqualTo(new NoChange());
    }

    @Test
    void viewCommitWithNoUpdatesChangesNothing() {
        assertThat(Metadata.commitView(NOTHING, base("view-create"))).isEqualTo(new NoChange());
    }

    @Test
    void viewCommitRestatingCurrentValuesChangesNothing() {
        assertThat(Metadata.commitView("""
                {"requirements": [], "updates": [{"action": "set-current-view-version", "view-version-id": 1}]}""",
                base("view-create"))).isEqualTo(new NoChange());
    }

    // 409: requirement failures and stale values

    @Test
    void failedRequirementIsAConflict() {
        Outcome outcome = Metadata.commitTable("""
                {"requirements": [{"type": "assert-table-uuid", "uuid": "ffffffff-0000-4000-8000-00000000000f"}],
                 "updates": []}""", base("v3-append"), location("v3-append"));

        assertThat(outcome).isEqualTo(new RequirementFailed("Requirement failed: UUID does not match: expected "
                + GoldenCase.V3_UUID + " != ffffffff-0000-4000-8000-00000000000f"));
    }

    @Test
    void failedViewRequirementIsAConflict() {
        Outcome outcome = Metadata.commitView("""
                {"requirements": [{"type": "assert-view-uuid", "uuid": "ffffffff-0000-4000-8000-00000000000f"}],
                 "updates": []}""", base("view-create"));

        assertThat(outcome).isInstanceOf(RequirementFailed.class);
    }

    @Test
    void stagedCreateOfAnExistingTableIsAConflict() {
        Outcome outcome = Metadata.commitTable(stagedCreate(""), base("v3-create"), location("v3-create"));

        assertThat(outcome).isEqualTo(new RequirementFailed("Requirement failed: table already exists"));
    }

    /** A stale first-row-id is iceberg-core's RetryableValidationException, answered 409 as the reference does. */
    @Test
    void staleFirstRowIdIsAConflict() {
        GoldenCase stale = GoldenCase.named("v3-stale-first-row-id");

        Outcome outcome = Metadata.commitTable(Golden.request(stale), Golden.baseJson(stale), Golden.baseLocation(stale));

        assertThat(outcome).isInstanceOf(RequirementFailed.class);
        assertThat(((RequirementFailed) outcome).message())
                .startsWith("Validation failed, please retry: Cannot add a snapshot, first-row-id is behind");
    }

    @Test
    void staleSequenceNumberIsAConflict() {
        GoldenCase stale = GoldenCase.named("v2-stale-sequence");

        Outcome outcome = Metadata.commitTable(Golden.request(stale), Golden.baseJson(stale), Golden.baseLocation(stale));

        assertThat(((RequirementFailed) outcome).message())
                .startsWith("Validation failed, please retry: Cannot add snapshot with sequence number");
    }

    // 400: requests that cannot be applied

    @Test
    void malformedRequestsAreBadRequests() {
        assertThat(Metadata.createTable("{", UUID, LOCATION)).isInstanceOf(BadRequest.class);
        assertThat(Metadata.commitTable("{", base("v3-create"), location("v3-create"))).isInstanceOf(BadRequest.class);
        assertThat(Metadata.createView("{", UUID, LOCATION, 0)).isInstanceOf(BadRequest.class);
        assertThat(Metadata.commitView("{", base("view-create"))).isInstanceOf(BadRequest.class);
    }

    @Test
    void createWithoutSchemaIsABadRequest() {
        assertThat(Metadata.createTable("{\"name\": \"positions\"}", UUID, LOCATION)).isInstanceOf(BadRequest.class);
    }

    @Test
    void unknownUpdateActionIsABadRequest() {
        assertThat(Metadata.commitTable("""
                {"requirements": [], "updates": [{"action": "rewrite-everything"}]}""",
                base("v3-create"), location("v3-create"))).isInstanceOf(BadRequest.class);
    }

    @Test
    void unknownSchemaIdIsABadRequest() {
        assertThat(Metadata.commitTable("""
                {"requirements": [], "updates": [{"action": "set-current-schema", "schema-id": 42}]}""",
                base("v3-create"), location("v3-create")))
                .isEqualTo(new BadRequest("IllegalArgumentException", "Cannot set current schema to unknown schema: 42"));
    }

    /** The reference answers 406 here (UnsupportedOperationException); the request is at fault, so 400. */
    @Test
    void viewUpdateOnATableIsABadRequest() {
        assertThat(Metadata.commitTable("""
                {"requirements": [], "updates": [{"action": "set-current-view-version", "view-version-id": 1}]}""",
                base("v3-create"), location("v3-create")))
                .isEqualTo(new BadRequest("Cannot apply update SetCurrentViewVersion to a table"));
    }

    @Test
    void tableUpdateOnAViewIsABadRequest() {
        assertThat(Metadata.commitView("""
                {"requirements": [], "updates": [{"action": "set-default-spec", "spec-id": 0}]}""",
                base("view-create")))
                .isEqualTo(new BadRequest("Cannot apply update SetDefaultPartitionSpec to a view"));
    }

    @Test
    void tableRequirementOnAViewIsABadRequest() {
        assertThat(Metadata.commitView("""
                {"requirements": [{"type": "assert-ref-snapshot-id", "ref": "main", "snapshot-id": null}],
                 "updates": []}""", base("view-create")))
                .isEqualTo(new BadRequest("ValidationException", "Cannot validate AssertRefSnapshotID against a view"));
    }

    @Test
    void createRequirementWithOthersIsABadRequest() {
        String request = stagedCreate("").replace("{\"type\": \"assert-create\"}",
                "{\"type\": \"assert-create\"}, {\"type\": \"assert-current-schema-id\", \"current-schema-id\": 0}");

        Outcome outcome = Metadata.commitTable(request, null, null);

        assertThat(outcome).isInstanceOf(BadRequest.class);
        assertThat(((BadRequest) outcome).message()).startsWith("Invalid create requirements: ");
    }

    @Test
    void nonSqlViewRepresentationIsABadRequest() {
        String request = createView("""
                [{"type": "substrait", "plan": "AAAA"}]""");

        assertThat(Metadata.createView(request, UUID, LOCATION, 0))
                .isEqualTo(new BadRequest("Found unsupported view representations: [substrait]"));
    }

    @Test
    void viewWithoutAQueryIsABadRequest() {
        assertThat(Metadata.createView(createView("[]"), UUID, LOCATION, 0)).isInstanceOf(BadRequest.class);
    }

    /** The reference answers 500 here (IllegalStateException); the request is at fault, so 400. */
    @Test
    void droppingAViewDialectIsABadRequest() {
        Outcome outcome = Metadata.commitView("""
                {"requirements": [],
                 "updates": [{"action": "add-view-version", "view-version": {
                                "version-id": 2, "timestamp-ms": 1767225660000, "schema-id": 0, "summary": {},
                                "representations": [{"type": "sql", "sql": "SELECT 1", "dialect": "trino"}],
                                "default-namespace": ["ns"]}},
                             {"action": "set-current-view-version", "view-version-id": -1}]}""",
                base("view-create"));

        assertThat(outcome).isInstanceOf(BadRequest.class);
        assertThat(((BadRequest) outcome).message()).startsWith("Cannot replace view due to loss of view dialects");
    }

    /**
     * geometry, geography and unknown are v3 types (spec, "Schemas and Data Types"). iceberg-core's builder refuses
     * them in a v2 schema with IllegalStateException, which the reference answers 500; the request is at fault,
     * so 400. A v3 schema with them is golden case v3-geo-unknown.
     */
    @Test
    void v3TypesInAV2SchemaAreABadRequest() {
        String request = """
                {"requirements": [],
                 "updates": [{"action": "add-schema", "schema": {"type": "struct", "schema-id": 1, "fields": [
                                {"id": 1, "name": "id", "required": true, "type": "long"},
                                {"id": 2, "name": "symbol", "required": false, "type": "string"},
                                {"id": 3, "name": "qty", "required": false, "type": "int"},
                                {"id": 4, "name": "pickup", "required": false, "type": "geometry"},
                                {"id": 5, "name": "route", "required": false, "type": "geography"},
                                {"id": 6, "name": "pending", "required": false, "type": "unknown"}]}},
                             {"action": "set-current-schema", "schema-id": -1}]}""";
        GoldenCase v2 = new GoldenCase("v2-v3-types", GoldenCase.Kind.COMMIT_TABLE, "v2-append", BadRequest.class, null);

        Outcome outcome = Metadata.commitTable(request, base("v2-append"), location("v2-append"));

        assertThat(outcome).isInstanceOf(BadRequest.class);
        assertThat(((BadRequest) outcome).type()).isEqualTo("BadRequestException");
        assertThat(((BadRequest) outcome).message()).startsWith("Invalid schema for v2:").contains(
                "Invalid type for pickup: geometry", "Invalid type for route: geography",
                "Invalid type for pending: unknown is not supported until v3");
        assertThatThrownBy(() -> Reference.run(v2, request)).isInstanceOf(IllegalStateException.class);
    }

    /**
     * An unknown column is always null (spec), so a non-null default for one cannot be read. iceberg-core's parser
     * throws UnsupportedOperationException, which the reference answers 406; the request is at fault, so 400. A
     * required unknown column is golden case bad-unknown-required, where the reference answers 400 too.
     */
    @Test
    void anUnknownFieldWithADefaultIsABadRequest() {
        String request = """
                {"requirements": [],
                 "updates": [{"action": "add-schema", "schema": {"type": "struct", "schema-id": 1, "fields": [
                                {"id": 1, "name": "id", "required": true, "type": "long"},
                                {"id": 2, "name": "data", "required": false, "type": "string"},
                                {"id": 3, "name": "ts", "required": false, "type": "timestamptz"},
                                {"id": 4, "name": "pending", "required": false, "type": "unknown",
                                 "initial-default": 1, "write-default": 1}]}},
                             {"action": "set-current-schema", "schema-id": -1}]}""";
        GoldenCase v3 = new GoldenCase("v3-unknown-default", GoldenCase.Kind.COMMIT_TABLE, "v3-create", BadRequest.class,
                null);

        assertThat(Metadata.commitTable(request, base("v3-create"), location("v3-create")))
                .isEqualTo(new BadRequest("Type: unknown is not supported"));
        assertThatThrownBy(() -> Reference.run(v3, request)).isInstanceOf(UnsupportedOperationException.class);
    }

    // 404

    @Test
    void commitToATableWithNoMetadataIsNotFound() {
        assertThat(Metadata.commitTable(NOTHING, null, null)).isEqualTo(new NotFound());
    }

    // Injection and facts

    @Test
    void createTableTakesTheGivenUuidAndDefaultLocation() {
        NewMetadata created = (NewMetadata) Metadata.createTable(createTable("{}"), UUID, LOCATION);

        assertThat(created.json()).contains("\"table-uuid\":\"" + UUID + "\"", "\"location\":\"" + LOCATION + "\"");
        assertThat(created.before()).isNull();
        TableFacts after = (TableFacts) created.after();
        assertThat(after.uuid()).isEqualTo(UUID);
        assertThat(after.location()).isEqualTo(LOCATION);
        assertThat(after.currentSchemaId()).isZero();
        assertThat(after.defaultSpecId()).isZero();
        assertThat(after.snapshotTimestamps()).isEmpty();
    }

    /** D28: the catalog assigns table and view locations; the RCK knows this as rck.overrides-requested-location. */
    @Test
    void createPutsATableWhereTheCatalogSaysNotWhereTheRequestAsks() {
        String request = "{\"location\": \"s3://elsewhere/positions\", " + createTable("{}").substring(1);

        NewMetadata created = (NewMetadata) Metadata.createTable(request, UUID, LOCATION);

        assertThat(created.after().location()).isEqualTo(LOCATION);
        assertThat(created.json()).doesNotContain("elsewhere");
    }

    @Test
    void createPutsAViewWhereTheCatalogSays() {
        String request = "{\"location\": \"s3://elsewhere/v\", "
                + createView("[{\"type\": \"sql\", \"sql\": \"select 1\", \"dialect\": \"spark\"}]").substring(1);

        NewMetadata created = (NewMetadata) Metadata.createView(request, UUID, LOCATION, 0);

        assertThat(created.after().location()).isEqualTo(LOCATION);
    }

    @Test
    void theJournalHoldsTheRequirementsAndUpdatesAsSent() {
        Metadata.Journal journal = Metadata.journal(stagedCreate(""));

        assertThat(journal.requirementsJson()).isEqualTo("[{\"type\":\"assert-create\"}]");
        assertThat(journal.updatesJson()).startsWith("[{\"action\":\"assign-uuid\",\"uuid\":\"" + UUID + "\"}");
        assertThat(Metadata.journal("{}")).isEqualTo(new Metadata.Journal("[]", "[]"));
    }

    @Test
    void commitReportsFactsBeforeAndAfter() {
        GoldenCase append = GoldenCase.named("v3-append");

        NewMetadata appended = (NewMetadata) Metadata.commitTable(Golden.request(append), Golden.baseJson(append),
                Golden.baseLocation(append));

        TableFacts before = (TableFacts) appended.before();
        TableFacts after = (TableFacts) appended.after();
        assertThat(before.snapshotTimestamps()).isEmpty();
        assertThat(after.snapshotTimestamps()).containsExactly(Map.entry(3001L, 1748736060000L));
        assertThat(after.uuid()).isEqualTo(before.uuid()).isEqualTo(GoldenCase.V3_UUID);
        assertThat(after.properties()).containsEntry("owner", "trading");
        assertThat(after.location()).isEqualTo("s3://warehouse/ns/orders");
    }

    @Test
    void createViewTakesTheGivenUuidTimestampAndDefaultLocation() {
        String request = Golden.request(GoldenCase.named("view-create"));

        NewMetadata created = (NewMetadata) Metadata.createView(request, UUID, LOCATION, 42L);

        assertThat(created.json()).contains("\"view-uuid\":\"" + UUID + "\"", "\"timestamp-ms\":42,",
                "\"location\":\"" + LOCATION + "\"");
        assertThat(created.before()).isNull();
        assertThat(created.after()).isEqualTo(new ViewFacts(UUID, 1, LOCATION,
                Map.of("comment", "daily pnl"), 0, 1));
    }

    @Test
    void viewCommitReportsFactsBeforeAndAfter() {
        GoldenCase replace = GoldenCase.named("view-replace");

        NewMetadata replaced = (NewMetadata) Metadata.commitView(Golden.request(replace), Golden.baseJson(replace));

        assertThat(((ViewFacts) replaced.before()).currentVersionId()).isEqualTo(1);
        assertThat(((ViewFacts) replaced.after()).currentVersionId()).isEqualTo(2);
        assertThat(replaced.after().properties()).containsEntry("owner", "finance").doesNotContainKey("comment");
    }

    // The caller's faults

    @Test
    void unreadableCurrentMetadataThrows() {
        assertThatThrownBy(() -> Metadata.commitTable(NOTHING, "{", "s3://warehouse/broken.metadata.json"))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> Metadata.commitView(NOTHING, "{}")).isInstanceOf(RuntimeException.class);
    }

    private static String createTable(String properties) {
        return "{\"name\": \"positions\", \"schema\": " + SCHEMA + ", \"properties\": " + properties + "}";
    }

    private static String createView(String representations) {
        return "{\"name\": \"v\", \"schema\": " + SCHEMA + ", \"view-version\": {\"version-id\": 1, \"timestamp-ms\": 0,"
                + " \"schema-id\": 0, \"summary\": {}, \"default-namespace\": [\"ns\"], \"representations\": "
                + representations + "}, \"properties\": {}}";
    }

    /** A staged create, as a client commits it; {@code first} goes ahead of the other updates. */
    private static String stagedCreate(String first) {
        return """
                {"requirements": [{"type": "assert-create"}],
                 "updates": [%s
                   {"action": "assign-uuid", "uuid": "%s"},
                   {"action": "add-schema", "schema": %s},
                   {"action": "set-current-schema", "schema-id": -1},
                   {"action": "add-spec", "spec": {"spec-id": 0, "fields": []}},
                   {"action": "set-default-spec", "spec-id": -1},
                   {"action": "add-sort-order", "sort-order": {"order-id": 0, "fields": []}},
                   {"action": "set-default-sort-order", "sort-order-id": -1},
                   {"action": "set-location", "location": "%s"}]}""".formatted(first, UUID, SCHEMA, LOCATION);
    }

    private static String base(String name) {
        return Golden.compact(Golden.expected(GoldenCase.named(name)));
    }

    private static String location(String name) {
        return Golden.WAREHOUSE + "/golden/" + name + ".metadata.json";
    }
}
