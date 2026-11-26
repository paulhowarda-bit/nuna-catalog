package nuna.maintenance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The worker's bearer token for the catalog: it signs in as a maintenance principal acting as itself (D30), never for
 * an end user. Asked for before every request, so a token is never used after it expires.
 */
public interface TokenSource {

    /** A token valid now. */
    String token();

    /**
     * How an Iceberg client started on the worker's behalf (a compaction Job) signs in: the client-credentials grant,
     * with the worker's scope and audience, so it renews its own token for as long as the Job runs.
     */
    Map<String, String> clientProperties();

    /** The token source the settings name, asking the identity provider for tokens through {@code http}. */
    static TokenSource of(WorkerSettings.Token settings, Clock clock, HttpClient http) {
        return settings.file() != null ? new FileToken(settings.file()) : new ClientCredentials(settings, http, clock);
    }

    /** A token kept in a file by something else (a mounted Secret), read again whenever the file changes. */
    final class FileToken implements TokenSource {

        private final Path file;
        private FileTime read;
        private String token;

        public FileToken(Path file) {
            this.file = file;
        }

        @Override
        public synchronized String token() {
            try {
                FileTime modified = Files.getLastModifiedTime(file);
                if (!modified.equals(read)) {
                    token = Files.readString(file, StandardCharsets.UTF_8).strip();
                    read = modified;
                }
                return token;
            } catch (IOException e) {
                throw new UncheckedIOException("cannot read the worker's token from " + file, e);
            }
        }

        /** Never: a Job may outlive the token in the file (the settings refuse compaction with one). */
        @Override
        public Map<String, String> clientProperties() {
            throw new IllegalStateException("compaction needs the client-credentials grant: a token file's token"
                    + " could expire while a Job runs");
        }
    }

    /**
     * The OAuth 2 client-credentials grant (RFC 6749 4.4): the worker's client id and secret buy a token, kept until a
     * minute before it expires, or until half its life has passed for one that lives less than two minutes. A request
     * that cannot reach the identity provider, or that it answers with a 5xx, is sent again after a pause, 1 s, then
     * twice as long each time up to 8 s, for at most {@link #PATIENCE} in all, so a worker or identity provider just
     * restarted still signs in at its first scan (item 275). Any other answer but 200, a 4xx for a wrong client or
     * secret above all, fails at once.
     */
    final class ClientCredentials implements TokenSource {

        /** How long one token request may take, its retries and pauses included: well inside a scan. */
        static final Duration PATIENCE = Duration.ofSeconds(30);
        private static final Duration FIRST_PAUSE = Duration.ofSeconds(1);
        private static final Duration LONGEST_PAUSE = Duration.ofSeconds(8);
        private static final Logger LOG = LoggerFactory.getLogger(TokenSource.class);
        private static final ObjectMapper JSON = new ObjectMapper();

        /** How the worker waits between two tries; a test's waits only move its clock. */
        interface Pause {
            void pause(Duration time) throws InterruptedException;
        }

        private final WorkerSettings.Token settings;
        private final HttpClient http;
        private final Clock clock;
        private final Pause pause;
        private String token;
        private Instant renewAt = Instant.MIN;

        ClientCredentials(WorkerSettings.Token settings, HttpClient http, Clock clock) {
            this(settings, http, clock, Thread::sleep);
        }

        ClientCredentials(WorkerSettings.Token settings, HttpClient http, Clock clock, Pause pause) {
            this.settings = settings;
            this.http = http;
            this.clock = clock;
            this.pause = pause;
        }

        @Override
        public synchronized String token() {
            Instant now = clock.instant();
            if (token == null || !now.isBefore(renewAt)) {
                JsonNode answer = request();
                token = answer.path("access_token").asText(null);
                if (token == null) {
                    throw new IllegalStateException("the token endpoint " + settings.endpoint() + " sent no access_token");
                }
                Duration life = Duration.ofSeconds(answer.path("expires_in").asLong(300));
                Duration margin = life.compareTo(Duration.ofMinutes(2)) < 0 ? life.dividedBy(2) : Duration.ofMinutes(1);
                renewAt = now.plus(life).minus(margin);
            }
            return token;
        }

        @Override
        public Map<String, String> clientProperties() {
            Map<String, String> properties = new LinkedHashMap<>();
            properties.put("credential", settings.clientId() + ":" + settings.clientSecret());
            properties.put("oauth2-server-uri", settings.endpoint().toString());
            if (settings.scope() != null) {
                properties.put("scope", settings.scope());
            }
            if (settings.audience() != null) {
                properties.put("audience", settings.audience());
            }
            return properties;
        }

        private JsonNode request() {
            Map<String, String> form = new LinkedHashMap<>();
            form.put("grant_type", "client_credentials");
            form.put("client_id", settings.clientId());
            form.put("client_secret", settings.clientSecret());
            if (settings.scope() != null) {
                form.put("scope", settings.scope());
            }
            if (settings.audience() != null) {
                form.put("audience", settings.audience());
            }
            String body = form.entrySet().stream().map(e -> encode(e.getKey()) + "=" + encode(e.getValue()))
                    .collect(Collectors.joining("&"));
            Instant deadline = clock.instant().plus(PATIENCE);
            Duration wait = FIRST_PAUSE;
            while (true) {
                Duration left = Duration.between(clock.instant(), deadline);
                HttpRequest request = HttpRequest.newBuilder(settings.endpoint())
                        .timeout(left.compareTo(FIRST_PAUSE) < 0 ? FIRST_PAUSE : left)
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build();
                RuntimeException failure;
                try {
                    HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
                    if (response.statusCode() == 200) {
                        return parse(response.body());
                    }
                    failure = new IllegalStateException("the token endpoint " + settings.endpoint() + " answered "
                            + response.statusCode());
                    if (response.statusCode() < 500) {
                        throw failure;
                    }
                } catch (IOException e) {
                    failure = new UncheckedIOException("cannot reach the token endpoint " + settings.endpoint(), e);
                } catch (InterruptedException e) {
                    throw interrupted(e);
                }
                if (!clock.instant().plus(wait).isBefore(deadline)) {
                    throw failure;
                }
                LOG.warn("Asking the token endpoint again in {}: {}", wait, failure.getMessage());
                try {
                    pause.pause(wait);
                } catch (InterruptedException e) {
                    throw interrupted(e);
                }
                Duration doubled = wait.multipliedBy(2);
                wait = doubled.compareTo(LONGEST_PAUSE) > 0 ? LONGEST_PAUSE : doubled;
            }
        }

        private JsonNode parse(String body) {
            try {
                return JSON.readTree(body);
            } catch (IOException e) {
                throw new UncheckedIOException("cannot read the answer of the token endpoint " + settings.endpoint(),
                        e);
            }
        }

        private static IllegalStateException interrupted(InterruptedException e) {
            Thread.currentThread().interrupt();
            return new IllegalStateException("interrupted while asking for a token", e);
        }

        private static String encode(String value) {
            return URLEncoder.encode(value, StandardCharsets.UTF_8);
        }
    }
}
