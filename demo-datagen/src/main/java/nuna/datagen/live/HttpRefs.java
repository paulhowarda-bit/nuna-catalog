package nuna.datagen.live;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.apache.iceberg.rest.RESTCatalog;

/**
 * The catalog's references over its management API (docs/refs.md, D33): {@code /management/v1/catalogs/{catalog}/refs}.
 * Writes on a branch go through the warehouse {@code <catalog>@<branch>}.
 */
public final class HttpRefs implements MonthEndWriter.Refs {

    private final HttpClient http = HttpClient.newHttpClient();
    private final String uri, catalog, token;
    private final Map<String, String> catalogProperties;

    /**
     * @param uri               the catalog's base URI, as the Iceberg REST client is given it
     * @param catalog           the warehouse, the catalog's name
     * @param catalogProperties the properties a RESTCatalog on a branch is opened with, less its warehouse
     */
    public HttpRefs(String uri, String catalog, String token, Map<String, String> catalogProperties) {
        this.uri = uri.endsWith("/") ? uri.substring(0, uri.length() - 1) : uri;
        this.catalog = catalog;
        this.token = token;
        this.catalogProperties = catalogProperties;
    }

    private String refs() {
        return uri + "/management/v1/catalogs/" + catalog + "/refs";
    }

    private HttpResponse<String> send(String method, String url, String body) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
            .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body))
            .header("Content-Type", "application/json");
        if (token != null) {
            b.header("Authorization", "Bearer " + token);
        }
        if (!method.equals("GET")) {
            b.header("Idempotency-Key", UUID.randomUUID().toString());
        }
        try {
            return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new IllegalStateException(method + " " + url + ": " + e, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", e);
        }
    }

    private static void expect(HttpResponse<String> r, int... codes) {
        for (int c : codes) {
            if (r.statusCode() == c) {
                return;
            }
        }
        throw new IllegalStateException(r.request().method() + " " + r.request().uri() + " answered " + r.statusCode()
            + ": " + r.body());
    }

    @Override
    public boolean exists(String ref) {
        HttpResponse<String> r = send("GET", refs() + "/" + ref, null);
        expect(r, 200, 404);
        return r.statusCode() == 200;
    }

    private void create(String name, String kind, String from, String description) {
        expect(send("POST", refs(), "{\"name\":" + json(name) + ",\"kind\":\"" + kind + "\",\"from\":" + json(from)
            + ",\"description\":" + json(description) + "}"), 201);
    }

    @Override
    public void createTag(String name, String from, String description) {
        create(name, "tag", from, description);
    }

    @Override
    public void createBranch(String name, String from, String description) {
        create(name, "branch", from, description);
    }

    @Override
    public void delete(String name) {
        expect(send("DELETE", refs() + "/" + name, null), 204, 404);
    }

    @Override
    public void merge(String branch) {
        expect(send("POST", refs() + "/" + branch + "/merge", null), 200);
    }

    @Override
    public RESTCatalog onBranch(String branch) {
        RESTCatalog c = new RESTCatalog();
        Map<String, String> p = new HashMap<>(catalogProperties);
        p.put("warehouse", catalog + "@" + branch);
        c.initialize("brokerage@" + branch, p);
        return c;
    }

    static String json(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (char c : (s.length() > 200 ? s.substring(0, 200) : s).toCharArray()) {
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                default -> b.append(c < 0x20 ? ' ' : c);
            }
        }
        return b.append('"').toString();
    }
}
