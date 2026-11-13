package keel.maintenance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The catalog's management routes the worker uses (D30), as a maintenance principal acting as itself: the work list,
 * the purge claim and its completion, and the pending deletions it records and completes.
 */
final class Management {

    /** A file list or id list the catalog takes in one request. */
    static final int BATCH = 1000;

    private static final ObjectMapper JSON = new ObjectMapper();

    /** One section of the work list, as the catalog names it. */
    enum Section { TABLES, PURGES, DELETIONS }

    /** An active table whose resolved maintenance policy runs a job, with that policy (guardrails applied). */
    record TableWork(String catalog, UUID id, List<String> namespace, String name, String location,
                     Map<String, String> policy) {

        boolean runs(String job) {
            return "true".equals(policy.get(job));
        }

        Duration duration(String key) {
            return Duration.parse(policy.get(key));
        }

        long count(String key) {
            return Long.parseLong(policy.get(key));
        }
    }

    /** A dropped object past its window, or one claimed for purge. */
    record PurgeWork(String catalog, UUID id, String kind, String name, String state, String location,
                     Optional<String> claimant) {
    }

    /** A file that may now be deleted. */
    record DeletionWork(String catalog, UUID id, UUID objectId, String location, Instant notBefore) {
    }

    /** One page of one section. */
    record Page<T>(List<T> items, Optional<String> nextToken) {
    }

    /** What the catalog answered a purge claim. */
    enum Claim {
        /** The caller holds the claim, newly or again. */
        CLAIMED,
        /** A hold or a DR freeze stops it, or the object is no longer dropped. */
        REFUSED,
        /** Another worker holds it. */
        CLAIMED_BY_ANOTHER,
        /** The catalog's purge rate limit: try next scan. */
        RATE_LIMITED
    }

    /** An answer the worker did not expect. */
    static final class CatalogException extends RuntimeException {

        final int status;

        CatalogException(String what, int status, String body) {
            super(what + " answered " + status + ": " + body);
            this.status = status;
        }
    }

    private final URI base;
    private final TokenSource tokens;
    private final HttpClient http;
    private final int pageSize;

    Management(URI base, TokenSource tokens, HttpClient http, int pageSize) {
        this.base = base.toString().endsWith("/") ? base : URI.create(base + "/");
        this.tokens = tokens;
        this.http = http;
        this.pageSize = pageSize;
    }

    Page<TableWork> tables(Optional<String> token) {
        return page(Section.TABLES, token, node -> new TableWork(node.path("catalog").asText(),
                UUID.fromString(node.path("id").asText()), strings(node.path("namespace")), node.path("name").asText(),
                node.path("location").asText(), policy(node.path("policy"))));
    }

    Page<PurgeWork> purges(Optional<String> token) {
        return page(Section.PURGES, token, node -> new PurgeWork(node.path("catalog").asText(),
                UUID.fromString(node.path("id").asText()), node.path("kind").asText(), node.path("name").asText(),
                node.path("state").asText(), node.path("location").asText(),
                Optional.ofNullable(node.path("purge-claimant").asText(null))));
    }

    Page<DeletionWork> deletions(Optional<String> token) {
        return page(Section.DELETIONS, token, node -> new DeletionWork(node.path("catalog").asText(),
                UUID.fromString(node.path("id").asText()), UUID.fromString(node.path("object-id").asText()),
                node.path("location").asText(), Instant.parse(node.path("not-before").asText())));
    }

    /** Claims the object's purge for the worker, or resumes its own claim. */
    Claim claimPurge(String catalog, UUID id) {
        HttpResponse<String> response = send("POST", object(catalog, id) + "/purge-claim", null);
        return switch (response.statusCode()) {
            case 200 -> Claim.CLAIMED;
            case 403, 404 -> Claim.REFUSED;
            case 409 -> response.body().contains("PurgeClaimedException") ? Claim.CLAIMED_BY_ANOTHER : Claim.REFUSED;
            case 429 -> Claim.RATE_LIMITED;
            default -> throw new CatalogException("purge-claim of " + id, response.statusCode(), response.body());
        };
    }

    /** Marks a claimed object purged, once its files are gone. */
    void completePurge(String catalog, UUID id) {
        expect("purge of " + id, send("POST", object(catalog, id) + "/purge", null), 204);
    }

    /**
     * Records files of a table for deletion once the database's PITR window has passed, at most {@link #BATCH} at a
     * time; files already pending keep their time.
     */
    void recordDeletions(String catalog, UUID objectId, Collection<String> locations) {
        List<String> all = List.copyOf(locations);
        for (int from = 0; from < all.size(); from += BATCH) {
            ObjectNode body = JSON.createObjectNode();
            ArrayNode list = body.putArray("locations");
            all.subList(from, Math.min(all.size(), from + BATCH)).forEach(list::add);
            expect("recording deletions of " + objectId, send("POST", object(catalog, objectId)
                    + "/pending-deletions", body), 200, 201);
        }
    }

    /** Reports pending deletions done, once their files are gone. */
    void completeDeletions(String catalog, UUID objectId, Collection<UUID> ids) {
        List<UUID> all = List.copyOf(ids);
        for (int from = 0; from < all.size(); from += BATCH) {
            ObjectNode body = JSON.createObjectNode();
            ArrayNode list = body.putArray("ids");
            all.subList(from, Math.min(all.size(), from + BATCH)).forEach(id -> list.add(id.toString()));
            expect("completing deletions of " + objectId, send("POST", object(catalog, objectId)
                    + "/pending-deletions/complete", body), 204);
        }
    }

    private <T> Page<T> page(Section section, Optional<String> token, java.util.function.Function<JsonNode, T> item) {
        StringBuilder path = new StringBuilder("management/v1/maintenance/work?section=")
                .append(section.name().toLowerCase(java.util.Locale.ROOT)).append("&pageSize=").append(pageSize);
        token.ifPresent(t -> path.append("&pageToken=").append(encode(t)));
        HttpResponse<String> response = expect("the work list", send("GET", path.toString(), null), 200);
        JsonNode body = read(response.body());
        List<T> items = new ArrayList<>();
        body.path(section.name().toLowerCase(java.util.Locale.ROOT)).forEach(node -> items.add(item.apply(node)));
        return new Page<>(items, Optional.ofNullable(body.path("next-page-token").asText(null)));
    }

    private static String object(String catalog, UUID id) {
        return "management/v1/catalogs/" + encode(catalog) + "/objects/" + id;
    }

    private HttpResponse<String> send(String method, String path, JsonNode body) {
        HttpRequest.Builder request = HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofMinutes(1))
                .header("Authorization", "Bearer " + tokens.token())
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body.toString()));
        if (body != null) {
            request.header("Content-Type", "application/json");
        }
        try {
            return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new UncheckedIOException("cannot reach the catalog at " + base, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", e);
        }
    }

    private static HttpResponse<String> expect(String what, HttpResponse<String> response, int... statuses) {
        for (int status : statuses) {
            if (response.statusCode() == status) {
                return response;
            }
        }
        throw new CatalogException(what, response.statusCode(), response.body());
    }

    private static JsonNode read(String body) {
        try {
            return JSON.readTree(body);
        } catch (IOException e) {
            throw new UncheckedIOException("the catalog sent JSON the worker cannot read", e);
        }
    }

    private static List<String> strings(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(value -> values.add(value.asText()));
        return List.copyOf(values);
    }

    private static Map<String, String> policy(JsonNode node) {
        Map<String, String> policy = new LinkedHashMap<>();
        node.properties().forEach(field -> policy.put(field.getKey(), field.getValue().asText()));
        return Map.copyOf(policy);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
