package nuna.catalog.server;

import nuna.catalog.core.CatalogError;
import nuna.catalog.core.commit.CommitSettings;
import nuna.catalog.iceberg.Wire;
import nuna.catalog.server.metrics.RequestMetrics;
import nuna.catalog.server.rest.Callers;
import nuna.catalog.server.rest.RequestContext;
import nuna.catalog.server.rest.RestSettings;
import nuna.catalog.server.security.Holdings;
import nuna.catalog.store.CatalogRecord;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /v1/config}: tells a client which catalog its warehouse names (as the REST
 * prefix), which endpoints this server supports (D13, D28), and that mutations take idempotency
 * keys (D10). A client that names no warehouse gets the deployment's default catalog, if one is set
 * ({@code nuna.rest.default-warehouse}); the spec makes the parameter optional. A warehouse the caller holds nothing
 * in, named or the default, is answered as one that does not exist (D18, amended 2026-11-20, items 68 and 82).
 */
@RestController
public class ConfigRoute {

    private final RestSettings settings;
    private final Callers callers;
    private final Holdings holdings;

    public ConfigRoute(RestSettings settings, Callers callers, Holdings holdings) {
        this.settings = settings;
        this.callers = callers;
        this.holdings = holdings;
    }

    @GetMapping(path = "/v1/config", produces = MediaType.APPLICATION_JSON_VALUE)
    public String config(@RequestParam(name = "warehouse", required = false) String requested,
                         HttpServletRequest http) {
        String warehouse = requested == null || requested.isBlank() ? defaultWarehouse() : requested;
        RequestContext request = RequestContext.of(http, callers);
        CatalogRecord catalog = holdings.catalog(warehouse, request.caller(), request.actor(), "getConfig", null);
        RequestMetrics.catalog(catalog.name());
        return Wire.config(
                Map.of(),
                Map.of("prefix", catalog.normalizedName()),
                AdvertisedEndpoints.ALL,
                CommitSettings.DEFAULTS.keyLifetime().toString());
    }

    /** The deployment's default warehouse, for a client that names none; with none set, the client must name one. */
    private String defaultWarehouse() {
        String warehouse = settings.defaultWarehouse();
        if (warehouse == null || warehouse.isBlank()) {
            throw CatalogError.badRequest("Missing query parameter warehouse, and this server has no default"
                    + " warehouse");
        }
        return warehouse;
    }
}
