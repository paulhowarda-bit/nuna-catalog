package nuna.maintenance;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
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
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.exceptions.NotFoundException;
import org.apache.iceberg.inmemory.InMemoryCatalog;
import org.apache.iceberg.inmemory.InMemoryFileIO;
import org.apache.iceberg.inmemory.InMemoryInputFile;
import org.apache.iceberg.io.FileInfo;
import org.apache.iceberg.io.InputFile;
import org.apache.iceberg.io.OutputFile;
import org.apache.iceberg.io.SeekableInputStream;
import org.apache.iceberg.io.SupportsPrefixOperations;
import org.apache.iceberg.types.Types;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The DR check of one table (D37, amended 2026-11-20): two in-memory stores, the primary an Iceberg catalog commits
 * to, and the DR store, which holds what the test replicates to it. The watermark stops before the first version
 * missing any file it adds (its metadata file, manifest list, manifests, data and delete files, statistics files),
 * passes a commit that adds no snapshot on its metadata file alone and the version a rewind writes on its metadata file
 * alone, and a table with no watermark is checked whole in one listing, which gives it the newest version whole there
 * (item 99).
 */
class DrVerifyTest {

    private static final Schema SCHEMA = new Schema(Types.NestedField.required(1, "id", Types.LongType.get()));
    private static final Instant NOW = Instant.parse("2026-11-20T12:00:00Z");

    private final MemoryDr dr = new MemoryDr();
    private final DrVerify verify = new DrVerify(dr);
    private final Table table;
    private final InMemoryFileIO primary;
    /** Each version's metadata location, version 1 first. */
    private final List<String> versions = new ArrayList<>();

    DrVerifyTest() {
        InMemoryCatalog catalog = new InMemoryCatalog();
        catalog.initialize("primary", Map.of(CatalogProperties.WAREHOUSE_LOCATION, "s3://lake/dr-"
                + UUID.randomUUID()));
        catalog.createNamespace(Namespace.of("ns"));
        table = catalog.createTable(TableIdentifier.of("ns", "t"), SCHEMA, PartitionSpec.unpartitioned(),
                Map.of("format-version", "2"));
        primary = (InMemoryFileIO) table.io();
        committed();
    }

    // ---- the watermark stops at the first version missing a file ----

    @Test
    @DisplayName("a version missing its data file, a manifest, its manifest list or its metadata file stops the"
            + " watermark before it, and no later version that adds files passes")
    void theWatermarkStopsBeforeTheFirstVersionMissingAFile() {
        append("a");
        DataFile b = append("b");
        append("c");
        replicateAll();
        Snapshot third = metadata(3).currentSnapshot();
        ManifestFile added = third.allManifests(primary).stream()
                .filter(manifest -> manifest.snapshotId() == third.snapshotId()).findFirst().orElseThrow();
        List<String> missing = List.of(b.location(), added.path(), third.manifestListLocation(), versions.get(2));

        for (String location : missing) {
            byte[] held = dr.remove(location);

            DrVerify.Result result = verify.check(item(1, 4));

            assertThat(result.safe()).as("without %s", location).map(DrVerify.Version::version).contains(2L);
            assertThat(result.missing()).as("without %s", location).contains(location);
            dr.put(location, held);
        }
        assertThat(verify.check(item(1, 4)).safe()).map(DrVerify.Version::version).contains(4L);
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
        replicateAll();

        byte[] held = dr.remove(deletes.location());
        assertThat(verify.check(item(1, 4)).safe()).map(DrVerify.Version::version).contains(2L);
        dr.put(deletes.location(), held);
        dr.remove(statistics.path());
        assertThat(verify.check(item(1, 4)).safe()).map(DrVerify.Version::version).contains(3L);
        assertThat(verify.check(item(1, 4)).missing()).contains(statistics.path());
    }

    @Test
    @DisplayName("a commit that adds no snapshot passes on its metadata file alone")
    void aCommitWithoutASnapshotPassesOnItsMetadataFile() {
        append("a");
        table.updateProperties().set("owner", "dr").commit();
        committed();
        replicate(versions.get(0));
        replicate(versions.get(1));
        replicateFilesOf(metadata(2));

        assertThat(verify.check(item(2, 3)).missing()).contains(versions.get(2));
        assertThat(verify.check(item(2, 3)).safe()).isEmpty();

        replicate(versions.get(2));

        assertThat(verify.check(item(2, 3)).safe()).map(DrVerify.Version::version).contains(3L);
    }

    @Test
    @DisplayName("nothing above the watermark passes while its next version lacks a file: the check names no version"
            + " at or below the watermark")
    void nothingPassesWhileTheNextVersionLacksAFile() {
        append("a");
        DataFile b = append("b");
        replicateAll();
        dr.remove(b.location());

        DrVerify.Result result = verify.check(item(2, 3));

        assertThat(result.safe()).isEmpty();
        assertThat(result.missing()).contains(b.location());
    }

    @Test
    @DisplayName("the version a rewind writes, the watermark's metadata again, passes on its metadata file alone, past"
            + " the versions it gave up")
    void aRewindPassesOnItsMetadataFile() {
        append("a");
        DataFile b = append("b");
        DataFile c = append("c");
        replicateAll();
        dr.remove(b.location());
        dr.remove(c.location());
        dr.remove(versions.get(3));
        String rewound = table.location() + "/metadata/00005-" + UUID.randomUUID() + ".metadata.json";
        OutputFile copy = primary.newOutputFile(rewound);
        try (var out = copy.createOrOverwrite()) {
            out.write(bytes(versions.get(1)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        versions.add(rewound);

        assertThat(verify.check(item(2, 5)).safe()).as("not yet in the DR store").isEmpty();

        replicate(rewound);

        assertThat(verify.check(item(2, 5)).safe()).map(DrVerify.Version::version).contains(5L);
        assertThat(verify.check(item(2, 5)).missing()).isEmpty();
    }

    // ---- whole checks ----

    @Test
    @DisplayName("a table with no watermark is checked whole at its pointer, every file its snapshots reach found in"
            + " one listing of its location")
    void aTableWithNoWatermarkIsCheckedWholeInOneListing() {
        DataFile a = append("a");
        append("b");
        replicateAll();

        DrVerify.Result whole = verify.check(unwatermarked(3));

        assertThat(whole.safe()).map(DrVerify.Version::version).contains(3L);
        assertThat(dr.listings.get()).isEqualTo(1);
        assertThat(dr.heads.get()).as("everything is under the table's location").isZero();

        dr.remove(a.location());

        assertThat(verify.check(unwatermarked(3)).safe()).isEmpty();
        assertThat(verify.check(unwatermarked(3)).missing()).contains(a.location());
    }

    @Test
    @DisplayName("a busy table with no watermark, whose newest commits are still on their way to the DR store, gets the"
            + " newest version whole there from the one listing")
    void aBusyTableGetsTheNewestWholeVersion() {
        append("a");
        append("b");
        append("c");
        append("d");
        DataFile e = append("e");
        replicateAll();
        // Behind: version 6's metadata file, manifest list and data file, and version 5's manifest list.
        dr.remove(versions.get(5));
        dr.remove(metadata(6).currentSnapshot().manifestListLocation());
        dr.remove(e.location());
        dr.remove(metadata(5).currentSnapshot().manifestListLocation());

        DrVerify.Result result = verify.check(journaled(6, 6));

        assertThat(result.safe()).map(DrVerify.Version::version).contains(4L);
        assertThat(result.safe()).map(DrVerify.Version::metadataLocation).contains(versions.get(3));
        assertThat(result.missing()).as("the pointer's").contains(versions.get(5));
        assertThat(dr.listings.get()).isEqualTo(1);
        assertThat(dr.heads.get()).as("everything is under the table's location").isZero();

        replicate(versions.get(5));

        assertThat(verify.check(journaled(6, 6)).safe()).as("its metadata file is not enough")
                .map(DrVerify.Version::version).contains(4L);

        replicateAll();

        assertThat(verify.check(journaled(6, 6)).safe()).map(DrVerify.Version::version).contains(6L);
    }

    @Test
    @DisplayName("a file every listed version reaches, lost long ago: no version is whole, found in one walk of the"
            + " whole table, not one for each version")
    void aFileLostLongAgoCostsOneWalk() {
        DataFile a = append("a");
        append("b");
        append("c");
        append("d");
        append("e");
        replicateAll();
        dr.remove(a.location());
        String manifest = metadata(2).currentSnapshot().allManifests(primary).getFirst().path();

        DrVerify.Result result = verify.check(journaled(6, 5));

        assertThat(result.safe()).isEmpty();
        assertThat(result.missing()).contains(a.location());
        assertThat(dr.reads(manifest)).as("version 2's manifest, which each listed version reaches").isEqualTo(1);
    }

    @Test
    @DisplayName("a view with no watermark whose newest metadata file is not yet in the DR store gets the newest that is")
    void aViewWithNoWatermarkGetsTheNewestVersionThere() {
        String root = "s3://lake/views/" + UUID.randomUUID();
        List<DrVerify.Version> view = new ArrayList<>();
        for (int v = 1; v <= 3; v++) {
            view.add(new DrVerify.Version(v, root + "/metadata/0000" + v + "-" + UUID.randomUUID() + ".metadata.json",
                    Optional.of(NOW)));
        }
        dr.put(view.get(0).metadataLocation(), "{}".getBytes(StandardCharsets.UTF_8));
        dr.put(view.get(1).metadataLocation(), "{}".getBytes(StandardCharsets.UTF_8));

        DrVerify.Result result = verify.check(new DrVerify.Item(UUID.randomUUID(), "view", root, view.get(2),
                Optional.empty(), view, Optional.of(NOW)));

        assertThat(result.safe()).contains(view.get(1));
        assertThat(result.missing()).contains(view.get(2).metadataLocation());
    }

    @Test
    @DisplayName("journaled versions that do not follow the watermark one by one are checked whole at the pointer")
    void aGapInTheVersionsIsCheckedWhole() {
        append("a");
        append("b");
        replicateAll();
        DrVerify.Item gap = new DrVerify.Item(UUID.randomUUID(), "table", table.location(), version(3),
                Optional.of(version(1)), List.of(version(3)), Optional.of(NOW));

        assertThat(gap.whole()).isTrue();
        assertThat(verify.check(gap).safe()).map(DrVerify.Version::version).contains(3L);
        assertThat(dr.listings.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("a view's version passes once its metadata file is in the DR store")
    void aViewPassesOnItsMetadataFile() {
        String root = "s3://lake/views/" + UUID.randomUUID();
        List<DrVerify.Version> view = new ArrayList<>();
        for (int v = 1; v <= 3; v++) {
            view.add(new DrVerify.Version(v, root + "/metadata/0000" + v + "-" + UUID.randomUUID() + ".metadata.json",
                    Optional.of(NOW)));
        }
        dr.put(view.get(0).metadataLocation(), "{}".getBytes(StandardCharsets.UTF_8));
        dr.put(view.get(1).metadataLocation(), "{}".getBytes(StandardCharsets.UTF_8));

        DrVerify.Result result = verify.check(new DrVerify.Item(UUID.randomUUID(), "view", root, view.get(2),
                Optional.of(view.get(0)), view.subList(1, 3), Optional.of(NOW)));

        assertThat(result.safe()).contains(view.get(1));
        assertThat(result.missing()).contains(view.get(2).metadataLocation());
        assertThat(verify.check(new DrVerify.Item(UUID.randomUUID(), "view", root, view.get(1), Optional.empty(),
                List.of(), Optional.of(NOW))).safe()).contains(view.get(1));
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
        return org.apache.iceberg.TableMetadataParser.read(primary, versions.get(version - 1));
    }

    private DrVerify.Version version(int version) {
        return new DrVerify.Version(version, versions.get(version - 1), Optional.of(NOW.minusSeconds(60L * version)));
    }

    /** The table with its watermark at {@code watermark} and its pointer at {@code pointer}. */
    private DrVerify.Item item(int watermark, int pointer) {
        List<DrVerify.Version> above = new ArrayList<>();
        for (int v = watermark + 1; v <= pointer; v++) {
            above.add(version(v));
        }
        return new DrVerify.Item(UUID.randomUUID(), "table", table.location(), version(pointer),
                Optional.of(version(watermark)), above, above.getFirst().committedAt());
    }

    private DrVerify.Item unwatermarked(int pointer) {
        return new DrVerify.Item(UUID.randomUUID(), "table", table.location(), version(pointer), Optional.empty(),
                List.of(), Optional.of(NOW));
    }

    /** The table with no watermark, as the work list names it: its {@code newest} newest versions, the pointer's last. */
    private DrVerify.Item journaled(int pointer, int newest) {
        List<DrVerify.Version> listed = new ArrayList<>();
        for (int v = Math.max(1, pointer - newest + 1); v <= pointer; v++) {
            listed.add(version(v));
        }
        return new DrVerify.Item(UUID.randomUUID(), "table", table.location(), version(pointer), Optional.empty(),
                listed, version(1).committedAt());
    }

    /** Every version's metadata file, and every file the newest reaches, as a replicator that caught up would. */
    private void replicateAll() {
        versions.forEach(this::replicate);
        replicateFilesOf(metadata(versions.size()));
    }

    /** Every file the metadata's snapshots reach, and its statistics files. */
    private void replicateFilesOf(TableMetadata metadata) {
        Set<String> files = new LinkedHashSet<>();
        for (Snapshot snapshot : metadata.snapshots()) {
            files.add(snapshot.manifestListLocation());
            for (ManifestFile manifest : snapshot.allManifests(primary)) {
                files.add(manifest.path());
                try {
                    if (manifest.content() == ManifestContent.DATA) {
                        try (ManifestReader<DataFile> reader = ManifestFiles.read(manifest, primary,
                                metadata.specsById())) {
                            reader.forEach(file -> files.add(file.location()));
                        }
                    } else {
                        try (ManifestReader<DeleteFile> reader = ManifestFiles.readDeleteManifest(manifest, primary,
                                metadata.specsById())) {
                            reader.forEach(file -> files.add(file.location()));
                        }
                    }
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
        }
        metadata.statisticsFiles().forEach(statistics -> files.add(statistics.path()));
        files.forEach(this::replicate);
    }

    private void replicate(String location) {
        dr.put(location, primary.fileExists(location) ? bytes(location) : new byte[] {1});
    }

    private byte[] bytes(String location) {
        InputFile file = primary.newInputFile(location);
        try (SeekableInputStream in = file.newStream()) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * The DR store: what the test put there, and how often the check asked for one file, listed a prefix or read a
     * file.
     */
    private static final class MemoryDr implements SupportsPrefixOperations {

        private final Map<String, byte[]> files = new ConcurrentHashMap<>();
        private final Map<String, AtomicInteger> reads = new ConcurrentHashMap<>();
        final AtomicInteger heads = new AtomicInteger();
        final AtomicInteger listings = new AtomicInteger();

        int reads(String location) {
            return reads.getOrDefault(location, new AtomicInteger()).get();
        }

        void put(String location, byte[] bytes) {
            files.put(location, bytes);
        }

        byte[] remove(String location) {
            byte[] held = files.remove(location);
            assertThat(held).as("the DR store held %s", location).isNotNull();
            return held;
        }

        @Override
        public InputFile newInputFile(String location) {
            byte[] bytes = files.get(location);
            if (bytes != null) {
                return new InMemoryInputFile(location, bytes) {
                    @Override
                    public boolean exists() {
                        heads.incrementAndGet();
                        return true;
                    }

                    @Override
                    public SeekableInputStream newStream() {
                        reads.computeIfAbsent(location, read -> new AtomicInteger()).incrementAndGet();
                        return super.newStream();
                    }
                };
            }
            return new InputFile() {
                @Override
                public long getLength() {
                    throw new NotFoundException("no %s in the DR store", location);
                }

                @Override
                public SeekableInputStream newStream() {
                    throw new NotFoundException("no %s in the DR store", location);
                }

                @Override
                public String location() {
                    return location;
                }

                @Override
                public boolean exists() {
                    heads.incrementAndGet();
                    return false;
                }
            };
        }

        @Override
        public Iterable<FileInfo> listPrefix(String prefix) {
            listings.incrementAndGet();
            return files.entrySet().stream().filter(file -> file.getKey().startsWith(prefix))
                    .map(file -> new FileInfo(file.getKey(), file.getValue().length, 0)).toList();
        }

        @Override
        public OutputFile newOutputFile(String location) {
            throw new UnsupportedOperationException("the DR store is only read");
        }

        @Override
        public void deleteFile(String location) {
            throw new UnsupportedOperationException("the DR store is only read");
        }

        @Override
        public void deletePrefix(String prefix) {
            throw new UnsupportedOperationException("the DR store is only read");
        }
    }
}
