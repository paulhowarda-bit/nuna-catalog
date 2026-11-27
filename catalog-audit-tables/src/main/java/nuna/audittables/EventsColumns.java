package nuna.audittables;

import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.apache.iceberg.MetadataUpdate;
import org.apache.iceberg.Schema;
import org.apache.iceberg.TableMetadata;
import org.apache.iceberg.TableMetadataParser;
import org.apache.iceberg.UpdateRequirement;
import org.apache.iceberg.UpdateRequirements;
import org.apache.iceberg.rest.requests.UpdateTableRequest;
import org.apache.iceberg.rest.requests.UpdateTableRequestParser;
import org.apache.iceberg.types.Types;

/**
 * The commit that gives an audit table made before them the columns this writer added since its first
 * ({@link EventsTable}), for the catalog server to make at startup (D23, amended 2026-11-27): a schema change needs
 * {@code modify}, which the writers' group does not hold. Iceberg stays in this module: the server hands over the
 * table's metadata as JSON and gets back an Iceberg REST commit request as JSON.
 */
public final class EventsColumns {

    private EventsColumns() {
    }

    /**
     * The commit request that adds the columns {@code metadataJson}'s current schema lacks, in their order and with
     * their ids, guarded by its current schema and last column id; none when it has them all.
     *
     * @throws IllegalStateException when a column it lacks has an id the table has given to another column: it is not
     *                               the table this writer writes, and the writer will refuse it on opening
     */
    public static Optional<String> update(String metadataJson) {
        TableMetadata base = TableMetadataParser.fromJson(metadataJson);
        Schema current = base.schema();
        List<Types.NestedField> absent = EventsTable.ADDED.stream()
                .filter(c -> current.findField(c.name()) == null).toList();
        if (absent.isEmpty()) {
            return Optional.empty();
        }
        for (Types.NestedField column : absent) {
            if (column.fieldId() <= base.lastColumnId()) {
                throw new IllegalStateException("the audit table lacks column " + column.name() + ", whose id "
                        + column.fieldId() + " it has already given another column: it is not the table the audit"
                        + " table writer writes");
            }
        }
        Schema schema = new Schema(Stream.concat(current.columns().stream(), absent.stream()).toList(),
                current.identifierFieldIds());
        TableMetadata updated = TableMetadata.buildFrom(base)
                .setCurrentSchema(schema, Math.max(base.lastColumnId(), schema.highestFieldId())).build();
        List<MetadataUpdate> updates = updated.changes();
        List<UpdateRequirement> requirements = UpdateRequirements.forUpdateTable(base, updates);
        return Optional.of(UpdateTableRequestParser.toJson(new UpdateTableRequest(requirements, updates)));
    }
}
