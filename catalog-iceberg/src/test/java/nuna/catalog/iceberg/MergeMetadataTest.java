package nuna.catalog.iceberg;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.apache.iceberg.Snapshot;
import org.apache.iceberg.TableMetadata;
import org.apache.iceberg.TableMetadataParser;
import org.junit.jupiter.api.Test;

/**
 * What a merge of a branch into main reads from table metadata and writes (D33, backlog 107): which of main's changes
 * since the cut are its own maintenance, which a merge rebases over (Paul, 2026-11-22); what a branch changed beyond
 * data, which needs modify; and main's own copy of the branch's metadata, whose predecessor is main's file.
 */
class MergeMetadataTest {

    private static final String UUID = "c0000000-0000-4000-8000-00000000000d";
    private static final String ROOT = "s3://warehouse/t";
    private static final long DAY = 86_400_000L;
    /** Snapshot times, well before now, so expiry has old snapshots to remove. */
    private static final long T0 = System.currentTimeMillis() - 30 * DAY;

    /** A table with two appends: snapshots 1 then 2, main at 2, written as versions 1 to 3. */
    private final Chain cut = Chain.created().then(append(1, null, 1, T0), main(1)).then(append(2, 1L, 2, T0 + DAY),
            main(2));

    // ---- main's own maintenance ----

    @Test
    void mainUnmovedIsNoConflict() {
        assertThat(MergeMetadata.beyondMaintenance(cut.json, cut.json)).isEmpty();
    }

    @Test
    void compactionAndExpiryOnMainAreMaintenance() {
        Chain main = cut.then(replace(3, 2L, 3, T0 + 2 * DAY), main(3)).then(replace(4, 3L, 4, T0 + 3 * DAY), main(4))
                .then(removeSnapshots(1));

        assertThat(MergeMetadata.beyondMaintenance(cut.json, main.json)).isEmpty();
    }

    @Test
    void dataOnMainConflicts() {
        Chain main = cut.then(append(3, 2L, 3, T0 + 2 * DAY), main(3));

        assertThat(MergeMetadata.beyondMaintenance(cut.json, main.json)).hasValueSatisfying(reason ->
                assertThat(reason).isEqualTo("main committed snapshot 3 (append)"));
    }

    @Test
    void dataAfterACompactionOnMainConflicts() {
        Chain main = cut.then(replace(3, 2L, 3, T0 + 2 * DAY), main(3)).then(append(4, 3L, 4, T0 + 3 * DAY), main(4));

        assertThat(MergeMetadata.beyondMaintenance(cut.json, main.json)).contains("main committed snapshot 4 (append)");
    }

    @Test
    void aDefinitionChangeOnMainConflicts() {
        assertThat(MergeMetadata.beyondMaintenance(cut.json, cut.then(setProperties("owner", "ops")).json))
                .contains("properties [owner] changed");
        assertThat(MergeMetadata.beyondMaintenance(cut.json, cut.then(addColumn()).json))
                .contains("current schema 0 became 1");
    }

    @Test
    void aRollbackOrANewRefOnMainConflicts() {
        assertThat(MergeMetadata.beyondMaintenance(cut.json, cut.then(main(1)).json))
                .contains("main moved ref main from snapshot 2 to 1");
        assertThat(MergeMetadata.beyondMaintenance(cut.json, cut.then(tag("audit", 2)).json))
                .contains("main made ref audit");
    }

    @Test
    void aCompactionOffTheCutsLineConflicts() {
        Chain main = cut.then(replace(3, 1L, 3, T0 + 2 * DAY), main(3));

        assertThat(MergeMetadata.beyondMaintenance(cut.json, main.json))
                .contains("main moved ref main from snapshot 2 to 3");
    }

    // ---- what the branch changed ----

    @Test
    void dataAloneIsNoDefinitionChange() {
        Chain branch = cut.then(append(3, 2L, 3, T0 + 2 * DAY), main(3)).then(removeSnapshots(1));

        assertThat(MergeMetadata.definitionChange(cut.json, branch.json)).isEmpty();
    }

    @Test
    void aSchemaPropertyOrPartitionChangeIsADefinitionChange() {
        assertThat(MergeMetadata.definitionChange(cut.json, cut.then(addColumn()).json))
                .contains("current schema 0 became 1");
        assertThat(MergeMetadata.definitionChange(cut.json, cut.then(setProperties("write.format.default", "orc"))
                .json)).contains("properties [write.format.default] changed");
        assertThat(MergeMetadata.definitionChange(cut.json, cut.then(partitionById()).json))
                .hasValueSatisfying(reason -> assertThat(reason).startsWith("partition specs [0] (default 0) became"));
    }

    // ---- main's copy ----

    @Test
    void theCopyIsTheBranchsMetadataAfterMainsFile() {
        Chain main = cut.then(replace(3, 2L, 3, T0 + 2 * DAY), main(3));
        Chain branch = cut.then(append(3, 2L, 3, T0 + 2 * DAY), main(3)).then(append(5, 3L, 4, T0 + 3 * DAY), main(5));
        long at = System.currentTimeMillis();

        String copy = MergeMetadata.merged(cut.json, branch.json, main.json, main.location, at);

        TableMetadata merged = TableMetadataParser.fromJson(copy);
        TableMetadata fromBranch = TableMetadataParser.fromJson(branch.json);
        assertThat(merged.previousFiles().getLast().file()).as("its predecessor is main's file").isEqualTo(main.location);
        assertThat(merged.previousFiles()).extracting(TableMetadata.MetadataLogEntry::file)
                .as("main's chain, never the branch's files").containsExactlyElementsOf(main.locations);
        assertThat(merged.lastUpdatedMillis()).isGreaterThanOrEqualTo(at);
        assertThat(merged.currentSnapshot().snapshotId()).isEqualTo(5L);
        assertThat(merged.snapshots()).extracting(Snapshot::snapshotId).containsExactly(1L, 2L, 3L, 5L);
        assertThat(merged.lastSequenceNumber()).isEqualTo(fromBranch.lastSequenceNumber());
        assertThat(Metadata.tableLink(copy, ROOT + "/metadata/00005-x.metadata.json").facts())
                .isEqualTo(Metadata.tableLink(branch.json, branch.location).facts());
        assertThat(Metadata.tableLink(copy, ROOT + "/metadata/00005-x.metadata.json").previous())
                .contains(main.location);
    }

    @Test
    void theCopyKeepsTheLogAsLongAsTheTableAsks() {
        Chain main = cut.then(setProperties("write.metadata.previous-versions-max", "2"));
        Chain branch = main.then(append(3, 2L, 3, T0 + 2 * DAY), main(3));

        TableMetadata merged = TableMetadataParser.fromJson(MergeMetadata.merged(main.json, branch.json, main.json,
                main.location, System.currentTimeMillis()));

        assertThat(merged.previousFiles()).extracting(TableMetadata.MetadataLogEntry::file)
                .containsExactly(main.locations.get(main.locations.size() - 2), main.location);
    }

    @Test
    void theCopyKeepsWhatMainsExpiryRemoved() {
        Chain tagged = cut.then(tag("q3", 1));
        Chain main = tagged.then(removeRef("q3"), removeSnapshots(1));
        Chain branch = tagged.then(append(3, 2L, 3, T0 + 2 * DAY), main(3));
        assertThat(MergeMetadata.beyondMaintenance(tagged.json, main.json)).as("expiry is main's maintenance").isEmpty();

        TableMetadata merged = TableMetadataParser.fromJson(MergeMetadata.merged(tagged.json, branch.json, main.json,
                main.location, System.currentTimeMillis()));

        assertThat(merged.snapshots()).extracting(Snapshot::snapshotId)
                .as("main expired 1 and its tag; the branch's own 3 stays").containsExactly(2L, 3L);
        assertThat(merged.refs()).containsOnlyKeys("main");
        assertThat(merged.snapshotLog()).extracting(entry -> entry.snapshotId()).containsExactly(2L, 3L);

        Chain kept = branch.then(tag("audit", 1));
        assertThat(TableMetadataParser.fromJson(MergeMetadata.merged(tagged.json, kept.json, main.json, main.location,
                System.currentTimeMillis())).snapshots()).extracting(Snapshot::snapshotId)
                .as("a ref the branch made keeps the snapshot it names").containsExactly(1L, 2L, 3L);
    }

    // ---- building metadata as clients commit it ----

    /** A table's metadata after each commit, with the location each version was written to. */
    private record Chain(String json, String location, List<String> locations) {

        static Chain created() {
            String json = ((Outcome.NewMetadata) Metadata.createTable("{\"name\":\"t\",\"schema\":{\"type\":\"struct\","
                    + "\"schema-id\":0,\"fields\":[{\"id\":1,\"name\":\"id\",\"required\":true,\"type\":\"long\"}]},"
                    + "\"properties\":{\"format-version\":\"2\"}}", UUID, ROOT)).json();
            String location = file(1);
            return new Chain(json, location, List.of(location));
        }

        /** The commit of {@code updates}; the log of the version it writes ends with this one's file. */
        Chain then(String... updates) {
            Outcome outcome = Metadata.commitTable("{\"requirements\":[],\"updates\":[" + String.join(",", updates)
                    + "]}", json, location);
            assertThat(outcome).as(String.join(",", updates)).isInstanceOf(Outcome.NewMetadata.class);
            String next = file(locations.size() + 1);
            List<String> all = new java.util.ArrayList<>(locations);
            all.add(next);
            return new Chain(((Outcome.NewMetadata) outcome).json(), next, List.copyOf(all));
        }

        private static String file(int version) {
            return String.format("%s/metadata/%05d-%s.metadata.json", ROOT, version, java.util.UUID.randomUUID());
        }
    }

    private static String append(long id, Long parent, long sequence, long timestampMs) {
        return snapshot(id, parent, sequence, timestampMs, "append");
    }

    private static String replace(long id, Long parent, long sequence, long timestampMs) {
        return snapshot(id, parent, sequence, timestampMs, "replace");
    }

    private static String snapshot(long id, Long parent, long sequence, long timestampMs, String operation) {
        return "{\"action\":\"add-snapshot\",\"snapshot\":{\"snapshot-id\":" + id
                + (parent == null ? "" : ",\"parent-snapshot-id\":" + parent)
                + ",\"sequence-number\":" + sequence + ",\"timestamp-ms\":" + timestampMs
                + ",\"manifest-list\":\"" + ROOT + "/metadata/snap-" + id + ".avro\""
                + ",\"summary\":{\"operation\":\"" + operation + "\"},\"schema-id\":0}}";
    }

    private static String main(long snapshotId) {
        return "{\"action\":\"set-snapshot-ref\",\"ref-name\":\"main\",\"type\":\"branch\",\"snapshot-id\":"
                + snapshotId + "}";
    }

    private static String tag(String name, long snapshotId) {
        return "{\"action\":\"set-snapshot-ref\",\"ref-name\":\"" + name + "\",\"type\":\"tag\",\"snapshot-id\":"
                + snapshotId + "}";
    }

    private static String removeRef(String name) {
        return "{\"action\":\"remove-snapshot-ref\",\"ref-name\":\"" + name + "\"}";
    }

    private static String removeSnapshots(long id) {
        return "{\"action\":\"remove-snapshots\",\"snapshot-ids\":[" + id + "]}";
    }

    private static String setProperties(String key, String value) {
        return "{\"action\":\"set-properties\",\"updates\":" + Metadata.REQUESTS.valueToTree(Map.of(key, value)) + "}";
    }

    private static String addColumn() {
        return "{\"action\":\"add-schema\",\"schema\":{\"type\":\"struct\",\"schema-id\":1,\"fields\":[{\"id\":1,"
                + "\"name\":\"id\",\"required\":true,\"type\":\"long\"},{\"id\":2,\"name\":\"note\",\"required\":false,"
                + "\"type\":\"string\"}]}},{\"action\":\"set-current-schema\",\"schema-id\":-1}";
    }

    private static String partitionById() {
        return "{\"action\":\"add-spec\",\"spec\":{\"spec-id\":1,\"fields\":[{\"name\":\"id\",\"transform\":"
                + "\"identity\",\"source-id\":1,\"field-id\":1000}]}},{\"action\":\"set-default-spec\",\"spec-id\":-1}";
    }
}
