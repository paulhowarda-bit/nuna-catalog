package nuna.maintenance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import nuna.catalog.objectstore.StoreKind;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntUnaryOperator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The worker's own sign-in (D30): the client-credentials grant, its token kept until a minute before it expires, asked
 * again for up to 30 s when the identity provider cannot be reached or answers 5xx, and not again when it answers 4xx
 * (item 275); or a token file another process keeps fresh, read again when it changes. A compaction Job signs in with the grant itself,
 * asking for the worker's scope, so compaction is refused with a token file or without a scope, and outside a
 * namespace of its own.
 */
class TokenSourceTest {

    /** The sample client secret, made per run so no credential-shaped literal sits in the source. */
    private static final String SECRET = "cs-" + UUID.randomUUID();

    @TempDir
    Path folder;

    private final List<HttpServer> idps = new ArrayList<>();

    @Test
    void theClientCredentialsGrantIsAskedAgainOnlyNearExpiry() throws IOException {
        AtomicInteger issued = new AtomicInteger();
        List<String> forms = new CopyOnWriteArrayList<>();
        HttpServer idp = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        idp.createContext("/token", exchange -> {
            forms.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = ("{\"access_token\":\"t" + issued.incrementAndGet() + "\",\"expires_in\":600}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        idp.start();
        try {
            FakeClock clock = new FakeClock();
            WorkerSettings.Token settings = new WorkerSettings.Token(null,
                    URI.create("http://127.0.0.1:" + idp.getAddress().getPort() + "/token"), "nuna-maintenance",
                    SECRET, "catalog-access", "nuna-catalog");
            TokenSource tokens = new TokenSource.ClientCredentials(settings, HttpClient.newHttpClient(), clock);

            assertThat(tokens.token()).isEqualTo("t1");
            clock.advance(Duration.ofMinutes(8));
            assertThat(tokens.token()).as("8 of 10 minutes").isEqualTo("t1");
            clock.advance(Duration.ofMinutes(1).plusSeconds(1));
            assertThat(tokens.token()).as("within a minute of expiry").isEqualTo("t2");

            assertThat(forms.getFirst()).contains("grant_type=client_credentials", "client_id=nuna-maintenance",
                    "client_secret=" + SECRET, "scope=catalog-access", "audience=nuna-catalog");
            assertThat(tokens.clientProperties()).as("the Job asks for what the worker asks for")
                    .containsEntry("credential", "nuna-maintenance:" + SECRET).containsEntry("scope", "catalog-access")
                    .containsEntry("audience", "nuna-catalog").doesNotContainKey("token");
        } finally {
            idp.stop(0);
        }
    }

    @Test
    void anIdentityProviderThatCannotBeReachedOrAnswers5xxIsAskedAgainUntilItAnswers() throws IOException {
        // The first request finds no answer (the connection is closed on it), the second a 503, the third a token.
        Asked asked = ask(n -> n == 1 ? 0 : n == 2 ? 503 : 200);

        assertThat(asked.tokens().token()).isEqualTo("t3");
        assertThat(asked.requests()).hasValue(3);
        assertThat(asked.pauses()).as("1 s, then twice as long").containsExactly(Duration.ofSeconds(1),
                Duration.ofSeconds(2));
    }

    @Test
    void a4xxFailsAtOnce() throws IOException {
        Asked asked = ask(n -> 401);

        assertThatThrownBy(asked.tokens()::token).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("answered 401");
        assertThat(asked.requests()).as("a wrong client or secret is not asked again").hasValue(1);
        assertThat(asked.pauses()).isEmpty();
    }

    @Test
    void anIdentityProviderThatNeverAnswersIsGivenUpWithin30Seconds() throws IOException {
        Asked unreachable = ask(n -> 0);

        assertThatThrownBy(unreachable.tokens()::token).isInstanceOf(UncheckedIOException.class)
                .hasMessageContaining("cannot reach the token endpoint");
        assertThat(unreachable.pauses()).as("up to 8 s each, 30 s in all").containsExactly(Duration.ofSeconds(1),
                Duration.ofSeconds(2), Duration.ofSeconds(4), Duration.ofSeconds(8), Duration.ofSeconds(8));
        assertThat(unreachable.requests()).hasValue(6);

        Asked failing = ask(n -> 502);

        assertThatThrownBy(failing.tokens()::token).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("answered 502");
        assertThat(failing.requests()).hasValue(6);
    }

    /** A token source on an identity provider that answers its nth request with {@code status}, 0 for no answer. */
    private Asked ask(IntUnaryOperator status) throws IOException {
        AtomicInteger requests = new AtomicInteger();
        HttpServer idp = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        idp.createContext("/token", exchange -> {
            exchange.getRequestBody().readAllBytes();
            int n = requests.incrementAndGet();
            int answer = status.applyAsInt(n);
            if (answer == 0) {
                exchange.close();
                return;
            }
            byte[] body = (answer == 200 ? "{\"access_token\":\"t" + n + "\",\"expires_in\":600}"
                    : "{\"error\":\"no\"}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(answer, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        idp.start();
        idps.add(idp);
        FakeClock clock = new FakeClock();
        List<Duration> pauses = new CopyOnWriteArrayList<>();
        WorkerSettings.Token settings = new WorkerSettings.Token(null,
                URI.create("http://127.0.0.1:" + idp.getAddress().getPort() + "/token"), "nuna-maintenance", SECRET,
                "catalog-access", null);
        TokenSource tokens = new TokenSource.ClientCredentials(settings, HttpClient.newHttpClient(), clock, time -> {
            pauses.add(time);
            clock.advance(time);
        });
        return new Asked(tokens, requests, pauses);
    }

    private record Asked(TokenSource tokens, AtomicInteger requests, List<Duration> pauses) {
    }

    @AfterEach
    void stopIdps() {
        idps.forEach(idp -> idp.stop(0));
    }

    @Test
    void aTokenFileIsReadAgainWhenItChanges() throws IOException {
        Path file = folder.resolve("token");
        Files.writeString(file, "first\n");
        Files.setLastModifiedTime(file, FileTime.from(Instant.parse("2026-11-12T00:00:00Z")));
        TokenSource tokens = new TokenSource.FileToken(file);

        assertThat(tokens.token()).isEqualTo("first");
        Files.writeString(file, "second\n");
        Files.setLastModifiedTime(file, FileTime.from(Instant.parse("2026-11-12T00:05:00Z")));

        assertThat(tokens.token()).isEqualTo("second");
        assertThatThrownBy(tokens::clientProperties).as("a Job could outlive the file's token")
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void compactionNeedsTheGrantWithAScopeAndANamespaceOfItsOwn() {
        URI endpoint = URI.create("https://idp.test/token");
        WorkerSettings.Token grant = new WorkerSettings.Token(null, endpoint, "nuna-maintenance", SECRET,
                "catalog-access", null);
        WorkerSettings.Token noScope = new WorkerSettings.Token(null, endpoint, "nuna-maintenance", SECRET, null,
                null);
        WorkerSettings.Token file = new WorkerSettings.Token(folder.resolve("token"), null, null, null, "catalog-access",
                null);
        WorkerSettings.Compaction compaction = compaction("nuna/spark:test", "nuna-compaction");

        assertThat(settings(grant, compaction).compaction().enabled()).isTrue();
        assertThat(settings(file, compaction(null, null)).compaction().enabled()).as("compaction off")
                .isFalse();
        assertThatThrownBy(() -> settings(file, compaction)).hasMessageContaining("client-credentials grant");
        assertThatThrownBy(() -> settings(noScope, compaction)).hasMessageContaining("token.scope");
        assertThatThrownBy(() -> settings(grant, compaction("nuna/spark:test", null)))
                .hasMessageContaining("compaction.namespace");
    }

    private static WorkerSettings settings(WorkerSettings.Token token, WorkerSettings.Compaction compaction) {
        return new WorkerSettings(URI.create("http://nuna-catalog:8181/"), Duration.ofMinutes(10),
                new CleanupWindow(LocalTime.of(1, 0), LocalTime.of(5, 0), ZoneId.of("Europe/London")),
                Duration.ofDays(1), 100, token, Map.of("s3", new WorkerSettings.Store(StoreKind.S3,
                        URI.create("https://s3.us-east-1.amazonaws.com"), "us-east-1", null, null, null)), compaction,
                Duration.ofHours(2));
    }

    private static WorkerSettings.Compaction compaction(String image, String namespace) {
        return new WorkerSettings.Compaction(image, "IfNotPresent", namespace, "nuna-compaction",
                URI.create("https://kubernetes.default.svc"), Path.of("/var/run/secrets/kubernetes.io/serviceaccount"),
                null, "2Gi", null, "1g", 2, 185, Map.of());
    }
}
