package nuna.catalog.server.rest;

import nuna.catalog.core.CatalogError;
import nuna.catalog.core.commit.CommitEngine;
import nuna.catalog.core.commit.CommitReply;
import nuna.catalog.core.commit.ObjectName;
import nuna.catalog.iceberg.RestRequest;
import nuna.catalog.server.metrics.CatalogMetrics;
import nuna.catalog.store.CatalogObject;
import nuna.catalog.store.CatalogObject.Kind;
import nuna.catalog.store.CatalogRecord;
import nuna.catalog.store.NamespaceRecord;
import nuna.catalog.store.RefEntry;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
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
 * transaction gives no partial publish; it does not give readers a view of the tables at one instant. Through a branch
 * (D33, item 105) each table is one the branch's cut names, and the transaction swaps the branch's own pointers.
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
        RequestContext request = RequestContext.of(http, callers);
        OnRef on = lookup.forCommit(prefix, request, Guard.COMMIT_TRANSACTION);
        CatalogRecord catalog = on.catalog();
        String json = body == null ? "" : body;
        List<RestRequest.TableCommit> commits = RestRequest.commitTransaction(json);
        if (commits.size() > settings.maxTransactionTables()) {
            throw CatalogError.badRequest("A transaction may change at most " + settings.maxTransactionTables()
                    + " tables; this one names " + commits.size());
        }
        // Keyed by the name the engine finds each table under again on main, and by id on a branch, whose engine finds
        // each by its cut entry whatever main has named it since: so every event finds its path.
        Map<Named, List<String>> byName = new HashMap<>();
        Map<UUID, List<String>> byId = new HashMap<>();
        Function<CatalogObject, List<String>> paths = object -> on.onMain() ? byName.get(Named.of(object))
                : byId.get(object.id());
        Map<ObjectName, RefEntry> cut = new HashMap<>();
        List<CommitEngine.TableChange> changes = new ArrayList<>();
        for (RestRequest.TableCommit commit : commits) {
            List<String> levels = commit.identifier().namespace();
            String table = commit.identifier().name();
            String qualified = Http.qualified(levels, table);
            NamespaceRecord namespace = lookup.namespace(on, levels).orElseThrow(() -> Http.noSuchTable(qualified));
            Optional<Lookup.Seen> seen = lookup.seen(on, namespace, levels, table, Kind.TABLE);
            CatalogObject object = seen.orElseThrow(() -> Http.noSuchTable(qualified)).object();
            List<String> path = AuditEvents.path(levels, table);
            if (byId.put(object.id(), path) != null) {
                throw CatalogError.badRequest("Table " + qualified + " is named more than once in the transaction");
            }
            byName.put(Named.of(object), path);
            guard.onCommit(request, on, object, path, guard.commit(request, commit.json(), on),
                    Http.noSuchTable(qualified));
            ObjectName name = ObjectRoutes.name(catalog, namespace, levels, table);
            cut.putAll(ObjectRoutes.cut(name, seen));
            changes.add(new CommitEngine.TableChange(name, commit.json()));
        }
        CommitReply reply = engines.forTransaction(on, paths, request, cut)
                .commitTransaction(changes, json, request.caller(), Optional.ofNullable(key));
        meters.commit(catalog.name(), on.refKind(), Operations.COMMIT_TRANSACTION, reply);
        guard.refused(request, catalog, Guard.COMMIT_TRANSACTION, paths, reply);
        engines.committed(on, reply, request);
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
