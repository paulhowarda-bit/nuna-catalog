package keel.maintenance;

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

/**
 * The worker's bearer token for the catalog: it signs in as a maintenance principal acting as itself (D30), never for
 * an end user. Asked for before every request, so a token is never used after it expires.
 */
public interface TokenSource {

    /** A token valid now. */
    String token();

    /**
     * How an Iceberg client started on the worker's behalf (a compaction Job) signs in: the client-credentials grant,
     * so it renews its own token, or else the worker's current token.
     */
    Map<String, String> clientProperties();

    /** The token source the settings name. */
    static TokenSource of(WorkerSettings.Token settings, Clock clock) {
        return settings.file() != null ? new FileToken(settings.file())
                : new ClientCredentials(settings, HttpClient.newHttpClient(), clock);
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

        @Override
        public Map<String, String> clientProperties() {
            return Map.of("token", token());
        }
    }

    /**
     * The OAuth 2 client-credentials grant (RFC 6749 4.4): the worker's client id and secret buy a token, kept until a
     * minute before it expires, or until half its life has passed for one that lives less than two minutes.
     */
    final class ClientCredentials implements TokenSource {

        private static final ObjectMapper JSON = new ObjectMapper();

        private final WorkerSettings.Token settings;
        private final HttpClient http;
        private final Clock clock;
        private String token;
        private Instant renewAt = Instant.MIN;

        ClientCredentials(WorkerSettings.Token settings, HttpClient http, Clock clock) {
            this.settings = settings;
            this.http = http;
            this.clock = clock;
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
            HttpRequest request = HttpRequest.newBuilder(settings.endpoint()).timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            try {
                HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() != 200) {
                    throw new IllegalStateException("the token endpoint " + settings.endpoint() + " answered "
                            + response.statusCode());
                }
                return JSON.readTree(response.body());
            } catch (IOException e) {
                throw new UncheckedIOException("cannot reach the token endpoint " + settings.endpoint(), e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while asking for a token", e);
            }
        }

        private static String encode(String value) {
            return URLEncoder.encode(value, StandardCharsets.UTF_8);
        }
    }
}
