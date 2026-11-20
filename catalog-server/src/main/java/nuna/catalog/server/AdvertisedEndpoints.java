package nuna.catalog.server;

import nuna.catalog.server.rest.OnRef;
import nuna.catalog.store.Ref;
import java.util.List;
import java.util.stream.Stream;

/**
 * The endpoints list sent in {@code /v1/config} (D13). On main it names exactly the routes this server
 * serves; {@code AdvertisedEndpointsTest} checks it against the registered routes. Through a branch or a tag it
 * names what that ref serves (D33). Neither
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
            "POST /v1/{prefix}/namespaces/{namespace}/tables/{table}/unregister",
            "POST /v1/{prefix}/namespaces/{namespace}/tables/{table}/metrics",
            "GET /v1/{prefix}/namespaces/{namespace}/tables/{table}/credentials",
            "GET /v1/{prefix}/namespaces/{namespace}/views",
            "POST /v1/{prefix}/namespaces/{namespace}/views",
            "GET /v1/{prefix}/namespaces/{namespace}/views/{view}",
            "HEAD /v1/{prefix}/namespaces/{namespace}/views/{view}",
            "POST /v1/{prefix}/namespaces/{namespace}/views/{view}",
            "DELETE /v1/{prefix}/namespaces/{namespace}/views/{view}",
            "POST /v1/{prefix}/views/rename",
            "POST /v1/{prefix}/namespaces/{namespace}/register-view",
            // Atomic on every supported store: one transaction swaps every pointer (D15).
            "POST /v1/{prefix}/transactions/commit");

    /**
     * What a tag serves, read-only to every engine (D33, item 26's choice 5): the reads, a scan's metrics report and
     * credentials, which only read. Iceberg's clients check this list before a call, so a stock engine's write on a
     * tag fails at once, before it reaches the server.
     */
    static final List<String> READ = List.of(
            "GET /v1/{prefix}/namespaces",
            "GET /v1/{prefix}/namespaces/{namespace}",
            "HEAD /v1/{prefix}/namespaces/{namespace}",
            "GET /v1/{prefix}/namespaces/{namespace}/tables",
            "GET /v1/{prefix}/namespaces/{namespace}/tables/{table}",
            "HEAD /v1/{prefix}/namespaces/{namespace}/tables/{table}",
            "POST /v1/{prefix}/namespaces/{namespace}/tables/{table}/metrics",
            "GET /v1/{prefix}/namespaces/{namespace}/tables/{table}/credentials",
            "GET /v1/{prefix}/namespaces/{namespace}/views",
            "GET /v1/{prefix}/namespaces/{namespace}/views/{view}",
            "HEAD /v1/{prefix}/namespaces/{namespace}/views/{view}");

    /**
     * What a branch serves: the reads and the commits, to a table, a view and several tables at once. Creates, drops,
     * renames, registrations and namespace changes happen on main only (D33, as amended 2026-11-20), so a stock engine
     * fails them at once, as it fails a write on a tag.
     */
    static final List<String> BRANCH = Stream.concat(READ.stream(), Stream.of(
            "POST /v1/{prefix}/namespaces/{namespace}/tables/{table}",
            "POST /v1/{prefix}/namespaces/{namespace}/views/{view}",
            "POST /v1/{prefix}/transactions/commit")).toList();

    private AdvertisedEndpoints() {
    }

    /** The list for what a prefix names: main, a branch or a tag. */
    static List<String> of(OnRef on) {
        return on.ref().map(ref -> ref.kind() == Ref.Kind.TAG ? READ : BRANCH).orElse(ALL);
    }
}
