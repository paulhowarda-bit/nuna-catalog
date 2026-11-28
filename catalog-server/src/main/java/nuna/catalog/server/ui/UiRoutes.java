package nuna.catalog.server.ui;

import nuna.catalog.core.Names;
import nuna.catalog.core.principal.Caller;
import nuna.catalog.security.Authorizer;
import nuna.catalog.security.Privilege;
import nuna.catalog.security.Separation;
import nuna.catalog.server.rest.Callers;
import nuna.catalog.server.security.AuditCatalogSettings;
import nuna.catalog.server.security.Holdings;
import nuna.catalog.server.storage.StorageSettings;
import nuna.catalog.store.CatalogRecord;
import nuna.catalog.store.CatalogStore;
import nuna.catalog.store.GrantRecord;
import nuna.catalog.store.Page;
import nuna.catalog.store.PageRequest;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
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
    private final Authorizer authorizer;
    private final Separation separation;
    private final AuditCatalogSettings auditTables;
    private final byte[] index;

    UiRoutes(UiSettings settings, StorageSettings storage, Callers callers, CatalogStore store, Holdings holdings,
             Authorizer authorizer, Separation separation, AuditCatalogSettings auditTables) {
        this.settings = settings;
        this.storage = storage;
        this.callers = callers;
        this.store = store;
        this.holdings = holdings;
        this.authorizer = authorizer;
        this.separation = separation;
        this.auditTables = auditTables;
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
        out.put("docs-url", settings.docsLink());
        out.put("grafana-url", settings.grafanaUrl());
        // The brand pack's words; its look is in /ui/brand/brand.css, which the page links itself (backlog 333). The
        // folder stays on the server.
        if (settings.brand() == null) {
            out.put("brand", null);
        } else {
            Map<String, Object> brand = new LinkedHashMap<>();
            brand.put("name", settings.brand().name());
            brand.put("mark-label", settings.brand().markLabel());
            brand.put("show-name", settings.brand().showName());
            brand.put("toggle-label", settings.brand().toggleLabel());
            out.put("brand", brand);
        }
        if (settings.oidc() == null) {
            out.put("oidc", null);
        } else {
            Map<String, Object> oidc = new LinkedHashMap<>();
            oidc.put("issuer", settings.oidc().issuer().toString());
            oidc.put("client-id", settings.oidc().clientId());
            oidc.put("scopes", settings.oidc().scopes());
            oidc.put("redirect-uri", settings.oidc().redirectUri() == null ? null
                    : settings.oidc().redirectUri().toString());
            oidc.put("name", settings.oidc().name());
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
     *
     * <p>Each catalog also says what the caller's groups may do somewhere in it, so the console draws only the pages,
     * tabs and buttons they can use (Atelier's review, F4 and F12; backlog 184): {@code may} has {@code select},
     * {@code modify}, {@code manage-grants} and {@code manage-tags}, each true when one of the subject's groups holds a
     * grant that implies it on the catalog or on anything in it (a grant admin holds {@code manage-grants} on every
     * catalog). The answer's own {@code may} says whether the caller reads the audit feed ({@code read-audit-feed}:
     * select on the audit tables' catalog) and operates the deployment ({@code operate}: a platform operator). The
     * audit tables' catalog says so ({@code audit}), so the console lands nobody on it as if it were their data. Every
     * one is read from the caller's own grants and groups, so asking writes no denial; each route still decides for
     * itself, and these say only what the console offers.
     */
    @GetMapping(path = "/management/v1/catalogs", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<String> catalogs(HttpServletRequest http) {
        Caller caller = callers.caller(http);
        List<Map<String, Object>> catalogs = new ArrayList<>();
        storage.catalogs().keySet().stream().sorted(String.CASE_INSENSITIVE_ORDER)
                .forEach(name -> store.findCatalog(Names.normalize(name))
                        .filter(record -> holdings.inCatalog(caller, record.id()))
                        .ifPresent(record -> catalogs.add(Map.of("name", name, "may", may(caller, record),
                                "audit", isAuditCatalog(record)))));
        Map<String, Object> may = new TreeMap<>();
        may.put("read-audit-feed", readsAuditFeed(caller));
        may.put("operate", in(caller, separation.platformOperatorGroups()));
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON)
                .body(JSON.writeValueAsString(Map.of("catalogs", catalogs, "may", may)));
    }

    /** What the caller's groups hold somewhere in the catalog, by the privilege each grant implies. */
    private Map<String, Boolean> may(Caller caller, CatalogRecord catalog) {
        Set<String> groups = caller.subject().groups();
        List<Privilege> held = new ArrayList<>();
        if (!groups.isEmpty()) {
            PageRequest page = PageRequest.first(500);
            while (true) {
                Page<GrantRecord> grants = store.listGrantsOfGroups(catalog.id(), groups, page);
                for (GrantRecord grant : grants.items()) {
                    held.add(Privilege.fromWireName(grant.grant().privilege()));
                }
                if (grants.nextToken().isEmpty()) {
                    break;
                }
                page = page.after(grants);
            }
        }
        boolean grantAdmin = in(caller, separation.grantAdminGroups());
        Map<String, Boolean> may = new TreeMap<>();
        for (Privilege privilege : List.of(Privilege.SELECT, Privilege.MODIFY, Privilege.MANAGE_GRANTS,
                Privilege.MANAGE_TAGS)) {
            may.put(privilege.wireName(), held.stream().anyMatch(p -> p.implies(privilege))
                    || (privilege == Privilege.MANAGE_GRANTS && grantAdmin));
        }
        return may;
    }

    /** Whether the catalog holds the audit tables, which the console leaves out of a sign-in's landing (backlog 200). */
    private boolean isAuditCatalog(CatalogRecord catalog) {
        return auditTables.catalog() != null && !auditTables.catalog().isBlank()
                && Names.normalize(auditTables.catalog()).equals(catalog.normalizedName());
    }

    /** Whether the caller may read the audit feed: select on the audit tables' catalog, as the feed's route asks. */
    private boolean readsAuditFeed(Caller caller) {
        if (auditTables.catalog() == null || auditTables.catalog().isBlank()) {
            return false;
        }
        return store.findCatalog(Names.normalize(auditTables.catalog()))
                .map(audit -> authorizer.decide(caller, new Authorizer.Target.Catalog(audit.id()),
                        new Authorizer.Operation("readAuditFeed", Privilege.SELECT, false)).allowed())
                .orElse(false);
    }

    private static boolean in(Caller caller, Set<String> groups) {
        return caller.subject().groups().stream().anyMatch(groups::contains);
    }
}
