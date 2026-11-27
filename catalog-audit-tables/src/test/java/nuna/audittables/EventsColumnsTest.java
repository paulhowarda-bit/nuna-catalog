package nuna.audittables;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Optional;
import org.apache.iceberg.HasTableOperations;
import org.apache.iceberg.MetadataUpdate;
import org.apache.iceberg.Table;
import org.apache.iceberg.TableMetadata;
import org.apache.iceberg.TableMetadataParser;
import org.apache.iceberg.TableOperations;
import org.apache.iceberg.TableProperties;
import org.apache.iceberg.UpdateRequirement;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.exceptions.CommitFailedException;
import org.apache.iceberg.rest.requests.UpdateTableRequest;
import org.apache.iceberg.rest.requests.UpdateTableRequestParser;
import org.apache.iceberg.types.Types;
import org.junit.jupiter.api.Test;

/**
 * The commit the catalog server makes at startup to give an audit table made before them the writer's added columns
 * (D23, amended 2026-11-27), applied here as the server's commit path applies an Iceberg REST commit: requirements
 * checked against the table's metadata, then its updates. Needs only Java; {@code AuditTablesTest} (heavy) has the
 * server make it for a writer that holds only create and write.
 */
class EventsColumnsTest {

    private static final Namespace NAMESPACE = Namespace.of("audit");
    private static final TableIdentifier ID = TableIdentifier.of(NAMESPACE, EventsTable.NAME);

    private final FaultyCatalog catalog = new FaultyCatalog();

    private Table madeWithTheFirstColumns() {
        catalog.createNamespace(NAMESPACE);
        return catalog.buildTable(ID, EventsTable.FIRST).withPartitionSpec(EventsTable.SPEC)
                .withProperty(TableProperties.FORMAT_VERSION, "2").create();
    }

    private static String json(Table table) {
        return TableMetadataParser.toJson(((HasTableOperations) table).operations().current());
    }

    /** Commits {@code request} to the table as a REST catalog would. */
    private void commit(String request) {
        UpdateTableRequest parsed = UpdateTableRequestParser.fromJson(request);
        TableOperations ops = ((HasTableOperations) catalog.loadTable(ID)).operations();
        TableMetadata base = ops.current();
        parsed.requirements().forEach(requirement -> requirement.validate(base));
        TableMetadata.Builder builder = TableMetadata.buildFrom(base);
        parsed.updates().forEach(update -> update.applyTo(builder));
        ops.commit(base, builder.build());
    }

    @Test
    void aTableMadeWithTheFirstColumnsIsGivenEveryAddedColumnWithItsId() {
        Table made = madeWithTheFirstColumns();

        commit(EventsColumns.update(json(made)).orElseThrow());

        Table evolved = catalog.loadTable(ID);
        assertThat(evolved.schema().asStruct()).isEqualTo(EventsTable.SCHEMA.asStruct());
        assertThat(evolved.schema().findField("subject_ids").type().asListType().elementId()).isEqualTo(24);
        assertThat(EventsTable.open(catalog, NAMESPACE, LogWriter.SOURCE))
                .as("the writer opens it with nothing left to add").isNotNull();
        assertThat(catalog.loadTable(ID).schema().schemaId()).as("the writer changed nothing")
                .isEqualTo(evolved.schema().schemaId());
    }

    @Test
    void aTableGivenSomeOfThemGetsTheRest() {
        Table made = madeWithTheFirstColumns();
        made.updateSchema().addColumn("log_run", Types.StringType.get()).addColumn("log_seq", Types.LongType.get())
                .commit();

        commit(EventsColumns.update(json(catalog.loadTable(ID))).orElseThrow());

        Table evolved = catalog.loadTable(ID);
        assertThat(evolved.schema().findField("branch").fieldId()).isEqualTo(22);
        assertThat(evolved.schema().findField("subject_ids").fieldId()).isEqualTo(23);
        assertThat(EventsTable.open(catalog, NAMESPACE, LogWriter.SOURCE)).isNotNull();
    }

    @Test
    void aTableWithEveryColumnNeedsNoCommit() {
        EventsTable.open(catalog, NAMESPACE, LogWriter.SOURCE);

        assertThat(EventsColumns.update(json(catalog.loadTable(ID)))).isEqualTo(Optional.empty());
    }

    @Test
    void theCommitIsGuardedByTheSchemaItWasMadeFrom() {
        Table made = madeWithTheFirstColumns();
        String request = EventsColumns.update(json(made)).orElseThrow();
        made.updateSchema().addColumn("log_run", Types.StringType.get()).commit();

        assertThat(UpdateTableRequestParser.fromJson(request).requirements())
                .hasAtLeastOneElementOfType(UpdateRequirement.AssertCurrentSchemaID.class);
        assertThatThrownBy(() -> commit(request)).as("another server added a column since")
                .isInstanceOf(CommitFailedException.class);
    }

    @Test
    void refusesATableThatGaveAnAddedColumnsIdToAnotherColumn() {
        Table made = madeWithTheFirstColumns();
        made.updateSchema().addColumn("something_else", Types.StringType.get()).commit();

        assertThatThrownBy(() -> EventsColumns.update(json(catalog.loadTable(ID))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("log_run");
    }

    @Test
    void addsColumnsAndNothingElse() {
        Table made = madeWithTheFirstColumns();

        assertThat(UpdateTableRequestParser.fromJson(EventsColumns.update(json(made)).orElseThrow()).updates())
                .allSatisfy(update -> assertThat(update).isInstanceOfAny(MetadataUpdate.AddSchema.class,
                        MetadataUpdate.SetCurrentSchema.class));
    }
}
