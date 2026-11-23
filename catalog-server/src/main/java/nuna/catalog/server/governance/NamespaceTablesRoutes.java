package nuna.catalog.server.governance;

import static nuna.catalog.server.governance.Management.NAMESPACE;

import nuna.catalog.core.CatalogError;
import nuna.catalog.security.Authorizer;
import nuna.catalog.security.Authorizer.Decision;
import nuna.catalog.security.Privilege;
import nuna.catalog.server.governance.Management.Request;
import nuna.catalog.server.security.Holdings;
import nuna.catalog.store.CatalogObject;
import nuna.catalog.store.CatalogStore;
import nuna.catalog.store.JournalStore;
import nuna.catalog.store.Page;
import nuna.catalog.store.PageRequest;
import nuna.catalog.store.Pointer;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Optional;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The tables of one namespace with each one's format version, snapshot count and last commit, for the console's
 * Contents (D45, backlog 137; docs/observability/namespace-tables.md). Everything comes from what the catalog keeps:
 * the projection stored beside each main pointer (D5) and the commit journal's entry for that pointer's version. No
 * metadata file is read and no table is loaded, so nothing is audited per table.
 *
 * <p>A table is listed only to a caller who may {@code describe} it; any other is skipped, never named. The namespace
 * is answered as Iceberg's listing answers it to a caller who holds something on it, above it or in it; any other
 * caller is refused, audited, and answered as for a namespace that does not exist (D18).
 */
@RestController
class NamespaceTablesRoutes {

    static final String TABLES = NAMESPACE + "/tables";
    /** Rows a page holds unless it asks. */
    static final int PAGE = 100;
    /** Rows a page holds at most. */
    static final int MAX_PAGE = 200;
    /** Tables one page looks at, at most; a page that finds fewer the caller may describe ends early, with a token. */
    static final int MAX_EXAMINED = 1000;
    private static final String OPERATION = "listNamespaceTables";
    private static final Authorizer.Operation DESCRIBE = new Authorizer.Operation(OPERATION, Privilege.DESCRIBE,
            false);

    private final CatalogStore store;
    private final Management management;
    private final ManagementGuard guard;
    private final Holdings holdings;
    private final Authorizer authorizer;

    NamespaceTablesRoutes(CatalogStore store, Management management, ManagementGuard guard, Holdings holdings,
                          Authorizer authorizer) {
        this.store = store;
        this.management = management;
        this.guard = guard;
        this.holdings = holdings;
        this.authorizer = authorizer;
    }

    /**
     * {@code {"tables": [{"id", "name", "format-version", "snapshots", "last-commit": {"version", "committed-at",
     * "principal", "end-user", "engine"}}], "next-page-token"}}, by name. Each page holds at most {@code pageSize} rows
     * (100 by default, 1 to 200), and fewer when the caller may describe few of the tables it looks at (1,000 at
     * most); {@code next-page-token} is there while more may follow.
     */
    @GetMapping(path = TABLES, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<String> tables(@PathVariable("catalog") String prefix, @PathVariable String namespace,
                                  @RequestParam(required = false) String pageToken,
                                  @RequestParam(required = false) Integer pageSize, HttpServletRequest http) {
        Request request = management.request(http);
        Management.Target target = management.target(request, OPERATION, Privilege.DESCRIBE, prefix, namespace,
                null, null);
        if (!holdings.onAboveOrIn(request.caller(), target.catalog().id(), target.securable().id())) {
            guard.require(request, target, OPERATION, Privilege.DESCRIBE);
        }
        int size = pageSize == null ? PAGE : Math.clamp(pageSize, 1, MAX_PAGE);
        ObjectNode body = Management.JSON.createObjectNode();
        ArrayNode rows = body.putArray("tables");
        Optional<String> cursor = Optional.ofNullable(pageToken).filter(token -> !token.isEmpty());
        Optional<String> next;
        int examined = 0;
        while (true) {
            Page<CatalogObject> page;
            try {
                page = store.listObjects(target.securable().id(), CatalogObject.Kind.TABLE,
                        CatalogObject.State.ACTIVE, new PageRequest(cursor,
                                Math.min(size - rows.size(), MAX_EXAMINED - examined)));
            } catch (IllegalArgumentException e) {
                throw CatalogError.badRequest("Invalid page token: " + pageToken);
            }
            List<CatalogObject> tables = page.items();
            examined += tables.size();
            List<Decision> decisions = tables.isEmpty() ? List.of() : authorizer.decide(request.caller(), tables
                    .stream().map(table -> new Authorizer.Request(new Authorizer.Target.TableOrView(table), DESCRIBE))
                    .toList());
            for (int i = 0; i < tables.size(); i++) {
                if (decisions.get(i).allowed()) {
                    rows.add(row(tables.get(i)));
                }
            }
            next = page.nextToken();
            if (next.isEmpty() || rows.size() == size || examined >= MAX_EXAMINED) {
                break;
            }
            cursor = next;
        }
        next.ifPresent(token -> body.put("next-page-token", token));
        return GovernanceRoutes.json(200, body);
    }

    /** The table's row: its format version and snapshot count from its main pointer, its last commit from the journal. */
    private ObjectNode row(CatalogObject table) {
        ObjectNode row = Management.JSON.createObjectNode();
        row.put("id", table.id().toString());
        row.put("name", table.name().display());
        store.readPointer(table.mainKey()).ifPresent(pointer -> {
            JsonNode projection = Management.JSON.readTree(pointer.projection());
            if (projection.path("format-version").isNumber()) {
                row.put("format-version", projection.get("format-version").asInt());
            }
            if (projection.path("snapshots").isNumber()) {
                row.put("snapshots", projection.get("snapshots").asLong());
            }
            lastCommit(table, pointer).ifPresent(commit -> row.set("last-commit", commit));
        });
        return row;
    }

    /** The journal's entry for the pointer's version: when it was committed, and by whom. */
    private Optional<ObjectNode> lastCommit(CatalogObject table, Pointer pointer) {
        return store.readJournal(table.mainKey(), JournalStore.EARLIEST, JournalStore.LATEST,
                        JournalStore.afterVersion(pointer.version() - 1, 1)).items().stream()
                .filter(entry -> entry.newVersion() == pointer.version()).findFirst().map(entry -> {
                    ObjectNode commit = Management.JSON.createObjectNode();
                    commit.put("version", entry.newVersion());
                    commit.put("committed-at", entry.committedAt().toString());
                    commit.put("principal", entry.entry().principal());
                    if (entry.entry().endUser() != null) {
                        commit.put("end-user", entry.entry().endUser());
                    }
                    if (entry.entry().engine() != null) {
                        commit.put("engine", entry.entry().engine());
                    }
                    return commit;
                });
    }
}
