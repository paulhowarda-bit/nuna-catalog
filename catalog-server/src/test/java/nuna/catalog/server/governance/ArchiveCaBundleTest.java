package nuna.catalog.server.governance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import nuna.catalog.audit.archive.ObjectLockArchive;
import nuna.catalog.objectstore.StoreKind;
import nuna.catalog.server.storage.StorageSettings;
import nuna.catalog.tests.s3.PrivateCa;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The audit archive's S3 client and its storage profile's {@code ca-bundle} (backlog 87), with no container and no
 * Spring: the archive's store is an HTTPS server in the test JVM whose certificate the test's private CA signed,
 * answering the startup check of the bucket's Object Lock as S3 does. With the bundle the check reaches it and the
 * archive starts; without, the JVM's CAs alone are trusted and the handshake fails before any request. A bundle the
 * server cannot read stops startup, naming the profile's setting and the file, before the store is asked anything.
 */
class ArchiveCaBundleTest {

    private static final String LOCKED = """
            <ObjectLockConfiguration xmlns="http://s3.amazonaws.com/doc/2006-03-01/">
              <ObjectLockEnabled>Enabled</ObjectLockEnabled>
            </ObjectLockConfiguration>
            """;
    private static final String ROOT = "s3://lake/tables";

    private PrivateCa.Server objectStore;

    @TempDir
    Path dir;

    @BeforeEach
    void startStore() {
        objectStore = PrivateCa.shared().serve(request -> PrivateCa.Answer.xml(LOCKED));
    }

    @AfterEach
    void stopStore() {
        objectStore.close();
    }

    @Test
    @DisplayName("with the profile's CA bundle, the archive's client trusts the store and its bucket check passes")
    void bundleTrusted() {
        Optional<ObjectLockArchive> archive = GovernanceWiring.archive(audit(), storage(PrivateCa.shared().bundle),
                Clock.systemUTC());

        assertThat(archive).isPresent();
        assertThat(objectStore.requests).singleElement().asString().startsWith("GET /audit-archive?object-lock");
    }

    @Test
    @DisplayName("without a CA bundle, the JVM's CAs alone are trusted, and the store's certificate is refused")
    void noBundleNoTrust() {
        assertThatThrownBy(() -> GovernanceWiring.archive(audit(), storage(null), Clock.systemUTC()))
                .hasStackTraceContaining("javax.net.ssl.SSLHandshakeException");
        assertThat(objectStore.requests).isEmpty();
    }

    @Test
    @DisplayName("a CA bundle the server cannot read or parse stops startup, naming the profile and the file")
    void unreadableBundleStopsStartup() throws IOException {
        Path missing = dir.resolve("missing.pem");
        Path empty = Files.writeString(dir.resolve("empty.pem"), "");
        Path broken = Files.writeString(dir.resolve("broken.pem"),
                "-----BEGIN CERTIFICATE-----\nnot base64!\n-----END CERTIFICATE-----\n");

        for (Path bundle : List.of(missing, empty, broken)) {
            assertThatThrownBy(() -> GovernanceWiring.archive(audit(), storage(bundle), Clock.systemUTC()))
                    .as(bundle.getFileName().toString())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageStartingWith("nuna.storage.profiles.archive.ca-bundle: ")
                    .hasMessageContaining(bundle.toString());
        }
        assertThat(objectStore.requests).isEmpty();
    }

    /** The archive in bucket {@code audit-archive} on profile {@code archive}; nothing else of audit is set. */
    private static AuditSettings audit() {
        return new AuditSettings(null, "nuna.catalog.audit", "nuna.catalog.changes", Duration.ofSeconds(5),
                Duration.ofSeconds(2), Duration.ofSeconds(10), Duration.ofMillis(250), Duration.ofSeconds(30), 500,
                new AuditSettings.Archive("audit-archive", "archive", "audit", ObjectLockArchive.MINIMUM_RETENTION,
                        10_000, Duration.ofMinutes(5), "nuna-audit-archive"));
    }

    /** One Ceph profile, {@code archive}, on the test's store, with the archive's own keys and this CA bundle. */
    private StorageSettings storage(Path caBundle) {
        return new StorageSettings(Map.of("archive", new StorageSettings.Profile(StoreKind.CEPH,
                objectStore.endpoint.toString(), "us-east-1", ROOT, List.of(ROOT), null, "archiver", "archiver-secret",
                null, caBundle, null)), Map.of(), Duration.ofMinutes(30), 100, true);
    }
}
