package keel.maintenance;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.iceberg.AppendFiles;
import org.apache.iceberg.DataFiles;
import org.apache.iceberg.FileFormat;
import org.apache.iceberg.Table;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.rest.RESTCatalog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The orphan scan's memory on a large table (D30 (8)): the scan holds every file the table reaches in one set, so its
 * heap grows with the table. The table grows to {@value #LARGEST} data files (metadata only: none is written) in
 * appends of {@value #PER_APPEND}; at each size a scan runs in its own JVM ({@link OrphanScanProcess}, {@value #HEAP}
 * of heap) and reports what it held once its reachable set was whole. Prints bytes per file and how many files the
 * chart's default worker (2 GiB, 75% of it heap) could scan; checks the cost stays under a kilobyte a file and that
 * nothing reachable is taken for an orphan. Overnight: it commits a million files and scans three times.
 */
@Tag("overnight")
class LargeOrphanScanTest {

    private static final int PER_APPEND = 10_000;
    private static final int LARGEST = 1_000_000;
    private static final List<Integer> SIZES = List.of(250_000, 500_000, LARGEST);
    private static final String HEAP = "-Xmx2g";

    private final TestCatalog catalog = TestCatalog.get();

    @TempDir
    Path folder;

    @AfterEach
    void quiet() {
        catalog.quiet();
    }

    @Test
    void theScansHeapGrowsByLessThanAKilobyteAFile() throws Exception {
        try (RESTCatalog writer = catalog.writer()) {
            TableIdentifier id = TestCatalog.table(writer, "large");
            Table table = writer.loadTable(id);
            int files = 0;
            for (int size : SIZES) {
                while (files < size) {
                    AppendFiles append = table.newFastAppend();
                    for (int i = 0; i < PER_APPEND; i++, files++) {
                        append.appendFile(DataFiles.builder(table.spec())
                                .withPath(table.location() + "/data/" + UUID.randomUUID() + "-" + files + ".parquet")
                                .withFormat(FileFormat.PARQUET).withFileSizeInBytes(1 << 20).withRecordCount(1000)
                                .build());
                    }
                    append.commit();
                }
                List<String> orphans = new ArrayList<>();
                Map<String, Long> scan = scan(id, orphans);
                long perFile = scan.get("HEAP") / files;
                long fits = (long) (2L * 1024 * 1024 * 1024 * 0.75) / perFile;
                String line = String.format("%,d files: %,d MB held, %,d bytes a file, %,d ms; a 2 GiB worker"
                        + " scans about %,d files", files, scan.get("HEAP") >> 20, perFile, scan.get("MILLIS"), fits);
                System.out.println("LargeOrphanScanTest: " + line);
                assertThat(orphans).as("every manifest list and manifest is reachable; only metadata files the"
                        + " metadata log has let go are not").allMatch(orphan -> orphan.endsWith(".metadata.json"));
                assertThat(scan.get("ORPHANS")).isEqualTo(orphans.size());
                assertThat(perFile).as(line).isLessThan(1024);
            }
        }
    }

    /** Runs a scan in its own JVM; returns its numbers and adds the orphans it found to {@code orphans}. */
    private Map<String, Long> scan(TableIdentifier id, List<String> orphans) throws Exception {
        List<String> arguments = new ArrayList<>(ChildJvm.workerArguments(catalog, folder));
        arguments.addAll(List.of(TestCatalog.CATALOG, id.namespace().level(0), id.name()));
        Process process = ChildJvm.start(folder, List.of(HEAP), OrphanScanProcess.class, arguments);
        Map<String, Long> values = new HashMap<>();
        StringBuilder output = new StringBuilder();
        try (BufferedReader out = new BufferedReader(new InputStreamReader(process.getInputStream(),
                StandardCharsets.UTF_8))) {
            String line;
            while ((line = out.readLine()) != null) {
                if (line.startsWith("ORPHAN ")) {
                    orphans.add(line.substring("ORPHAN ".length()));
                    continue;
                }
                output.append(line).append('\n');
                String[] words = line.split(" ");
                if (words.length == 2 && List.of("HEAP", "ORPHANS", "MILLIS").contains(words[0])) {
                    values.put(words[0], Long.parseLong(words[1]));
                }
            }
        } finally {
            if (!process.waitFor(10, TimeUnit.MINUTES)) {
                process.destroyForcibly();
            }
        }
        assertThat(process.exitValue()).as("the scan's output:%n%s", output).isZero();
        assertThat(values).as("the scan's output:%n%s", output).containsKeys("HEAP", "ORPHANS", "MILLIS");
        return values;
    }
}
