package nuna.catalog.server.storage;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import nuna.catalog.core.commit.TableFiles;
import nuna.catalog.objectstore.Access;
import nuna.catalog.objectstore.ObjectStoreException;
import nuna.catalog.objectstore.SessionIdentity;
import nuna.catalog.objectstore.StoreKind;
import nuna.catalog.server.rest.RestSettings;
import nuna.catalog.store.CatalogStore;
import nuna.catalog.tests.s3.PrivateCa;
import io.micrometer.observation.ObservationRegistry;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code nuna.storage.profiles.<name>.ca-bundle} as the server's startup reads it (backlog 21), with no container: the
 * profile's store is an HTTPS server in the test JVM whose certificate the test's private CA signed, answering a GET as
 * S3 does and a POST as STS's AssumeRole does. With the bundle, the profile's metadata files and credential vendor both
 * reach it; without, the JVM's CAs alone are trusted and neither does. A bundle the server cannot read stops startup,
 * naming the profile and the file, before anything is written to the store.
 */
class CaBundleProfileTest {

    private static final String JSON = "{\"format-version\":2}";
    private static final String STS = """
            <AssumeRoleResponse xmlns="https://sts.amazonaws.com/doc/2011-06-15/">
              <AssumeRoleResult>
                <Credentials>
                  <AccessKeyId>ASIAPRIVATECA</AccessKeyId>
                  <SecretAccessKey>secret</SecretAccessKey>
                  <SessionToken>token</SessionToken>
                  <Expiration>%s</Expiration>
                </Credentials>
              </AssumeRoleResult>
              <ResponseMetadata><RequestId>request-1</RequestId></ResponseMetadata>
            </AssumeRoleResponse>
            """.formatted(Instant.now().plus(Duration.ofMinutes(15)).truncatedTo(ChronoUnit.SECONDS));
    private static final String ROOT = "s3://lake/tables";
    private static final UUID TABLE = new UUID(0, 1);
    private static final RestSettings REST = new RestSettings(100, 1000, 1_048_576, 1000, 268_435_456,
            Duration.ofDays(30), Duration.ofMinutes(5), "the test's identity provider", URI.create("urn:nuna:test"),
            100, null);

    private final List<String> storeCalls = new CopyOnWriteArrayList<>();
    private final List<AutoCloseable> closing = new CopyOnWriteArrayList<>();
    private PrivateCa.Server objectStore;

    @TempDir
    Path dir;

    @BeforeEach
    void startStore() {
        objectStore = PrivateCa.shared().serve(request -> request.startsWith("POST ")
                ? PrivateCa.Answer.xml(STS)
                : new PrivateCa.Answer(200, "application/json", JSON.getBytes(UTF_8)));
    }

    @AfterEach
    void stop() throws Exception {
        for (AutoCloseable resource : closing) {
            resource.close();
        }
        objectStore.close();
    }

    @Test
    @DisplayName("with the profile's CA bundle, its metadata files and credential vendor both trust the store")
    void bundleTrusted() {
        CatalogStorage.Bound bound = start(PrivateCa.shared().bundle).profiles().getFirst();

        TableFiles.Read read = bound.files().read(ROOT + "/" + TABLE + "/metadata/00001-a.metadata.json", sha256());
        assertThat(read.json()).isEqualTo(JSON);
        assertThat(bound.vendor().vend(ROOT + "/" + TABLE, Access.READ, identity()).accessKeyId())
                .isEqualTo("ASIAPRIVATECA");
        assertThat(objectStore.requests).hasSize(2);
    }

    @Test
    @DisplayName("without a CA bundle, the JVM's CAs alone are trusted, and the store's certificate is refused")
    void noBundleNoTrust() {
        CatalogStorage.Bound bound = start(null).profiles().getFirst();

        assertThatThrownBy(() -> bound.files().read(ROOT + "/" + TABLE + "/metadata/00001-a.metadata.json", sha256()))
                .hasStackTraceContaining("javax.net.ssl.SSLHandshakeException");
        assertThatThrownBy(() -> bound.vendor().vend(ROOT + "/" + TABLE, Access.READ, identity()))
                .isInstanceOf(ObjectStoreException.class)
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
            assertThatThrownBy(() -> start(bundle)).as(bundle.getFileName().toString())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageStartingWith("nuna.storage.profiles.onprem.ca-bundle: ")
                    .hasMessageContaining(bundle.toString());
        }
        assertThat(storeCalls).as("nothing was read from or written to the store").isEmpty();
        assertThat(objectStore.requests).isEmpty();
    }

    /** The server's storage, as {@code StorageWiring} builds it, with one Ceph profile on the test's store. */
    private CatalogStorage start(Path caBundle) {
        StorageSettings settings = new StorageSettings(Map.of("onprem", new StorageSettings.Profile(StoreKind.CEPH,
                objectStore.endpoint.toString(), "us-east-1", ROOT, List.of(ROOT), "arn:aws:iam:::role/nuna-vending",
                "catalog", "catalog-secret", null, caBundle)), Map.of(), Duration.ofMinutes(30), 100, true);
        CatalogStorage storage = new CatalogStorage(settings, store(), REST, Clock.systemUTC(),
                ObservationRegistry.NOOP);
        closing.add(storage);
        return storage;
    }

    /** A store that holds no profile yet, takes the one written, and records what it was asked. */
    private CatalogStore store() {
        return (CatalogStore) Proxy.newProxyInstance(CatalogStore.class.getClassLoader(),
                new Class<?>[] {CatalogStore.class}, (proxy, method, args) -> {
                    storeCalls.add(method.getName());
                    return switch (method.getName()) {
                        case "readStorageProfile" -> Optional.empty();
                        case "putStorageProfile" -> null;
                        default -> throw new UnsupportedOperationException(method.getName());
                    };
                });
    }

    private static SessionIdentity identity() {
        return new SessionIdentity("alice@corp.example", Optional.empty(), "spark", "req-1", "trading", TABLE);
    }

    private static String sha256() {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(JSON.getBytes(UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
