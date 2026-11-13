package keel.catalog.iceberg;

import static org.assertj.core.api.Assertions.assertThat;

import keel.catalog.iceberg.Outcome.BadRequest;
import keel.catalog.iceberg.Outcome.NewMetadata;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Gravitino's backstop list against iceberg-core 1.12.0 (D11). Each refused case first shows that the
 * reference handlers accept it, which is why the backstop exists; each has an accepted neighbour, so the
 * backstop is no broader than its rule. The checks the builder already makes are pinned at the end, so an
 * iceberg-core upgrade that drops one is noticed.
 */
class BackstopTest {

    private static final String V3 = base("v3-append-2");
    private static final String V3_AT = location("v3-append-2");

    private static final String ID = "{\"id\": 1, \"name\": \"id\", \"required\": true, \"type\": \"long\"}";
    private static final String DATA = "{\"id\": 2, \"name\": \"data\", \"required\": false, \"type\": \"string\"}";
    private static final String TS = "{\"id\": 3, \"name\": \"ts\", \"required\": false, \"type\": \"timestamptz\"}";

    /** A snapshot that follows v3-append-2's 3002; OP and SCHEMA are filled in per case. */
    private static final String SNAPSHOT = """
            {"action": "add-snapshot", "snapshot": {"snapshot-id": 3099, "parent-snapshot-id": 3002,
             "sequence-number": 3, "timestamp-ms": 1748736180000,
             "manifest-list": "s3://warehouse/ns/orders/metadata/snap-3099.avro",
             "summary": {"operation": "OP"}, "schema-id": SCHEMA, "first-row-id": 150, "added-rows": 10}}""";

    @Nested
    class Schemas {

        @Test
        void anIncompatibleTypeChangeIsRefused() {
            String request = schema(ID, "{\"id\": 2, \"name\": \"data\", \"required\": false, \"type\": \"int\"}", TS);

            assertReferenceAccepts(request);
            assertThat(ours(request)).isEqualTo(new BadRequest(
                    "Cannot change field 2 (data) from string to int: not a valid type promotion"));
        }

        @Test
        void aNarrowingIsRefused() {
            String request = schema("{\"id\": 1, \"name\": \"id\", \"required\": true, \"type\": \"int\"}", DATA, TS);

            assertReferenceAccepts(request);
            assertThat(ours(request)).isEqualTo(new BadRequest(
                    "Cannot change field 1 (id) from long to int: not a valid type promotion"));
        }

        @Test
        void aPrimitiveCannotBecomeAStruct() {
            String request = schema(ID, """
                    {"id": 2, "name": "data", "required": false,
                     "type": {"type": "struct", "fields": [{"id": 4, "name": "a", "required": false, "type": "int"}]}}""",
                    TS);

            assertReferenceAccepts(request);
            assertThat(((BadRequest) ours(request)).message()).startsWith("Cannot change field 2 (data) from string to struct");
        }

        @Test
        void anOptionalFieldCannotBecomeRequired() {
            String request = schema(ID, "{\"id\": 2, \"name\": \"data\", \"required\": true, \"type\": \"string\"}", TS);

            assertReferenceAccepts(request);
            assertThat(ours(request)).isEqualTo(new BadRequest("Cannot make optional field 2 (data) required"));
        }

        @Test
        void aRequiredFieldMayBecomeOptional() {
            String request = schema("{\"id\": 1, \"name\": \"id\", \"required\": false, \"type\": \"long\"}", DATA, TS)
                    .replace("\"identifier-field-ids\": [1], ", "");

            assertThat(ours(request)).isInstanceOf(NewMetadata.class);
        }

        @Test
        void aNewRequiredFieldNeedsAnInitialDefault() {
            String request = schema(ID, DATA, TS, "{\"id\": 4, \"name\": \"r\", \"required\": true, \"type\": \"int\"}");

            assertReferenceAccepts(request);
            assertThat(ours(request)).isEqualTo(new BadRequest("New required field 4 (r) needs an initial-default"));
        }

        @Test
        void aReplaceThatStartsMainAfreshMayAddARequiredFieldWithoutADefault() {
            String request = request("{\"action\": \"remove-snapshot-ref\", \"ref-name\": \"main\"},"
                    + " {\"action\": \"add-schema\", \"schema\": {\"type\": \"struct\", \"schema-id\": 1,"
                    + " \"fields\": [" + ID + ", " + DATA + ", " + TS + ","
                    + " {\"id\": 4, \"name\": \"r\", \"required\": true, \"type\": \"int\"}]}},"
                    + " {\"action\": \"set-current-schema\", \"schema-id\": -1}");

            assertReferenceAccepts(request);
            assertThat(ours(request)).isInstanceOf(NewMetadata.class);
        }

        @Test
        void aReplaceThatStartsMainAfreshMayChangeAFieldsType() {
            String request = replace("{\"id\": 1, \"name\": \"id\", \"required\": true, \"type\": \"string\"}",
                    "{\"id\": 2, \"name\": \"data\", \"required\": false, \"type\": \"int\"}");

            assertReferenceAccepts(request);
            assertThat(ours(request)).as("D11, amended 2026-11-12: main reads none of the old rows")
                    .isInstanceOf(NewMetadata.class);
        }

        @Test
        void aReplaceMayStillNotMakeAnOptionalFieldRequiredNorTurnAPrimitiveIntoAStruct() {
            String required = replace(ID, "{\"id\": 2, \"name\": \"data\", \"required\": true, \"type\": \"string\"}");
            String nested = replace(ID, """
                    {"id": 2, "name": "data", "required": false,
                     "type": {"type": "struct", "fields": [{"id": 4, "name": "a", "required": false, "type": "int"}]}}""");

            assertReferenceAccepts(required);
            assertReferenceAccepts(nested);
            assertThat(ours(required)).as("only the type-promotion check is waived for a replace")
                    .isEqualTo(new BadRequest("Cannot make optional field 2 (data) required"));
            assertThat(((BadRequest) ours(nested)).message()).startsWith("Cannot change field 2 (data) from string to struct");
        }

        @Test
        void aReplaceOfATableWithNoSnapshotYetMayChangeAFieldsType() {
            String empty = base("v3-create");
            String request = request("{\"action\": \"add-schema\", \"schema\": {\"type\": \"struct\", \"schema-id\": 1,"
                    + " \"fields\": [{\"id\": 1, \"name\": \"id\", \"required\": false, \"type\": \"string\"}, "
                    + DATA + ", " + TS + "]}}, {\"action\": \"set-current-schema\", \"schema-id\": -1},"
                    + " {\"action\": \"add-snapshot\", \"snapshot\": {\"snapshot-id\": 4001, \"sequence-number\": 1,"
                    + " \"timestamp-ms\": 1767225700000, \"manifest-list\": \"s3://warehouse/ns/orders/metadata/snap-4001.avro\","
                    + " \"summary\": {\"operation\": \"overwrite\"}, \"schema-id\": 1, \"first-row-id\": 0, \"added-rows\": 1}},"
                    + " {\"action\": \"set-snapshot-ref\", \"ref-name\": \"main\", \"snapshot-id\": 4001, \"type\": \"branch\"}");

            assertThat(Reference.commitTable(empty, location("v3-create"), request)).as("the reference accepts it")
                    .isInstanceOf(NewMetadata.class);
            assertThat(Metadata.commitTable(request, empty, location("v3-create")))
                    .as("CREATE TABLE t (id bigint), then CREATE OR REPLACE TABLE t AS SELECT 'k' AS id")
                    .isInstanceOf(NewMetadata.class);
        }

        @Test
        void aRollbackOfMainThatAlsoRetypesIsRefused() {
            String request = request("{\"action\": \"set-snapshot-ref\", \"ref-name\": \"main\", \"snapshot-id\": 3001,"
                    + " \"type\": \"branch\"}, {\"action\": \"add-schema\", \"schema\": {\"type\": \"struct\","
                    + " \"schema-id\": 1, \"fields\": [{\"id\": 1, \"name\": \"id\", \"required\": true, \"type\": \"string\"}, "
                    + DATA + ", " + TS + "]}}, {\"action\": \"set-current-schema\", \"schema-id\": -1}");

            assertReferenceAccepts(request);
            assertThat(ours(request)).as("snapshot 3001's long ids would be read as strings").isEqualTo(new BadRequest(
                    "Cannot change field 1 (id) from long to string: not a valid type promotion"));
        }

        @Test
        void theSameRetypeInPlaceIsRefused() {
            String request = schema("{\"id\": 1, \"name\": \"id\", \"required\": true, \"type\": \"string\"}",
                    DATA, TS);

            assertReferenceAccepts(request);
            assertThat(ours(request)).isEqualTo(new BadRequest(
                    "Cannot change field 1 (id) from long to string: not a valid type promotion"));
        }

        @Test
        void aNewRequiredFieldWithAnInitialDefaultIsAccepted() {
            String request = schema(ID, DATA, TS, """
                    {"id": 4, "name": "r", "required": true, "type": "int", "initial-default": 1, "write-default": 1}""");

            assertThat(ours(request)).isInstanceOf(NewMetadata.class);
        }

        @Test
        void aRequiredFieldInsideANewOptionalStructNeedsNoDefault() {
            String request = schema(ID, DATA, TS, """
                    {"id": 4, "name": "s", "required": false,
                     "type": {"type": "struct", "fields": [{"id": 5, "name": "x", "required": true, "type": "int"}]}}""");

            assertThat(ours(request)).isInstanceOf(NewMetadata.class);
        }

        @Test
        void aRetiredFieldIdCannotBeReused() {
            String dropped = ((NewMetadata) ours(schema(ID, TS))).json();
            String request = schema(ID, TS, "{\"id\": 2, \"name\": \"data2\", \"required\": false, \"type\": \"int\"}")
                    .replace("\"schema-id\": 1", "\"schema-id\": 2");

            assertThat(Reference.commitTable(dropped, location("dropped"), request)).isInstanceOf(NewMetadata.class);
            assertThat(Metadata.commitTable(request, dropped, location("dropped"))).isEqualTo(new BadRequest(
                    "New field 2 (data2) reuses an ID at or below last-column-id 3"));
        }

        @Test
        void aNewFieldAboveLastColumnIdIsAccepted() {
            assertThat(ours(schema(ID, DATA, TS, "{\"id\": 4, \"name\": \"n\", \"required\": false, \"type\": \"int\"}")))
                    .isInstanceOf(NewMetadata.class);
        }
    }

    /**
     * Promotions on a table with date, int and (v3) unknown columns: date d is partitioned by day, date e
     * by identity, date f not at all.
     */
    @Nested
    class Promotions {

        private static final List<String> FIELDS = List.of(
                "{\"id\": 1, \"name\": \"id\", \"required\": true, \"type\": \"long\"}",
                "{\"id\": 2, \"name\": \"d\", \"required\": false, \"type\": \"date\"}",
                "{\"id\": 3, \"name\": \"e\", \"required\": false, \"type\": \"date\"}",
                "{\"id\": 4, \"name\": \"f\", \"required\": false, \"type\": \"date\"}",
                "{\"id\": 5, \"name\": \"n\", \"required\": false, \"type\": \"int\"}",
                "{\"id\": 6, \"name\": \"u\", \"required\": false, \"type\": \"unknown\"}");

        private static List<String> fields(int formatVersion) {
            return formatVersion == 3 ? FIELDS : FIELDS.subList(0, 5);
        }

        private static String table(int formatVersion) {
            String request = """
                    {"name": "t", "schema": {"type": "struct", "schema-id": 0, "fields": [%s]},
                     "partition-spec": {"spec-id": 0, "fields": [
                       {"name": "d_day", "transform": "day", "source-id": 2, "field-id": 1000},
                       {"name": "e", "transform": "identity", "source-id": 3, "field-id": 1001}]},
                     "properties": {"format-version": "%d"}}""".formatted(String.join(",", fields(formatVersion)),
                    formatVersion);
            Outcome created = Metadata.createTable(request, "d0000000-0000-4000-8000-00000000000d", "s3://warehouse/t");
            assertThat(created).as("the base table").isInstanceOf(NewMetadata.class);
            return ((NewMetadata) created).json();
        }

        private static Outcome promote(int formatVersion, int fieldId, String type) {
            List<String> fields = fields(formatVersion).stream()
                    .map(f -> f.startsWith("{\"id\": " + fieldId + ",") ? f.replaceAll("\"type\": \"\\w+\"",
                            "\"type\": \"" + type + "\"") : f)
                    .toList();
            String request = request("{\"action\": \"add-schema\", \"schema\": {\"type\": \"struct\", \"schema-id\": 1,"
                    + " \"fields\": [" + String.join(",", fields) + "]}}, {\"action\": \"set-current-schema\", \"schema-id\": -1}");
            return Metadata.commitTable(request, table(formatVersion), location("promotions"));
        }

        @Test
        void intToLongIsAccepted() {
            assertThat(promote(3, 5, "long")).isInstanceOf(NewMetadata.class);
        }

        /** iceberg-core's TypeUtil knows only the v1 promotions; the backstop must not refuse v3's. */
        @Test
        void v3DateToTimestampIsAccepted() {
            assertThat(promote(3, 4, "timestamp")).isInstanceOf(NewMetadata.class);
            assertThat(promote(3, 4, "timestamp_ns")).isInstanceOf(NewMetadata.class);
        }

        /**
         * The spec allows it, since day gives a date and its midnight the same value, but the 1.12.0 builder
         * cannot bind its date transform to a timestamp. Pinned so an upgrade that allows it is noticed.
         */
        @Test
        void v3DateToTimestampUnderADayPartitionIsRefusedByTheBuilder() {
            assertThat(promote(3, 2, "timestamp"))
                    .isEqualTo(new BadRequest("ValidationException", "Invalid source type timestamp for transform: day"));
        }

        @Test
        void v3DateToTimestampIsRefusedUnderAnIdentityPartition() {
            assertThat(promote(3, 3, "timestamp")).isEqualTo(new BadRequest(
                    "Cannot change field 3 (e) from date to timestamp: not a valid type promotion"));
        }

        @Test
        void dateToTimestamptzIsRefused() {
            assertThat(promote(3, 4, "timestamptz")).isEqualTo(new BadRequest(
                    "Cannot change field 4 (f) from date to timestamptz: not a valid type promotion"));
        }

        @Test
        void v2DateToTimestampIsRefused() {
            assertThat(promote(2, 4, "timestamp")).isEqualTo(new BadRequest(
                    "Cannot change field 4 (f) from date to timestamp: not a valid type promotion"));
        }

        @Test
        void v3UnknownPromotesToAnyType() {
            assertThat(promote(3, 6, "string")).isInstanceOf(NewMetadata.class);
        }
    }

    @Nested
    class PartitionFieldIds {

        @Test
        void anIdUsedByAnotherFieldIsRefused() {
            String request = spec("{\"name\": \"id_b\", \"transform\": \"bucket[4]\", \"source-id\": 1, \"field-id\": 1000}");

            assertReferenceAccepts(request);
            assertThat(ours(request)).isEqualTo(new BadRequest("Partition field ID 1000 (id_b) is already used by ts_day"
                    + " (day of field 3); give the new field an unused ID"));
        }

        /** The builder numbers a field sent without an ID from 1000 again, onto ts_day's ID. */
        @Test
        void aFieldSentWithoutAnIdIsRefusedRatherThanRenumberedOntoAnother() {
            String request = spec("{\"name\": \"id_b\", \"transform\": \"bucket[4]\", \"source-id\": 1}");

            assertReferenceAccepts(request);
            assertThat(ours(request)).isEqualTo(new BadRequest("Partition field ID 1000 (id_b) is already used by ts_day"
                    + " (day of field 3); give the new field an unused ID"));
        }

        @Test
        void anUnusedIdAtOrBelowLastPartitionIdIsRefused() {
            String request = spec("{\"name\": \"id_b\", \"transform\": \"bucket[4]\", \"source-id\": 1, \"field-id\": 999}");

            assertReferenceAccepts(request);
            assertThat(ours(request)).isEqualTo(new BadRequest(
                    "Partition field ID 999 (id_b) is not above last-partition-id 1000"));
        }

        @Test
        void theSameFieldKeepsItsIdAndANewOneTakesAFreshId() {
            assertThat(ours(spec("{\"name\": \"ts_day\", \"transform\": \"day\", \"source-id\": 3, \"field-id\": 1000},"
                    + "{\"name\": \"id_b\", \"transform\": \"bucket[4]\", \"source-id\": 1, \"field-id\": 1001}")))
                    .isInstanceOf(NewMetadata.class);
        }
    }

    @Nested
    class Transforms {

        private static final String ZORDER_SPEC = """
                {"action": "add-spec", "spec": {"spec-id": 1, "fields": [
                  {"name": "z", "transform": "zorder", "source-id": 1, "field-id": 1001}]}}""";
        private static final String ZORDER_ORDER = """
                {"action": "add-sort-order", "sort-order": {"order-id": 2, "fields": [
                  {"transform": "zorder", "source-id": 1, "direction": "asc", "null-order": "nulls-first"}]}}""";

        @Test
        void anUnknownTransformCannotBecomeTheDefaultSpec() {
            String request = request(ZORDER_SPEC + ", {\"action\": \"set-default-spec\", \"spec-id\": -1}");

            assertReferenceAccepts(request);
            assertThat(ours(request)).isEqualTo(new BadRequest("Default partition spec uses unknown transform zorder"));
        }

        @Test
        void anUnknownTransformCannotBecomeTheDefaultSortOrder() {
            String request = request(ZORDER_ORDER + ", {\"action\": \"set-default-sort-order\", \"sort-order-id\": -1}");

            assertReferenceAccepts(request);
            assertThat(ours(request)).isEqualTo(new BadRequest("Default sort order uses unknown transform zorder"));
        }

        @Test
        void aSpecWithAnUnknownTransformMayBeAddedWithoutBecomingTheDefault() {
            assertThat(ours(request(ZORDER_SPEC))).isInstanceOf(NewMetadata.class);
            assertThat(ours(request(ZORDER_ORDER))).isInstanceOf(NewMetadata.class);
        }

        @Test
        void aStagedCreateIsCheckedToo() {
            String request = """
                    {"requirements": [{"type": "assert-create"}],
                     "updates": [
                       {"action": "assign-uuid", "uuid": "e0000000-0000-4000-8000-00000000000e"},
                       {"action": "add-schema", "schema": {"type": "struct", "schema-id": 0, "fields": [%s]}},
                       {"action": "set-current-schema", "schema-id": -1},
                       {"action": "add-spec", "spec": {"spec-id": 0, "fields": []}},
                       {"action": "set-default-spec", "spec-id": -1},
                       %s,
                       {"action": "set-default-sort-order", "sort-order-id": -1},
                       {"action": "set-location", "location": "s3://warehouse/e"}]}""".formatted(ID, ZORDER_ORDER);

            assertThat(Metadata.commitTable(request, null, null))
                    .isEqualTo(new BadRequest("Default sort order uses unknown transform zorder"));
        }
    }

    @Nested
    class Snapshots {

        @Test
        void anUnknownOperationIsRefused() {
            String request = request(SNAPSHOT.replace("OP", "frobnicate").replace("SCHEMA", "0"));

            assertReferenceAccepts(request);
            assertThat(ours(request)).isEqualTo(new BadRequest("Snapshot 3099 has unknown operation frobnicate"));
        }

        @Test
        void anUnknownSchemaIsRefused() {
            String request = request(SNAPSHOT.replace("OP", "append").replace("SCHEMA", "7"));

            assertReferenceAccepts(request);
            assertThat(ours(request)).isEqualTo(new BadRequest("Snapshot 3099 names unknown schema 7"));
        }

        @Test
        void aKnownOperationOnAKnownSchemaIsAccepted() {
            for (String operation : new String[] {"append", "replace", "overwrite", "delete"}) {
                assertThat(ours(request(SNAPSHOT.replace("OP", operation).replace("SCHEMA", "0"))))
                        .as(operation).isInstanceOf(NewMetadata.class);
            }
        }
    }

    /** Gravitino checks the builder already makes: no backstop, but an upgrade that drops one fails here. */
    @Nested
    class AlreadyInTheBuilder {

        @Test
        void mainMustBeABranch() {
            assertThat(ours(request("{\"action\": \"set-snapshot-ref\", \"ref-name\": \"main\", \"snapshot-id\": 3001,"
                    + " \"type\": \"tag\"}"))).isEqualTo(new BadRequest("ValidationException", "Cannot set main to a tag, it must be a branch"));
        }

        @Test
        void theCurrentSchemaCannotBeRemoved() {
            assertThat(ours(request("{\"action\": \"remove-schemas\", \"schema-ids\": [0]}")))
                    .isEqualTo(new BadRequest("IllegalArgumentException", "Cannot remove the current schema"));
        }

        @Test
        void theDefaultSpecCannotBeRemoved() {
            assertThat(ours(request("{\"action\": \"remove-partition-specs\", \"spec-ids\": [0]}")))
                    .isEqualTo(new BadRequest("IllegalArgumentException", "Cannot remove the default partition spec"));
        }
    }

    private static Outcome ours(String request) {
        return Metadata.commitTable(request, V3, V3_AT);
    }

    private static void assertReferenceAccepts(String request) {
        assertThat(Reference.commitTable(V3, V3_AT, request)).as("the reference accepts it").isInstanceOf(NewMetadata.class);
    }

    private static String request(String updates) {
        return "{\"requirements\": [], \"updates\": [" + updates + "]}";
    }

    private static String schema(String... fields) {
        return request("{\"action\": \"add-schema\", \"schema\": {\"type\": \"struct\", \"schema-id\": 1,"
                + " \"identifier-field-ids\": [1], \"fields\": [" + String.join(",", fields) + "]}},"
                + " {\"action\": \"set-current-schema\", \"schema-id\": -1}");
    }

    /** A replace of v3-append-2 that starts main afresh, with fields id and data as given and ts as before. */
    private static String replace(String id, String data) {
        return request("{\"action\": \"remove-snapshot-ref\", \"ref-name\": \"main\"},"
                + " {\"action\": \"add-schema\", \"schema\": {\"type\": \"struct\", \"schema-id\": 1,"
                + " \"fields\": [" + id + ", " + data + ", " + TS + "]}},"
                + " {\"action\": \"set-current-schema\", \"schema-id\": -1}");
    }

    private static String spec(String fields) {
        return request("{\"action\": \"add-spec\", \"spec\": {\"spec-id\": 1, \"fields\": [" + fields + "]}}");
    }

    private static String base(String name) {
        return Golden.compact(Golden.expected(GoldenCase.named(name)));
    }

    private static String location(String name) {
        return Golden.WAREHOUSE + "/golden/" + name + ".metadata.json";
    }
}
