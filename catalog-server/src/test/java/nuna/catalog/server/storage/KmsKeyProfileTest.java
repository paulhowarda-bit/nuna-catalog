package nuna.catalog.server.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import nuna.catalog.audit.EventCodec;
import nuna.catalog.audit.StorageSetup;
import nuna.catalog.objectstore.Access;
import nuna.catalog.objectstore.StoreKind;
import nuna.catalog.objectstore.VendedCredentials;
import nuna.catalog.server.rest.CredentialVending;
import nuna.catalog.server.rest.RestSettings;
import nuna.catalog.store.CatalogStore;
import nuna.catalog.store.Change;
import nuna.catalog.store.StorageProfile;
import nuna.catalog.store.WriteResult;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.observation.ObservationRegistry;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code nuna.storage.profiles.<name>.kms-key} as the server reads it (D26, amended 2026-11-19; backlog 23), with no
 * container: the profile's store is an HTTP server in the test JVM that takes every PUT and records its headers. With
 * a key, the server's metadata files are written naming it, and the config handed to engines tells Iceberg's
 * S3FileIO to write with it; without one, nothing changes. A key that is not a key ARN in an AWS S3 profile's region
 * stops startup, naming the profile, before anything is written to the store. The key is recorded with the profile in
 * the store, so a change of key alone is written and audited (backlog 98). That vended sessions are refused any
 * other key is {@code SessionPolicyTest} here and {@code KmsKeyAwsTest} on AWS.
 */
class KmsKeyProfileTest {

    /** A synthetic key ARN, in AWS's documentation account. */
    private static final String KEY = "arn:aws:kms:us-east-1:111122223333:key/1234abcd-12ab-34cd-56ef-1234567890ab";
    /** Another, in the same account and region. */
    private static final String OTHER_KEY =
            "arn:aws:kms:us-east-1:111122223333:key/0987dcba-09fe-87dc-65ba-ab0987654321";
    private static final String ROOT = "s3://lake/tables";
    private static final String TABLE = ROOT + "/" + new UUID(0, 1);
    private static final RestSettings REST = new RestSettings(100, 1000, 1_048_576, 1000, 268_435_456,
            Duration.ofDays(30), Duration.ofMinutes(5), "the test's identity provider", URI.create("urn:nuna:test"),
            100, null);

    private final List<String> storeCalls = new CopyOnWriteArrayList<>();
    private final List<AutoCloseable> closing = new CopyOnWriteArrayList<>();
    /** Each request the store got: its method and path, then its headers, lower-cased, as {@code name: value}. */
    private final List<List<String>> requests = new CopyOnWriteArrayList<>();
    private HttpServer objectStore;

    @BeforeEach
    void startStore() throws IOException {
        objectStore = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        objectStore.createContext("/", exchange -> {
            try (InputStream body = exchange.getRequestBody()) {
                body.readAllBytes();
            }
            List<String> seen = new ArrayList<>();
            seen.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getRawPath());
            exchange.getRequestHeaders().forEach((name, values) -> values.forEach(
                    value -> seen.add(name.toLowerCase(Locale.ROOT) + ": " + value)));
            requests.add(seen);
            exchange.getResponseHeaders().add("ETag", "\"etag\"");
            exchange.sendResponseHeaders("PUT".equals(exchange.getRequestMethod()) ? 200 : 501, -1);
            exchange.close();
        });
        objectStore.start();
    }

    @AfterEach
    void stop() throws Exception {
        for (AutoCloseable resource : closing) {
            resource.close();
        }
        objectStore.stop(0);
    }

    @Test
    @DisplayName("with the profile's KMS key, the server writes each metadata file with SSE-KMS under that key")
    void metadataWriteNamesTheKey() {
        CatalogStorage.Bound bound = start(StoreKind.S3, KEY).profiles().getFirst();
        assertThat(bound.profile().kmsKey()).contains(KEY);

        bound.files().write(TABLE, 1, "{\"format-version\":2}");

        assertThat(requests).singleElement().satisfies(request -> {
            assertThat(request.getFirst()).startsWith("PUT /lake/tables/").endsWith(".metadata.json");
            assertThat(request).contains("x-amz-server-side-encryption: aws:kms",
                    "x-amz-server-side-encryption-aws-kms-key-id: " + KEY);
        });
    }

    @Test
    @DisplayName("without a KMS key, the metadata write names no encryption, as before")
    void noKeyNoEncryptionHeader() {
        CatalogStorage.Bound bound = start(StoreKind.S3, null).profiles().getFirst();
        assertThat(bound.profile().kmsKey()).isEmpty();

        bound.files().write(TABLE, 1, "{\"format-version\":2}");

        assertThat(requests).singleElement().satisfies(request -> assertThat(request)
                .noneMatch(header -> header.startsWith("x-amz-server-side-encryption")));
    }

    @Test
    @DisplayName("with the profile's KMS key, the config handed to engines tells S3FileIO to write with it; the"
            + " storage credential carries only the secrets")
    void configNamesTheKey() {
        CredentialVending.Vended vended = StsCredentialVending.vended(start(StoreKind.S3, KEY).profiles().getFirst(),
                credentials(), "epoch", "v1/trading/namespaces/desk/tables/t/credentials", false);

        assertThat(vended.config()).containsEntry("s3.sse.type", "kms").containsEntry("s3.sse.key", KEY);
        assertThat(vended.credentials()).singleElement().satisfies(credential -> assertThat(credential.config())
                .containsOnlyKeys("s3.access-key-id", "s3.secret-access-key", "s3.session-token",
                        "s3.session-token-expires-at-ms"));
    }

    @Test
    @DisplayName("without a KMS key, the config handed to engines is as before")
    void noKeyConfigAsBefore() {
        CredentialVending.Vended vended = StsCredentialVending.vended(start(StoreKind.S3, null).profiles().getFirst(),
                credentials(), "epoch", "v1/trading/namespaces/desk/tables/t/credentials", false);

        assertThat(vended.config()).containsOnlyKeys("s3.access-key-id", "s3.secret-access-key", "s3.session-token",
                "s3.session-token-expires-at-ms", "client.region", "s3.path-style-access",
                "client.refresh-credentials-endpoint", "client.refresh-credentials-enabled");
    }

    @Test
    @DisplayName("a KMS key that is not a key ARN in an AWS S3 profile's region stops startup, naming the profile,"
            + " before anything is written")
    void badKeyStopsStartup() {
        Map<String, String> refused = Map.of(
                "alias/aws/s3", "not an alias or a bare key id",
                "arn:aws:kms:us-east-1:111122223333:alias/lake", "not an alias or a bare key id",
                "arn:aws:kms:eu-west-1:111122223333:key/1234abcd-12ab-34cd-56ef-1234567890ab",
                "not in the profile's region us-east-1");
        refused.forEach((key, why) -> assertThatThrownBy(() -> start(StoreKind.S3, key)).as(key)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageStartingWith("nuna.storage.profiles.lake.kms-key: ")
                .hasMessageContaining(why));
        assertThatThrownBy(() -> start(StoreKind.CEPH, KEY))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("nuna.storage.profiles.lake.kms-key: only an AWS S3 profile names a KMS key, not a CEPH"
                        + " profile");
        assertThat(storeCalls).as("nothing was read from or written to the store").isEmpty();
        assertThat(requests).isEmpty();
    }

    @Test
    @DisplayName("the profile is recorded with its KMS key: a change of key alone, from none to a key, to another and"
            + " back to none, is written and audited as PROFILE_SET naming the key (backlog 98)")
    void keyChangeIsRecordedAndAudited() {
        Map<UUID, StorageProfile> held = new ConcurrentHashMap<>();
        List<StorageSetup> audited = new CopyOnWriteArrayList<>();
        CatalogStore store = holding(held, audited);
        UUID id = CatalogStorage.profileId("lake");

        assertThat(restart(StoreKind.S3, null, store, audited)).singleElement()
                .satisfies(event -> assertThat(event.settings()).containsEntry("kms-key", ""));
        assertThat(held.get(id).kmsKey()).isEmpty();
        Map<String, String> before = audited.getFirst().settings();

        for (String key : new String[] {KEY, OTHER_KEY, null}) {
            List<StorageSetup> events = restart(StoreKind.S3, key, store, audited);

            assertThat(held.get(id).kmsKey()).as("the stored key after a start with %s", key)
                    .isEqualTo(Optional.ofNullable(key));
            assertThat(events).as("the events of a start with %s", key).singleElement().satisfies(event -> {
                assertThat(event.action()).isEqualTo(StorageSetup.Action.PROFILE_SET);
                assertThat(event.id()).isEqualTo(id);
                assertThat(event.name()).isEqualTo("lake");
                Map<String, String> expected = new HashMap<>(before);
                expected.put("kms-key", key == null ? "" : key);
                assertThat(event.settings()).isEqualTo(expected);
            });
        }
    }

    @Test
    @DisplayName("a start with the KMS key the store already holds for the profile writes and audits nothing")
    void unchangedKeyAuditsNothing() {
        Map<UUID, StorageProfile> held = new ConcurrentHashMap<>();
        List<StorageSetup> audited = new CopyOnWriteArrayList<>();
        CatalogStore store = holding(held, audited);
        UUID id = CatalogStorage.profileId("lake");

        assertThat(restart(StoreKind.S3, KEY, store, audited)).singleElement()
                .satisfies(event -> assertThat(event.settings()).containsEntry("kms-key", KEY));
        assertThat(restart(StoreKind.S3, KEY, store, audited)).as("a second start with the key").isEmpty();
        assertThat(held.get(id).kmsKey()).contains(KEY);

        assertThat(restart(StoreKind.S3, null, store, audited)).as("a start with no key").hasSize(1);
        assertThat(restart(StoreKind.S3, null, store, audited)).as("a second start with no key").isEmpty();
        assertThat(held.get(id).kmsKey()).isEmpty();
    }

    /** The server's storage, as {@code StorageWiring} builds it, with one profile on the test's store. */
    private CatalogStorage start(StoreKind kind, String kmsKey) {
        return start(kind, kmsKey, store());
    }

    private CatalogStorage start(StoreKind kind, String kmsKey, CatalogStore store) {
        StorageSettings settings = new StorageSettings(Map.of("lake", new StorageSettings.Profile(kind,
                "http://127.0.0.1:" + objectStore.getAddress().getPort(), "us-east-1", ROOT, List.of(ROOT),
                "arn:aws:iam::111122223333:role/nuna-vending", "catalog", "catalog-secret", null, null, kmsKey)),
                Map.of(), Duration.ofMinutes(30), 100, false);
        CatalogStorage storage = new CatalogStorage(settings, store, REST, Clock.systemUTC(),
                ObservationRegistry.NOOP);
        closing.add(storage);
        return storage;
    }

    /** Starts a server on {@code store} with {@code kmsKey}, and returns the events it audited as it started. */
    private List<StorageSetup> restart(StoreKind kind, String kmsKey, CatalogStore store, List<StorageSetup> audited) {
        int before = audited.size();
        start(kind, kmsKey, store);
        return List.copyOf(audited.subList(before, audited.size()));
    }

    /**
     * A store that holds the profiles written to it, as every server of a deployment shares one, and keeps the
     * {@code storage-setup} event each write carries.
     */
    private static CatalogStore holding(Map<UUID, StorageProfile> held, List<StorageSetup> audited) {
        return (CatalogStore) Proxy.newProxyInstance(CatalogStore.class.getClassLoader(),
                new Class<?>[] {CatalogStore.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "readStorageProfile" -> Optional.ofNullable(held.get((UUID) args[0]));
                    case "putStorageProfile" -> {
                        StorageProfile profile = (StorageProfile) args[0];
                        ((Change) args[1]).outbox().forEach(event -> audited.add((StorageSetup) EventCodec.decode(
                                event.payloadJson().getBytes(StandardCharsets.UTF_8)).data()));
                        held.put(profile.id(), profile);
                        yield WriteResult.APPLIED;
                    }
                    default -> throw new UnsupportedOperationException(method.getName());
                });
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

    /** Synthetic credentials, as STS would hand them for {@link #TABLE}. */
    private static VendedCredentials credentials() {
        return new VendedCredentials("ASIASYNTHETIC", "synthetic-secret", "synthetic-token",
                Instant.now().plus(Duration.ofMinutes(15)), TABLE, Access.READ_WRITE, "nuna-test");
    }
}
