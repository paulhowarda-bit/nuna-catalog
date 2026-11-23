package nuna.maintenance;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.apache.iceberg.CatalogProperties;
import org.apache.iceberg.DataFile;
import org.apache.iceberg.DataFiles;
import org.apache.iceberg.FileFormat;
import org.apache.iceberg.HasTableOperations;
import org.apache.iceberg.Schema;
import org.apache.iceberg.Table;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.inmemory.InMemoryCatalog;
import org.apache.iceberg.types.Types;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * What the catalog's refs reach of a table's files (D33, item 108): a tag keeps what its cut reaches, a branch what its
 * cut and its own head reach, a file both reach counts for the branch, and with no refs nothing is read. Needs only
 * Java: the table is Iceberg's in-memory catalog's, its metadata in its in-memory files.
 */
class RefReachTest {

    private static final Schema SCHEMA = new Schema(Types.NestedField.required(1, "id", Types.LongType.get()));

    private final InMemoryCatalog catalog = new InMemoryCatalog();
    private Table table;

    @BeforeEach
    void start() {
        catalog.initialize("refs", Map.of(CatalogProperties.WAREHOUSE_LOCATION, "mem://warehouse"));
        catalog.createNamespace(Namespace.of("ns"));
        table = catalog.buildTable(TableIdentifier.of("ns", "t" + UUID.randomUUID().toString().replace("-", "")),
                SCHEMA).create();
    }

    @Test
    void aTagKeepsWhatItsCutReachesAndNothingElse() {
        DataFile a = file("a");
        table.newAppend().appendFile(a).commit();
        String cut = metadata();
        String manifestList = table.currentSnapshot().manifestListLocation();
        table.newOverwrite().deleteFile(a).addFile(file("b")).commit();
        String other = table.location() + "/data/other.parquet";

        RefReach.Split split = RefReach.split(List.of(a.location(), manifestList, other),
                List.of(ref(Management.RefKind.TAG, cut)), table.io(), Set.of());

        assertThat(split.unreached()).containsExactly(other);
        assertThat(split.kept()).containsExactly(Map.entry(Management.RefKind.TAG, 2));
    }

    @Test
    void aBranchKeepsWhatItsOwnHeadReaches() {
        table.newAppend().appendFile(file("a")).commit();
        String cut = metadata();
        DataFile c = file("c");
        table.newAppend().appendFile(c).commit();
        String head = metadata();

        assertThat(RefReach.split(List.of(c.location()), List.of(ref(Management.RefKind.TAG, cut)), table.io(),
                Set.of()).unreached()).as("the cut alone").containsExactly(c.location());
        RefReach.Split split = RefReach.split(List.of(c.location()), List.of(ref(Management.RefKind.BRANCH, cut,
                head)), table.io(), Set.of());
        assertThat(split.unreached()).isEmpty();
        assertThat(split.kept()).containsExactly(Map.entry(Management.RefKind.BRANCH, 1));
    }

    @Test
    void aFileIsKeptForTheBranchWhenATagReachesItToo() {
        DataFile a = file("a");
        table.newAppend().appendFile(a).commit();
        String first = metadata();
        DataFile c = file("c");
        table.newAppend().appendFile(c).commit();
        String second = metadata();

        RefReach.Split split = RefReach.split(List.of(a.location(), c.location()), List.of(
                ref(Management.RefKind.TAG, second), ref(Management.RefKind.BRANCH, first)), table.io(), Set.of());

        assertThat(split.unreached()).isEmpty();
        assertThat(split.kept()).containsExactlyInAnyOrderEntriesOf(Map.of(Management.RefKind.BRANCH, 1,
                Management.RefKind.TAG, 1));
    }

    @Test
    void mainsOwnSnapshotsAreNotReadAgain() {
        DataFile a = file("a");
        table.newAppend().appendFile(a).commit();

        RefReach.Split split = RefReach.split(List.of(a.location()), List.of(ref(Management.RefKind.TAG, metadata())),
                table.io(), Set.of(table.currentSnapshot().snapshotId()));

        assertThat(split.unreached()).as("a snapshot main still has reaches no file its job left").containsExactly(
                a.location());
    }

    @Test
    void withNoRefsOrNoFilesNothingIsRead() {
        String missing = table.location() + "/metadata/missing.metadata.json";
        assertThat(RefReach.split(List.of("mem://warehouse/x"), List.of(), null, Set.of()).unreached())
                .containsExactly("mem://warehouse/x");
        assertThat(RefReach.split(List.of(), List.of(ref(Management.RefKind.TAG, missing)), null, Set.of())
                .unreached()).isEmpty();
    }

    private DataFile file(String name) {
        return DataFiles.builder(table.spec()).withPath(table.location() + "/data/" + name + ".parquet")
                .withFormat(FileFormat.PARQUET).withFileSizeInBytes(10).withRecordCount(1).build();
    }

    private String metadata() {
        return ((HasTableOperations) table).operations().current().metadataFileLocation();
    }

    private static Management.RefWork ref(Management.RefKind kind, String... metadata) {
        return new Management.RefWork(UUID.randomUUID(), kind, List.of(metadata));
    }
}
