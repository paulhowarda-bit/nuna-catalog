package fidice.catalog.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The catalog never calls KMS and holds no data keys (M6): encryption at rest is the bucket's default
 * key, and a catalog records only the name of its KMS key set. So the server has no KMS client, and no
 * main code names a KMS key or a server-side-encryption header on a request.
 */
class NoKmsBoundaryTest {

    @Test
    void noKmsClientOnTheServerClasspath() {
        assertThatThrownBy(() -> Class.forName("software.amazon.awssdk.services.kms.KmsClient"))
                .isInstanceOf(ClassNotFoundException.class);
    }

    @Test
    void noMainCodeSetsAKmsKeyOrEncryptionHeader() throws IOException {
        Path root = Path.of(System.getProperty("repo.root")).toRealPath();
        List<String> offenders;
        try (Stream<Path> files = Files.walk(root)) {
            offenders = files
                    .filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> root.relativize(p).toString().replace('\\', '/').matches("[^/]+/src/main/java/.*"))
                    .filter(NoKmsBoundaryTest::touchesKms)
                    .map(p -> root.relativize(p).toString())
                    .toList();
        }
        assertThat(offenders).isEmpty();
    }

    private static boolean touchesKms(Path file) {
        try (Stream<String> lines = Files.lines(file)) {
            return lines.anyMatch(line -> line.contains("services.kms") || line.contains("ssekmsKeyId")
                    || line.contains("serverSideEncryption(") || line.contains("x-amz-server-side-encryption"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
