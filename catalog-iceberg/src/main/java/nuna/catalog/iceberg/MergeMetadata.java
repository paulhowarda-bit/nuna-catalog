package nuna.catalog.iceberg;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import org.apache.iceberg.DataOperations;
import org.apache.iceberg.Snapshot;
import org.apache.iceberg.SnapshotRef;
import org.apache.iceberg.TableMetadata;
import org.apache.iceberg.TableMetadataParser;
import org.apache.iceberg.TableProperties;

/**
 * Table metadata as a merge of a branch into main reads and writes it (D33, backlog 107). Pure, as {@link Metadata}
 * is: the caller reads the files and writes the result.
 */
public final class MergeMetadata {

    private MergeMetadata() {
    }

    /**
     * Why main's changes to a table since a branch's cut go beyond main's own maintenance, which a merge rebases over
     * (Paul, 2026-11-22: "maintenance doesn't count"); empty when they are only that. Maintenance is compaction and
     * manifest rewrites, snapshots of operation {@code replace}, which hold no rows their parents lack, on main's own
     * line from the cut's snapshot; and snapshot expiry, which removes snapshots, refs, and the schemas and partition
     * specs only they used. Statistics are not compared. Anything else (data, a schema, partitioning, sort order,
     * properties, the location, the format version, a ref made or moved) is a change the merge would undo, so it is a
     * conflict.
     */
    public static Optional<String> beyondMaintenance(String cutJson, String mainJson) {
        TableMetadata cut = TableMetadataParser.fromJson(cutJson);
        TableMetadata main = TableMetadataParser.fromJson(mainJson);
        Optional<String> definition = definition(cut, main, false);
        if (definition.isPresent()) {
            return definition;
        }
        for (Snapshot snapshot : main.snapshots()) {
            if (cut.snapshot(snapshot.snapshotId()) == null
                    && !DataOperations.REPLACE.equals(snapshot.operation())) {
                return Optional.of("main committed snapshot " + snapshot.snapshotId() + " (" + snapshot.operation()
                        + ")");
            }
        }
        for (Map.Entry<String, SnapshotRef> ref : main.refs().entrySet()) {
            SnapshotRef was = cut.ref(ref.getKey());
            if (was == null) {
                return Optional.of("main made ref " + ref.getKey());
            }
            if (!SnapshotRef.builderFrom(was, ref.getValue().snapshotId()).build().equals(ref.getValue())
                    || !compactedFrom(main, cut, ref.getValue().snapshotId(), was.snapshotId())) {
                return Optional.of("main moved ref " + ref.getKey() + " from snapshot " + was.snapshotId() + " to "
                        + ref.getValue().snapshotId());
            }
        }
        return Optional.empty();
    }

    /**
     * What a branch changed in a table's definition since its cut ({@code base}), which needs {@code modify} on main
     * as a commit of it would (D18); empty when it changed only data and its bookkeeping (snapshots, refs and
     * statistics), which {@code write} allows. The cut's definition is main's, maintenance aside
     * ({@link #beyondMaintenance}), so this is the definition change the merge makes on main.
     */
    public static Optional<String> definitionChange(String baseJson, String branchJson) {
        return definition(TableMetadataParser.fromJson(baseJson), TableMetadataParser.fromJson(branchJson), true);
    }

    /**
     * Main's own copy of a branch's table metadata, for main's next version: the branch's metadata whole, its metadata
     * log replaced by main's, ending with main's current file, and stamped {@code atMillis} (or later, so the log stays
     * in order). Its predecessor is main's file, so a DR reconcile walks from main's chain into it as into any commit
     * on main, and never into the branch's files (D37; backlog 107's choice).
     *
     * @param mainLocation where {@code mainJson}, main's current metadata, is stored
     */
    public static String merged(String branchJson, String mainJson, String mainLocation, long atMillis) {
        TableMetadata main = TableMetadataParser.fromJson(mainLocation, mainJson);
        TableMetadata branch = TableMetadataParser.fromJson(branchJson);
        ObjectNode copy;
        try {
            copy = (ObjectNode) Metadata.REQUESTS.readTree(branchJson);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
        List<ObjectNode> log = new ArrayList<>();
        for (TableMetadata.MetadataLogEntry entry : main.previousFiles()) {
            log.add(Metadata.REQUESTS.createObjectNode().put("timestamp-ms", entry.timestampMillis())
                    .put("metadata-file", entry.file()));
        }
        log.add(Metadata.REQUESTS.createObjectNode().put("timestamp-ms", main.lastUpdatedMillis())
                .put("metadata-file", mainLocation));
        int keep = branch.propertyAsInt(TableProperties.METADATA_PREVIOUS_VERSIONS_MAX,
                TableProperties.METADATA_PREVIOUS_VERSIONS_MAX_DEFAULT);
        ArrayNode entries = copy.putArray("metadata-log");
        log.subList(Math.max(0, log.size() - keep), log.size()).forEach(entries::add);
        copy.put("last-updated-ms", Math.max(atMillis, Math.max(main.lastUpdatedMillis(), branch.lastUpdatedMillis())));
        return TableMetadataParser.toJson(TableMetadataParser.fromJson(copy.toString()));
    }

    /**
     * What main's journal records for a table or view a merge moved, as the one update of that commit: an action of its
     * own, {@code merge-branch}, naming the branch, the branch's version and file main took a copy of, the cut's version,
     * how many of main's commits since the cut it was rebased over, and what it changed beyond data, where a schema or
     * partitioning change on a regulated table reads (D11a).
     */
    public static String journal(String branch, String branchId, long branchVersion, String branchLocation,
                                 long cutVersion, long rebasedOver, Optional<String> definition) {
        ObjectNode update = Metadata.REQUESTS.createObjectNode()
                .put("action", "merge-branch")
                .put("branch", branch)
                .put("branch-id", branchId)
                .put("branch-version", branchVersion)
                .put("branch-location", branchLocation)
                .put("cut-version", cutVersion)
                .put("rebased-over", rebasedOver);
        definition.ifPresent(change -> update.put("definition-change", change));
        return Metadata.REQUESTS.createArrayNode().add(update).toString();
    }

    /**
     * How {@code after}'s definition differs from {@code before}'s. With {@code both}, schemas, specs and sort orders
     * either side lacks count; without, only those {@code after} adds, since expiry removes unused ones.
     */
    private static Optional<String> definition(TableMetadata before, TableMetadata after, boolean both) {
        if (before.formatVersion() != after.formatVersion()) {
            return Optional.of("format version " + before.formatVersion() + " became " + after.formatVersion());
        }
        if (!Objects.equals(before.location(), after.location())) {
            return Optional.of("location " + before.location() + " became " + after.location());
        }
        if (!before.properties().equals(after.properties())) {
            Set<String> keys = new TreeSet<>(before.properties().keySet());
            keys.addAll(after.properties().keySet());
            keys.removeIf(key -> Objects.equals(before.properties().get(key), after.properties().get(key)));
            return Optional.of("properties " + keys + " changed");
        }
        if (before.currentSchemaId() != after.currentSchemaId()) {
            return Optional.of("current schema " + before.currentSchemaId() + " became " + after.currentSchemaId());
        }
        if (differ(before.schemasById().keySet(), after.schemasById().keySet(), both)) {
            return Optional.of("schemas " + before.schemasById().keySet() + " became " + after.schemasById().keySet());
        }
        if (before.defaultSpecId() != after.defaultSpecId()
                || differ(before.specsById().keySet(), after.specsById().keySet(), both)) {
            return Optional.of("partition specs " + before.specsById().keySet() + " (default " + before.defaultSpecId()
                    + ") became " + after.specsById().keySet() + " (default " + after.defaultSpecId() + ")");
        }
        if (before.defaultSortOrderId() != after.defaultSortOrderId()
                || differ(before.sortOrdersById().keySet(), after.sortOrdersById().keySet(), both)) {
            return Optional.of("sort orders " + before.sortOrdersById().keySet() + " (default "
                    + before.defaultSortOrderId() + ") became " + after.sortOrdersById().keySet() + " (default "
                    + after.defaultSortOrderId() + ")");
        }
        return Optional.empty();
    }

    private static boolean differ(Set<Integer> before, Set<Integer> after, boolean both) {
        return both ? !before.equals(after) : !before.containsAll(after);
    }

    /**
     * Whether main's snapshot {@code id} is the cut's {@code from}, or reached from it through snapshots main added
     * since the cut, each a {@code replace}.
     */
    private static boolean compactedFrom(TableMetadata main, TableMetadata cut, long id, long from) {
        Long at = id;
        while (at != null && at != from) {
            Snapshot snapshot = main.snapshot(at);
            if (snapshot == null || cut.snapshot(at) != null
                    || !DataOperations.REPLACE.equals(snapshot.operation())) {
                return false;
            }
            at = snapshot.parentId();
        }
        return at != null;
    }
}
