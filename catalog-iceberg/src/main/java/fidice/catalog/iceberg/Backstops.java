package fidice.catalog.iceberg;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.apache.iceberg.DataOperations;
import org.apache.iceberg.PartitionField;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.Schema;
import org.apache.iceberg.Snapshot;
import org.apache.iceberg.SortField;
import org.apache.iceberg.SortOrder;
import org.apache.iceberg.TableMetadata;
import org.apache.iceberg.transforms.Transform;
import org.apache.iceberg.transforms.UnknownTransform;
import org.apache.iceberg.types.Type;
import org.apache.iceberg.types.TypeUtil;
import org.apache.iceberg.types.Types;

/**
 * Correctness checks iceberg-core's builder does not make (D11): each one is here because a test shows
 * the 1.12.0 builder, and so the reference handlers, accept the metadata it refuses. The list comes from
 * Gravitino's IcebergCommitValidation; its checks the builder already makes (main must be a branch, the
 * current schema and default spec cannot be removed) are pinned by tests instead. Removing a spec that
 * manifests still use is not checked: the catalog does not read manifests.
 */
final class Backstops {

    private static final Set<String> OPERATIONS =
            Set.of(DataOperations.APPEND, DataOperations.REPLACE, DataOperations.OVERWRITE, DataOperations.DELETE);

    /** Transforms that give a date and the same instant as a timestamp the same value. */
    private static final Set<String> DATE_STABLE_TRANSFORMS = Set.of("year", "month", "day", "void");

    private Backstops() {
    }

    /** The first problem with {@code after}, or empty. {@code before} is null for a new table. */
    static Optional<String> check(TableMetadata before, TableMetadata after) {
        Optional<String> problem = Optional.empty();
        if (before != null) {
            problem = schemas(before, after).or(() -> partitionFieldIds(before, after));
        }
        return problem.or(() -> transforms(before, after)).or(() -> snapshots(before, after));
    }

    /**
     * Every schema this commit adds evolves the table's current schema by the spec's rules. A new required
     * field needs an initial default only when main keeps its rows: a replace that starts main afresh
     * has no rows to fill, and the reference allows it.
     */
    private static Optional<String> schemas(TableMetadata before, TableMetadata after) {
        boolean keepsRows = keepsRows(before, after);
        Map<Integer, Types.NestedField> current = TypeUtil.indexById(before.schema().asStruct());
        for (Schema added : after.schemas()) {
            if (before.schemasById().containsKey(added.schemaId())) {
                continue;
            }
            Map<Integer, Integer> parents = TypeUtil.indexParents(added.asStruct());
            for (Types.NestedField field : TypeUtil.indexById(added.asStruct()).values()) {
                Types.NestedField old = current.get(field.fieldId());
                Optional<String> problem = old != null
                        ? evolved(old, field, after)
                        : added(field, parents.get(field.fieldId()), current, before.lastColumnId(), keepsRows);
                if (problem.isPresent()) {
                    return problem;
                }
            }
        }
        return Optional.empty();
    }

    private static Optional<String> evolved(Types.NestedField old, Types.NestedField field, TableMetadata after) {
        Type from = old.type();
        Type to = field.type();
        if (from.isPrimitiveType() != to.isPrimitiveType() || (!from.isPrimitiveType() && from.typeId() != to.typeId())) {
            return Optional.of("Cannot change field " + field.fieldId() + " (" + field.name() + ") from " + from
                    + " to " + to);
        }
        if (from.isPrimitiveType() && !promotable(from, to.asPrimitiveType(), field.fieldId(), after)) {
            return Optional.of("Cannot change field " + field.fieldId() + " (" + field.name() + ") from " + from
                    + " to " + to + ": not a valid type promotion");
        }
        if (old.isOptional() && field.isRequired()) {
            return Optional.of("Cannot make optional field " + field.fieldId() + " (" + field.name() + ") required");
        }
        return Optional.empty();
    }

    /**
     * A field that is not in the current schema is new: its ID must be fresh, and if it is required and
     * sits in a struct that already holds data, existing rows need its initial default.
     */
    private static Optional<String> added(Types.NestedField field, Integer parent, Map<Integer, Types.NestedField> current,
                                          int lastColumnId, boolean keepsRows) {
        if (field.fieldId() <= lastColumnId) {
            return Optional.of("New field " + field.fieldId() + " (" + field.name() + ") reuses an ID at or below"
                    + " last-column-id " + lastColumnId);
        }
        boolean parentExists = keepsRows && (parent == null || current.containsKey(parent));
        if (field.isRequired() && field.initialDefault() == null && parentExists) {
            return Optional.of("New required field " + field.fieldId() + " (" + field.name()
                    + ") needs an initial-default");
        }
        return Optional.empty();
    }

    /**
     * Whether main after the commit still reads the rows main held before: its snapshot is the old one
     * or descends from it. A parent missing from the history counts as keeping them. Not covered: a later
     * commit that points main back at an old snapshot reads its rows through the replaced schema.
     */
    private static boolean keepsRows(TableMetadata before, TableMetadata after) {
        Snapshot old = before.currentSnapshot();
        if (old == null) {
            return false;
        }
        Snapshot snapshot = after.currentSnapshot();
        while (snapshot != null) {
            if (snapshot.snapshotId() == old.snapshotId()) {
                return true;
            }
            if (snapshot.parentId() == null) {
                return false;
            }
            snapshot = after.snapshot(snapshot.parentId());
            if (snapshot == null) {
                return true;
            }
        }
        return false;
    }

    /**
     * The spec's primitive promotions. iceberg-core's TypeUtil covers int, float and decimal; v3 adds
     * unknown to any type and date to timestamp or timestamp_ns, the latter only when no partition
     * field over the column would change value.
     */
    private static boolean promotable(Type from, Type.PrimitiveType to, int fieldId, TableMetadata after) {
        if (TypeUtil.isPromotionAllowed(from, to)) {
            return true;
        }
        if (after.formatVersion() < 3) {
            return false;
        }
        if (from.typeId() == Type.TypeID.UNKNOWN) {
            return true;
        }
        boolean dateToTimestamp = from.typeId() == Type.TypeID.DATE
                && ((to instanceof Types.TimestampType t && !t.shouldAdjustToUTC())
                || (to instanceof Types.TimestampNanoType n && !n.shouldAdjustToUTC()));
        return dateToTimestamp && after.specs().stream()
                .flatMap(spec -> spec.fields().stream())
                .filter(f -> f.sourceId() == fieldId)
                .allMatch(f -> DATE_STABLE_TRANSFORMS.contains(f.transform().toString()));
    }

    /**
     * From v2, a partition field ID names one field for the table's life: a spec this commit adds reuses
     * an ID only for the same source and transform, and otherwise takes one above last-partition-id. The
     * builder numbers a field sent without an ID from 1000 again, which this refuses too.
     */
    private static Optional<String> partitionFieldIds(TableMetadata before, TableMetadata after) {
        if (after.formatVersion() < 2) {
            return Optional.empty();
        }
        for (PartitionSpec spec : after.specs()) {
            if (before.specsById().containsKey(spec.specId())) {
                continue;
            }
            for (PartitionField field : spec.fields()) {
                Optional<PartitionField> existing = before.specs().stream()
                        .flatMap(s -> s.fields().stream())
                        .filter(f -> f.fieldId() == field.fieldId())
                        .findFirst();
                if (existing.isPresent()) {
                    PartitionField old = existing.get();
                    if (old.sourceId() != field.sourceId() || !old.transform().toString().equals(field.transform().toString())) {
                        return Optional.of("Partition field ID " + field.fieldId() + " (" + field.name()
                                + ") is already used by " + old.name() + " (" + old.transform() + " of field "
                                + old.sourceId() + "); give the new field an unused ID");
                    }
                } else if (field.fieldId() <= before.lastAssignedPartitionId()) {
                    return Optional.of("Partition field ID " + field.fieldId() + " (" + field.name()
                            + ") is not above last-partition-id " + before.lastAssignedPartitionId());
                }
            }
        }
        return Optional.empty();
    }

    /** A default spec or sort order newly set by this commit uses only transforms engines know. */
    private static Optional<String> transforms(TableMetadata before, TableMetadata after) {
        if (before == null || before.spec().specId() != after.spec().specId()) {
            for (PartitionField field : after.spec().fields()) {
                if (unknown(field.transform())) {
                    return Optional.of("Default partition spec uses unknown transform " + field.transform());
                }
            }
        }
        SortOrder order = after.sortOrder();
        if (before == null || before.sortOrder().orderId() != order.orderId()) {
            for (SortField field : order.fields()) {
                if (unknown(field.transform())) {
                    return Optional.of("Default sort order uses unknown transform " + field.transform());
                }
            }
        }
        return Optional.empty();
    }

    private static boolean unknown(Transform<?, ?> transform) {
        return transform instanceof UnknownTransform;
    }

    /** Snapshots this commit adds name a known operation and, when they name a schema, one the table has. */
    private static Optional<String> snapshots(TableMetadata before, TableMetadata after) {
        Set<Long> existing = before == null ? Set.of() : before.snapshots().stream()
                .map(Snapshot::snapshotId).collect(java.util.stream.Collectors.toSet());
        for (Snapshot snapshot : after.snapshots()) {
            if (existing.contains(snapshot.snapshotId())) {
                continue;
            }
            String operation = snapshot.operation();
            if (operation == null ? after.formatVersion() > 1 : !OPERATIONS.contains(operation)) {
                return Optional.of("Snapshot " + snapshot.snapshotId() + " has unknown operation " + operation);
            }
            Integer schemaId = snapshot.schemaId();
            if (schemaId != null && !after.schemasById().containsKey(schemaId)) {
                return Optional.of("Snapshot " + snapshot.snapshotId() + " names unknown schema " + schemaId);
            }
        }
        return Optional.empty();
    }
}
