package nuna.catalog.server.rest;

import nuna.catalog.core.CatalogError;
import nuna.catalog.core.Names;
import nuna.catalog.core.commit.CommitEngine;
import nuna.catalog.core.commit.CommitReply;
import nuna.catalog.core.commit.GovernanceChain;
import nuna.catalog.core.commit.ObjectName;
import nuna.catalog.iceberg.Metadata;
import nuna.catalog.iceberg.MetadataViews;
import nuna.catalog.iceberg.Outcome;
import nuna.catalog.iceberg.RestRequest;
import nuna.catalog.iceberg.RestResponse;
import nuna.catalog.server.governance.ReadAudit;
import nuna.catalog.server.metrics.CatalogMetrics;
import nuna.catalog.server.rest.CredentialVending.Vended;
import nuna.catalog.store.CatalogObject;
import nuna.catalog.store.CatalogObject.Kind;
import nuna.catalog.store.CatalogRecord;
import nuna.catalog.store.CatalogStore;
import nuna.catalog.store.GovernanceAttribute;
import nuna.catalog.store.Name;
import nuna.catalog.store.NamespaceRecord;
import nuna.catalog.store.Pointer;
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
 * loads answer 304 from the pointer alone when the client's ETag still holds (D14). Through a branch or tag
 * ({@code catalog@ref}, D33), lists, loads, HEAD, metrics and credentials read as of the ref; every write there is
 * refused ({@link OnRef}), and a tag's credentials only read.
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
    private final CatalogMetrics meters;

    TableRoutes(CatalogStore store, Lookup lookup, ObjectRoutes objects, Engines engines, MetadataViews views,
                CredentialVending vending, MetricsSink metrics, ServerAlerts alerts,
                RestSettings settings, Callers callers, Guard guard, Clock clock, ReadAudit reads,
                CatalogMetrics meters) {
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
        this.meters = meters;
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
        RequestContext request = RequestContext.of(http, callers);
        CatalogRecord catalog = lookup.catalog(prefix, request, Guard.CREATE_TABLE);
        List<String> levels = Lookup.levels(namespace);
        String json = body == null ? "" : body;
        RestRequest.NewTable table = RestRequest.createTable(json);
        NamespaceRecord record = lookup.existingNamespace(catalog, levels);
        guard.onNamespace(request, catalog, record, levels, Guard.CREATE_TABLE, Http.noSuchNamespace(levels));
        // The name is checked only now, so a bad one tells a caller that may not see the namespace nothing.
        ObjectName name = ObjectRoutes.name(catalog, record, levels, table.name());
        Target target = new Target(catalog, prefix, namespace, table.name(), request, Optional.ofNullable(delegation));
        if (table.stageCreate()) {
            return staged(target, record, name, json);
        }
        CommitReply reply = engines.forRequest(catalog, AuditEvents.path(levels, table.name()), request)
                .createTable(name, json, request.caller(), Optional.ofNullable(key));
        meters.commit(catalog.name(), Operations.CREATE_TABLE, reply);
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
        RequestContext request = RequestContext.of(http, callers);
        OnRef on = lookup.onRef(prefix, request, Guard.LOAD_TABLE);
        CatalogRecord catalog = on.catalog();
        boolean refsOnly = refsOnly(snapshots);
        List<String> levels = Lookup.levels(namespace);
        Lookup.Seen seen = objects.seen(on, levels, table, Kind.TABLE);
        CatalogObject object = seen.object();
        // A tag is read-only to every engine, so its credentials only read (item 26's choice 5).
        boolean write = guard.onObjectMayWrite(request, catalog, object, AuditEvents.path(levels, table),
                Guard.LOAD_TABLE, Http.noSuchTable(Http.qualified(levels, table))) && !on.tag();
        Vended vended = vend(new Target(catalog, prefix, namespace, table, request, Optional.ofNullable(delegation)),
                object.id(), object.locationRoot(), write);
        boolean cacheable = !vended.expireBefore(clock.instant().plus(settings.credentialRefreshMargin()));
        Map<String, String> labels = GovernanceChain.of(store, object).labels();
        Optional<Pointer> pointer = lookup.pointer(on, seen);
        if (cacheable && pointer.isPresent()) {
            String etag = ETags.of(pointer.get().metadataLocation(), refsOnly, referencedBy, vended.epoch(), labels,
                    on.refId());
            if (ETags.matches(ifNoneMatch, etag)) {
                reads.read(http, catalog, AuditEvents.path(levels, table), object, pointer.get(), on.branch());
                return ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(etag).build();
            }
        }
        CommitEngine.Current.Loaded current = ObjectRoutes.current(engines.reader(catalog), object, pointer);
        String location = current.pointer().metadataLocation();
        reads.read(http, catalog, AuditEvents.path(levels, table), object, current.pointer(), on.branch());
        alerts.loaded(object.id(), location, views.tableStats(location, current.json()));
        String response = views.loadTable(location, current.json(), refsOnly, vended.config(), vended.credentials(),
                labels);
        ResponseEntity.BodyBuilder builder = ResponseEntity.ok();
        if (cacheable) {
            builder.eTag(ETags.of(location, refsOnly, referencedBy, vended.epoch(), labels, on.refId()));
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
        RequestContext request = RequestContext.of(http, callers);
        CatalogRecord catalog = lookup.forCommit(prefix, request, Guard.WRITE_DATA);
        List<String> levels = Lookup.levels(namespace);
        String qualified = Http.qualified(levels, table);
        NamespaceRecord record = lookup.namespace(catalog, levels).orElseThrow(() -> Http.noSuchTable(qualified));
        Optional<CatalogObject> existing = lookup.object(record, table, Kind.TABLE);
        if (existing.isPresent()) {
            guard.onObject(request, catalog, existing.get(), AuditEvents.path(levels, table), guard.commit(request, body),
                    Http.noSuchTable(qualified));
        } else {
            guard.onNewObject(request, catalog, record, levels, Guard.CREATE_TABLE, Http.noSuchTable(qualified));
        }
        Target target = new Target(catalog, prefix, namespace, table, request, Optional.ofNullable(delegation));
        CommitReply reply = engines.forRequest(catalog, AuditEvents.path(levels, table), request)
                .commitTable(ObjectRoutes.name(catalog, record, levels, table), body == null ? "" : body,
                        request.caller(), Optional.ofNullable(key));
        meters.commit(catalog.name(), Operations.UPDATE_TABLE, reply);
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
     * the file a dropped or unregistered table left at brings that table back, only in the namespace it left,
     * and a dropped one only for a caller who may undrop it (D13, amended 2026-11-12). {@code overwrite} onto a
     * table that holds the name is refused with 400 rather than ignored (D13, amended 2026-11-14).
     */
    @PostMapping(REGISTER)
    ResponseEntity<String> register(@PathVariable String prefix, @PathVariable String namespace,
                                    @RequestHeader(name = Http.IDEMPOTENCY_KEY, required = false) String key,
                                    @RequestHeader(name = Http.ACCESS_DELEGATION, required = false) String delegation,
                                    @RequestBody(required = false) String body,
                                    HttpServletRequest http) {
        RequestContext request = RequestContext.of(http, callers);
        CatalogRecord catalog = lookup.catalog(prefix, request, Guard.REGISTER_TABLE);
        List<String> levels = Lookup.levels(namespace);
        String json = body == null ? "" : body;
        RestRequest.Registration registration = RestRequest.register(json);
        NamespaceRecord record = lookup.existingNamespace(catalog, levels);
        guard.onNamespace(request, catalog, record, levels, Guard.REGISTER_TABLE, Http.noSuchNamespace(levels));
        if (registration.overwrite() && lookup.object(record, registration.name(), Kind.TABLE).isPresent()) {
            throw CatalogError.badRequest("Registering over an existing table (overwrite) is not supported: "
                    + Http.qualified(levels, registration.name()) + ". Commit to the table, or drop or unregister it"
                    + " first; a register would skip the commit rules and the table's journal");
        }
        Target target = new Target(catalog, prefix, namespace, registration.name(), request,
                Optional.ofNullable(delegation));
        CommitReply reply = engines.forRequest(catalog, AuditEvents.path(levels, registration.name()), request)
                .registerTable(ObjectRoutes.name(catalog, record, levels, registration.name()), json,
                        request.caller(), Optional.ofNullable(key), objects.undrop(catalog, request));
        meters.commit(catalog.name(), Operations.REGISTER_TABLE, reply);
        return ObjectRoutes.reply(reply, done -> loaded(target, done));
    }

    /**
     * Unregisters a table (D13, amended 2026-11-12): the catalog stops tracking it and deletes nothing. The
     * answer names its current metadata file and holds its content. It needs {@code modify}; a regulated
     * table, or one flagged for row/column policy, is refused until a steward removes the flag (D20, D27),
     * and a hold, a DR freeze, or a flag set since this check refuses it in the store. The engine unregisters
     * only the table checked here, so one that takes the name meanwhile is left alone. A table already gone
     * still reaches the engine, so a retry with the key replays the first answer. So do a table the caller may
     * not learn exists, with nothing authorized, and a namespace that does not exist, by an id no namespace has:
     * the engine answers each, and a key keeps each answer, as for a missing table (D18).
     */
    @PostMapping(UNREGISTER)
    ResponseEntity<String> unregister(@PathVariable String prefix, @PathVariable String namespace,
                                      @PathVariable String table,
                                      @RequestHeader(name = Http.IDEMPOTENCY_KEY, required = false) String key,
                                      HttpServletRequest http) {
        RequestContext request = RequestContext.of(http, callers);
        CatalogRecord catalog = lookup.catalog(prefix, request, Guard.UNREGISTER_TABLE);
        List<String> levels = Lookup.levels(namespace);
        String qualified = Http.qualified(levels, table);
        Optional<NamespaceRecord> record = lookup.namespace(catalog, levels);
        Optional<CatalogObject> existing = record.flatMap(ns -> lookup.object(ns, table, Kind.TABLE));
        Optional<UUID> authorized = Optional.empty();
        if (existing.isPresent()) {
            CatalogObject object = existing.get();
            List<String> path = AuditEvents.path(levels, table);
            if (guard.allowsOnObject(request, catalog, object, path, Guard.UNREGISTER_TABLE)) {
                GovernanceChain governance = GovernanceChain.of(store, object);
                if (governance.regulated()) {
                    throw guard.refuse(request, catalog, object, path, Guard.UNREGISTER_TABLE, "regulated", "table "
                            + qualified + " is in a regulated area and cannot be unregistered; a steward removes the"
                            + " regulated flag first");
                }
                if (governance.attributes().stream().anyMatch(a -> a.target().equals(object.securable())
                        && a.kind() == GovernanceAttribute.Kind.ROW_COLUMN_POLICY
                        && "true".equalsIgnoreCase(a.value()))) {
                    throw guard.refuse(request, catalog, object, path, Guard.UNREGISTER_TABLE, "flagged_table",
                            "table " + qualified + " has a row/column policy and cannot be unregistered; a steward"
                                    + " removes the flag first");
                }
                authorized = Optional.of(object.id());
            }
        }
        ObjectName name = record.isPresent() ? ObjectRoutes.name(catalog, record.get(), levels, table)
                : new ObjectName(catalog.id(), nowhere(catalog, levels), new Name(Names.normalize(table), table),
                        qualified);
        CommitReply reply = engines.forRequest(catalog, AuditEvents.path(levels, table), request)
                .unregisterTable(name, authorized, request.caller(), Optional.ofNullable(key));
        meters.commit(catalog.name(), Operations.UNREGISTER_TABLE, reply);
        return ObjectRoutes.reply(reply, done -> Http.json(200,
                RestResponse.unregistered(done.metadataLocation(), done.metadataJson())));
    }

    /**
     * Forwarded to telemetry, never stored (D13). Only for a table that exists (404 NoSuchTableException otherwise)
     * and that the caller may read; one that holds nothing on it gets the same 404 (D18).
     */
    @PostMapping(METRICS)
    ResponseEntity<Void> metrics(@PathVariable String prefix, @PathVariable String namespace,
                                 @PathVariable String table, @RequestBody(required = false) String body,
                                 HttpServletRequest http) {
        OnRef on = lookup.onRef(prefix, RequestContext.of(http, callers), Guard.REPORT_METRICS);
        CatalogRecord catalog = on.catalog();
        List<String> levels = Lookup.levels(namespace);
        CatalogObject object = objects.seen(on, levels, table, Kind.TABLE).object();
        guard.onObject(RequestContext.of(http, callers), catalog, object, AuditEvents.path(levels, table),
                Guard.REPORT_METRICS, Http.noSuchTable(Http.qualified(levels, table)));
        RestRequest.MetricsSummary summary = RestRequest.reportMetrics(body == null ? "" : body);
        metrics.report(catalog.name(), levels, table, summary);
        return ResponseEntity.noContent().build();
    }

    @GetMapping(CREDENTIALS)
    ResponseEntity<String> credentials(@PathVariable String prefix, @PathVariable String namespace,
                                       @PathVariable String table,
                                       @RequestHeader(name = Http.ACCESS_DELEGATION, required = false) String delegation,
                                       HttpServletRequest http) {
        RequestContext request = RequestContext.of(http, callers);
        OnRef on = lookup.onRef(prefix, request, Guard.LOAD_CREDENTIALS);
        CatalogRecord catalog = on.catalog();
        List<String> levels = Lookup.levels(namespace);
        CatalogObject object = objects.seen(on, levels, table, Kind.TABLE).object();
        boolean write = guard.onObjectMayWrite(request, catalog, object, AuditEvents.path(levels, table),
                Guard.LOAD_CREDENTIALS, Http.noSuchTable(Http.qualified(levels, table))) && !on.tag();
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
        return meters.vend(target.catalog().name(), () -> vending.vend(new CredentialVending.Request(
                target.catalog(), tableId, AuditEvents.path(Lookup.levels(target.namespace()), target.table()),
                location, target.request().caller(), target.request().requestId(), target.delegation(), refresh,
                write)));
    }

    /**
     * The id an unregister names a namespace that does not exist by: name-based, so no namespace has it (theirs are
     * random), and the same for every request naming that path, so a key binds to it as to any other.
     */
    private static UUID nowhere(CatalogRecord catalog, List<String> levels) {
        String path = catalog.id() + "\u001f" + String.join("\u001f", levels.stream().map(Names::normalize).toList());
        return UUID.nameUUIDFromBytes(path.getBytes(StandardCharsets.UTF_8));
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
