package nuna.catalog.server;

import nuna.catalog.core.CatalogError;
import nuna.catalog.core.Names;
import nuna.catalog.core.commit.CommitSettings;
import nuna.catalog.iceberg.Wire;
import nuna.catalog.server.metrics.RequestMetrics;
import nuna.catalog.server.rest.Callers;
import nuna.catalog.server.rest.RequestContext;
import nuna.catalog.server.rest.RestSettings;
import nuna.catalog.server.security.Holdings;
import nuna.catalog.store.CatalogRecord;
import nuna.catalog.store.CatalogStore;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /v1/config}: tells a client which catalog its warehouse names (as the REST
 * prefix), which endpoints this server supports (D13, D28), and that mutations take idempotency
 * keys (D10). A warehouse the caller holds nothing on is answered as one that does not exist (D18, amended
 * 2026-11-20, item 68). A client that names no warehouse gets the deployment's default catalog, if one is set
 * ({@code nuna.rest.default-warehouse}); the spec makes the parameter optional.
 */
@RestController
public class ConfigRoute {

    private final CatalogStore store;
    private final RestSettings settings;
    private final Callers callers;
    private final Holdings holdings;

    public ConfigRoute(CatalogStore store, RestSettings settings, Callers callers, Holdings holdings) {
        this.store = store;
        this.settings = settings;
        this.callers = callers;
        this.holdings = holdings;
    }

    @GetMapping(path = "/v1/config", produces = MediaType.APPLICATION_JSON_VALUE)
    public String config(@RequestParam(name = "warehouse", required = false) String requested,
                         HttpServletRequest http) {
        CatalogRecord catalog;
        if (requested == null || requested.isBlank()) {
            catalog = defaultCatalog();
        } else {
            RequestContext request = RequestContext.of(http, callers);
            catalog = holdings.catalog(requested, request.caller(), request.actor(), "getConfig", null);
        }
        RequestMetrics.catalog(catalog.name());
        return Wire.config(
                Map.of(),
                Map.of("prefix", catalog.normalizedName()),
                AdvertisedEndpoints.ALL,
                CommitSettings.DEFAULTS.keyLifetime().toString());
    }

    /**
     * The deployment's default catalog, for a client that names no warehouse, whatever the caller holds on it:
     * whether it is hidden from a caller that holds nothing there is not decided (item 82, Paul decides).
     */
    private CatalogRecord defaultCatalog() {
        String warehouse = settings.defaultWarehouse();
        if (warehouse == null || warehouse.isBlank()) {
            throw CatalogError.badRequest("Missing query parameter warehouse, and this server has no default"
                    + " warehouse");
        }
        return store.findCatalog(Names.normalize(warehouse))
                .orElseThrow(() -> CatalogError.noSuchWarehouse("The given warehouse does not exist: " + warehouse));
    }
}
