package keel.maintenance;

import static org.assertj.core.api.Assertions.assertThat;

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
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The worker's own sign-in (D30): the client-credentials grant, its token kept until a minute before it expires; or a
 * token file another process keeps fresh, read again when it changes.
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
                    URI.create("http://127.0.0.1:" + idp.getAddress().getPort() + "/token"), "keel-maintenance",
                    "s3cret", null, "keel-catalog");
            TokenSource tokens = new TokenSource.ClientCredentials(settings, HttpClient.newHttpClient(), clock);

            assertThat(tokens.token()).isEqualTo("t1");
            clock.advance(Duration.ofMinutes(8));
            assertThat(tokens.token()).as("8 of 10 minutes").isEqualTo("t1");
            clock.advance(Duration.ofMinutes(1).plusSeconds(1));
            assertThat(tokens.token()).as("within a minute of expiry").isEqualTo("t2");

            assertThat(forms.getFirst()).contains("grant_type=client_credentials", "client_id=keel-maintenance",
                    "client_secret=s3cret", "audience=keel-catalog");
            assertThat(tokens.clientProperties()).containsEntry("credential", "keel-maintenance:s3cret")
                    .containsEntry("audience", "keel-catalog").doesNotContainKey("token");
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
        assertThat(tokens.clientProperties()).containsEntry("token", "second");
    }
}
