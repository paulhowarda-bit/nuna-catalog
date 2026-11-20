package nuna.catalog.core.commit;

import static org.assertj.core.api.Assertions.assertThat;

import nuna.catalog.core.principal.Caller;
import nuna.catalog.core.principal.Principal;
import nuna.catalog.store.CatalogObject;
import nuna.catalog.store.CatalogStore;
import nuna.catalog.store.CommitBatch;
import nuna.catalog.store.CommitResult;
import nuna.catalog.store.DrWatermark;
import nuna.catalog.store.GovernanceAttribute;
import nuna.catalog.store.JournalEntry;
import nuna.catalog.store.JournalRecord;
import nuna.catalog.store.Name;
import nuna.catalog.store.OutboxEvent;
import nuna.catalog.store.Page;
import nuna.catalog.store.PageRequest;
import nuna.catalog.store.PageToken;
import nuna.catalog.store.Pointer;
import nuna.catalog.store.PointerSwap;
import nuna.catalog.store.Securable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.apache.iceberg.CatalogProperties;
import org.apache.iceberg.DataFile;
import org.apache.iceberg.DataFiles;
import org.apache.iceberg.DeleteFile;
import org.apache.iceberg.FileFormat;
import org.apache.iceberg.FileMetadata;
import org.apache.iceberg.GenericStatisticsFile;
import org.apache.iceberg.HasTableOperations;
import org.apache.iceberg.ManifestContent;
import org.apache.iceberg.ManifestFile;
import org.apache.iceberg.ManifestFiles;
import org.apache.iceberg.ManifestReader;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.Schema;
import org.apache.iceberg.Snapshot;
import org.apache.iceberg.StatisticsFile;
import org.apache.iceberg.Table;
import org.apache.iceberg.TableMetadata;
import org.apache.iceberg.TableMetadataParser;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.inmemory.InMemoryCatalog;
import org.apache.iceberg.inmemory.InMemoryFileIO;
import org.apache.iceberg.io.SeekableInputStream;
import org.apache.iceberg.types.Types;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The walk with a file check (D37, amended 2026-11-20: "Walk with a file check"; item 83 folded into item 28). An
 * Iceberg client committed a table's versions on the primary; the store the reconcile runs against, the DR store,
 * holds what the test replicates to it, and the DR database's pointer names version 1. The walk moves the table forward
 * only through versions whose every added file, data included, is in that store, and stops at the first that lacks
 * one, saying which. A table whose pointer is above its DR watermark is checked from the watermark (D37, amended again
 * 2026-11-20: "Check from the watermark"): if a version between them lacks a file, the walk takes nothing for it and
 * reports it for the rewind. Against a store double holding the table's pointer, watermark and journal, so it needs no
 * database.
 */
class ReconcileFileCheckTest {

    private static final Schema SCHEMA = new Schema(Types.NestedField.required(1, "id", Types.LongType.get()));
    private static final Caller OPERATOR = new Caller(new Principal("operator", Set.of("platform-operators")),
            "dr-runbook", false, Optional.empty());
    private static final UUID NAMESPACE = UUID.randomUUID();

    /** The DR store, as the catalog servers in DR read it. */
    private final MemoryFiles dr = new MemoryFiles();
    private final Table table;
    private final InMemoryFileIO primary;
    /** Each version's metadata location, version 0 first: Iceberg's client numbers its files from 0. */
    private final List<String> versions = new ArrayList<>();
    private final CatalogObject object;
    private final List<PointerSwap> swapped = new ArrayList<>();
    private Pointer pointer;
    private Optional<DrWatermark> watermark = Optional.empty();
    /** Versions the DR database's journal lacks. */
    private final Set<Long> unjournaled = new HashSet<>();
    private final Reconcile reconcile;

    ReconcileFileCheckTest() {
        InMemoryCatalog catalog = new InMemoryCatalog();
        catalog.initialize("primary", Map.of(CatalogProperties.WAREHOUSE_LOCATION, MemoryFiles.ROOT));
        catalog.createNamespace(Namespace.of("ns"));
        table = catalog.createTable(TableIdentifier.of("ns", "t" + UUID.randomUUID().toString().replace("-", "")),
                SCHEMA, PartitionSpec.unpartitioned(), Map.of("format-version", "2"));
        primary = (InMemoryFileIO) table.io();
        committed();
        object = new CatalogObject(UUID.randomUUID(), table.uuid(), NAMESPACE,
                new Name("t", "t"), CatalogObject.Kind.TABLE, CatalogObject.State.ACTIVE, Optional.empty(),
                Optional.empty(), table.location(), Optional.empty());
        reconcile = new Reconcile(store(), dr, CommitSettings.DEFAULTS, java.time.Clock.systemUTC(), wait -> {
            throw new AssertionError("nothing waits here");
        });
    }

    // ---- the walk stops before the first version missing a file ----

    @Test
    @DisplayName("a version missing its data file, a manifest it adds or its manifest list stops the walk before it,"
            + " and the plan names the file")
    void theWalkStopsBeforeTheFirstVersionMissingAFile() {
        append("a");
        append("b");
        DataFile c = append("c");
        append("d");
        replicateAll();
        at(1);
        Snapshot third = metadata(3).currentSnapshot();
        ManifestFile added = third.allManifests(primary).stream()
                .filter(manifest -> manifest.snapshotId() == third.snapshotId()).findFirst().orElseThrow();

        assertThat(reconcile.plan(object, Set.of(versions.get(4))).steps()).as("with every file there")
                .extracting(Reconcile.Step::version).containsExactly(2L, 3L, 4L);
        for (String location : List.of(c.location(), added.path(), third.manifestListLocation())) {
            byte[] held = remove(location);

            Reconcile.Plan plan = reconcile.plan(object, Set.of(versions.get(4)));

            assertThat(plan.steps()).as("without %s", location).extracting(Reconcile.Step::location)
                    .containsExactly(versions.get(2));
            assertThat(plan.tips()).isEmpty();
            assertThat(plan.problem()).as("without %s", location).get().asString()
                    .contains("storage does not hold " + location).contains("which version 3 adds")
                    .contains(versions.get(3)).contains("the walk stops before it");
            dr.put(location, held);
        }
    }

    @Test
    @DisplayName("only the steps before the first missing file are swapped, each still one version per commit")
    void onlyTheCheckedStepsAreApplied() {
        append("a");
        append("b");
        DataFile c = append("c");
        replicateAll();
        at(1);
        remove(c.location());

        Reconcile.Applied applied = reconcile.apply(reconcile.plan(object, Set.of(versions.get(3))), OPERATOR,
                commit -> List.of(new OutboxEvent("test.commit", "{}")));

        assertThat(applied.stepsApplied()).isEqualTo(1);
        assertThat(applied.plan().problem()).get().asString().contains(c.location());
        assertThat(pointer.metadataLocation()).isEqualTo(versions.get(2));
        assertThat(swapped).extracting(swap -> swap.next().version()).containsExactly(2L);
    }

    @Test
    @DisplayName("delete files and statistics files are checked as data files are")
    void deleteAndStatisticsFilesAreChecked() {
        append("a");
        DeleteFile deletes = FileMetadata.deleteFileBuilder(table.spec()).ofPositionDeletes()
                .withPath(table.location() + "/data/deletes-" + UUID.randomUUID() + ".parquet")
                .withFormat(FileFormat.PARQUET).withFileSizeInBytes(10).withRecordCount(1).build();
        table.newRowDelta().addDeletes(deletes).commit();
        committed();
        StatisticsFile statistics = new GenericStatisticsFile(table.currentSnapshot().snapshotId(),
                table.location() + "/metadata/stats-" + UUID.randomUUID() + ".puffin", 10, 5, List.of());
        table.updateStatistics().setStatistics(statistics).commit();
        committed();
        table.updateProperties().set("owner", "dr").commit();
        committed();
        replicateAll();
        at(1);

        assertThat(reconcile.plan(object, Set.of(versions.get(4))).steps()).extracting(Reconcile.Step::version)
                .containsExactly(2L, 3L, 4L);
        byte[] held = remove(deletes.location());
        assertThat(reconcile.plan(object, Set.of(versions.get(4))).steps()).isEmpty();
        dr.put(deletes.location(), held);
        remove(statistics.path());
        Reconcile.Plan plan = reconcile.plan(object, Set.of(versions.get(4)));
        assertThat(plan.steps()).extracting(Reconcile.Step::version).containsExactly(2L);
        assertThat(plan.problem()).get().asString().contains(statistics.path()).contains("which version 3 adds");
    }

    @Test
    @DisplayName("a manifest that cannot be read stops the walk before the version that adds it")
    void anUnreadableManifestStopsTheWalk() {
        append("a");
        append("b");
        replicateAll();
        at(1);
        Snapshot second = metadata(2).currentSnapshot();
        ManifestFile added = second.allManifests(primary).stream()
                .filter(manifest -> manifest.snapshotId() == second.snapshotId()).findFirst().orElseThrow();
        dr.put(added.path(), new byte[] {1, 2, 3});

        Reconcile.Plan plan = reconcile.plan(object, Set.of(versions.get(2)));

        assertThat(plan.steps()).isEmpty();
        assertThat(plan.problem()).get().asString().contains("the files version 2 adds")
                .contains("cannot be checked");
    }

    // ---- the check from the watermark ----

    @Test
    @DisplayName("a pointer above its watermark walks on from the pointer when every version between them is whole")
    void aWholeStretchWalksOn() {
        append("a");
        append("b");
        append("c");
        append("d");
        replicateAll();
        at(3);
        watermark = Optional.of(new DrWatermark(1, versions.get(1), Instant.now()));

        Reconcile.Plan plan = reconcile.plan(object, Set.of(versions.get(4)));

        assertThat(plan.problem()).isEmpty();
        assertThat(plan.steps()).extracting(Reconcile.Step::version).containsExactly(4L);
        Reconcile.Applied applied = reconcile.apply(plan, OPERATOR,
                commit -> List.of(new OutboxEvent("test.commit", "{}")));
        assertThat(applied.stepsApplied()).isEqualTo(1);
        assertThat(pointer.metadataLocation()).isEqualTo(versions.get(4));
    }

    @Test
    @DisplayName("a version between the watermark and the pointer missing a file it adds, even one a later version"
            + " deletes, or its own metadata file, takes nothing for the table and reports it for the rewind")
    void aStretchMissingAFileTakesNothing() {
        DataFile b = deletedBeforeThePointer();
        watermark = Optional.of(new DrWatermark(1, versions.get(1), Instant.now()));
        assertThat(reconcile.plan(object, Set.of(versions.get(4))).steps()).as("with every file there")
                .extracting(Reconcile.Step::version).containsExactly(4L);
        String manifestList = metadata(2).currentSnapshot().manifestListLocation();

        for (String location : List.of(b.location(), manifestList, versions.get(2), versions.get(3))) {
            byte[] held = remove(location);

            Reconcile.Plan plan = reconcile.plan(object, Set.of(versions.get(4)));

            assertThat(plan.steps()).as("without %s", location).isEmpty();
            assertThat(plan.tips()).as("without %s", location).isEmpty();
            assertThat(plan.problem()).as("without %s", location).get().asString()
                    .contains("storage does not hold").contains(location)
                    .contains("between the DR watermark, version 1, and the pointer, version 3")
                    .contains("the walk takes nothing for the table; rewind it to its watermark");
            dr.put(location, held);
        }
        byte[] held = remove(b.location());
        assertThat(reconcile.plan(object, Set.of(versions.get(4))).problem()).get().asString()
                .contains("which version 2 adds (" + versions.get(2) + ")");
        dr.put(b.location(), held);
        remove(versions.get(3));
        assertThat(reconcile.plan(object, Set.of(versions.get(4))).problem()).get().asString()
                .contains("storage does not hold the pointer's file " + versions.get(3));
        assertThat(swapped).isEmpty();
    }

    @Test
    @DisplayName("a pointer at its watermark walks from the pointer as before: a file missing below it changes nothing,"
            + " and each step past it is checked")
    void aPointerAtItsWatermarkIsUnchanged() {
        DataFile b = deletedBeforeThePointer();
        watermark = Optional.of(new DrWatermark(3, versions.get(3), Instant.now()));

        walksFromThePointer(b);
    }

    @Test
    @DisplayName("a table with no watermark walks from its pointer as before: a file missing below it changes nothing,"
            + " and each step past it is checked")
    void noWatermarkIsUnchanged() {
        DataFile b = deletedBeforeThePointer();
        watermark = Optional.empty();

        walksFromThePointer(b);
    }

    @Test
    @DisplayName("versions between the watermark and the pointer that the journal does not hold one by one, or a"
            + " watermark whose file the store lacks, cannot be checked, and the walk takes nothing for the table")
    void aStretchThatCannotBeCheckedTakesNothing() {
        deletedBeforeThePointer();
        watermark = Optional.of(new DrWatermark(1, versions.get(1), Instant.now()));
        unjournaled.add(2L);

        Reconcile.Plan gap = reconcile.plan(object, Set.of(versions.get(4)));

        assertThat(gap.steps()).isEmpty();
        assertThat(gap.problem()).get().asString().contains("the journal does not hold every version between the DR"
                + " watermark, version 1, and the pointer, version 3, one by one");
        unjournaled.clear();
        remove(versions.get(1));

        Reconcile.Plan gone = reconcile.plan(object, Set.of(versions.get(4)));

        assertThat(gone.steps()).isEmpty();
        assertThat(gone.problem()).get().asString().contains("storage does not hold the DR watermark's file "
                + versions.get(1));
    }

    /**
     * Version 2 adds data file b and version 3 deletes it, so the pointer's version 3 and version 4 past it are whole
     * without b, but version 2 is not; the DR database's pointer names version 3, and the DR store holds every file.
     */
    private DataFile deletedBeforeThePointer() {
        append("a");
        DataFile b = append("b");
        table.newDelete().deleteFile(b).commit();
        committed();
        append("d");
        replicateAll();
        at(3);
        return b;
    }

    /** The walk from the pointer: b, which only version 2 adds, is not needed; d, which version 4 adds, is. */
    private void walksFromThePointer(DataFile b) {
        remove(b.location());

        assertThat(reconcile.plan(object, Set.of(versions.get(4))).steps()).extracting(Reconcile.Step::version)
                .containsExactly(4L);
        DataFile d = metadata(4).currentSnapshot().addedDataFiles(primary).iterator().next();
        remove(d.location());
        Reconcile.Plan plan = reconcile.plan(object, Set.of(versions.get(4)));
        assertThat(plan.steps()).isEmpty();
        assertThat(plan.problem()).get().asString().contains("storage does not hold " + d.location())
                .contains("which version 4 adds").contains("the walk stops before it");
    }

    // ---- the primary and the replication ----

    private DataFile append(String name) {
        DataFile file = DataFiles.builder(table.spec())
                .withPath(table.location() + "/data/" + name + "-" + UUID.randomUUID() + ".parquet")
                .withFormat(FileFormat.PARQUET).withFileSizeInBytes(10).withRecordCount(1).build();
        table.newAppend().appendFile(file).commit();
        committed();
        return file;
    }

    private void committed() {
        TableMetadata current = ((HasTableOperations) table).operations().refresh();
        versions.add(current.metadataFileLocation());
    }

    private TableMetadata metadata(int version) {
        return TableMetadataParser.read(primary, versions.get(version));
    }

    /** The DR database's pointer at {@code version}. */
    private void at(int version) {
        String location = versions.get(version);
        assertThat(Reconcile.version(location)).isEqualTo(version);
        pointer = new Pointer(location, version, MemoryFiles.sha256(dr.bytes(location)), new byte[0]);
    }

    /** Every version's metadata file, and every file the newest reaches, as a replicator that caught up would. */
    private void replicateAll() {
        versions.forEach(this::replicate);
        TableMetadata newest = metadata(versions.size() - 1);
        Set<String> files = new LinkedHashSet<>();
        for (Snapshot snapshot : newest.snapshots()) {
            files.add(snapshot.manifestListLocation());
            for (ManifestFile manifest : snapshot.allManifests(primary)) {
                files.add(manifest.path());
                try {
                    if (manifest.content() == ManifestContent.DATA) {
                        try (ManifestReader<DataFile> reader = ManifestFiles.read(manifest, primary,
                                newest.specsById())) {
                            reader.forEach(file -> files.add(file.location()));
                        }
                    } else {
                        try (ManifestReader<DeleteFile> reader = ManifestFiles.readDeleteManifest(manifest, primary,
                                newest.specsById())) {
                            reader.forEach(file -> files.add(file.location()));
                        }
                    }
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
        }
        newest.statisticsFiles().forEach(statistics -> files.add(statistics.path()));
        files.forEach(this::replicate);
    }

    /** One file to the DR store; a data file the client only named arrives as a placeholder. */
    private void replicate(String location) {
        dr.put(location, primary.fileExists(location) ? bytes(location) : new byte[] {1});
    }

    private byte[] remove(String location) {
        byte[] held = dr.bytes(location);
        dr.remove(location);
        return held;
    }

    private byte[] bytes(String location) {
        try (SeekableInputStream in = primary.newInputFile(location).newStream()) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ---- the store double ----

    /** The store calls a reconcile makes, over the one table; any other call fails the test. */
    private CatalogStore store() {
        return (CatalogStore) Proxy.newProxyInstance(CatalogStore.class.getClassLoader(),
                new Class<?>[] {CatalogStore.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "readPointer" -> Optional.of(pointer);
                    case "readDrWatermark" -> watermark;
                    case "readJournal" -> journal((PageRequest) args[3]);
                    case "readNamespace" -> Optional.empty();
                    case "attributesOf" -> List.of(new GovernanceAttribute(
                            new Securable(Securable.Kind.NAMESPACE, NAMESPACE), GovernanceAttribute.Kind.DR_FREEZE,
                            "DR drill", "operator", "operator", Instant.now()));
                    case "commit" -> {
                        CommitBatch batch = (CommitBatch) args[0];
                        PointerSwap swap = batch.swaps().getFirst();
                        if (!swap.expected().equals(Optional.of(pointer))) {
                            yield new CommitResult.Conflict(swap.key(), Optional.of(pointer));
                        }
                        pointer = swap.next();
                        swapped.add(swap);
                        yield new CommitResult.Committed(batch.commitId());
                    }
                    default -> throw new UnsupportedOperationException("a reconcile does not call " + method.getName());
                });
    }

    /**
     * The DR database's journal of the main pointer, in one page: a record for each version after the page's token up
     * to the pointer's, but those in {@link #unjournaled}.
     */
    private Page<JournalRecord> journal(PageRequest page) {
        long after = page.token().map(token -> Long.parseLong(PageToken.decode(token, 1).getFirst())).orElse(0L);
        List<JournalRecord> records = new ArrayList<>();
        for (long version = after + 1; version <= pointer.version(); version++) {
            if (!unjournaled.contains(version)) {
                records.add(new JournalRecord(UUID.randomUUID(), object.mainKey(), Instant.now(),
                        new JournalEntry("writer", null, "spark", "[]", "[]"),
                        Optional.of(versions.get((int) version - 1)), versions.get((int) version), version));
            }
        }
        return new Page<>(records, Optional.empty());
    }
}
