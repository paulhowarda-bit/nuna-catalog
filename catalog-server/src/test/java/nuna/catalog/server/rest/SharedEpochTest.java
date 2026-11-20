package nuna.catalog.server.rest;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import nuna.catalog.core.principal.Caller;
import nuna.catalog.core.principal.Principal;
import nuna.catalog.objectstore.StoreKind;
import nuna.catalog.server.rest.CredentialVending.Vended;
import nuna.catalog.server.storage.CatalogStorage;
import nuna.catalog.server.storage.StorageSettings;
import nuna.catalog.server.storage.StsCredentialVending;
import nuna.catalog.store.CatalogRecord;
import nuna.catalog.store.CatalogSettings;
import nuna.catalog.store.CatalogStore;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.observation.ObservationRegistry;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Item 15 (D14, amended 2026-11-19, "Shared epoch"): the credential epoch in a table load's ETag is the issuance window
 * the load falls in, so servers that each mint and cache their own STS credentials give one client the same ETag for
 * the same table state, and a client that switches servers keeps its 304. Java only: each server here is an
 * {@link StsCredentialVending} of its own, with its own clock, storage profile, STS client and credential cache, as two
 * servers build theirs, against one stand-in STS that gives every AssumeRole new keys. The ETag is the route's own
 * ({@link ETags}). {@code CredentialExpiryTest} and {@code VendingNearExpiryTest} cover the route's 304 rule with a
 * running server.
 */
class SharedEpochTest {

    /** 10:00 UTC is a window boundary for every lifetime used here: windows are counted from the Unix epoch. */
    private static final Instant START = Instant.parse("2026-11-20T10:00:00Z");
    private static final Duration LIFETIME = Duration.ofMinutes(30);
    /** A quarter of {@link #LIFETIME}. */
    private static final Duration WINDOW = Duration.ofSeconds(450);
    private static final Duration MARGIN = Duration.ofMinutes(5);
    private static final UUID CATALOG = UUID.fromString("0b6f5d1e-3c2a-4f7e-9a41-6d2c8e0f1a35");
    private static final UUID TABLE = UUID.fromString("5e2d7c4b-8a19-4f30-b6e2-91d4c3a7f018");
    private static final String LOCATION = "s3://warehouse/tables/" + TABLE;
    private static final String METADATA = LOCATION + "/metadata/00001-7f3e2a1c.metadata.json";
    private static final Map<String, String> LABELS = Map.of("classification", "internal");
    private static final Caller DAVE = new Caller(new Principal("dave", Set.of("traders")), "nuna-console", false,
            Optional.empty());
    private static final Caller TRINO_FOR_DAVE = new Caller(new Principal("svc-trino", Set.of()), "trino-prod", true,
            Optional.of(new Principal("dave", Set.of("traders"))));

    /** True time, which the test moves: STS's clock and every server's are set from it. */
    private final AtomicReference<Instant> now = new AtomicReference<>(START);
    private final List<AutoCloseable> closing = new ArrayList<>();
    private Sts sts;

    @BeforeEach
    void startSts() throws IOException {
        sts = new Sts(now);
        closing.add(sts);
    }

    @AfterEach
    void close() throws Exception {
        for (AutoCloseable resource : closing.reversed()) {
            resource.close();
        }
    }

    @Test
    @DisplayName("two servers, each with credentials of its own, give one ETag within a window and a new one after it")
    void twoServersGiveOneETagWithinAWindow() {
        Server a = server(LIFETIME, MARGIN, Duration.ZERO);
        Server b = server(LIFETIME, MARGIN, Duration.ofSeconds(-2));

        at(START.plus(Duration.ofMinutes(1)));
        Vended fromA = a.vend(DAVE, false);
        Vended fromB = b.vend(DAVE, false);
        assertThat(key(fromB)).as("each server minted its own credentials").isNotEqualTo(key(fromA));
        String etag = etag(fromA);
        assertThat(etag(fromB)).as("the same ETag from both").isEqualTo(etag);
        assertThat(ETags.matches(etag, etag(fromB))).isTrue();

        at(START.plus(WINDOW).minusSeconds(30));
        assertThat(etag(b.vend(DAVE, false))).as("later in the window, on B").isEqualTo(etag);
        assertThat(etag(a.vend(DAVE, false))).as("later in the window, on A").isEqualTo(etag);

        at(START.plus(WINDOW).plusSeconds(1));
        assertThat(etag(a.vend(DAVE, false))).as("A's clock is past the boundary").isNotEqualTo(etag);
        assertThat(etag(b.vend(DAVE, false)))
                .as("B's clock, two seconds behind, is not yet: near a boundary a client may lose a 304, nothing worse")
                .isEqualTo(etag);

        at(START.plus(WINDOW).plusSeconds(5));
        String next = etag(a.vend(DAVE, false));
        assertThat(next).as("the next window").isNotEqualTo(etag);
        assertThat(etag(b.vend(DAVE, false))).isEqualTo(next);
    }

    @Test
    @DisplayName("a client whose loads go round three servers gets its 304s, and never one for credentials near expiry")
    void aClientGoingRoundTheServersKeepsItsNotModified() {
        Duration lag = Duration.ofMillis(1500);
        List<Server> servers = List.of(server(LIFETIME, MARGIN, Duration.ZERO),
                server(LIFETIME, MARGIN, lag.negated()), server(LIFETIME, MARGIN, Duration.ofMillis(800)));
        Duration run = Duration.ofHours(2);
        long windows = run.dividedBy(WINDOW);
        String held = null;
        Instant heldExpires = null;
        int loaded = 0;
        int notModified = 0;
        for (Instant at = START; at.isBefore(START.plus(run)); at = at.plusSeconds(20)) {
            at(at);
            Answer answer = servers.get((loaded + notModified) % servers.size()).load(held);
            if (answer.notModified()) {
                notModified++;
                assertThat(heldExpires).as("the credentials a 304 at %s keeps", at)
                        .isAfter(at.plus(MARGIN).minus(lag));
            } else {
                loaded++;
                held = answer.etag();
                heldExpires = answer.expiresAt();
            }
        }
        assertThat(loaded).as("one full load in each of %d windows, the other %d loads answered 304", windows,
                notModified).isEqualTo((int) windows);
    }

    @Test
    @DisplayName("every vend's credentials last through its window's end and the margin, and both servers agree")
    void everyVendLastsThroughItsWindowAndTheMargin() {
        sts.behind = Duration.ofSeconds(30);
        for (Duration lifetime : List.of(Duration.ofMinutes(15), LIFETIME, Duration.ofMinutes(60))) {
            Server a = server(lifetime, MARGIN, Duration.ZERO);
            Server b = server(lifetime, MARGIN, Duration.ZERO);
            long window = lifetime.dividedBy(4).toMillis();
            int issuedBefore = sts.issued.get();
            for (Instant at = START; at.isBefore(START.plus(lifetime.multipliedBy(3))); at = at.plusSeconds(10)) {
                at(at);
                Instant windowEnds = Instant.ofEpochMilli((Math.floorDiv(at.toEpochMilli(), window) + 1) * window);
                for (boolean write : List.of(false, true)) {
                    Vended fromA = a.vend(DAVE, write);
                    Vended fromB = b.vend(DAVE, write);
                    for (Vended vended : List.of(fromA, fromB)) {
                        assertThat(vended.expiresAt().orElseThrow())
                                .as("%s credentials vended at %s, write %s", lifetime, at, write)
                                .isAfterOrEqualTo(windowEnds.plus(MARGIN));
                    }
                    assertThat(fromB.epoch()).as("%s credentials vended at %s, write %s", lifetime, at, write)
                            .isEqualTo(fromA.epoch());
                }
            }
            assertThat(sts.issued.get() - issuedBefore).as("credentials were renewed along the way")
                    .isGreaterThan(2 * 2 * 3);
        }
    }

    @Test
    @DisplayName("credentials that would not last through the window and the margin get an epoch of their own")
    void credentialsThatCannotLastTheWindowKeepTheirOwnEpoch() {
        // A margin longer than credentials live, as VendingNearExpiryTest sets it: none can last a window out.
        Server a = server(Duration.ofMinutes(15), Duration.ofMinutes(20), Duration.ZERO);
        Server b = server(Duration.ofMinutes(15), Duration.ofMinutes(20), Duration.ZERO);
        at(START.plus(Duration.ofMinutes(1)));
        Vended first = a.vend(DAVE, false);
        assertThat(b.vend(DAVE, false).epoch()).as("another server").isNotEqualTo(first.epoch());
        Vended again = a.vend(DAVE, false);
        assertThat(key(again)).isNotEqualTo(key(first));
        assertThat(again.epoch()).as("new credentials on the same server").isNotEqualTo(first.epoch());
    }

    @Test
    @DisplayName("credentials from an STS whose clock is far behind get an epoch of their own until it catches up")
    void credentialsFromAnStsFarBehindKeepTheirOwnEpoch() {
        Server a = server(LIFETIME, MARGIN, Duration.ZERO);
        Server b = server(LIFETIME, MARGIN, Duration.ZERO);
        sts.behind = Duration.ofMinutes(25);
        at(START.plus(Duration.ofMinutes(1)));
        Vended fromA = a.vend(DAVE, false);
        assertThat(fromA.expiresAt().orElseThrow()).as("five minutes left on the servers' clocks")
                .isEqualTo(now.get().plus(Duration.ofMinutes(5)));
        assertThat(b.vend(DAVE, false).epoch()).isNotEqualTo(fromA.epoch());

        sts.behind = Duration.ZERO;
        assertThat(b.vend(DAVE, false).epoch()).as("renewed, and shared again").isEqualTo(a.vend(DAVE, false).epoch());
    }

    @Test
    @DisplayName("the epoch names whose credentials they are and for what: another caller or access is another ETag")
    void theEpochNamesWhoseCredentialsForWhat() {
        Server a = server(LIFETIME, MARGIN, Duration.ZERO);
        Server b = server(LIFETIME, MARGIN, Duration.ZERO);
        at(START.plus(Duration.ofMinutes(1)));
        String read = a.vend(DAVE, false).epoch();
        assertThat(a.vend(DAVE, true).epoch()).as("read-write").isNotEqualTo(read);
        assertThat(a.vend(TRINO_FOR_DAVE, false).epoch()).as("an engine acting for the same user").isNotEqualTo(read);
        assertThat(b.vend(TRINO_FOR_DAVE, true).epoch()).isEqualTo(a.vend(TRINO_FOR_DAVE, true).epoch());
    }

    private void at(Instant instant) {
        now.set(instant);
    }

    private static String etag(Vended vended) {
        return ETags.of(METADATA, false, null, vended.epoch(), LABELS);
    }

    private static String key(Vended vended) {
        return vended.config().get("s3.access-key-id");
    }

    /** One server's storage and vending, built as {@code StorageWiring} builds them, on a clock of its own. */
    private Server server(Duration lifetime, Duration margin, Duration clockOffset) {
        Clock clock = new OffsetClock(now, clockOffset);
        StorageSettings settings = new StorageSettings(Map.of("warehouse", new StorageSettings.Profile(StoreKind.MINIO,
                sts.endpoint(), "us-east-1", "s3://warehouse/tables", List.of("s3://warehouse/tables"), null, "catalog",
                "catalog-secret", null, null)), Map.of(), lifetime, 100, false);
        RestSettings rest = new RestSettings(100, 1000, 1_048_576, 1000, 268_435_456, Duration.ofDays(30), margin,
                "the test's identity provider", URI.create("urn:nuna:test"), 100, null);
        AtomicReference<UUID> profile = new AtomicReference<>();
        CatalogStorage storage = new CatalogStorage(settings, store(profile), rest, clock, ObservationRegistry.NOOP);
        closing.add(storage);
        profile.set(storage.profiles().getFirst().id());
        return new Server(new StsCredentialVending(storage, event -> { }, rest.auditSource(), settings, margin, clock,
                ObservationRegistry.NOOP), clock, margin);
    }

    /** A store that binds the catalog to the server's one profile and records nothing. */
    private static CatalogStore store(AtomicReference<UUID> profile) {
        return (CatalogStore) Proxy.newProxyInstance(CatalogStore.class.getClassLoader(),
                new Class<?>[] {CatalogStore.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "readStorageProfile" -> Optional.empty();
                    case "putStorageProfile" -> null;
                    case "catalogSettings" -> Optional.of(new CatalogSettings(profile.get(), "test-keys", "test-entity"));
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private record Answer(boolean notModified, String etag, Instant expiresAt) {
    }

    private record Server(StsCredentialVending vending, Clock clock, Duration margin) {

        Vended vend(Caller caller, boolean write) {
            return vending.vend(new CredentialVending.Request(new CatalogRecord(CATALOG, "trading", "trading"), TABLE,
                    List.of("desk", "t"), LOCATION, caller, UUID.randomUUID().toString(), Optional.empty(),
                    "v1/trading/namespaces/desk/tables/t/credentials", write));
        }

        /**
         * Dave's conditional load of the unchanged table, decided as {@code TableRoutes.load} decides it: cacheable
         * unless this server's credentials expire within the margin; a 304 when cacheable and the client holds the
         * ETag; otherwise a 200 with the credentials, and the ETag when cacheable.
         */
        Answer load(String ifNoneMatch) {
            Vended vended = vend(DAVE, false);
            boolean cacheable = !vended.expireBefore(clock.instant().plus(margin));
            String etag = etag(vended);
            if (cacheable && ETags.matches(ifNoneMatch, etag)) {
                return new Answer(true, etag, null);
            }
            return new Answer(false, cacheable ? etag : null, vended.expiresAt().orElseThrow());
        }
    }

    /** A server's clock: true time plus the server's offset. */
    private static final class OffsetClock extends Clock {

        private final AtomicReference<Instant> now;
        private final Duration offset;

        OffsetClock(AtomicReference<Instant> now, Duration offset) {
            this.now = now;
            this.offset = offset;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get().plus(offset);
        }
    }

    /**
     * Stands in for STS (the AWS query protocol's AssumeRole): every call gets new keys, expiring the duration asked
     * for after STS's own clock, which is {@link #behind} true time.
     */
    private static final class Sts implements AutoCloseable {

        private static final String RESPONSE = """
                <AssumeRoleResponse xmlns="https://sts.amazonaws.com/doc/2011-06-15/">
                  <AssumeRoleResult>
                    <Credentials>
                      <AccessKeyId>ASIATEST%08d</AccessKeyId>
                      <SecretAccessKey>secret-%d</SecretAccessKey>
                      <SessionToken>token-%d</SessionToken>
                      <Expiration>%s</Expiration>
                    </Credentials>
                    <AssumedRoleUser>
                      <AssumedRoleId>AROATEST:session</AssumedRoleId>
                      <Arn>arn:aws:sts::000000000000:assumed-role/test/session</Arn>
                    </AssumedRoleUser>
                  </AssumeRoleResult>
                  <ResponseMetadata>
                    <RequestId>request-%d</RequestId>
                  </ResponseMetadata>
                </AssumeRoleResponse>
                """;

        private final HttpServer server;
        private final AtomicInteger issued = new AtomicInteger();
        private volatile Duration behind = Duration.ZERO;

        Sts(AtomicReference<Instant> now) throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                String form = new String(exchange.getRequestBody().readAllBytes(), UTF_8);
                long seconds = Arrays.stream(form.split("&"))
                        .filter(parameter -> parameter.startsWith("DurationSeconds="))
                        .mapToLong(parameter -> Long.parseLong(parameter.substring("DurationSeconds=".length())))
                        .findFirst().orElseThrow();
                int n = issued.incrementAndGet();
                Instant expires = now.get().minus(behind).plusSeconds(seconds);
                byte[] body = RESPONSE.formatted(n, n, n, expires, n).getBytes(UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/xml");
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            });
            server.start();
        }

        String endpoint() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
