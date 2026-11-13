package keel.catalog.server.rest;

import keel.catalog.core.CatalogError;
import keel.catalog.core.commit.CommitEngine;
import keel.catalog.core.commit.CommitReply;
import keel.catalog.core.commit.GovernanceChain;
import keel.catalog.core.commit.ObjectName;
import keel.catalog.iceberg.Metadata;
import keel.catalog.iceberg.MetadataViews;
import keel.catalog.iceberg.Outcome;
import keel.catalog.iceberg.RestRequest;
import keel.catalog.iceberg.RestResponse;
import keel.catalog.server.governance.ReadAudit;
import keel.catalog.server.rest.CredentialVending.Vended;
import keel.catalog.store.CatalogObject;
import keel.catalog.store.CatalogObject.Kind;
import keel.catalog.store.CatalogRecord;
import keel.catalog.store.CatalogStore;
import keel.catalog.store.GovernanceAttribute;
import keel.catalog.store.NamespaceRecord;
import keel.catalog.store.Pointer;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.Map;
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
import org.springframework.web.util.UriUtils;

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
    static final String UNREGISTER = TABLE + "/unregister";
    static final String METRICS = TABLE + "/metrics";
    static final String CREDENTIALS = TABLE + "/credentials";

    private final CatalogStore store;
    private final Lookup lookup;
    private final ObjectRoutes objects;
    private final Engines engines;
    private final MetadataViews views;
    private final CredentialVending vending;
    private final MetricsSink metrics;
    private final ServerAlerts alerts;
    private final RestSettings settings;
    private final Callers callers;
    private final Guard guard;
    private final ReadAudit reads;
    private final Clock clock;

    TableRoutes(CatalogStore store, Lookup lookup, ObjectRoutes objects, Engines engines, MetadataViews views,
                CredentialVending vending, MetricsSink metrics, ServerAlerts alerts,
                RestSettings settings, Callers callers, Guard guard, Clock clock, ReadAudit reads) {
        this.store = store;
        this.lookup = lookup;
        this.objects = objects;
        this.engines = engines;
        this.views = views;
        this.vending = vending;
        this.metrics = metrics;
        this.alerts = alerts;
        this.settings = settings;
        this.callers = callers;
        this.guard = guard;
        this.clock = clock;
        this.reads = reads;
    }

    @GetMapping(TABLES)
    ResponseEntity<String> list(@PathVariable String prefix, @PathVariable String namespace,
                                @RequestParam(required = false) String pageToken,
                                @RequestParam(required = false) String pageSize,
                                HttpServletRequest http) {
        return objects.list(prefix, namespace, pageToken, pageSize, Kind.TABLE, RequestContext.of(http, callers));
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
        RequestContext request = RequestContext.of(http, callers);
        guard.onNamespace(request, catalog, record, levels, Guard.CREATE_TABLE);
        Target target = new Target(catalog, prefix, namespace, table.name(), request, Optional.ofNullable(delegation));
        if (table.stageCreate()) {
            return staged(target, record, name, json);
        }
        CommitReply reply = engines.forRequest(catalog, AuditEvents.path(levels, table.name()), request)
                .createTable(name, json, request.caller(), Optional.ofNullable(key));
        return ObjectRoutes.reply(reply, done -> loaded(target, done));
    }

    @GetMapping(TABLE)
    ResponseEntity<String> load(@PathVariable String prefix, @PathVariable String namespace,
                                @PathVariable String table,
                                @RequestParam(required = false) String snapshots,
                                @RequestParam(name = "referenced-by", required = false) String referencedBy,
                                @RequestHeader(name = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch,
                                @RequestHeader(name = Http.ACCESS_DELEGATION, required = false) String delegation,
                                HttpServletRequest http) {
        CatalogRecord catalog = lookup.catalog(prefix);
        boolean refsOnly = refsOnly(snapshots);
        List<String> levels = Lookup.levels(namespace);
        CatalogObject object = objects.existing(catalog, levels, table, Kind.TABLE);
        RequestContext request = RequestContext.of(http, callers);
        boolean write = guard.onObjectMayWrite(request, catalog, object, AuditEvents.path(levels, table),
                Guard.LOAD_TABLE);
        Vended vended = vend(new Target(catalog, prefix, namespace, table, request, Optional.ofNullable(delegation)),
                object.id(), object.locationRoot(), write);
        boolean cacheable = !vended.expireBefore(clock.instant().plus(settings.credentialRefreshMargin()));
        Map<String, String> labels = GovernanceChain.of(store, object).labels();
        Optional<Pointer> pointer = store.readPointer(object.mainKey());
        if (cacheable && pointer.isPresent()) {
            String etag = ETags.of(pointer.get().metadataLocation(), refsOnly, referencedBy, vended.epoch(), labels);
            if (ETags.matches(ifNoneMatch, etag)) {
                reads.read(http, catalog, AuditEvents.path(levels, table), object, pointer.get());
                return ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(etag).build();
            }
        }
        CommitEngine.Current.Loaded current = ObjectRoutes.current(engines.reader(catalog), object);
        String location = current.pointer().metadataLocation();
        reads.read(http, catalog, AuditEvents.path(levels, table), object, current.pointer());
        alerts.loaded(object.id(), location, views.tableStats(location, current.json()));
        String response = views.loadTable(location, current.json(), refsOnly, vended.config(), vended.credentials(),
                labels);
        ResponseEntity.BodyBuilder builder = ResponseEntity.ok();
        if (cacheable) {
            builder.eTag(ETags.of(location, refsOnly, referencedBy, vended.epoch(), labels));
        }
        return builder.contentType(MediaType.APPLICATION_JSON).body(response);
    }

    @RequestMapping(path = TABLE, method = RequestMethod.HEAD)
    ResponseEntity<Void> exists(@PathVariable String prefix, @PathVariable String namespace,
                                @PathVariable String table, HttpServletRequest http) {
        return objects.exists(prefix, namespace, table, Kind.TABLE, RequestContext.of(http, callers));
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
        Optional<CatalogObject> existing = lookup.object(record, table, Kind.TABLE);
        if (existing.isPresent()) {
            guard.onObject(request, catalog, existing.get(), AuditEvents.path(levels, table), Guard.commit(body));
        } else {
            guard.onNamespace(request, catalog, record, levels, Guard.CREATE_TABLE);
        }
        Target target = new Target(catalog, prefix, namespace, table, request, Optional.ofNullable(delegation));
        CommitReply reply = engines.forRequest(catalog, AuditEvents.path(levels, table), request)
                .commitTable(ObjectRoutes.name(catalog, record, levels, table), body == null ? "" : body,
                        request.caller(), Optional.ofNullable(key));
        return ObjectRoutes.reply(reply, done -> loaded(target, done));
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

    /**
     * A table whose metadata file already exists (D34). The engine refuses a UUID another table or view holds;
     * the file of a dropped or unregistered table brings that table back, and a dropped one only for a caller
     * who may undrop it (D13, amended 2026-11-12).
     */
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
        guard.onNamespace(request, catalog, record, levels, Guard.REGISTER_TABLE);
        Target target = new Target(catalog, prefix, namespace, registration.name(), request,
                Optional.ofNullable(delegation));
        CommitReply reply = engines.forRequest(catalog, AuditEvents.path(levels, registration.name()), request)
                .registerTable(ObjectRoutes.name(catalog, record, levels, registration.name()), json,
                        request.caller(), Optional.ofNullable(key), objects.undrop(catalog, request));
        return ObjectRoutes.reply(reply, done -> loaded(target, done));
    }

    /**
     * Unregisters a table (D13, amended 2026-11-12): the catalog stops tracking it and deletes nothing. The
     * answer names its current metadata file and holds its content. It needs {@code modify}; a regulated
     * table, or one flagged for row/column policy, is refused until a steward removes the flag (D20, D27),
     * and a hold or a DR freeze refuses it in the store. A table already gone still reaches the engine, so a
     * retry with the key replays the first answer.
     */
    @PostMapping(UNREGISTER)
    ResponseEntity<String> unregister(@PathVariable String prefix, @PathVariable String namespace,
                                      @PathVariable String table,
                                      @RequestHeader(name = Http.IDEMPOTENCY_KEY, required = false) String key,
                                      HttpServletRequest http) {
        CatalogRecord catalog = lookup.catalog(prefix);
        List<String> levels = Lookup.levels(namespace);
        NamespaceRecord record = lookup.namespace(catalog, levels)
                .orElseThrow(() -> Http.noSuchTable(Http.qualified(levels, table)));
        RequestContext request = RequestContext.of(http, callers);
        Optional<CatalogObject> existing = lookup.object(record, table, Kind.TABLE);
        if (existing.isPresent()) {
            CatalogObject object = existing.get();
            List<String> path = AuditEvents.path(levels, table);
            guard.onObject(request, catalog, object, path, Guard.UNREGISTER_TABLE);
            GovernanceChain governance = GovernanceChain.of(store, object);
            String qualified = Http.qualified(levels, table);
            if (governance.regulated()) {
                throw guard.refuse(request, catalog, object, path, Guard.UNREGISTER_TABLE, "regulated", "table "
                        + qualified + " is in a regulated area and cannot be unregistered; a steward removes the"
                        + " regulated flag first");
            }
            if (governance.attributes().stream().anyMatch(a -> a.target().equals(object.securable())
                    && a.kind() == GovernanceAttribute.Kind.ROW_COLUMN_POLICY && "true".equalsIgnoreCase(a.value()))) {
                throw guard.refuse(request, catalog, object, path, Guard.UNREGISTER_TABLE, "flagged_table", "table "
                        + qualified + " has a row/column policy and cannot be unregistered; a steward removes the"
                        + " flag first");
            }
        }
        CommitReply reply = engines.forRequest(catalog, AuditEvents.path(levels, table), request)
                .unregisterTable(ObjectRoutes.name(catalog, record, levels, table), request.caller(),
                        Optional.ofNullable(key));
        return ObjectRoutes.reply(reply, done -> Http.json(200,
                RestResponse.unregistered(done.metadataLocation(), done.metadataJson())));
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
                                       @RequestHeader(name = Http.ACCESS_DELEGATION, required = false) String delegation,
                                       HttpServletRequest http) {
        CatalogRecord catalog = lookup.catalog(prefix);
        List<String> levels = Lookup.levels(namespace);
        CatalogObject object = objects.existing(catalog, levels, table, Kind.TABLE);
        RequestContext request = RequestContext.of(http, callers);
        boolean write = guard.onObjectMayWrite(request, catalog, object, AuditEvents.path(levels, table),
                Guard.LOAD_CREDENTIALS);
        Vended vended = vend(new Target(catalog, prefix, namespace, table, request, Optional.ofNullable(delegation)),
                object.id(), object.locationRoot(), write);
        return Http.json(200, RestResponse.credentials(vended.credentials()));
    }

    /**
     * Stage-create: the table's first metadata, at the location and UUID the catalog assigns, with no file
     * and no object yet. The create commit later takes that UUID (M3).
     */
    private ResponseEntity<String> staged(Target target, NamespaceRecord namespace, ObjectName name, String json) {
        Optional<CatalogObject> holder = store.findObject(namespace.id(), name.name().normalized());
        if (holder.isPresent()) {
            throw CatalogError.of(409, "AlreadyExistsException", holder.get().kind() == Kind.TABLE
                    ? "Table already exists: " + name.qualified()
                    : "View with same name already exists: " + name.qualified());
        }
        UUID id = UUID.randomUUID();
        String location = engines.newLocation(target.catalog(), id);
        return switch (Metadata.createTable(json, id.toString(), location)) {
            case Outcome.NewMetadata created -> {
                // Its caller may create the table, and its create commit may carry data files written first.
                Vended vended = vend(target, id, location, true);
                yield Http.json(200, MetadataViews.stagedTable(created.json(), vended.config(), vended.credentials(),
                        GovernanceChain.of(store, namespace).labels()));
            }
            case Outcome.BadRequest bad -> Http.error(400, bad.type(), bad.message());
            case Outcome.RequirementFailed failed -> Http.error(409, "CommitFailedException", failed.message());
            case Outcome.NoChange noChange -> throw new IllegalStateException("a create always writes metadata");
            case Outcome.NotFound notFound -> throw new IllegalStateException("a create never reads metadata");
        };
    }

    /**
     * The load response for a create, commit or registration, with its ETag for the next load. The change is
     * already committed, so if credentials cannot be issued now the response carries none and the client
     * asks the credentials route.
     */
    private ResponseEntity<String> loaded(Target target, CommitReply.Success done) {
        String location = done.metadataLocation();
        Optional<CatalogObject> table = store.readObject(done.objectId());
        Map<String, String> labels = table.map(object -> GovernanceChain.of(store, object).labels()).orElse(Map.of());
        Vended vended = table.map(object -> {
            try {
                return vend(target, object.id(), object.locationRoot(), guard.mayWrite(target.request(), object));
            } catch (CatalogError e) {
                if (e.status() != 503) {
                    throw e;
                }
                return Vended.NONE;
            }
        }).orElse(Vended.NONE);
        String response = views.loadTable(location, done.metadataJson(), false, vended.config(), vended.credentials(),
                labels);
        return ResponseEntity.ok()
                .eTag(ETags.of(location, false, null, vended.epoch(), labels))
                .contentType(MediaType.APPLICATION_JSON)
                .body(response);
    }

    /** One table of one request, as the URL names it: what credentials are vended for. */
    private record Target(CatalogRecord catalog, String prefix, String namespace, String table,
                          RequestContext request, Optional<String> delegation) {
    }

    private Vended vend(Target target, UUID tableId, String location, boolean write) {
        String refresh = "v1/" + segment(target.prefix()) + "/namespaces/" + segment(target.namespace()) + "/tables/"
                + segment(target.table()) + "/credentials";
        return vending.vend(new CredentialVending.Request(target.catalog(), tableId,
                AuditEvents.path(Lookup.levels(target.namespace()), target.table()), location,
                target.request().caller(), target.request().requestId(), target.delegation(), refresh, write));
    }

    private static String segment(String value) {
        return UriUtils.encodePathSegment(value, StandardCharsets.UTF_8);
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
