package nuna.maintenance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.iceberg.AppendFiles;
import org.apache.iceberg.CatalogProperties;
import org.apache.iceberg.DataFile;
import org.apache.iceberg.DataFiles;
import org.apache.iceberg.DeleteFile;
import org.apache.iceberg.FileFormat;
import org.apache.iceberg.FileMetadata;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.Schema;
import org.apache.iceberg.Table;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.inmemory.InMemoryCatalog;
import org.apache.iceberg.types.Types;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Whether a table needs compaction (D30, amended 2026-11-15), from its current manifests alone, on tables of an
 * in-memory catalog whose files exist only as manifest entries: enough data files under 75% of the target size in one
 * partition; delete files (position deletes, deletion vectors, equality deletes) above their share of the data files;
 * any equality-delete file. Thresholds come from the policy, rewrite_data_files' own by default. The neediest table
 * comes first, one with equality deletes before any other, and the Job's options carry the thresholds and partial
 * progress.
 */
class CompactionNeedTest {

    private static final Schema SCHEMA = new Schema(Types.NestedField.required(1, "id", Types.LongType.get()),
            Types.NestedField.optional(2, "name", Types.StringType.get()));
    private static final Map<String, String> TARGET_1000 = Map.of(CompactionNeed.TARGET_FILE_SIZE, "1000");

    private final InMemoryCatalog catalog = new InMemoryCatalog();

    @BeforeEach
    void start() {
        catalog.initialize("need", Map.of(CatalogProperties.WAREHOUSE_LOCATION, "mem://warehouse"));
        catalog.createNamespace(Namespace.of("ns"));
    }

    @AfterEach
    void stop() throws IOException {
        catalog.close();
    }

    @Test
    void aTableWithoutASnapshotNeedsNone() {
        CompactionNeed need = CompactionNeed.assess(table("empty", PartitionSpec.unpartitioned(), 2), Map.of());

        assertThat(need.needed()).isFalse();
        assertThat(need.dataFiles()).isZero();
        assertThat(need.minInputFiles()).as("rewrite_data_files' own").isEqualTo(5);
        assertThat(need.deleteFileRatio()).isEqualTo(0.3);
        assertThat(need.targetFileSize()).isEqualTo(512L * 1024 * 1024);
    }

    @Test
    void smallFilesAreThoseUnderThreeQuartersOfTheTarget() {
        Table table = table("sized", PartitionSpec.unpartitioned(), 2);
        List<DataFile> small = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            small.add(data(table, null, 749));
        }
        append(table, small);
        append(table, List.of(data(table, null, 750), data(table, null, 750), data(table, null, 900)));

        CompactionNeed four = CompactionNeed.assess(table, TARGET_1000);
        assertThat(four.dataFiles()).isEqualTo(7);
        assertThat(four.smallFiles()).as("four small files, under the threshold of five").isZero();
        assertThat(four.needed()).isFalse();

        DataFile fifth = data(table, null, 10);
        append(table, List.of(fifth));
        CompactionNeed five = CompactionNeed.assess(table, TARGET_1000);
        assertThat(five.reasons()).containsExactly(CompactionNeed.Reason.SMALL_FILES);
        assertThat(five.smallFiles()).isEqualTo(5);
        assertThat(five.score()).isEqualTo(1.0);

        table.newDelete().deleteFile(fifth).commit();
        assertThat(CompactionNeed.assess(table, TARGET_1000).needed()).as("a deleted file is not live").isFalse();
        assertThat(CompactionNeed.assess(table, Map.of(CompactionNeed.TARGET_FILE_SIZE, "1000",
                CompactionNeed.MIN_INPUT_FILES, "4")).smallFiles()).as("the policy's threshold").isEqualTo(4);
        assertThat(CompactionNeed.assess(table, Map.of()).smallFiles()).as("every file is small against 512 MiB")
                .isEqualTo(7);
    }

    @Test
    void smallFilesAreCountedPerPartition() {
        Table table = table("partitioned", PartitionSpec.builderFor(SCHEMA).identity("name").build(), 2);
        List<DataFile> files = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            files.add(data(table, "name=a", 10));
            files.add(data(table, "name=b", 10));
        }
        files.add(data(table, "name=c", 10));
        append(table, files);

        assertThat(CompactionNeed.assess(table, Map.of()).needed()).as("seven small files, none five to a partition")
                .isFalse();
        CompactionNeed three = CompactionNeed.assess(table, Map.of(CompactionNeed.MIN_INPUT_FILES, "3"));
        assertThat(three.smallFiles()).as("a and b, not c's one").isEqualTo(6);
        assertThat(three.score()).isEqualTo(2.0);
    }

    @Test
    void deleteFilesPastTheirShareOfTheDataFiles() {
        Table table = tenLargeFiles("deleted", 2);
        for (int i = 0; i < 3; i++) {
            table.newRowDelta().addDeletes(positionDeletes(table)).commit();
        }
        assertThat(CompactionNeed.assess(table, TARGET_1000).needed()).as("3 on 10, at the share of 0.3, not past it")
                .isFalse();

        table.newRowDelta().addDeletes(positionDeletes(table)).commit();
        CompactionNeed four = CompactionNeed.assess(table, TARGET_1000);
        assertThat(four.reasons()).containsExactly(CompactionNeed.Reason.DELETES);
        assertThat(four.deleteFiles()).isEqualTo(4);
        assertThat(four.equalityDeleteFiles()).isZero();
        assertThat(four.score()).isCloseTo(0.4 / 0.3, within(1e-9));
        assertThat(CompactionNeed.assess(table, Map.of(CompactionNeed.TARGET_FILE_SIZE, "1000",
                CompactionNeed.DELETE_FILE_RATIO, "0.5")).needed()).as("the policy's share").isFalse();
    }

    @Test
    void deletionVectorsAreDeleteFiles() {
        Table table = tenLargeFiles("vectors", 3);
        List<DataFile> files = new ArrayList<>();
        table.currentSnapshot().addedDataFiles(table.io()).forEach(files::add);
        for (int i = 0; i < 4; i++) {
            table.newRowDelta().addDeletes(FileMetadata.deleteFileBuilder(table.spec()).ofPositionDeletes()
                    .withFormat(FileFormat.PUFFIN).withPath(table.location() + "/data/" + UUID.randomUUID() + ".puffin")
                    .withFileSizeInBytes(100).withRecordCount(1).withReferencedDataFile(files.get(i).location())
                    .withContentOffset(4).withContentSizeInBytes(40).build()).commit();
        }

        CompactionNeed need = CompactionNeed.assess(table, TARGET_1000);
        assertThat(need.deleteFiles()).isEqualTo(4);
        assertThat(need.reasons()).containsExactly(CompactionNeed.Reason.DELETES);
    }

    @Test
    void anyEqualityDeleteFileNeedsCompaction() {
        Table table = tenLargeFiles("equality", 2);
        table.newRowDelta().addDeletes(FileMetadata.deleteFileBuilder(table.spec()).ofEqualityDeletes(1)
                .withFormat(FileFormat.PARQUET).withPath(table.location() + "/data/" + UUID.randomUUID() + ".parquet")
                .withFileSizeInBytes(100).withRecordCount(1).build()).commit();

        CompactionNeed need = CompactionNeed.assess(table, TARGET_1000);
        assertThat(need.reasons()).as("1 on 10, well under the share").containsExactly(
                CompactionNeed.Reason.EQUALITY_DELETES);
        assertThat(need.equalityDeleteFiles()).isEqualTo(1);
        assertThat(need.score()).isLessThan(1);
    }

    @Test
    void equalityDeletesFirstThenTheNeediest() {
        CompactionNeed equality = new CompactionNeed(10, 0, 1, 1, 5, 0.3, 1000);
        CompactionNeed manySmall = new CompactionNeed(30, 15, 0, 0, 5, 0.3, 1000);
        CompactionNeed someSmall = new CompactionNeed(30, 6, 0, 0, 5, 0.3, 1000);
        CompactionNeed deletes = new CompactionNeed(10, 0, 4, 0, 5, 0.3, 1000);

        List<CompactionNeed> needs = new ArrayList<>(List.of(someSmall, deletes, manySmall, equality));
        needs.sort(CompactionNeed.NEEDIEST_FIRST);

        assertThat(needs).containsExactly(equality, manySmall, deletes, someSmall);
    }

    @Test
    void theJobsOptionsCarryTheThresholdsAndPartialProgress() {
        assertThat(new CompactionNeed(30, 15, 0, 0, 5, 0.3, 1000).options()).containsExactly(
                Map.entry("target-file-size-bytes", "1000"), Map.entry("min-input-files", "5"),
                Map.entry("partial-progress.enabled", "true"));
        assertThat(new CompactionNeed(10, 0, 4, 0, 5, 0.3, 1000).options())
                .containsEntry("partial-progress.enabled", "true")
                .containsEntry("delete-file-threshold", "1")
                .containsEntry("remove-dangling-deletes", "true");
        assertThat(new CompactionNeed(10, 0, 1, 1, 5, 0.3, 1000).options())
                .containsEntry("delete-file-threshold", "1");
    }

    private Table table(String name, PartitionSpec spec, int formatVersion) {
        return catalog.buildTable(TableIdentifier.of("ns", name), SCHEMA).withPartitionSpec(spec)
                .withProperty("format-version", Integer.toString(formatVersion)).create();
    }

    /** A table of ten data files of 1000 bytes: none small against a target of 1000. */
    private Table tenLargeFiles(String name, int formatVersion) {
        Table table = table(name, PartitionSpec.unpartitioned(), formatVersion);
        List<DataFile> files = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            files.add(data(table, null, 1000));
        }
        append(table, files);
        return table;
    }

    private static DataFile data(Table table, String partition, long size) {
        DataFiles.Builder file = DataFiles.builder(table.spec())
                .withPath(table.location() + "/data/" + UUID.randomUUID() + ".parquet").withFormat(FileFormat.PARQUET)
                .withFileSizeInBytes(size).withRecordCount(1);
        if (partition != null) {
            file.withPartitionPath(partition);
        }
        return file.build();
    }

    private static DeleteFile positionDeletes(Table table) {
        return FileMetadata.deleteFileBuilder(table.spec()).ofPositionDeletes().withFormat(FileFormat.PARQUET)
                .withPath(table.location() + "/data/" + UUID.randomUUID() + "-deletes.parquet")
                .withFileSizeInBytes(100).withRecordCount(1).build();
    }

    private static void append(Table table, List<DataFile> files) {
        AppendFiles append = table.newAppend();
        files.forEach(append::appendFile);
        append.commit();
    }
}
