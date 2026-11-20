package nuna.catalog.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * iceberg-core is pinned behind one adapter (D2): of the catalog's code, only catalog-iceberg's main code imports it.
 * The SingleStore change feed is not the catalog but a client of it, shipped as its own connector jar, and writes
 * through Iceberg's own client like any engine (D35). The maintenance worker is a client too: a separate service that
 * commits through the catalog's REST API with Iceberg's client (D30). So is the audit table writer,
 * catalog-audit-tables: a separate service that writes the audit topic to the audit catalog with Iceberg's client
 * (D23).
 */
class IcebergImportBoundaryTest {

    private static final Set<String> CLIENTS = Set.of("singlestore-change-feed", "catalog-maintenance",
            "catalog-audit-tables");

    @Test
    void onlyTheAdapterModuleImportsIceberg() throws IOException {
        Path root = Path.of(System.getProperty("repo.root")).toRealPath();
        List<String> offenders;
        try (Stream<Path> files = Files.walk(root)) {
            offenders = files
                    .filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> root.relativize(p).toString().replace('\\', '/').matches("[^/]+/src/main/java/.*"))
                    .filter(p -> !root.relativize(p).startsWith("catalog-iceberg"))
                    .filter(p -> !CLIENTS.contains(root.relativize(p).getName(0).toString()))
                    .filter(IcebergImportBoundaryTest::importsIceberg)
                    .map(p -> root.relativize(p).toString())
                    .toList();
        }
        assertThat(offenders).isEmpty();
    }

    private static boolean importsIceberg(Path file) {
        try (Stream<String> lines = Files.lines(file)) {
            return lines.anyMatch(line -> line.startsWith("import org.apache.iceberg.")
                    || line.startsWith("import static org.apache.iceberg."));
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
