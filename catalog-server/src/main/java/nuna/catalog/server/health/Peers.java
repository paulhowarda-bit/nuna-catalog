package nuna.catalog.server.health;

import java.io.IOException;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Asks the other servers for their figures ({@code GET /management/v1/health/server}), at every address each
 * {@code nuna.health.peers} entry resolves to, all at once. The servers mount no service account token and call no
 * Kubernetes API: the Helm chart's headless Service is a DNS name with an address for each ready server. Each request
 * carries the platform operator's own token, which the peer checks as it checks any caller's; the asking server has
 * already found the caller to be an operator, so no one else's token ever leaves it.
 */
final class Peers implements AutoCloseable {

    /** What one address answered: a server's figures, or why it gave none. */
    sealed interface Answer {
        String address();
    }

    record Answered(String address, HealthWindow.Window window) implements Answer {
    }

    record Silent(String address, String why) implements Answer {
    }

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final int MAX_BODY = 1 << 20;
    private static final int MAX_NAME = 253;

    private final List<URI> entries;
    private final Duration timeout;
    private final HttpClient http;

    Peers(HealthSettings settings) {
        this.entries = settings.peers().stream().map(peer -> URI.create("http://" + peer)).toList();
        this.timeout = settings.peerTimeout();
        this.http = HttpClient.newBuilder().connectTimeout(timeout)
                .executor(Executors.newVirtualThreadPerTaskExecutor()).followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /** Every peer's answer, asked with {@code authorization}, the caller's own {@code Authorization} header. */
    List<Answer> ask(String authorization) {
        List<Answer> answers = new ArrayList<>();
        List<CompletableFuture<Answer>> asked = new ArrayList<>();
        for (URI entry : entries) {
            InetAddress[] addresses;
            try {
                addresses = InetAddress.getAllByName(entry.getHost());
            } catch (UnknownHostException e) {
                answers.add(new Silent(entry.getRawAuthority(), "the name did not resolve"));
                continue;
            }
            for (InetAddress address : addresses) {
                String host = address instanceof Inet6Address ? "[" + address.getHostAddress() + "]"
                        : address.getHostAddress();
                String at = host + ":" + entry.getPort();
                HttpRequest request = HttpRequest.newBuilder(URI.create("http://" + at + CatalogHealthRoutes.SERVER))
                        .timeout(timeout).header("Accept", "application/json")
                        .header("Authorization", authorization).GET().build();
                asked.add(http.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream())
                        .thenApply(response -> answer(at, response))
                        .exceptionally(failure -> new Silent(at, why(failure))));
            }
        }
        for (CompletableFuture<Answer> answer : asked) {
            try {
                answers.add(answer.get());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while asking the other servers", e);
            } catch (ExecutionException e) {
                throw new IllegalStateException(e.getCause());
            }
        }
        return answers;
    }

    private static Answer answer(String at, HttpResponse<java.io.InputStream> response) {
        byte[] body;
        try (java.io.InputStream in = response.body()) {
            body = in.readNBytes(MAX_BODY);
        } catch (IOException e) {
            return new Silent(at, why(e));
        }
        if (response.statusCode() != 200) {
            String type = "";
            try {
                JsonNode error = JSON.readTree(body).path("error");
                type = error.path("type").isString() ? " " + error.path("type").asString() : "";
            } catch (JacksonException e) {
                // no error body to name
            }
            return new Silent(at, "answered " + response.statusCode() + type);
        }
        try {
            return new Answered(at, window(JSON.readTree(body)));
        } catch (JacksonException | IllegalArgumentException | java.time.DateTimeException e) {
            return new Silent(at, "answered something other than a server's health");
        }
    }

    /** A peer's answer, checked: figures only of names a server gives, each a count that is finite and not negative. */
    static HealthWindow.Window window(JsonNode json) {
        JsonNode server = json.path("server");
        String name = text(server, "name");
        if (name.length() > MAX_NAME) {
            throw new IllegalArgumentException("a server's name is too long");
        }
        Map<String, Double> figures = new TreeMap<>();
        JsonNode values = json.path("figures");
        if (!values.isObject()) {
            throw new IllegalArgumentException("no figures");
        }
        for (Map.Entry<String, JsonNode> figure : values.properties()) {
            double value = figure.getValue().isNumber() ? figure.getValue().doubleValue() : Double.NaN;
            if (!Figures.NAME.matcher(figure.getKey()).matches() || !Double.isFinite(value) || value < 0) {
                throw new IllegalArgumentException("not a figure: " + figure.getKey());
            }
            figures.put(figure.getKey(), value);
        }
        return new HealthWindow.Window(UUID.fromString(text(server, "id")), name,
                Instant.parse(text(server, "started-at")), Instant.parse(text(json, "from")),
                Instant.parse(text(json, "to")), figures);
    }

    private static String text(JsonNode json, String field) {
        JsonNode value = json.path(field);
        if (!value.isString()) {
            throw new IllegalArgumentException("no " + field);
        }
        return value.asString();
    }

    /** Why a peer gave no answer, without the exception's message, which can be long or say nothing. */
    private static String why(Throwable failure) {
        Throwable cause = failure;
        while (cause.getCause() != null && (cause instanceof java.util.concurrent.CompletionException
                || cause instanceof ExecutionException)) {
            cause = cause.getCause();
        }
        if (cause instanceof HttpTimeoutException) {
            return "no answer in time";
        }
        if (cause instanceof java.net.ConnectException) {
            return "could not connect";
        }
        return "the request failed: " + cause.getClass().getSimpleName();
    }

    @Override
    public void close() {
        http.close();
    }
}
