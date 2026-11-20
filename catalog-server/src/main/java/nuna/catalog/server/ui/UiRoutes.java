package nuna.catalog.server.ui;

import nuna.catalog.core.Names;
import nuna.catalog.core.principal.Caller;
import nuna.catalog.server.rest.Callers;
import nuna.catalog.server.security.Holdings;
import nuna.catalog.server.storage.StorageSettings;
import nuna.catalog.store.CatalogStore;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

/**
 * The console's page, its configuration, and the one listing the Iceberg REST API lacks: which catalogs the caller
 * may see. The page and {@code config.json} need no sign-in; {@code config.json} holds nothing secret (UiSettings
 * has no secret to hold). The catalog list needs a token like every API call.
 */
@RestController
class UiRoutes {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final UiSettings settings;
    private final StorageSettings storage;
    private final Callers callers;
    private final CatalogStore store;
    private final Holdings holdings;
    private final byte[] index;

    UiRoutes(UiSettings settings, StorageSettings storage, Callers callers, CatalogStore store, Holdings holdings) {
        this.settings = settings;
        this.storage = storage;
        this.callers = callers;
        this.store = store;
        this.holdings = holdings;
        try (InputStream in = new DefaultResourceLoader().getResource(UiWiring.FILES + "index.html").getInputStream()) {
            this.index = in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("the console's index.html is missing from the server jar", e);
        }
    }

    /** Relative links in the page need the trailing slash. */
    @GetMapping("/ui")
    ResponseEntity<Void> withSlash() {
        return ResponseEntity.status(302).location(URI.create("/ui/")).build();
    }

    @GetMapping("/ui/")
    ResponseEntity<byte[]> page() {
        return ResponseEntity.ok().cacheControl(CacheControl.noCache())
                .contentType(new MediaType(MediaType.TEXT_HTML, java.nio.charset.StandardCharsets.UTF_8)).body(index);
    }

    /** How the console signs in: OIDC with PKCE against the configured IdP, and the dev token box if switched on. */
    @GetMapping(path = "/ui/config.json", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<String> config() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("dev-token-login", settings.devTokenLogin());
        if (settings.oidc() == null) {
            out.put("oidc", null);
        } else {
            Map<String, Object> oidc = new LinkedHashMap<>();
            oidc.put("issuer", settings.oidc().issuer().toString());
            oidc.put("client-id", settings.oidc().clientId());
            oidc.put("scopes", settings.oidc().scopes());
            oidc.put("redirect-uri", settings.oidc().redirectUri() == null ? null
                    : settings.oidc().redirectUri().toString());
            out.put("oidc", oidc);
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.APPLICATION_JSON)
                .body(JSON.writeValueAsString(out));
    }

    /**
     * The catalogs this deployment declares ({@code nuna.storage.catalogs}) that the caller holds something on, by
     * name (D18, amended 2026-11-19: names are hidden from callers without access), as {@link Holdings#inCatalog}
     * counts it: one of its groups holds a grant on the catalog or on anything in it, the grants its own listing
     * there shows; authorization is for the end user when a trusted engine acts for one. Grant admins hold
     * {@code manage-grants} on every catalog, so they see every one. Platform operators hold no grant but run the
     * deployment, the DR freeze of each catalog among it, so they see every one too, as before the filter. A caller
     * who holds nothing gets an empty list, not a refusal; every route that names a catalog it holds nothing on
     * answers as for one that does not exist (item 68). Whether it may see inside a catalog is decided when it lists
     * that catalog's namespaces.
     */
    @GetMapping(path = "/management/v1/catalogs", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<String> catalogs(HttpServletRequest http) {
        Caller caller = callers.caller(http);
        List<Map<String, String>> catalogs = new ArrayList<>();
        storage.catalogs().keySet().stream().sorted(String.CASE_INSENSITIVE_ORDER)
                .filter(name -> holdsSomething(name, caller))
                .forEach(name -> catalogs.add(Map.of("name", name)));
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON)
                .body(JSON.writeValueAsString(Map.of("catalogs", catalogs)));
    }

    /** Whether the caller holds something on the declared catalog. */
    private boolean holdsSomething(String catalog, Caller caller) {
        return store.findCatalog(Names.normalize(catalog)).map(record -> holdings.inCatalog(caller, record.id()))
                .orElse(false);
    }
}
