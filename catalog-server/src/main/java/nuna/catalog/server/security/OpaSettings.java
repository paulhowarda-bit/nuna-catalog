package nuna.catalog.server.security;

import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * nuna's answers to Trino's OPA access control (D20, backlog 115; {@link OpaRoutes}).
 *
 * @param catalogs each Trino catalog whose tables carry row filters and column masks, to the warehouse it reads:
 *                 a catalog, or {@code catalog@ref} for one read through a branch or tag, as the catalog's
 *                 {@code iceberg.rest-catalog.warehouse} says. A catalog not named here gets no rules: name every
 *                 Trino catalog that reads nuna.
 */
@ConfigurationProperties("nuna.opa")
public record OpaSettings(Map<String, String> catalogs) {

    public OpaSettings {
        catalogs = catalogs == null ? Map.of() : Map.copyOf(catalogs);
    }
}
