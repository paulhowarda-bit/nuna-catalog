package fidice.catalog.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** iceberg-core is pinned behind one adapter (D2): only catalog-iceberg's main code imports it. */
class IcebergImportBoundaryTest {

    @Test
    void onlyTheAdapterModuleImportsIceberg() throws IOException {
        Path root = Path.of(System.getProperty("repo.root")).toRealPath();
        List<String> offenders;
        try (Stream<Path> files = Files.walk(root)) {
            offenders = files
                    .filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> root.relativize(p).toString().replace('\\', '/').matches("[^/]+/src/main/java/.*"))
                    .filter(p -> !root.relativize(p).startsWith("catalog-iceberg"))
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
