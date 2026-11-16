package keel.catalog.server.rest;

import keel.catalog.core.CatalogError;
import keel.catalog.core.commit.CommitEngine;
import keel.catalog.core.commit.CommitReply;
import keel.catalog.iceberg.RestRequest;
import keel.catalog.server.metrics.CatalogMetrics;
import keel.catalog.store.CatalogObject;
import keel.catalog.store.CatalogObject.Kind;
import keel.catalog.store.CatalogRecord;
import keel.catalog.store.NamespaceRecord;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /v1/{prefix}/transactions/commit} (D15): changes to several tables, committed together or not at
 * all, in one store transaction that locks the tables in a fixed order. Each table must exist and the caller
 * must be allowed to commit to each, as the table commit route asks; then the commit engine applies them. The
 * transaction gives no partial publish; it does not give readers a view of the tables at one instant.
 */
@RestController
class TransactionRoutes {

    static final String COMMIT = "/v1/{prefix}/transactions/commit";

    private final Lookup lookup;
    private final Engines engines;
    private final RestSettings settings;
    private final Callers callers;
    private final Guard guard;
    private final CatalogMetrics meters;

    TransactionRoutes(Lookup lookup, Engines engines, RestSettings settings, Callers callers, Guard guard,
                      CatalogMetrics meters) {
        this.lookup = lookup;
        this.engines = engines;
        this.settings = settings;
        this.callers = callers;
        this.guard = guard;
        this.meters = meters;
    }

    @PostMapping(COMMIT)
    ResponseEntity<String> commit(@PathVariable String prefix,
                                  @RequestHeader(name = Http.IDEMPOTENCY_KEY, required = false) String key,
                                  @RequestBody(required = false) String body,
                                  HttpServletRequest http) {
        CatalogRecord catalog = lookup.catalog(prefix);
        String json = body == null ? "" : body;
        List<RestRequest.TableCommit> commits = RestRequest.commitTransaction(json);
        if (commits.size() > settings.maxTransactionTables()) {
            throw CatalogError.badRequest("A transaction may change at most " + settings.maxTransactionTables()
                    + " tables; this one names " + commits.size());
        }
        RequestContext request = RequestContext.of(http, callers);
        // Keyed by the name the engine finds each table under again, so every event finds its path.
        Map<Named, List<String>> paths = new HashMap<>();
        List<CommitEngine.TableChange> changes = new ArrayList<>();
        for (RestRequest.TableCommit commit : commits) {
            List<String> levels = commit.identifier().namespace();
            String table = commit.identifier().name();
            String qualified = Http.qualified(levels, table);
            NamespaceRecord namespace = lookup.namespace(catalog, levels)
                    .orElseThrow(() -> Http.noSuchTable(qualified));
            CatalogObject object = lookup.object(namespace, table, Kind.TABLE)
                    .orElseThrow(() -> Http.noSuchTable(qualified));
            List<String> path = AuditEvents.path(levels, table);
            if (paths.put(Named.of(object), path) != null) {
                throw CatalogError.badRequest("Table " + qualified + " is named more than once in the transaction");
            }
            guard.onObject(request, catalog, object, path, guard.commit(request, commit.json()));
            changes.add(new CommitEngine.TableChange(ObjectRoutes.name(catalog, namespace, levels, table),
                    commit.json()));
        }
        CommitReply reply = engines.forTransaction(catalog, object -> paths.get(Named.of(object)), request)
                .commitTransaction(changes, json, request.caller(), Optional.ofNullable(key));
        meters.commit(catalog.name(), Operations.COMMIT_TRANSACTION, reply);
        return ObjectRoutes.reply(reply, done -> {
            throw new IllegalStateException("a transaction answers without metadata");
        });
    }

    /** A table by the name it is held under: its namespace and its normalized name. */
    private record Named(UUID namespaceId, String name) {
        static Named of(CatalogObject object) {
            return new Named(object.namespaceId(), object.name().normalized());
        }
    }
}
