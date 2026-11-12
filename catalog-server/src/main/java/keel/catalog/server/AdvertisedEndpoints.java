package keel.catalog.server;

import java.util.List;

/**
 * The endpoints list sent in {@code /v1/config} (D13). It names exactly the routes this server
 * serves; {@code AdvertisedEndpointsTest} checks it against the registered routes. Neither
 * {@code /v1/config} itself nor {@code /v1/oauth/tokens}, which answers only with a fixed error,
 * is listed. The list is never empty: iceberg-core omits an empty list, and the Java client then
 * assumes a legacy default set.
 */
final class AdvertisedEndpoints {

    static final List<String> ALL = List.of(
            "GET /v1/{prefix}/namespaces",
            "POST /v1/{prefix}/namespaces",
            "GET /v1/{prefix}/namespaces/{namespace}",
            "HEAD /v1/{prefix}/namespaces/{namespace}",
            "DELETE /v1/{prefix}/namespaces/{namespace}",
            "POST /v1/{prefix}/namespaces/{namespace}/properties",
            "GET /v1/{prefix}/namespaces/{namespace}/tables",
            "POST /v1/{prefix}/namespaces/{namespace}/tables",
            "GET /v1/{prefix}/namespaces/{namespace}/tables/{table}",
            "HEAD /v1/{prefix}/namespaces/{namespace}/tables/{table}",
            "POST /v1/{prefix}/namespaces/{namespace}/tables/{table}",
            "DELETE /v1/{prefix}/namespaces/{namespace}/tables/{table}",
            "POST /v1/{prefix}/tables/rename",
            "POST /v1/{prefix}/namespaces/{namespace}/register",
            "POST /v1/{prefix}/namespaces/{namespace}/tables/{table}/metrics",
            "GET /v1/{prefix}/namespaces/{namespace}/tables/{table}/credentials",
            "GET /v1/{prefix}/namespaces/{namespace}/views",
            "POST /v1/{prefix}/namespaces/{namespace}/views",
            "GET /v1/{prefix}/namespaces/{namespace}/views/{view}",
            "HEAD /v1/{prefix}/namespaces/{namespace}/views/{view}",
            "POST /v1/{prefix}/namespaces/{namespace}/views/{view}",
            "DELETE /v1/{prefix}/namespaces/{namespace}/views/{view}",
            "POST /v1/{prefix}/views/rename",
            // Atomic on every supported store: one transaction swaps every pointer (D15).
            "POST /v1/{prefix}/transactions/commit");

    private AdvertisedEndpoints() {
    }
}
