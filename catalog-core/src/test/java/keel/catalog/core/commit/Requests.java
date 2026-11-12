package keel.catalog.core.commit;

import java.util.Arrays;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/** Request bodies as clients send them to the REST routes. */
final class Requests {

    static final String SCHEMA = """
            {"type":"struct","schema-id":0,"fields":[{"id":1,"name":"id","required":true,"type":"long"}]}""";

    private Requests() {
    }

    /** Asks for a location of its own, which the catalog ignores (D28). */
    static String createTable(String name, Map<String, String> properties) {
        return "{\"name\":\"" + name + "\",\"location\":\"s3://elsewhere/" + name + "\",\"schema\":" + SCHEMA
                + ",\"properties\":" + object(properties) + "}";
    }

    static String createView(String name) {
        return """
                {"name":"%s","location":"s3://elsewhere/%s","schema":%s,
                 "view-version":{"version-id":1,"timestamp-ms":1748736000000,"schema-id":0,"summary":{},
                  "representations":[{"type":"sql","sql":"select 1 as id","dialect":"spark"}],
                  "default-namespace":["sales"]},
                 "properties":{}}""".formatted(name, name, SCHEMA);
    }

    static String update(String requirement, String... updates) {
        return "{\"requirements\":[" + requirement + "],\"updates\":[" + String.join(",", updates) + "]}";
    }

    static String assertUuid(String uuid) {
        return "{\"type\":\"assert-table-uuid\",\"uuid\":\"" + uuid + "\"}";
    }

    static String assertViewUuid(String uuid) {
        return "{\"type\":\"assert-view-uuid\",\"uuid\":\"" + uuid + "\"}";
    }

    static String assertMain(Long snapshotId) {
        return "{\"type\":\"assert-ref-snapshot-id\",\"ref\":\"main\",\"snapshot-id\":" + snapshotId + "}";
    }

    static String assertSchema(int schemaId) {
        return "{\"type\":\"assert-current-schema-id\",\"current-schema-id\":" + schemaId + "}";
    }

    static String setProperties(Map<String, String> properties) {
        return "{\"action\":\"set-properties\",\"updates\":" + object(properties) + "}";
    }

    static String removeProperties(String... keys) {
        return "{\"action\":\"remove-properties\",\"removals\":["
                + Arrays.stream(keys).map(k -> "\"" + k + "\"").collect(Collectors.joining(",")) + "]}";
    }

    static String setLocation(String location) {
        return "{\"action\":\"set-location\",\"location\":\"" + location + "\"}";
    }

    /** A v2 snapshot; v3 tables also need row lineage, which {@link #addV3Snapshot} carries. */
    static String addSnapshot(long id, Long parent, long sequence, long timestampMs) {
        return "{\"action\":\"add-snapshot\",\"snapshot\":{\"snapshot-id\":" + id
                + (parent == null ? "" : ",\"parent-snapshot-id\":" + parent)
                + ",\"sequence-number\":" + sequence + ",\"timestamp-ms\":" + timestampMs
                + ",\"manifest-list\":\"s3://lake/warehouse/manifests/snap-" + id + ".avro\""
                + ",\"summary\":{\"operation\":\"append\"},\"schema-id\":0}}";
    }

    static String addV3Snapshot(long id, Long parent, long sequence, long timestampMs, long firstRowId) {
        String v2 = addSnapshot(id, parent, sequence, timestampMs);
        return v2.substring(0, v2.length() - 2) + ",\"first-row-id\":" + firstRowId + ",\"added-rows\":10}}";
    }

    static String setMain(long snapshotId) {
        return "{\"action\":\"set-snapshot-ref\",\"ref-name\":\"main\",\"type\":\"branch\",\"snapshot-id\":"
                + snapshotId + "}";
    }

    static String removeSnapshots(long... ids) {
        return "{\"action\":\"remove-snapshots\",\"snapshot-ids\":["
                + Arrays.stream(ids).mapToObj(Long::toString).collect(Collectors.joining(",")) + "]}";
    }

    /** Adds an optional column and makes the new schema current. */
    static String[] addColumn(int schemaId, String name) {
        return new String[] {
                "{\"action\":\"add-schema\",\"schema\":{\"type\":\"struct\",\"schema-id\":" + schemaId
                        + ",\"fields\":[{\"id\":1,\"name\":\"id\",\"required\":true,\"type\":\"long\"},"
                        + "{\"id\":" + (schemaId + 1) + ",\"name\":\"" + name + "\",\"required\":false,\"type\":\"string\"}]}}",
                "{\"action\":\"set-current-schema\",\"schema-id\":-1}"};
    }

    /** Partitions by identity on the id column under a new default spec. */
    static String[] partitionById() {
        return new String[] {
                "{\"action\":\"add-spec\",\"spec\":{\"spec-id\":1,\"fields\":[{\"name\":\"id\",\"transform\":\"identity\","
                        + "\"source-id\":1,\"field-id\":1000}]}}",
                "{\"action\":\"set-default-spec\",\"spec-id\":-1}"};
    }

    /** The commit of a staged create: the table as the staging response described it. */
    static String stagedCreate(String uuid, String location) {
        return update("{\"type\":\"assert-create\"}",
                "{\"action\":\"assign-uuid\",\"uuid\":\"" + uuid + "\"}",
                "{\"action\":\"upgrade-format-version\",\"format-version\":2}",
                "{\"action\":\"add-schema\",\"schema\":" + SCHEMA + "}",
                "{\"action\":\"set-current-schema\",\"schema-id\":-1}",
                "{\"action\":\"add-spec\",\"spec\":{\"spec-id\":0,\"fields\":[]}}",
                "{\"action\":\"set-default-spec\",\"spec-id\":-1}",
                "{\"action\":\"add-sort-order\",\"sort-order\":{\"order-id\":0,\"fields\":[]}}",
                "{\"action\":\"set-default-sort-order\",\"sort-order-id\":-1}",
                setLocation(location),
                setProperties(Map.of("created-by", "staging")));
    }

    static String object(Map<String, String> entries) {
        return new TreeMap<>(entries).entrySet().stream()
                .map(e -> "\"" + e.getKey() + "\":\"" + e.getValue() + "\"")
                .collect(Collectors.joining(",", "{", "}"));
    }
}
