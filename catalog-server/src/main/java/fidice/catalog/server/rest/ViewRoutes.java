package fidice.catalog.server.rest;

import fidice.catalog.core.commit.CommitEngine;
import fidice.catalog.core.commit.CommitReply;
import fidice.catalog.iceberg.MetadataViews;
import fidice.catalog.iceberg.RestRequest;
import fidice.catalog.store.CatalogObject;
import fidice.catalog.store.CatalogObject.Kind;
import fidice.catalog.store.CatalogRecord;
import fidice.catalog.store.NamespaceRecord;
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

/** The view routes of D13. Creates and replaces go through the commit engine (M3). */
@RestController
class ViewRoutes {

    static final String VIEWS = NamespaceRoutes.NAMESPACE + "/views";
    static final String VIEW = VIEWS + "/{view}";
    static final String RENAME = "/v1/{prefix}/views/rename";

    private final Lookup lookup;
    private final ObjectRoutes objects;
    private final Engines engines;
    private final MetadataViews views;
    private final Callers callers;

    ViewRoutes(Lookup lookup, ObjectRoutes objects, Engines engines, MetadataViews views, Callers callers) {
        this.lookup = lookup;
        this.objects = objects;
        this.engines = engines;
        this.views = views;
        this.callers = callers;
    }

    @GetMapping(VIEWS)
    ResponseEntity<String> list(@PathVariable String prefix, @PathVariable String namespace,
                                @RequestParam(required = false) String pageToken,
                                @RequestParam(required = false) String pageSize) {
        return objects.list(prefix, namespace, pageToken, pageSize, Kind.VIEW);
    }

    @PostMapping(VIEWS)
    ResponseEntity<String> create(@PathVariable String prefix, @PathVariable String namespace,
                                  @RequestHeader(name = Http.IDEMPOTENCY_KEY, required = false) String key,
                                  @RequestBody(required = false) String body,
                                  HttpServletRequest http) {
        CatalogRecord catalog = lookup.catalog(prefix);
        List<String> levels = Lookup.levels(namespace);
        String json = body == null ? "" : body;
        String name = RestRequest.createViewName(json);
        NamespaceRecord record = lookup.existingNamespace(catalog, levels);
        RequestContext request = RequestContext.of(http, callers);
        CommitReply reply = engines.forRequest(catalog, AuditEvents.path(levels, name), request)
                .createView(ObjectRoutes.name(catalog, record, levels, name), json, request.caller(),
                        Optional.ofNullable(key));
        return ObjectRoutes.reply(reply, this::loaded);
    }

    @GetMapping(VIEW)
    ResponseEntity<String> load(@PathVariable String prefix, @PathVariable String namespace,
                                @PathVariable String view) {
        CatalogRecord catalog = lookup.catalog(prefix);
        CatalogObject object = objects.existing(catalog, Lookup.levels(namespace), view, Kind.VIEW);
        CommitEngine.Current.Loaded current = ObjectRoutes.current(engines.reader(catalog), object);
        return Http.json(200, views.loadView(current.pointer().metadataLocation(), current.json(), Map.of()));
    }

    @RequestMapping(path = VIEW, method = RequestMethod.HEAD)
    ResponseEntity<Void> exists(@PathVariable String prefix, @PathVariable String namespace,
                                @PathVariable String view) {
        return objects.exists(prefix, namespace, view, Kind.VIEW);
    }

    /** Replace: a new version of the view (D28 keeps its location where the catalog put it). */
    @PostMapping(VIEW)
    ResponseEntity<String> replace(@PathVariable String prefix, @PathVariable String namespace,
                                   @PathVariable String view,
                                   @RequestHeader(name = Http.IDEMPOTENCY_KEY, required = false) String key,
                                   @RequestBody(required = false) String body,
                                   HttpServletRequest http) {
        CatalogRecord catalog = lookup.catalog(prefix);
        List<String> levels = Lookup.levels(namespace);
        NamespaceRecord record = lookup.namespace(catalog, levels)
                .orElseThrow(() -> Http.noSuchView(Http.qualified(levels, view)));
        RequestContext request = RequestContext.of(http, callers);
        CommitReply reply = engines.forRequest(catalog, AuditEvents.path(levels, view), request)
                .commitView(ObjectRoutes.name(catalog, record, levels, view), body == null ? "" : body,
                        request.caller(), Optional.ofNullable(key));
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
        return Http.json(200, views.loadView(done.metadataLocation(), done.metadataJson(), Map.of()));
    }
}
