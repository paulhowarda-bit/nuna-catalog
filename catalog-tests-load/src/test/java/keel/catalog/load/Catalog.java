package keel.catalog.load;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The REST client of the M8 tests, with a client-side load balancer over the servers in rotation: each
 * request goes to the next one, and a request that cannot reach a server (refused, reset) goes to the next
 * with the same body and headers. Every status is counted, and every 5xx kept with its body.
 */
final class Catalog {

    static final JsonMapper JSON = JsonMapper.builder().build();

    /** One answer: status, body, ETag, how long it took and which server gave it. */
    record Answer(int status, String body, String etag, long nanos, URI server) {
        JsonNode json() {
            return JSON.readTree(body);
        }
    }

    /** No server in rotation could be reached. */
    static final class Unreachable extends RuntimeException {
        Unreachable(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private final HttpClient http = HttpClient.newBuilder()
            .executor(Executors.newVirtualThreadPerTaskExecutor())
            .connectTimeout(Duration.ofSeconds(2))
            .version(HttpClient.Version.HTTP_1_1)
            .build();
    private final String token;
    private final List<URI> rotation = new CopyOnWriteArrayList<>();
    private final Map<URI, AtomicInteger> inFlight = new ConcurrentHashMap<>();
    private final AtomicLong next = new AtomicLong();
    final Map<Integer, AtomicLong> statuses = new ConcurrentHashMap<>();
    final List<String> serverErrors = new CopyOnWriteArrayList<>();
    /** 503 answers that did not say when to retry. */
    final List<String> withoutRetryAfter = new CopyOnWriteArrayList<>();
    final AtomicLong unreachable = new AtomicLong();

    Catalog(String token, URI... servers) {
        this.token = token;
        for (URI server : servers) {
            add(server);
        }
    }

    void add(URI server) {
        inFlight.putIfAbsent(server, new AtomicInteger());
        rotation.add(server);
    }

    /** Takes a server out of rotation and waits until no request of ours is in flight on it. */
    void drain(URI server) {
        rotation.remove(server);
        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        while (inFlight.get(server).get() > 0) {
            if (System.nanoTime() > deadline) {
                throw new IllegalStateException("requests to " + server + " did not finish draining");
            }
            ServerProcess.pause(Duration.ofMillis(20));
        }
    }

    // ---- routes ----

    void createNamespace(List<String> levels) {
        for (int depth = 1; depth <= levels.size(); depth++) {
            List<String> path = levels.subList(0, depth);
            Answer answer = send("POST", "/v1/" + Stack.CATALOG + "/namespaces",
                    JSON.writeValueAsString(Map.of("namespace", path)), Map.of());
            if (answer.status() != 200 && answer.status() != 409) {
                throw new IllegalStateException("namespace " + path + ": " + answer.status() + " " + answer.body());
            }
        }
    }

    Answer createTable(List<String> namespace, String name, int formatVersion) {
        String body = """
                {"name":"%s","schema":{"type":"struct","schema-id":0,"fields":[
                  {"id":1,"name":"id","required":true,"type":"long"},{"id":2,"name":"v","required":false,"type":"string"}]},
                 "properties":{"format-version":"%d"}}""".formatted(name, formatVersion);
        Answer answer = send("POST", "/v1/" + Stack.CATALOG + "/namespaces/" + ns(namespace) + "/tables", body, Map.of());
        if (answer.status() != 200) {
            throw new IllegalStateException("table " + name + ": " + answer.status() + " " + answer.body());
        }
        return answer;
    }

    Answer load(List<String> namespace, String table, Map<String, String> headers) {
        return send("GET", tablePath(namespace, table), null, headers);
    }

    Answer load(List<String> namespace, String table, String query, Map<String, String> headers) {
        return send("GET", tablePath(namespace, table) + query, null, headers);
    }

    Answer commit(List<String> namespace, String table, String body, String idempotencyKey) {
        return send("POST", tablePath(namespace, table), body, Map.of("Idempotency-Key", idempotencyKey));
    }

    static String tablePath(List<String> namespace, String table) {
        return "/v1/" + Stack.CATALOG + "/namespaces/" + ns(namespace) + "/tables/" + table;
    }

    static String ns(List<String> levels) {
        return levels.stream().map(level -> URLEncoder.encode(level, StandardCharsets.UTF_8))
                .collect(Collectors.joining("%1F"));
    }

    /** Sends to the next server in rotation, moving on to the others while a server cannot be reached. */
    Answer send(String method, String path, String body, Map<String, String> headers) {
        List<URI> servers = new ArrayList<>(rotation);
        if (servers.isEmpty()) {
            throw new Unreachable("no server in rotation", null);
        }
        int start = (int) (next.getAndIncrement() % servers.size());
        IOException last = null;
        for (int i = 0; i < servers.size(); i++) {
            URI server = servers.get((start + i) % servers.size());
            HttpRequest.Builder request = HttpRequest.newBuilder(server.resolve(path))
                    .timeout(Duration.ofSeconds(90))
                    .header("Authorization", "Bearer " + token)
                    .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                            : HttpRequest.BodyPublishers.ofString(body));
            if (body != null) {
                request.header("Content-Type", "application/json");
            }
            headers.forEach(request::header);
            AtomicInteger busy = inFlight.get(server);
            busy.incrementAndGet();
            long started = System.nanoTime();
            try {
                HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
                long nanos = System.nanoTime() - started;
                statuses.computeIfAbsent(response.statusCode(), s -> new AtomicLong()).incrementAndGet();
                if (response.statusCode() >= 500) {
                    serverErrors.add(response.statusCode() + " " + method + " " + path + " from " + server + ": "
                            + response.body());
                }
                if (response.statusCode() == 503 && response.headers().firstValue("Retry-After").isEmpty()) {
                    withoutRetryAfter.add(method + " " + path + " from " + server);
                }
                return new Answer(response.statusCode(), response.body(),
                        response.headers().firstValue("ETag").orElse(null), nanos, server);
            } catch (IOException e) {
                last = e;
                unreachable.incrementAndGet();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            } finally {
                busy.decrementAndGet();
            }
        }
        throw new Unreachable(method + " " + path + " reached no server", last);
    }

    /** Statuses at or above 500 seen so far. */
    long fiveHundreds() {
        return statuses.entrySet().stream().filter(e -> e.getKey() >= 500).mapToLong(e -> e.getValue().get()).sum();
    }

    String statusSummary() {
        return new java.util.TreeMap<>(statuses).toString() + (unreachable.get() > 0 ? " unreachable=" + unreachable : "");
    }
}
