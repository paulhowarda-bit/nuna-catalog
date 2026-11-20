package nuna.catalog.server.rest;

import nuna.catalog.core.commit.CommitEngine;
import nuna.catalog.core.commit.CommitReply;
import nuna.catalog.core.commit.GovernanceChain;
import nuna.catalog.iceberg.MetadataViews;
import nuna.catalog.iceberg.RestRequest;
import nuna.catalog.server.governance.ReadAudit;
import nuna.catalog.server.metrics.CatalogMetrics;
import nuna.catalog.store.CatalogObject;
import nuna.catalog.store.CatalogObject.Kind;
import nuna.catalog.store.CatalogRecord;
import nuna.catalog.store.CatalogStore;
import nuna.catalog.store.NamespaceRecord;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The view routes of D13. Creates and replaces go through the commit engine (M3). Through a branch or tag
 * ({@code catalog@ref}, D33), lists, loads and HEAD read as of the ref; every write there is refused ({@link OnRef}).
 */
@RestController
class ViewRoutes {

    static final String VIEWS = NamespaceRoutes.NAMESPACE + "/views";
    static final String VIEW = VIEWS + "/{view}";
    static final String RENAME = "/v1/{prefix}/views/rename";
    static final String REGISTER = NamespaceRoutes.NAMESPACE + "/register-view";

    private final CatalogStore store;
    private final Lookup lookup;
    private final ObjectRoutes objects;
    private final Engines engines;
    private final MetadataViews views;
    private final Callers callers;
    private final Guard guard;
    private final ReadAudit reads;
    private final CatalogMetrics meters;

    ViewRoutes(CatalogStore store, Lookup lookup, ObjectRoutes objects, Engines engines, MetadataViews views,
               Callers callers, Guard guard, ReadAudit reads, CatalogMetrics meters) {
        this.store = store;
        this.lookup = lookup;
        this.objects = objects;
        this.engines = engines;
        this.views = views;
        this.callers = callers;
        this.guard = guard;
        this.reads = reads;
        this.meters = meters;
    }

    @GetMapping(VIEWS)
    ResponseEntity<String> list(@PathVariable String prefix, @PathVariable String namespace,
                                @RequestParam(required = false) String pageToken,
                                @RequestParam(required = false) String pageSize,
                                HttpServletRequest http) {
        return objects.list(prefix, namespace, pageToken, pageSize, Kind.VIEW, RequestContext.of(http, callers));
    }

    @PostMapping(VIEWS)
    ResponseEntity<String> create(@PathVariable String prefix, @PathVariable String namespace,
                                  @RequestHeader(name = Http.IDEMPOTENCY_KEY, required = false) String key,
                                  @RequestBody(required = false) String body,
                                  HttpServletRequest http) {
        RequestContext request = RequestContext.of(http, callers);
        CatalogRecord catalog = lookup.catalog(prefix, request, Guard.CREATE_VIEW);
        List<String> levels = Lookup.levels(namespace);
        String json = body == null ? "" : body;
        String name = RestRequest.createViewName(json);
        NamespaceRecord record = lookup.existingNamespace(catalog, levels);
        guard.onNamespace(request, catalog, record, levels, Guard.CREATE_VIEW, Http.noSuchNamespace(levels));
        CommitReply reply = engines.forRequest(catalog, AuditEvents.path(levels, name), request)
                .createView(ObjectRoutes.name(catalog, record, levels, name), json, request.caller(),
                        Optional.ofNullable(key));
        meters.commit(catalog.name(), Operations.CREATE_VIEW, reply);
        return ObjectRoutes.reply(reply, this::loaded);
    }

    @GetMapping(VIEW)
    ResponseEntity<String> load(@PathVariable String prefix, @PathVariable String namespace,
                                @PathVariable String view, HttpServletRequest http) {
        OnRef on = lookup.onRef(prefix, RequestContext.of(http, callers), Guard.LOAD_VIEW);
        CatalogRecord catalog = on.catalog();
        List<String> levels = Lookup.levels(namespace);
        Lookup.Seen seen = objects.seen(on, levels, view, Kind.VIEW);
        CatalogObject object = seen.object();
        guard.onObject(RequestContext.of(http, callers), catalog, object, AuditEvents.path(levels, view),
                Guard.LOAD_VIEW, Http.noSuchView(Http.qualified(levels, view)));
        CommitEngine.Current.Loaded current = ObjectRoutes.current(engines.reader(catalog), object,
                lookup.pointer(on, seen));
        reads.read(http, catalog, AuditEvents.path(levels, view), object, current.pointer(), on.branch());
        return Http.json(200, views.loadView(current.pointer().metadataLocation(), current.json(), Map.of(),
                GovernanceChain.of(store, object).labels()));
    }

    @RequestMapping(path = VIEW, method = RequestMethod.HEAD)
    ResponseEntity<Void> exists(@PathVariable String prefix, @PathVariable String namespace,
                                @PathVariable String view, HttpServletRequest http) {
        return objects.exists(prefix, namespace, view, Kind.VIEW, RequestContext.of(http, callers));
    }

    /** Replace: a new version of the view (D28 keeps its location where the catalog put it). */
    @PostMapping(VIEW)
    ResponseEntity<String> replace(@PathVariable String prefix, @PathVariable String namespace,
                                   @PathVariable String view,
                                   @RequestHeader(name = Http.IDEMPOTENCY_KEY, required = false) String key,
                                   @RequestBody(required = false) String body,
                                   HttpServletRequest http) {
        RequestContext request = RequestContext.of(http, callers);
        CatalogRecord catalog = lookup.forCommit(prefix, request, Guard.REPLACE_VIEW);
        List<String> levels = Lookup.levels(namespace);
        String qualified = Http.qualified(levels, view);
        NamespaceRecord record = lookup.namespace(catalog, levels).orElseThrow(() -> Http.noSuchView(qualified));
        Optional<CatalogObject> existing = lookup.object(record, view, Kind.VIEW);
        if (existing.isPresent()) {
            guard.onObject(request, catalog, existing.get(), AuditEvents.path(levels, view), Guard.REPLACE_VIEW,
                    Http.noSuchView(qualified));
        } else {
            guard.onNewObject(request, catalog, record, levels, Guard.CREATE_VIEW, Http.noSuchView(qualified));
        }
        CommitReply reply = engines.forRequest(catalog, AuditEvents.path(levels, view), request)
                .commitView(ObjectRoutes.name(catalog, record, levels, view), body == null ? "" : body,
                        request.caller(), Optional.ofNullable(key));
        meters.commit(catalog.name(), Operations.REPLACE_VIEW, reply);
        return ObjectRoutes.reply(reply, this::loaded);
    }

    /**
     * A view whose metadata file already exists, as a table is registered: only from the location the catalog
     * assigns its UUID (D28), and the file a dropped or unregistered view left at brings it back, only in the
     * namespace it left (D13, amended 2026-11-12).
     */
    @PostMapping(REGISTER)
    ResponseEntity<String> register(@PathVariable String prefix, @PathVariable String namespace,
                                    @RequestHeader(name = Http.IDEMPOTENCY_KEY, required = false) String key,
                                    @RequestBody(required = false) String body,
                                    HttpServletRequest http) {
        RequestContext request = RequestContext.of(http, callers);
        CatalogRecord catalog = lookup.catalog(prefix, request, Guard.REGISTER_VIEW);
        List<String> levels = Lookup.levels(namespace);
        String json = body == null ? "" : body;
        RestRequest.Registration registration = RestRequest.registerView(json);
        NamespaceRecord record = lookup.existingNamespace(catalog, levels);
        guard.onNamespace(request, catalog, record, levels, Guard.REGISTER_VIEW, Http.noSuchNamespace(levels));
        CommitReply reply = engines.forRequest(catalog, AuditEvents.path(levels, registration.name()), request)
                .registerView(ObjectRoutes.name(catalog, record, levels, registration.name()), json,
                        request.caller(), Optional.ofNullable(key), objects.undrop(catalog, request));
        meters.commit(catalog.name(), Operations.REGISTER_VIEW, reply);
        return ObjectRoutes.reply(reply, this::loaded);
    }

    @DeleteMapping(VIEW)
    ResponseEntity<String> drop(@PathVariable String prefix, @PathVariable String namespace,
                                @PathVariable String view,
                                @RequestHeader(name = Http.IDEMPOTENCY_KEY, required = false) String key,
                                HttpServletRequest http) {
        return objects.drop(prefix, namespace, view, Kind.VIEW, Optional.ofNullable(key),
                RequestContext.of(http, callers));
    }

    @PostMapping(RENAME)
    ResponseEntity<String> rename(@PathVariable String prefix,
                                  @RequestHeader(name = Http.IDEMPOTENCY_KEY, required = false) String key,
                                  @RequestBody(required = false) String body,
                                  HttpServletRequest http) {
        return objects.rename(prefix, body, Kind.VIEW, Optional.ofNullable(key), RequestContext.of(http, callers));
    }

    private ResponseEntity<String> loaded(CommitReply.Success done) {
        Map<String, String> labels = store.readObject(done.objectId())
                .map(object -> GovernanceChain.of(store, object).labels()).orElse(Map.of());
        return Http.json(200, views.loadView(done.metadataLocation(), done.metadataJson(), Map.of(), labels));
    }
}
