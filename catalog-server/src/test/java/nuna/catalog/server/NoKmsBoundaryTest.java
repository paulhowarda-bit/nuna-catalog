package nuna.catalog.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The catalog never calls KMS and holds no data keys (M6, D26): a catalog records only the name of its KMS key set,
 * so the server has no KMS client. Since 2026-11-19 (D26 amended, backlog 23) a storage profile may name one KMS key,
 * which S3 uses on the catalog's behalf: only the metadata-file writer names it on a request, and only the session
 * policy names the encryption headers, in its condition. No other main code names a KMS key or a server-side-encryption
 * header on a request.
 */
class NoKmsBoundaryTest {

    /** The two files that may: the server's metadata writes, and the vended session's condition. */
    private static final Set<String> KEY_USERS = Set.of(
            "catalog-objectstore/src/main/java/nuna/catalog/objectstore/MetadataFiles.java",
            "catalog-objectstore/src/main/java/nuna/catalog/objectstore/SessionPolicy.java");

    @Test
    void noKmsClientOnTheServerClasspath() {
        assertThatThrownBy(() -> Class.forName("software.amazon.awssdk.services.kms.KmsClient"))
                .isInstanceOf(ClassNotFoundException.class);
    }

    @Test
    void onlyTheMetadataWriterAndSessionPolicyNameAKmsKeyOrEncryptionHeader() throws IOException {
        Path root = Path.of(System.getProperty("repo.root")).toRealPath();
        List<String> offenders;
        try (Stream<Path> files = Files.walk(root)) {
            offenders = files
                    .filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> root.relativize(p).toString().replace('\\', '/').matches("[^/]+/src/main/java/.*"))
                    .filter(p -> touchesKms(p) && !KEY_USERS.contains(root.relativize(p).toString().replace('\\', '/'))
                            || callsKms(p))
                    .map(p -> root.relativize(p).toString())
                    .toList();
        }
        assertThat(offenders).isEmpty();
    }

    private static boolean touchesKms(Path file) {
        try (Stream<String> lines = Files.lines(file)) {
            return lines.anyMatch(line -> line.contains("ssekmsKeyId") || line.contains("serverSideEncryption(")
                    || line.contains("x-amz-server-side-encryption"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static boolean callsKms(Path file) {
        try (Stream<String> lines = Files.lines(file)) {
            return lines.anyMatch(line -> line.contains("services.kms"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
