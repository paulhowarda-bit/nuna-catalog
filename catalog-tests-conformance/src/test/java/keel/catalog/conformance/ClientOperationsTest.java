package keel.catalog.conformance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.hadoop.conf.Configuration;
import org.apache.iceberg.CatalogProperties;
import org.apache.iceberg.ContentFile;
import org.apache.iceberg.DataFile;
import org.apache.iceberg.DataFiles;
import org.apache.iceberg.DeleteFile;
import org.apache.iceberg.FileContent;
import org.apache.iceberg.FileFormat;
import org.apache.iceberg.FileMetadata;
import org.apache.iceberg.FileScanTask;
import org.apache.iceberg.GenericBlobMetadata;
import org.apache.iceberg.GenericStatisticsFile;
import org.apache.iceberg.HasTableOperations;
import org.apache.iceberg.ImmutableGenericPartitionStatisticsFile;
import org.apache.iceberg.MetadataUpdate;
import org.apache.iceberg.OverwriteFiles;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.PartitionStatisticsFile;
import org.apache.iceberg.RowDelta;
import org.apache.iceberg.Schema;
import org.apache.iceberg.Snapshot;
import org.apache.iceberg.SnapshotParser;
import org.apache.iceberg.SnapshotRef;
import org.apache.iceberg.SnapshotRefType;
import org.apache.iceberg.StatisticsFile;
import org.apache.iceberg.Table;
import org.apache.iceberg.TableMetadata;
import org.apache.iceberg.TableOperations;
import org.apache.iceberg.TableProperties;
import org.apache.iceberg.Transaction;
import org.apache.iceberg.UpdateRequirement;
import org.apache.iceberg.aws.s3.S3FileIO;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.exceptions.BadRequestException;
import org.apache.iceberg.exceptions.ForbiddenException;
import org.apache.iceberg.exceptions.ValidationException;
import org.apache.iceberg.expressions.Expressions;
import org.apache.iceberg.io.CloseableIterable;
import org.apache.iceberg.io.OutputFile;
import org.apache.iceberg.rest.RESTCatalog;
import org.apache.iceberg.rest.requests.UpdateTableRequest;
import org.apache.iceberg.rest.requests.UpdateTableRequestParser;
import org.apache.iceberg.rest.responses.LoadTableResponseParser;
import org.apache.iceberg.types.Types;
import org.apache.iceberg.util.JsonUtil;
import org.apache.iceberg.util.SnapshotUtil;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The write operations a stock Iceberg 1.12 client makes beyond plain appends, against the server on PostgreSQL
 * and the run's S3 store: copy-on-write rewrites, row deltas with each kind of delete file, concurrent row-level
 * conflicts, branches, tags and write-audit-publish, snapshot expiry, v3 row lineage, lazy snapshot loading (D14)
 * and statistics.
 *
 * <p>Data and delete files are metadata only: made-up paths, sizes and counts. The catalog never reads them, and
 * the client still writes real manifests to S3. Every assertion reads what the server returns on a fresh load, a
 * plain GET of the table with no client cache in between, never the client's own table object. Expected values
 * come from the Iceberg spec or from what the client is documented to send. Each test makes a handful of commits,
 * so it stays in the fast build.
 */
class ClientOperationsTest {

    private static final Schema SCHEMA = new Schema(
            Types.NestedField.required(1, "id", Types.LongType.get()),
            Types.NestedField.optional(2, "p", Types.IntegerType.get()));
    private static final PartitionSpec UNPARTITIONED = PartitionSpec.unpartitioned();
    private static final PartitionSpec BY_P = PartitionSpec.builderFor(SCHEMA).identity("p").build();

    private static final HttpClient HTTP = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

    /**
     * Two engines: the writer, and a rival that loads the same tables and commits to them. The rival writes through
     * {@link InterleavingFileIO}, so a test can have the writer commit in the middle of the rival's commit.
     */
    private static RESTCatalog writer;
    private static RESTCatalog rival;

    /**
     * The server's first table create waits until Kafka has made the audit topic, since D23 publishes some records
     * before answering. That wait, half a minute on a loaded machine, is taken here rather than in the first case.
     */
    @BeforeAll
    static void clients() {
        writer = client(Map.of());
        rival = client(Map.of(CatalogProperties.FILE_IO_IMPL, InterleavingFileIO.class.getName()));
        create(namespace(), "warm_up", 3, UNPARTITIONED);
    }

    @AfterAll
    static void closeClients() throws IOException {
        writer.close();
        rival.close();
    }

    // 1. Copy-on-write

    /**
     * An overwrite that swaps one data file for another is {@code overwrite}; a rewrite that compacts two files into
     * one is {@code replace}. Each commit takes the next sequence number, and the summary counts what it changed.
     */
    @ParameterizedTest(name = "v{0}")
    @ValueSource(ints = {2, 3})
    void copyOnWriteOverwriteAndCompaction(int version) {
        Table table = create(namespace(), "cow", version, UNPARTITIONED);
        DataFile a = data(table, "a", 10, null);
        DataFile b = data(table, "b", 20, null);
        table.newAppend().appendFile(a).appendFile(b).commit();
        DataFile a2 = data(table, "a2", 8, null);
        table.newOverwrite().deleteFile(a).addFile(a2).commit();
        DataFile compacted = data(table, "ab", 28, null);
        table.newRewrite().deleteFile(a2).deleteFile(b).addFile(compacted).commit();

        TableMetadata metadata = fresh(table);
        List<Snapshot> history = history(metadata);
        assertThat(metadata.formatVersion()).isEqualTo(version);
        assertThat(history).extracting(Snapshot::operation).containsExactly("append", "overwrite", "replace");
        assertThat(history).extracting(Snapshot::sequenceNumber).containsExactly(1L, 2L, 3L);
        assertThat(metadata.lastSequenceNumber()).isEqualTo(3);
        assertThat(history.get(1).summary())
                .containsEntry("added-data-files", "1").containsEntry("deleted-data-files", "1")
                .containsEntry("added-records", "8").containsEntry("deleted-records", "10")
                .containsEntry("total-data-files", "2").containsEntry("total-records", "28");
        assertThat(history.get(2).summary())
                .containsEntry("added-data-files", "1").containsEntry("deleted-data-files", "2")
                .containsEntry("added-records", "28").containsEntry("deleted-records", "28")
                .containsEntry("total-data-files", "1").containsEntry("total-records", "28");
        // The manifests the server's metadata names: the compaction added one file and removed two.
        assertThat(locations(history.get(2).addedDataFiles(table.io()))).containsExactly(compacted.location());
        assertThat(locations(history.get(2).removedDataFiles(table.io())))
                .containsExactlyInAnyOrder(a2.location(), b.location());
        if (version >= 3) {
            assertRowLineage(metadata);
        }
    }

    // 2. Row deltas

    /**
     * v2 merge-on-read: a position-delete file, then an equality-delete file on field 1. A row delta that only adds
     * deletes is a {@code delete}. Each delete file's data sequence number is its snapshot's, above the data file's,
     * so it applies to that file (spec, "Scan Planning").
     */
    @Test
    void positionAndEqualityDeletesOnV2() {
        Table table = create(namespace(), "deltas", 2, UNPARTITIONED);
        DataFile file = data(table, "a", 10, null);
        table.newAppend().appendFile(file).commit();
        table.newRowDelta().addDeletes(positionDeletes(table, file, 3)).commit();
        DeleteFile equalities = FileMetadata.deleteFileBuilder(table.spec())
                .ofEqualityDeletes(1)
                .withPath(table.location() + "/data/a-eq-deletes.parquet")
                .withFormat(FileFormat.PARQUET)
                .withFileSizeInBytes(64)
                .withRecordCount(2)
                .build();
        table.newRowDelta().addDeletes(equalities).commit();

        TableMetadata metadata = fresh(table);
        List<Snapshot> history = history(metadata);
        assertThat(history).extracting(Snapshot::operation).containsExactly("append", "delete", "delete");
        assertThat(history).extracting(Snapshot::sequenceNumber).containsExactly(1L, 2L, 3L);
        assertThat(history.get(1).summary())
                .containsEntry("added-delete-files", "1").containsEntry("added-position-delete-files", "1")
                .containsEntry("added-position-deletes", "3").containsEntry("total-delete-files", "1")
                .containsEntry("total-position-deletes", "3").containsEntry("total-equality-deletes", "0")
                .doesNotContainKey("added-dvs");
        assertThat(history.get(2).summary())
                .containsEntry("added-delete-files", "1").containsEntry("added-equality-delete-files", "1")
                .containsEntry("added-equality-deletes", "2").containsEntry("total-delete-files", "2")
                .containsEntry("total-position-deletes", "3").containsEntry("total-equality-deletes", "2");

        DeleteFile storedPositions = only(history.get(1).addedDeleteFiles(table.io()));
        DeleteFile storedEqualities = only(history.get(2).addedDeleteFiles(table.io()));
        assertThat(only(history.get(0).addedDataFiles(table.io())).dataSequenceNumber()).isEqualTo(1L);
        assertThat(storedPositions.content()).isEqualTo(FileContent.POSITION_DELETES);
        assertThat(storedPositions.dataSequenceNumber()).isEqualTo(2L);
        assertThat(storedEqualities.content()).isEqualTo(FileContent.EQUALITY_DELETES);
        assertThat(storedEqualities.equalityFieldIds()).containsExactly(1);
        assertThat(storedEqualities.dataSequenceNumber()).isEqualTo(3L);
    }

    /**
     * v3 deletion vectors: a Puffin blob per data file, named by referenced-data-file, content-offset and
     * content-size. Deletes alone are a {@code delete}; new rows with deletes (an update) are an {@code overwrite}.
     * A DV counts as a DV, not a position-delete file, and its size in the summary is its blob's, not the file's.
     */
    @Test
    void deletionVectorsOnV3() {
        Table table = create(namespace(), "dvs", 3, UNPARTITIONED);
        DataFile a = data(table, "a", 10, null);
        DataFile b = data(table, "b", 6, null);
        table.newAppend().appendFile(a).appendFile(b).commit();
        table.newRowDelta().addDeletes(dv(table, a, 3)).commit();
        table.newRowDelta().addRows(data(table, "c", 2, null)).addDeletes(dv(table, b, 2)).commit();

        TableMetadata metadata = fresh(table);
        List<Snapshot> history = history(metadata);
        assertThat(history).extracting(Snapshot::operation).containsExactly("append", "delete", "overwrite");
        assertThat(history).extracting(Snapshot::sequenceNumber).containsExactly(1L, 2L, 3L);
        assertThat(history.get(1).summary())
                .containsEntry("added-delete-files", "1").containsEntry("added-dvs", "1")
                .containsEntry("added-position-deletes", "3").containsEntry("added-files-size", "40")
                .containsEntry("total-delete-files", "1").containsEntry("total-position-deletes", "3")
                .doesNotContainKey("added-position-delete-files");
        assertThat(history.get(2).summary())
                .containsEntry("added-data-files", "1").containsEntry("added-records", "2")
                .containsEntry("added-dvs", "1").containsEntry("total-delete-files", "2")
                .containsEntry("total-position-deletes", "5").containsEntry("total-data-files", "3");

        DeleteFile stored = only(history.get(1).addedDeleteFiles(table.io()));
        assertThat(stored.format()).isEqualTo(FileFormat.PUFFIN);
        assertThat(stored.referencedDataFile()).isEqualTo(a.location());
        assertThat(stored.contentOffset()).isEqualTo(4L);
        assertThat(stored.contentSizeInBytes()).isEqualTo(40L);
        assertThat(stored.dataSequenceNumber()).isEqualTo(2L);
        assertThat(history.get(0).addedDataFiles(table.io())).extracting(DataFile::dataSequenceNumber)
                .containsExactly(1L, 1L);
        assertThat(only(history.get(2).addedDataFiles(table.io())).dataSequenceNumber()).isEqualTo(3L);
        assertRowLineage(metadata);
    }

    /**
     * A position-delete file on v3 and a deletion vector on v2 are both invalid (spec: v3 position deletes are
     * DVs; DVs are v3 only). The client refuses each before sending anything: its row delta checks every delete
     * file against the table's format version. The server could not: the add-snapshot it receives names only a
     * manifest list, and the catalog does not read manifests (D11, Backstops). The tables stay as they were.
     */
    @Test
    void positionDeleteFilesOnV3AndDeletionVectorsOnV2AreRefusedByTheClient() {
        Namespace namespace = namespace();
        Table v2 = create(namespace, "v2", 2, UNPARTITIONED);
        Table v3 = create(namespace, "v3", 3, UNPARTITIONED);
        DataFile onV2 = data(v2, "a", 10, null);
        DataFile onV3 = data(v3, "a", 10, null);
        v2.newAppend().appendFile(onV2).commit();
        v3.newAppend().appendFile(onV3).commit();

        assertThatThrownBy(() -> v2.newRowDelta().addDeletes(dv(v2, onV2, 1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Must not use DVs for position deletes in V2");
        assertThatThrownBy(() -> v3.newRowDelta().addDeletes(positionDeletes(v3, onV3, 1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Must use DVs for position deletes in V3");

        assertThat(fresh(v2).snapshots()).singleElement().extracting(Snapshot::operation).isEqualTo("append");
        assertThat(fresh(v3).snapshots()).singleElement().extracting(Snapshot::operation).isEqualTo("append");
    }

    // 3. Concurrent row-level conflicts

    /**
     * The rival overwrites file F while the writer deletes rows from F. The rival's first attempt reads the table,
     * validates and writes its manifest list; the writer's DV on F commits just then ({@link InterleavingFileIO}),
     * so the attempt's request is stale and refused (409). The retry re-reads the table, finds the DV, and fails
     * validation. The first attempt validated before the DV existed, so only the retry can have seen it. The
     * writer's delete stays.
     */
    @Test
    void anOverwriteOfAFileThatGainedDeletesFailsAfterItsRetry() {
        Table mine = create(namespace(), "conflict", 3, BY_P);
        DataFile file = data(mine, "f", 10, 1);
        mine.newAppend().appendFile(file).commit();
        long start = mine.currentSnapshot().snapshotId();
        Table theirs = rival.loadTable(identifier(mine));
        InterleavingFileIO.beforeNextManifestList(() -> mine.newRowDelta().addDeletes(dv(mine, file, 2))
                .validateFromSnapshot(start).validateNoConflictingDataFiles().validateNoConflictingDeleteFiles()
                .commit());

        OverwriteFiles overwrite = theirs.newOverwrite().deleteFile(file).addFile(data(theirs, "f2", 10, 1))
                .validateFromSnapshot(start).validateNoConflictingDeletes();
        assertThatThrownBy(overwrite::commit).isInstanceOf(ValidationException.class)
                .hasMessageContaining("found new delete for replaced data file")
                .hasMessageContaining(file.location());

        assertTheWritersDeleteStands(fresh(mine), mine.currentSnapshot().snapshotId());
    }

    /** As above, with a rival row delta that also deletes from F; its retry names the writer's DV. */
    @Test
    void aRowDeltaOnAFileThatGainedDeletesFailsAfterItsRetry() {
        Table mine = create(namespace(), "conflict", 3, BY_P);
        DataFile file = data(mine, "f", 10, 1);
        mine.newAppend().appendFile(file).commit();
        long start = mine.currentSnapshot().snapshotId();
        Table theirs = rival.loadTable(identifier(mine));
        DeleteFile first = dv(mine, file, 2);
        InterleavingFileIO.beforeNextManifestList(() -> mine.newRowDelta().addDeletes(first)
                .validateFromSnapshot(start).validateNoConflictingDataFiles().validateNoConflictingDeleteFiles()
                .commit());

        RowDelta second = theirs.newRowDelta().addDeletes(dv(theirs, file, 1))
                .validateFromSnapshot(start).validateNoConflictingDataFiles().validateNoConflictingDeleteFiles();
        assertThatThrownBy(second::commit).isInstanceOf(ValidationException.class)
                .hasMessageContaining(first.location());

        assertTheWritersDeleteStands(fresh(mine), mine.currentSnapshot().snapshotId());
    }

    /**
     * Row deltas on different partitions, each validated against its own partition only. The writer's commits
     * during the rival's first attempt, as above, so that attempt is refused (409); the retry re-reads the table,
     * validates and commits on top of the writer's. The first attempt read the table before the writer's snapshot
     * existed, so a parent of the writer's snapshot shows the retry.
     */
    @Test
    void rowDeltasOnDifferentPartitionsBothCommitAfterARetry() {
        Table mine = create(namespace(), "partitions", 3, BY_P);
        DataFile one = data(mine, "one", 10, 1);
        DataFile two = data(mine, "two", 10, 2);
        mine.newAppend().appendFile(one).appendFile(two).commit();
        long start = mine.currentSnapshot().snapshotId();
        Table theirs = rival.loadTable(identifier(mine));
        InterleavingFileIO.beforeNextManifestList(() -> mine.newRowDelta().addDeletes(dv(mine, one, 1))
                .validateFromSnapshot(start).conflictDetectionFilter(Expressions.equal("p", 1))
                .validateNoConflictingDataFiles().validateNoConflictingDeleteFiles()
                .commit());

        theirs.newRowDelta().addDeletes(dv(theirs, two, 1))
                .validateFromSnapshot(start).conflictDetectionFilter(Expressions.equal("p", 2))
                .validateNoConflictingDataFiles().validateNoConflictingDeleteFiles()
                .commit();
        long minesId = mine.currentSnapshot().snapshotId();

        TableMetadata metadata = fresh(mine);
        List<Snapshot> history = history(metadata);
        assertThat(history).extracting(Snapshot::operation).containsExactly("append", "delete", "delete");
        assertThat(history).extracting(Snapshot::sequenceNumber).containsExactly(1L, 2L, 3L);
        assertThat(history.get(1).snapshotId()).isEqualTo(minesId);
        assertThat(history.get(2).parentId()).isEqualTo(minesId);
        assertThat(metadata.currentSnapshot().summary())
                .containsEntry("total-delete-files", "2").containsEntry("total-position-deletes", "2");
    }

    // 4. Branches, tags, write-audit-publish

    /** A commit to a branch leaves main alone; main then fast-forwards to it. A tag comes and goes. */
    @Test
    void branchCommitFastForwardAndTag() {
        Table table = create(namespace(), "branches", 3, UNPARTITIONED);
        table.newAppend().appendFile(data(table, "a", 10, null)).commit();
        long first = table.currentSnapshot().snapshotId();
        table.manageSnapshots().createBranch("audit", first).createTag("release", first).commit();
        table.newAppend().appendFile(data(table, "b", 5, null)).toBranch("audit").commit();

        TableMetadata branched = fresh(table);
        long onBranch = branched.ref("audit").snapshotId();
        assertThat(branched.ref("main").snapshotId()).isEqualTo(first);
        assertThat(branched.currentSnapshot().snapshotId()).isEqualTo(first);
        assertThat(branched.snapshot(onBranch).parentId()).isEqualTo(first);
        assertThat(branched.snapshot(onBranch).operation()).isEqualTo("append");
        // A branch commit is not main's history.
        assertThat(branched.snapshotLog()).extracting(entry -> entry.snapshotId()).containsExactly(first);
        assertThat(branched.ref("release").isTag()).isTrue();
        assertThat(branched.ref("release").snapshotId()).isEqualTo(first);

        table.manageSnapshots().fastForwardBranch("main", "audit").removeTag("release").commit();
        TableMetadata forwarded = fresh(table);
        assertThat(forwarded.ref("main").snapshotId()).isEqualTo(onBranch);
        assertThat(forwarded.ref("audit").snapshotId()).isEqualTo(onBranch);
        assertThat(forwarded.refs()).containsOnlyKeys("main", "audit");
        assertThat(forwarded.snapshotLog()).extracting(entry -> entry.snapshotId()).containsExactly(first, onBranch);
        assertThat(forwarded.snapshots()).hasSize(2);
    }

    /**
     * Write-audit-publish: a snapshot staged with a wap.id leaves main where it is; once main has moved on, a
     * cherry-pick publishes it as a new snapshot on main that records the wap.id and its source.
     */
    @Test
    void writeAuditPublishCherryPicksAStagedSnapshot() {
        Table table = create(namespace(), "wap", 3, UNPARTITIONED,
                Map.of(TableProperties.WRITE_AUDIT_PUBLISH_ENABLED, "true"));
        table.newAppend().appendFile(data(table, "a", 10, null)).commit();
        long first = table.currentSnapshot().snapshotId();
        DataFile staged = data(table, "staged", 4, null);
        table.newAppend().appendFile(staged).set("wap.id", "audit-7").stageOnly().commit();

        TableMetadata afterStaging = fresh(table);
        Snapshot wap = afterStaging.snapshots().stream()
                .filter(s -> "audit-7".equals(s.summary().get("wap.id"))).findFirst().orElseThrow();
        assertThat(afterStaging.currentSnapshot().snapshotId()).isEqualTo(first);
        assertThat(wap.parentId()).isEqualTo(first);
        assertThat(afterStaging.refs()).containsOnlyKeys("main");
        assertThat(afterStaging.properties()).containsEntry(TableProperties.WRITE_AUDIT_PUBLISH_ENABLED, "true");

        table.newAppend().appendFile(data(table, "b", 3, null)).commit();
        long second = table.currentSnapshot().snapshotId();
        table.manageSnapshots().cherrypick(wap.snapshotId()).commit();

        Snapshot picked = fresh(table).currentSnapshot();
        assertThat(picked.snapshotId()).isNotIn(first, second, wap.snapshotId());
        assertThat(picked.parentId()).isEqualTo(second);
        assertThat(picked.operation()).isEqualTo("append");
        assertThat(picked.summary())
                .containsEntry("published-wap-id", "audit-7")
                .containsEntry("source-snapshot-id", String.valueOf(wap.snapshotId()))
                .containsEntry("added-data-files", "1").containsEntry("total-data-files", "3");
        assertThat(locations(picked.addedDataFiles(table.io()))).containsExactly(staged.location());
    }

    /**
     * The clock-skew rule (D9, D11; Rules.clockSkew, one minute): a new snapshot may not be stamped further ahead
     * of the catalog's clock than the skew. On main the client's own metadata builder already refuses a snapshot
     * a minute ahead of its clock; off main nothing in the client does, so a client whose clock runs fast, or one
     * that commits metadata through TableOperations, reaches the catalog with it. A branch snapshot 30 s ahead is
     * inside the rule and lands; one 10 minutes ahead is refused (400) and leaves nothing.
     */
    @Test
    void aBranchSnapshotMayRunAheadOfTheCatalogClockOnlyWithinTheSkew() {
        Table table = create(namespace(), "skew", 3, UNPARTITIONED);
        table.newAppend().appendFile(data(table, "a", 10, null)).commit();
        TableOperations ops = ((HasTableOperations) table).operations();

        TableMetadata base = ops.current();
        Snapshot near = snapshot(base, ops.newSnapshotId(), System.currentTimeMillis() + 30_000, base.nextRowId());
        ops.commit(base, TableMetadata.buildFrom(base).addSnapshot(near)
                .setRef("near", SnapshotRef.branchBuilder(near.snapshotId()).build()).build());
        TableMetadata accepted = fresh(table);
        assertThat(accepted.ref("near").snapshotId()).isEqualTo(near.snapshotId());
        assertThat(accepted.snapshot(near.snapshotId()).timestampMillis()).isEqualTo(near.timestampMillis());
        assertThat(accepted.currentSnapshot().snapshotId()).isEqualTo(base.currentSnapshot().snapshotId());

        TableMetadata current = ops.current();
        Snapshot far = snapshot(current, ops.newSnapshotId(), System.currentTimeMillis() + 600_000,
                current.nextRowId());
        TableMetadata ahead = TableMetadata.buildFrom(current).addSnapshot(far)
                .setRef("far", SnapshotRef.branchBuilder(far.snapshotId()).build()).build();
        assertThatThrownBy(() -> ops.commit(current, ahead)).isInstanceOf(BadRequestException.class)
                .hasMessageContaining("ahead of the catalog clock");
        TableMetadata refused = fresh(table);
        assertThat(refused.refs()).containsOnlyKeys("main", "near");
        assertThat(refused.snapshot(far.snapshotId())).isNull();

        // On main the client never sends it.
        assertThatThrownBy(() -> TableMetadata.buildFrom(current).setBranchSnapshot(far, "main").build())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Invalid update timestamp");
    }

    // 5. Snapshot expiry

    @Test
    void expiringASnapshotRemovesIt() {
        Table table = create(namespace(), "expire", 3, UNPARTITIONED);
        table.newAppend().appendFile(data(table, "a", 5, null)).commit();
        long first = table.currentSnapshot().snapshotId();
        table.newAppend().appendFile(data(table, "b", 5, null)).commit();
        long second = table.currentSnapshot().snapshotId();

        table.expireSnapshots().expireSnapshotId(first).cleanExpiredFiles(false).commit();

        TableMetadata metadata = fresh(table);
        assertThat(metadata.snapshots()).extracting(Snapshot::snapshotId).containsExactly(second);
        assertThat(metadata.snapshot(first)).isNull();
        assertThat(metadata.currentSnapshot().snapshotId()).isEqualTo(second);
    }

    /**
     * D24: a table under legal hold keeps every snapshot. A tag steward places the hold through the management
     * API; the writer's expiry is then refused (403, ForbiddenException in the client) and changes nothing. Once
     * the hold is released the same expiry goes through, so it was the hold that refused it.
     */
    @Test
    void expiryOnAHeldTableIsRefused() {
        Table table = create(namespace(), "held", 3, UNPARTITIONED);
        table.newAppend().appendFile(data(table, "a", 5, null)).commit();
        long first = table.currentSnapshot().snapshotId();
        table.newAppend().appendFile(data(table, "b", 5, null)).commit();
        String hold = placeHold(identifier(table), "litigation 12");

        assertThatThrownBy(() -> table.expireSnapshots().expireSnapshotId(first).cleanExpiredFiles(false).commit())
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("under legal hold");
        assertThat(fresh(table).snapshots()).hasSize(2);

        releaseHold(identifier(table), hold);
        table.expireSnapshots().expireSnapshotId(first).cleanExpiredFiles(false).commit();
        assertThat(fresh(table).snapshots()).hasSize(1);
    }

    // 6. v3 row lineage

    /**
     * Spec, "Row Lineage": a snapshot's first-row-id is the table's next-row-id before the commit, and next-row-id
     * then advances by the snapshot's added-rows (the rows of data manifests new in it; none for deletes). The
     * client proposes first-row-id and added-rows in its add-snapshot; it never sends next-row-id, which the server
     * works out. A rival whose first attempt proposed row IDs from 10, while the writer's append took 10 to 14, is
     * refused (409) and proposes 15 on its retry. A request that names a row ID already used, with every
     * requirement met, is refused (409) too. No two snapshots share row IDs.
     */
    @Test
    void v3RowLineageIsAssignedFromTheTablesNextRowId() {
        Table mine = create(namespace(), "lineage", 3, UNPARTITIONED);
        assertThat(fresh(mine).nextRowId()).isZero();

        DataFile ten = data(mine, "ten", 10, null);
        mine.newAppend().appendFile(ten).commit();
        Table theirs = rival.loadTable(identifier(mine));
        InterleavingFileIO.beforeNextManifestList(
                () -> mine.newAppend().appendFile(data(mine, "five", 5, null)).commit());
        theirs.newAppend().appendFile(data(theirs, "seven", 7, null)).commit();
        mine.newRowDelta().addDeletes(dv(mine, ten, 4)).commit();

        TableMetadata metadata = fresh(mine);
        List<Snapshot> history = history(metadata);
        assertThat(history).extracting(Snapshot::operation).containsExactly("append", "append", "append", "delete");
        assertThat(history).extracting(Snapshot::firstRowId).containsExactly(0L, 10L, 15L, 22L);
        assertThat(history).extracting(Snapshot::addedRows).containsExactly(10L, 5L, 7L, 0L);
        assertThat(metadata.nextRowId()).isEqualTo(22L);

        // first-row-id 15 is behind next-row-id 22; the requirements hold, so only the row-ID check can refuse it.
        TableMetadata base = ((HasTableOperations) mine).operations().refresh();
        Snapshot stale = snapshot(base, ((HasTableOperations) mine).operations().newSnapshotId(),
                System.currentTimeMillis(), 15L);
        String request = UpdateTableRequestParser.toJson(new UpdateTableRequest(
                List.of(new UpdateRequirement.AssertTableUUID(base.uuid()),
                        new UpdateRequirement.AssertRefSnapshotID("main", base.currentSnapshot().snapshotId())),
                List.of(new MetadataUpdate.AddSnapshot(stale),
                        new MetadataUpdate.SetSnapshotRef("main", stale.snapshotId(), SnapshotRefType.BRANCH,
                                null, null, null))));
        HttpResponse<String> refused = send(HttpRequest.newBuilder(table(identifier(mine)))
                .header("Authorization", "Bearer " + ConformanceServer.token())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(request)));
        assertThat(refused.statusCode()).as(refused.body()).isEqualTo(409);
        assertThat(refused.body()).contains("first-row-id is behind table next-row-id");
        assertThat(fresh(mine).snapshots()).hasSize(4);
    }

    // 7. Lazy snapshot loading (D14)

    /**
     * {@code snapshots=refs} returns only the snapshots a branch or tag points at, and drops the statistics of the
     * others with them; the ETag differs by mode, so neither mode's tag answers 304 for the other. A client in
     * refs mode fetches the full list when it needs an unreferenced snapshot, so time travel to one works, and a
     * commit it makes keeps every snapshot it never saw.
     */
    @Test
    void refsModeLoadsReferencedSnapshotsAndFetchesTheRestOnDemand() throws IOException {
        Table table = create(namespace(), "lazy", 3, UNPARTITIONED);
        DataFile a = data(table, "a", 5, null);
        table.newAppend().appendFile(a).commit();
        long first = table.currentSnapshot().snapshotId();
        table.newAppend().appendFile(data(table, "b", 5, null)).commit();
        long second = table.currentSnapshot().snapshotId();
        table.updateStatistics().setStatistics(statistics(table, first)).commit();
        TableIdentifier id = identifier(table);

        HttpResponse<String> refs = get(id, "refs", null);
        HttpResponse<String> all = get(id, "all", null);
        TableMetadata referenced = LoadTableResponseParser.fromJson(refs.body()).tableMetadata();
        TableMetadata everything = LoadTableResponseParser.fromJson(all.body()).tableMetadata();
        assertThat(referenced.snapshots()).extracting(Snapshot::snapshotId).containsExactly(second);
        assertThat(referenced.statisticsFiles()).isEmpty();
        assertThat(everything.snapshots()).extracting(Snapshot::snapshotId).containsExactly(first, second);
        assertThat(everything.statisticsFiles()).extracting(StatisticsFile::snapshotId).containsExactly(first);

        String refsTag = refs.headers().firstValue("ETag").orElseThrow();
        String allTag = all.headers().firstValue("ETag").orElseThrow();
        assertThat(refsTag).isNotEqualTo(allTag);
        assertThat(get(id, "refs", refsTag).statusCode()).isEqualTo(304);
        assertThat(get(id, "all", refsTag).statusCode()).isEqualTo(200);

        // Metrics reports go out on a background thread; this client closes straight after its commit.
        try (RESTCatalog lazy = client(Map.of("snapshot-loading-mode", "refs", "rest-metrics-reporting-enabled",
                "false"))) {
            Table loaded = lazy.loadTable(id);
            assertThat(loaded.currentSnapshot().snapshotId()).isEqualTo(second);
            Snapshot unreferenced = loaded.snapshot(first);
            assertThat(unreferenced).isNotNull();
            assertThat(unreferenced.sequenceNumber()).isEqualTo(1L);
            assertThat(loaded.snapshots()).extracting(Snapshot::snapshotId).containsExactly(first, second);
            try (CloseableIterable<FileScanTask> tasks = loaded.newScan().useSnapshot(first).planFiles()) {
                assertThat(tasks).extracting(task -> task.file().location()).containsExactly(a.location());
            }

            loaded.newAppend().appendFile(data(loaded, "c", 5, null)).commit();
        }
        TableMetadata after = fresh(table);
        assertThat(after.snapshots()).hasSize(3);
        assertThat(after.currentSnapshot().parentId()).isEqualTo(second);
        assertThat(after.statisticsFiles()).extracting(StatisticsFile::snapshotId).containsExactly(first);
    }

    // 8. Statistics

    /** Both kinds of statistics file, set and then removed, each in one transaction (one commit). */
    @Test
    void statisticsAndPartitionStatisticsAreSetAndRemoved() {
        Table table = create(namespace(), "stats", 3, UNPARTITIONED);
        table.newAppend().appendFile(data(table, "a", 10, null)).commit();
        long snapshot = table.currentSnapshot().snapshotId();
        StatisticsFile statistics = statistics(table, snapshot);
        PartitionStatisticsFile partitionStatistics = ImmutableGenericPartitionStatisticsFile.builder()
                .snapshotId(snapshot)
                .path(table.location() + "/metadata/partition-stats-" + snapshot + ".parquet")
                .fileSizeInBytes(321)
                .build();

        Transaction set = table.newTransaction();
        set.updateStatistics().setStatistics(statistics).commit();
        set.updatePartitionStatistics().setPartitionStatistics(partitionStatistics).commit();
        set.commitTransaction();

        TableMetadata withStatistics = fresh(table);
        StatisticsFile stored = only(withStatistics.statisticsFiles());
        assertThat(stored.snapshotId()).isEqualTo(snapshot);
        assertThat(stored.path()).isEqualTo(statistics.path());
        assertThat(stored.fileSizeInBytes()).isEqualTo(512L);
        assertThat(stored.fileFooterSizeInBytes()).isEqualTo(64L);
        assertThat(only(stored.blobMetadata()).type()).isEqualTo("apache-datasketches-theta-v1");
        assertThat(only(stored.blobMetadata()).fields()).containsExactly(1);
        assertThat(only(stored.blobMetadata()).properties()).containsEntry("ndv", "10");
        PartitionStatisticsFile storedPartitions = only(withStatistics.partitionStatisticsFiles());
        assertThat(storedPartitions.snapshotId()).isEqualTo(snapshot);
        assertThat(storedPartitions.path()).isEqualTo(partitionStatistics.path());
        assertThat(storedPartitions.fileSizeInBytes()).isEqualTo(321L);
        // Statistics change no data: main and its history are where they were.
        assertThat(withStatistics.snapshots()).extracting(Snapshot::snapshotId).containsExactly(snapshot);

        Transaction remove = table.newTransaction();
        remove.updateStatistics().removeStatistics(snapshot).commit();
        remove.updatePartitionStatistics().removePartitionStatistics(snapshot).commit();
        remove.commitTransaction();
        TableMetadata removed = fresh(table);
        assertThat(removed.statisticsFiles()).isEmpty();
        assertThat(removed.partitionStatisticsFiles()).isEmpty();
    }

    // Helpers

    private static RESTCatalog client(Map<String, String> extra) {
        Map<String, String> properties = new HashMap<>(ConformanceServer.clientStorage());
        properties.put(CatalogProperties.URI, ConformanceServer.start().toString());
        properties.put(CatalogProperties.WAREHOUSE_LOCATION, ConformanceServer.WAREHOUSE);
        properties.put("token", ConformanceServer.token());
        properties.putAll(extra);
        RESTCatalog client = new RESTCatalog();
        client.setConf(new Configuration());
        client.initialize("operations", properties);
        return client;
    }

    private static Namespace namespace() {
        Namespace namespace = Namespace.of("ops_" + System.nanoTime());
        writer.createNamespace(namespace);
        return namespace;
    }

    private static Table create(Namespace namespace, String name, int version, PartitionSpec spec) {
        return create(namespace, name, version, spec, Map.of());
    }

    /**
     * A table at {@code version} made by the writer. Commit retries wait 5 to 20 ms instead of the client's
     * 100 ms and up, so a test that expects one stays fast.
     */
    private static Table create(Namespace namespace, String name, int version, PartitionSpec spec,
                                Map<String, String> properties) {
        return writer.buildTable(TableIdentifier.of(namespace, name), SCHEMA)
                .withPartitionSpec(spec)
                .withProperties(properties)
                .withProperty(TableProperties.FORMAT_VERSION, String.valueOf(version))
                .withProperty(TableProperties.COMMIT_MIN_RETRY_WAIT_MS, "5")
                .withProperty(TableProperties.COMMIT_MAX_RETRY_WAIT_MS, "20")
                .create();
    }

    private static TableIdentifier identifier(Table table) {
        // A REST table's name is the catalog name, then the identifier.
        String[] parts = table.name().split("\\.");
        return TableIdentifier.of(parts[1], parts[2]);
    }

    private static DataFile data(Table table, String name, long rows, Integer partition) {
        DataFiles.Builder builder = DataFiles.builder(table.spec())
                .withPath(table.location() + "/data/" + name + "-" + UUID.randomUUID() + ".parquet")
                .withFormat(FileFormat.PARQUET)
                .withFileSizeInBytes(1000 + rows)
                .withRecordCount(rows);
        if (partition != null) {
            builder.withPartitionPath("p=" + partition);
        }
        return builder.build();
    }

    private static DeleteFile positionDeletes(Table table, DataFile target, long rows) {
        return FileMetadata.deleteFileBuilder(table.spec())
                .ofPositionDeletes()
                .withPath(table.location() + "/data/" + UUID.randomUUID() + "-deletes.parquet")
                .withFormat(FileFormat.PARQUET)
                .withFileSizeInBytes(128)
                .withRecordCount(rows)
                .withPartition(target.partition())
                .build();
    }

    /** A deletion vector for {@code target}: a 40-byte blob at offset 4 of a 100-byte Puffin file. */
    private static DeleteFile dv(Table table, DataFile target, long rows) {
        return FileMetadata.deleteFileBuilder(table.spec())
                .ofPositionDeletes()
                .withPath(table.location() + "/data/" + UUID.randomUUID() + "-dv.puffin")
                .withFormat(FileFormat.PUFFIN)
                .withFileSizeInBytes(100)
                .withRecordCount(rows)
                .withReferencedDataFile(target.location())
                .withContentOffset(4)
                .withContentSizeInBytes(40)
                .withPartition(target.partition())
                .build();
    }

    private static StatisticsFile statistics(Table table, long snapshotId) {
        long sequenceNumber = table.snapshot(snapshotId).sequenceNumber();
        return new GenericStatisticsFile(snapshotId, table.location() + "/metadata/stats-" + snapshotId + ".puffin",
                512, 64, List.of(new GenericBlobMetadata("apache-datasketches-theta-v1", snapshotId, sequenceNumber,
                List.of(1), Map.of("ndv", "10"))));
    }

    /**
     * A v3 append snapshot on top of main, as a client would make one: the next sequence number, main's manifest
     * list (it adds no rows) and schema, and the given timestamp and first-row-id.
     */
    private static Snapshot snapshot(TableMetadata base, long snapshotId, long timestampMillis, long firstRowId) {
        Snapshot parent = base.currentSnapshot();
        return SnapshotParser.fromJson("""
                {"sequence-number": %d, "snapshot-id": %d, "parent-snapshot-id": %d, "timestamp-ms": %d,
                 "summary": {"operation": "append"}, "manifest-list": "%s", "schema-id": %d,
                 "first-row-id": %d, "added-rows": 0}""".formatted(base.lastSequenceNumber() + 1, snapshotId,
                parent.snapshotId(), timestampMillis, parent.manifestListLocation(), base.currentSchemaId(),
                firstRowId));
    }

    /** The table as the server returns it now: a plain GET, with no client cache or ETag in between. */
    private static TableMetadata fresh(Table table) {
        HttpResponse<String> response = get(identifier(table), "all", null);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return LoadTableResponseParser.fromJson(response.body()).tableMetadata();
    }

    private static HttpResponse<String> get(TableIdentifier id, String snapshots, String ifNoneMatch) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(table(id) + "?snapshots=" + snapshots))
                .header("Authorization", "Bearer " + ConformanceServer.token())
                .GET();
        if (ifNoneMatch != null) {
            request.header("If-None-Match", ifNoneMatch);
        }
        return send(request);
    }

    private static URI table(TableIdentifier id) {
        return ConformanceServer.start().resolve("v1/" + ConformanceServer.WAREHOUSE + "/namespaces/"
                + id.namespace().level(0) + "/tables/" + id.name());
    }

    private static URI management(TableIdentifier id) {
        return ConformanceServer.start().resolve("management/v1/catalogs/" + ConformanceServer.WAREHOUSE
                + "/namespaces/" + id.namespace().level(0) + "/tables/" + id.name());
    }

    /** Places a legal hold on the table as a tag steward (D24); returns the hold's id. */
    private static String placeHold(TableIdentifier id, String reason) {
        HttpResponse<String> response = send(HttpRequest.newBuilder(URI.create(management(id) + "/holds"))
                .header("Authorization", "Bearer " + ConformanceServer.stewardToken())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"reason\": \"" + reason + "\"}")));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        try {
            return JsonUtil.mapper().readTree(response.body()).get("id").asText();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void releaseHold(TableIdentifier id, String hold) {
        HttpResponse<String> response = send(HttpRequest.newBuilder(URI.create(management(id) + "/holds/" + hold))
                .header("Authorization", "Bearer " + ConformanceServer.stewardToken())
                .DELETE());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(204);
    }

    private static HttpResponse<String> send(HttpRequest.Builder request) {
        try {
            return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /** Main's snapshots, oldest first, by following parent IDs from the current one. */
    private static List<Snapshot> history(TableMetadata metadata) {
        List<Snapshot> history = new ArrayList<>();
        SnapshotUtil.ancestorsOf(metadata.currentSnapshot().snapshotId(), metadata::snapshot).forEach(history::add);
        Collections.reverse(history);
        return history;
    }

    /** Every snapshot on main took the next-row-id its predecessor left, and the table's is past the last one. */
    private static void assertRowLineage(TableMetadata metadata) {
        long next = 0;
        for (Snapshot snapshot : history(metadata)) {
            assertThat(snapshot.firstRowId()).as("first-row-id of %s", snapshot.snapshotId()).isEqualTo(next);
            next += snapshot.addedRows();
        }
        assertThat(metadata.nextRowId()).isEqualTo(next);
    }

    /** No lost update: main is the writer's delete, with its one DV, and nothing of the rival's landed. */
    private static void assertTheWritersDeleteStands(TableMetadata metadata, long deleted) {
        assertThat(metadata.currentSnapshot().snapshotId()).isEqualTo(deleted);
        assertThat(metadata.snapshots()).hasSize(2);
        assertThat(metadata.currentSnapshot().summary())
                .containsEntry("total-delete-files", "1").containsEntry("total-data-files", "1")
                .containsEntry("total-position-deletes", "2");
    }

    private static List<String> locations(Iterable<? extends ContentFile<?>> files) {
        List<String> locations = new ArrayList<>();
        files.forEach(file -> locations.add(file.location()));
        return locations;
    }

    private static <T> T only(Iterable<T> items) {
        List<T> all = new ArrayList<>();
        items.forEach(all::add);
        assertThat(all).hasSize(1);
        return all.getFirst();
    }

    /**
     * The rival's FileIO. A stock client re-reads the table at the start of every commit attempt, so it sends a
     * stale commit only when another lands between that read and its request. This puts one there on cue: the
     * runnable given to {@link #beforeNextManifestList} runs once, when the next attempt opens its manifest list
     * ({@code snap-*.avro}), after the attempt has read the table and validated, before it sends anything.
     */
    public static class InterleavingFileIO extends S3FileIO {

        private static final AtomicReference<Runnable> NEXT = new AtomicReference<>();

        static void beforeNextManifestList(Runnable commit) {
            NEXT.set(commit);
        }

        @Override
        public OutputFile newOutputFile(String path) {
            if (path.substring(path.lastIndexOf('/') + 1).startsWith("snap-")) {
                Runnable commit = NEXT.getAndSet(null);
                if (commit != null) {
                    commit.run();
                }
            }
            return super.newOutputFile(path);
        }
    }
}
