package nuna.maintenance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
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
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The worker's own sign-in (D30): the client-credentials grant, its token kept until a minute before it expires; or a
 * token file another process keeps fresh, read again when it changes. A compaction Job signs in with the grant itself,
 * asking for the worker's scope, so compaction is refused with a token file or without a scope, and outside a
 * namespace of its own.
 */
class TokenSourceTest {

    @TempDir
    Path folder;

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
                    "s3cret", "catalog-access", "nuna-catalog");
            TokenSource tokens = new TokenSource.ClientCredentials(settings, HttpClient.newHttpClient(), clock);

            assertThat(tokens.token()).isEqualTo("t1");
            clock.advance(Duration.ofMinutes(8));
            assertThat(tokens.token()).as("8 of 10 minutes").isEqualTo("t1");
            clock.advance(Duration.ofMinutes(1).plusSeconds(1));
            assertThat(tokens.token()).as("within a minute of expiry").isEqualTo("t2");

            assertThat(forms.getFirst()).contains("grant_type=client_credentials", "client_id=nuna-maintenance",
                    "client_secret=s3cret", "scope=catalog-access", "audience=nuna-catalog");
            assertThat(tokens.clientProperties()).as("the Job asks for what the worker asks for")
                    .containsEntry("credential", "nuna-maintenance:s3cret").containsEntry("scope", "catalog-access")
                    .containsEntry("audience", "nuna-catalog").doesNotContainKey("token");
        } finally {
            idp.stop(0);
        }
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
        WorkerSettings.Token grant = new WorkerSettings.Token(null, endpoint, "nuna-maintenance", "s3cret",
                "catalog-access", null);
        WorkerSettings.Token noScope = new WorkerSettings.Token(null, endpoint, "nuna-maintenance", "s3cret", null,
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
                Duration.ofDays(1), 100, token, Map.of("s3", new WorkerSettings.StorageProfile(
                        List.of("s3://lake/tables"), null, "us-east-1", false, null, null, null)), compaction,
                Duration.ofHours(2));
    }

    private static WorkerSettings.Compaction compaction(String image, String namespace) {
        return new WorkerSettings.Compaction(image, "IfNotPresent", namespace, "nuna-compaction",
                URI.create("https://kubernetes.default.svc"), Path.of("/var/run/secrets/kubernetes.io/serviceaccount"),
                null, "2Gi", null, "1g", 2, 185, Map.of());
    }
}
