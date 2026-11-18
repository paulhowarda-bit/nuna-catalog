package nuna.catalog.server;

import nuna.catalog.core.CatalogError;
import nuna.catalog.core.Names;
import nuna.catalog.core.commit.CommitSettings;
import nuna.catalog.iceberg.Wire;
import nuna.catalog.server.metrics.RequestMetrics;
import nuna.catalog.server.rest.RestSettings;
import nuna.catalog.store.CatalogRecord;
import nuna.catalog.store.CatalogStore;

import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /v1/config}: tells a client which catalog its warehouse names (as the REST
 * prefix), which endpoints this server supports (D13, D28), and that mutations take idempotency
 * keys (D10). A client that names no warehouse gets the deployment's default catalog, if one is set
 * ({@code nuna.rest.default-warehouse}); the spec makes the parameter optional.
 */
@RestController
public class ConfigRoute {

    private final CatalogStore store;
    private final RestSettings settings;

    public ConfigRoute(CatalogStore store, RestSettings settings) {
        this.store = store;
        this.settings = settings;
    }

    @GetMapping(path = "/v1/config", produces = MediaType.APPLICATION_JSON_VALUE)
    public String config(@RequestParam(name = "warehouse", required = false) String requested) {
        String warehouse = requested == null || requested.isBlank() ? settings.defaultWarehouse() : requested;
        if (warehouse == null || warehouse.isBlank()) {
            throw CatalogError.badRequest("Missing query parameter warehouse, and this server has no default"
                    + " warehouse");
        }
        CatalogRecord catalog = store.findCatalog(Names.normalize(warehouse))
                .orElseThrow(() -> CatalogError.noSuchWarehouse("The given warehouse does not exist: " + warehouse));
        RequestMetrics.catalog(catalog.name());
        return Wire.config(
                Map.of(),
                Map.of("prefix", catalog.normalizedName()),
                AdvertisedEndpoints.ALL,
                CommitSettings.DEFAULTS.keyLifetime().toString());
    }
}
