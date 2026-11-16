package keel.catalog.server.rest;

import java.util.List;
import java.util.Optional;
import org.springframework.http.HttpMethod;
import org.springframework.http.server.PathContainer;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

/**
 * The Iceberg REST operations this server answers, each by the operationId the spec gives it
 * (spec/rest-catalog-open-api.yaml), so that their meters carry the spec's names. Matched on the raw path, before
 * authentication, so a refused call is named too.
 */
public final class Operations {

    public static final String CREATE_TABLE = "createTable";
    public static final String UPDATE_TABLE = "updateTable";
    public static final String REGISTER_TABLE = "registerTable";
    public static final String UNREGISTER_TABLE = "unregisterTable";
    public static final String COMMIT_TRANSACTION = "commitTransaction";
    public static final String CREATE_VIEW = "createView";
    public static final String REPLACE_VIEW = "replaceView";
    public static final String REGISTER_VIEW = "registerView";

    private record Route(HttpMethod method, PathPattern pattern, String operationId) {
    }

    private static final List<Route> ROUTES = List.of(
            route(HttpMethod.GET, "/v1/config", "getConfig"),
            route(HttpMethod.POST, OAuthRoute.TOKENS, "getToken"),
            route(HttpMethod.GET, NamespaceRoutes.NAMESPACES, "listNamespaces"),
            route(HttpMethod.POST, NamespaceRoutes.NAMESPACES, "createNamespace"),
            route(HttpMethod.GET, NamespaceRoutes.NAMESPACE, "loadNamespaceMetadata"),
            route(HttpMethod.HEAD, NamespaceRoutes.NAMESPACE, "namespaceExists"),
            route(HttpMethod.DELETE, NamespaceRoutes.NAMESPACE, "dropNamespace"),
            route(HttpMethod.POST, NamespaceRoutes.PROPERTIES, "updateProperties"),
            route(HttpMethod.GET, TableRoutes.TABLES, "listTables"),
            route(HttpMethod.POST, TableRoutes.TABLES, CREATE_TABLE),
            route(HttpMethod.GET, TableRoutes.TABLE, "loadTable"),
            route(HttpMethod.HEAD, TableRoutes.TABLE, "tableExists"),
            route(HttpMethod.POST, TableRoutes.TABLE, UPDATE_TABLE),
            route(HttpMethod.DELETE, TableRoutes.TABLE, "dropTable"),
            route(HttpMethod.POST, TableRoutes.RENAME, "renameTable"),
            route(HttpMethod.POST, TableRoutes.REGISTER, REGISTER_TABLE),
            route(HttpMethod.POST, TableRoutes.UNREGISTER, UNREGISTER_TABLE),
            route(HttpMethod.POST, TableRoutes.METRICS, "reportMetrics"),
            route(HttpMethod.GET, TableRoutes.CREDENTIALS, "loadCredentials"),
            route(HttpMethod.POST, TransactionRoutes.COMMIT, COMMIT_TRANSACTION),
            route(HttpMethod.GET, ViewRoutes.VIEWS, "listViews"),
            route(HttpMethod.POST, ViewRoutes.VIEWS, CREATE_VIEW),
            route(HttpMethod.GET, ViewRoutes.VIEW, "loadView"),
            route(HttpMethod.HEAD, ViewRoutes.VIEW, "viewExists"),
            route(HttpMethod.POST, ViewRoutes.VIEW, REPLACE_VIEW),
            route(HttpMethod.DELETE, ViewRoutes.VIEW, "dropView"),
            route(HttpMethod.POST, ViewRoutes.RENAME, "renameView"),
            route(HttpMethod.POST, ViewRoutes.REGISTER, REGISTER_VIEW));

    private Operations() {
    }

    /** The operationId of a call by {@code method} to {@code path} (the raw path, without a context path). */
    public static Optional<String> of(String method, String path) {
        PathContainer container = PathContainer.parsePath(path);
        return ROUTES.stream()
                .filter(route -> route.method().matches(method) && route.pattern().matches(container))
                .map(Route::operationId)
                .findFirst();
    }

    private static Route route(HttpMethod method, String path, String operationId) {
        return new Route(method, PathPatternParser.defaultInstance.parse(path), operationId);
    }
}
