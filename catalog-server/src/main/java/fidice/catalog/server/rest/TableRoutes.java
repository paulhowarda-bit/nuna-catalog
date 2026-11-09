package fidice.catalog.server.rest;

import fidice.catalog.core.CatalogError;
import fidice.catalog.core.commit.CommitEngine;
import fidice.catalog.core.commit.CommitReply;
import fidice.catalog.core.commit.ObjectName;
import fidice.catalog.core.commit.TableFiles;
import fidice.catalog.iceberg.Metadata;
import fidice.catalog.iceberg.MetadataViews;
import fidice.catalog.iceberg.Outcome;
import fidice.catalog.iceberg.RestRequest;
import fidice.catalog.iceberg.RestResponse;
import fidice.catalog.server.rest.CredentialVending.Vended;
import fidice.catalog.store.CatalogObject;
import fidice.catalog.store.CatalogObject.Kind;
import fidice.catalog.store.CatalogRecord;
import fidice.catalog.store.CatalogStore;
import fidice.catalog.store.NamespaceRecord;
import fidice.catalog.store.Pointer;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
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
 * The table routes of D13. Creates, commits and registrations go through the commit engine (M3);
 * loads answer 304 from the pointer alone when the client's ETag still holds (D14).
 */
@RestController
class TableRoutes {

    static final String TABLES = NamespaceRoutes.NAMESPACE + "/tables";
    static final String TABLE = TABLES + "/{table}";
    static final String RENAME = "/v1/{prefix}/tables/rename";
    static final String REGISTER = NamespaceRoutes.NAMESPACE + "/register";
    static final String METRICS = TABLE + "/metrics";
    static final String CREDENTIALS = TABLE + "/credentials";

    private final CatalogStore store;
    private final Lookup lookup;
    private final ObjectRoutes objects;
    private final Engines engines;
    private final MetadataViews views;
    private final TableFiles files;
    private final CredentialVending vending;
    private final MetricsSink metrics;
    private final ServerAlerts alerts;
    private final RestSettings settings;
    private final Callers callers;
    private final Clock clock;

    TableRoutes(CatalogStore store, Lookup lookup, ObjectRoutes objects, Engines engines, MetadataViews views,
                TableFiles files, CredentialVending vending, MetricsSink metrics, ServerAlerts alerts,
                RestSettings settings, Callers callers, Clock clock) {
        this.store = store;
        this.lookup = lookup;
        this.objects = objects;
        this.engines = engines;
        this.views = views;
        this.files = files;
        this.vending = vending;
        this.metrics = metrics;
        this.alerts = alerts;
        this.settings = settings;
        this.callers = callers;
        this.clock = clock;
    }

    @GetMapping(TABLES)
    ResponseEntity<String> list(@PathVariable String prefix, @PathVariable String namespace,
                                @RequestParam(required = false) String pageToken,
                                @RequestParam(required = false) String pageSize) {
        return objects.list(prefix, namespace, pageToken, pageSize, Kind.TABLE);
    }

    /** A create, or with {@code stage-create} the metadata of a table that exists only once its create commits. */
    @PostMapping(TABLES)
    ResponseEntity<String> create(@PathVariable String prefix, @PathVariable String namespace,
                                  @RequestHeader(name = Http.IDEMPOTENCY_KEY, required = false) String key,
                                  @RequestHeader(name = Http.ACCESS_DELEGATION, required = false) String delegation,
                                  @RequestBody(required = false) String body,
                                  HttpServletRequest http) {
        CatalogRecord catalog = lookup.catalog(prefix);
        List<String> levels = Lookup.levels(namespace);
        String json = body == null ? "" : body;
        RestRequest.NewTable table = RestRequest.createTable(json);
        NamespaceRecord record = lookup.existingNamespace(catalog, levels);
        ObjectName name = ObjectRoutes.name(catalog, record, levels, table.name());
        if (table.stageCreate()) {
            return staged(catalog, record, name, json, Optional.ofNullable(delegation));
        }
        RequestContext request = RequestContext.of(http, callers);
        CommitReply reply = engines.forRequest(catalog.name(), AuditEvents.path(levels, table.name()), request)
                .createTable(name, json, request.caller(), Optional.ofNullable(key));
        return ObjectRoutes.reply(reply, done -> loaded(catalog, done, Optional.ofNullable(delegation)));
    }

    @GetMapping(TABLE)
    ResponseEntity<String> load(@PathVariable String prefix, @PathVariable String namespace,
                                @PathVariable String table,
                                @RequestParam(required = false) String snapshots,
                                @RequestParam(name = "referenced-by", required = false) String referencedBy,
                                @RequestHeader(name = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch,
                                @RequestHeader(name = Http.ACCESS_DELEGATION, required = false) String delegation) {
        CatalogRecord catalog = lookup.catalog(prefix);
        boolean refsOnly = refsOnly(snapshots);
        CatalogObject object = objects.existing(catalog, Lookup.levels(namespace), table, Kind.TABLE);
        Vended vended = vending.vend(catalog.id(), object.id(), object.locationRoot(), Optional.ofNullable(delegation));
        boolean cacheable = !vended.expireBefore(clock.instant().plus(settings.credentialRefreshMargin()));
        Optional<Pointer> pointer = store.readPointer(object.mainKey());
        if (cacheable && pointer.isPresent()) {
            String etag = ETags.of(pointer.get().metadataLocation(), refsOnly, referencedBy, vended.epoch());
            if (ETags.matches(ifNoneMatch, etag)) {
                return ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(etag).build();
            }
        }
        CommitEngine.Current.Loaded current = ObjectRoutes.current(engines.reader(), object);
        String location = current.pointer().metadataLocation();
        alerts.loaded(object.id(), location, views.tableStats(location, current.json()));
        String response = views.loadTable(location, current.json(), refsOnly, vended.config(), vended.credentials());
        ResponseEntity.BodyBuilder builder = ResponseEntity.ok();
        if (cacheable) {
            builder.eTag(ETags.of(location, refsOnly, referencedBy, vended.epoch()));
        }
        return builder.contentType(MediaType.APPLICATION_JSON).body(response);
    }

    @RequestMapping(path = TABLE, method = RequestMethod.HEAD)
    ResponseEntity<Void> exists(@PathVariable String prefix, @PathVariable String namespace,
                                @PathVariable String table) {
        return objects.exists(prefix, namespace, table, Kind.TABLE);
    }

    /** A commit, including the commit of a staged create. */
    @PostMapping(TABLE)
    ResponseEntity<String> commit(@PathVariable String prefix, @PathVariable String namespace,
                                  @PathVariable String table,
                                  @RequestHeader(name = Http.IDEMPOTENCY_KEY, required = false) String key,
                                  @RequestHeader(name = Http.ACCESS_DELEGATION, required = false) String delegation,
                                  @RequestBody(required = false) String body,
                                  HttpServletRequest http) {
        CatalogRecord catalog = lookup.catalog(prefix);
        List<String> levels = Lookup.levels(namespace);
        NamespaceRecord record = lookup.namespace(catalog, levels)
                .orElseThrow(() -> Http.noSuchTable(Http.qualified(levels, table)));
        RequestContext request = RequestContext.of(http, callers);
        CommitReply reply = engines.forRequest(catalog.name(), AuditEvents.path(levels, table), request)
                .commitTable(ObjectRoutes.name(catalog, record, levels, table), body == null ? "" : body,
                        request.caller(), Optional.ofNullable(key));
        return ObjectRoutes.reply(reply, done -> loaded(catalog, done, Optional.ofNullable(delegation)));
    }

    /** {@code purgeRequested} is ignored: every drop is soft, and purge follows the retention (D25). */
    @DeleteMapping(TABLE)
    ResponseEntity<String> drop(@PathVariable String prefix, @PathVariable String namespace,
                                @PathVariable String table,
                                @RequestHeader(name = Http.IDEMPOTENCY_KEY, required = false) String key,
                                HttpServletRequest http) {
        return objects.drop(prefix, namespace, table, Kind.TABLE, Optional.ofNullable(key),
                RequestContext.of(http, callers));
    }

    @PostMapping(RENAME)
    ResponseEntity<String> rename(@PathVariable String prefix,
                                  @RequestHeader(name = Http.IDEMPOTENCY_KEY, required = false) String key,
                                  @RequestBody(required = false) String body,
                                  HttpServletRequest http) {
        return objects.rename(prefix, body, Kind.TABLE, Optional.ofNullable(key), RequestContext.of(http, callers));
    }

    /** A table whose metadata file already exists (D34); the engine refuses a UUID the catalog already holds. */
    @PostMapping(REGISTER)
    ResponseEntity<String> register(@PathVariable String prefix, @PathVariable String namespace,
                                    @RequestHeader(name = Http.IDEMPOTENCY_KEY, required = false) String key,
                                    @RequestHeader(name = Http.ACCESS_DELEGATION, required = false) String delegation,
                                    @RequestBody(required = false) String body,
                                    HttpServletRequest http) {
        CatalogRecord catalog = lookup.catalog(prefix);
        List<String> levels = Lookup.levels(namespace);
        String json = body == null ? "" : body;
        RestRequest.Registration registration = RestRequest.register(json);
        NamespaceRecord record = lookup.existingNamespace(catalog, levels);
        RequestContext request = RequestContext.of(http, callers);
        CommitReply reply = engines.forRequest(catalog.name(), AuditEvents.path(levels, registration.name()), request)
                .registerTable(ObjectRoutes.name(catalog, record, levels, registration.name()), json,
                        request.caller(), Optional.ofNullable(key));
        return ObjectRoutes.reply(reply, done -> loaded(catalog, done, Optional.ofNullable(delegation)));
    }

    /** Forwarded to telemetry, never stored (D13). */
    @PostMapping(METRICS)
    ResponseEntity<Void> metrics(@PathVariable String prefix, @PathVariable String namespace,
                                 @PathVariable String table, @RequestBody(required = false) String body) {
        CatalogRecord catalog = lookup.catalog(prefix);
        RestRequest.MetricsSummary summary = RestRequest.reportMetrics(body == null ? "" : body);
        metrics.report(catalog.name(), Lookup.levels(namespace), table, summary);
        return ResponseEntity.noContent().build();
    }

    @GetMapping(CREDENTIALS)
    ResponseEntity<String> credentials(@PathVariable String prefix, @PathVariable String namespace,
                                       @PathVariable String table,
                                       @RequestHeader(name = Http.ACCESS_DELEGATION, required = false) String delegation) {
        CatalogRecord catalog = lookup.catalog(prefix);
        CatalogObject object = objects.existing(catalog, Lookup.levels(namespace), table, Kind.TABLE);
        Vended vended = vending.vend(catalog.id(), object.id(), object.locationRoot(), Optional.ofNullable(delegation));
        return Http.json(200, RestResponse.credentials(vended.credentials()));
    }

    /**
     * Stage-create: the table's first metadata, at the location and UUID the catalog assigns, with no file
     * and no object yet. The create commit later takes that UUID (M3).
     */
    private ResponseEntity<String> staged(CatalogRecord catalog, NamespaceRecord namespace, ObjectName name,
                                          String json, Optional<String> delegation) {
        Optional<CatalogObject> holder = store.findObject(namespace.id(), name.name().normalized());
        if (holder.isPresent()) {
            throw CatalogError.of(409, "AlreadyExistsException", holder.get().kind() == Kind.TABLE
                    ? "Table already exists: " + name.qualified()
                    : "View with same name already exists: " + name.qualified());
        }
        UUID id = UUID.randomUUID();
        String location = files.newLocation(id);
        return switch (Metadata.createTable(json, id.toString(), location)) {
            case Outcome.NewMetadata created -> {
                Vended vended = vending.vend(catalog.id(), id, location, delegation);
                yield Http.json(200, MetadataViews.stagedTable(created.json(), vended.config(), vended.credentials()));
            }
            case Outcome.BadRequest bad -> Http.error(400, bad.type(), bad.message());
            case Outcome.RequirementFailed failed -> Http.error(409, "CommitFailedException", failed.message());
            case Outcome.NoChange noChange -> throw new IllegalStateException("a create always writes metadata");
            case Outcome.NotFound notFound -> throw new IllegalStateException("a create never reads metadata");
        };
    }

    /** The load response for a create, commit or registration, with its ETag for the next load. */
    private ResponseEntity<String> loaded(CatalogRecord catalog, CommitReply.Success done,
                                          Optional<String> delegation) {
        String location = done.metadataLocation();
        Optional<CatalogObject> object = store.readObject(done.objectId());
        Vended vended = object
                .map(o -> vending.vend(catalog.id(), o.id(), o.locationRoot(), delegation))
                .orElse(CredentialVending.NONE.vend(catalog.id(), done.objectId(), location, delegation));
        String response = views.loadTable(location, done.metadataJson(), false, vended.config(), vended.credentials());
        return ResponseEntity.ok()
                .eTag(ETags.of(location, false, null, vended.epoch()))
                .contentType(MediaType.APPLICATION_JSON)
                .body(response);
    }

    private static boolean refsOnly(String snapshots) {
        if (snapshots == null || snapshots.equalsIgnoreCase("all")) {
            return false;
        }
        if (snapshots.equalsIgnoreCase("refs")) {
            return true;
        }
        throw CatalogError.badRequest("Invalid snapshot mode: " + snapshots);
    }
}
