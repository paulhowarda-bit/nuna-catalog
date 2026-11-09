package fidice.catalog.server.governance;

import static fidice.catalog.server.governance.Management.CATALOG;
import static fidice.catalog.server.governance.Management.NAMESPACE;
import static fidice.catalog.server.governance.Management.ROOT;

import fidice.catalog.audit.ObjectRead;
import fidice.catalog.audit.Purge;
import fidice.catalog.audit.Undrop;
import fidice.catalog.core.CatalogError;
import fidice.catalog.core.Names;
import fidice.catalog.core.commit.GovernanceChain;
import fidice.catalog.core.commit.TableFiles;
import fidice.catalog.security.Authorizer;
import fidice.catalog.security.Privilege;
import fidice.catalog.server.storage.CatalogStorage;
import fidice.catalog.iceberg.RestRequest;
import fidice.catalog.server.governance.Management.Request;
import fidice.catalog.store.CatalogObject;
import fidice.catalog.store.CatalogRecord;
import fidice.catalog.store.CatalogStore;
import fidice.catalog.store.JournalRecord;
import fidice.catalog.store.Name;
import fidice.catalog.store.NamespaceRecord;
import fidice.catalog.store.Page;
import fidice.catalog.store.PageRequest;
import fidice.catalog.store.Pointer;
import fidice.catalog.store.PointerKey;
import fidice.catalog.store.WriteResult;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Dropped tables and views (D25): listing them, undrop within the window, purge after it by the
 * maintenance principal only and at a bounded rate, and the snapshot-and-journal export of any object.
 * The maintenance worker that deletes a purged object's files is v1.x; it calls purge once they are gone.
 * Each route asks {@link ManagementGuard} once the object or namespace it names is found.
 */
@RestController
class LifecycleRoutes {

    static final String DROPPED = NAMESPACE + "/dropped";
    static final String OBJECT = CATALOG + "/objects/{id}";
    static final String UNDROP = OBJECT + "/undrop";
    static final String PURGE = OBJECT + "/purge";
    static final String EXPORT = OBJECT + "/export";
    static final String PURGE_ELIGIBLE = ROOT + "/maintenance/purge-eligible";

    private static final int PAGE = 200;
    private static final Duration RATE_WINDOW = Duration.ofMinutes(1);

    private final CatalogStore store;
    private final Management management;
    private final ManagementGuard guard;
    private final GovernanceSettings settings;
    private final AuditTrail trail;
    private final CatalogStorage storage;
    private final Clock clock;
    private final Deque<Instant> recentPurges = new ArrayDeque<>();

    LifecycleRoutes(CatalogStore store, Management management, ManagementGuard guard, GovernanceSettings settings,
                    AuditTrail trail, CatalogStorage storage, Clock clock) {
        this.store = store;
        this.management = management;
        this.guard = guard;
        this.settings = settings;
        this.trail = trail;
        this.storage = storage;
        this.clock = clock;
    }

    /** The namespace's dropped tables and views, with when each may be purged. */
    @GetMapping(path = DROPPED, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<String> dropped(@PathVariable("catalog") String prefix, @PathVariable String namespace,
                                   HttpServletRequest http) {
        CatalogRecord catalog = management.catalog(prefix);
        List<String> levels = RestRequest.namespaceLevels(namespace);
        NamespaceRecord record = management.namespace(catalog, levels);
        guard.require(management.request(http), Authorizer.Target.of(record),
                namespaceAudit(catalog, record.id(), levels), "listDropped", Privilege.DESCRIBE);
        ObjectNode body = Management.JSON.createObjectNode();
        ArrayNode objects = body.putArray("dropped");
        for (CatalogObject.Kind kind : CatalogObject.Kind.values()) {
            PageRequest page = PageRequest.first(PAGE);
            while (true) {
                Page<CatalogObject> listed = store.listObjects(record.id(), kind, CatalogObject.State.DROPPED, page);
                listed.items().forEach(o -> objects.add(object(o)));
                if (listed.nextToken().isEmpty()) {
                    break;
                }
                page = page.after(listed);
            }
        }
        return GovernanceRoutes.json(200, body);
    }

    /**
     * Makes a dropped object active again, keeping its id, grants and history (D25), until its
     * purge-after time. Body, optional: {@code {"name": ...}} to take a new name when the old one is taken.
     */
    @PostMapping(path = UNDROP, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<String> undrop(@PathVariable("catalog") String prefix, @PathVariable String id,
                                  @RequestBody(required = false) String body, HttpServletRequest http) {
        CatalogRecord catalog = management.catalog(prefix);
        CatalogObject object = existing(catalog, id);
        List<String> levels = management.levels(catalog.id(), object.namespaceId()).orElseThrow(
                () -> CatalogError.of(404, "NoSuchNamespaceException", "The namespace of " + id + " no longer exists"));
        Request request = management.request(http);
        guard.require(request, new Authorizer.Target.Namespace(catalog.id(), object.namespaceId()),
                namespaceAudit(catalog, object.namespaceId(), levels), "undropObject", Privilege.CREATE);
        if (object.state() != CatalogObject.State.DROPPED) {
            throw notDropped(id);
        }
        JsonNode json = Management.body(body);
        Name name = json.has("name")
                ? new Name(Names.normalize(Management.text(json, "name", 1000)), json.get("name").asString())
                : object.name();
        Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        if (!now.isBefore(object.purgeAfter().orElseThrow())) {
            throw CatalogError.of(409, "UndropWindowClosedException", "The undrop window of " + id + " closed at "
                    + object.purgeAfter().get() + "; it is waiting to be purged");
        }
        Management.requireUnfrozen(GovernanceChain.of(store, object), "The namespace of " + id);
        WriteResult result = Management.write("Undropping " + id, () -> store.undropObject(object.id(),
                        object.namespaceId(), name, management.change(now, new Undrop(
                                Management.audit(catalog, levels, name.display(), object), request.actor()))),
                () -> store.readObject(object.id()).filter(o -> o.state() == CatalogObject.State.ACTIVE
                        && o.name().equals(name)).isPresent());
        return switch (result) {
            case WriteResult.Applied applied -> GovernanceRoutes.json(200,
                    object(store.readObject(object.id()).orElseThrow()));
            case WriteResult.NameTaken taken -> throw CatalogError.of(409, "AlreadyExistsException",
                    "The name " + name.display() + " is taken in its namespace; undrop it under another name");
            case WriteResult.Missing missing when missing.subject() == WriteResult.Subject.NAMESPACE ->
                    throw CatalogError.of(404, "NoSuchNamespaceException", "The namespace of " + id + " no longer exists");
            case WriteResult.Missing missing -> throw notDropped(id);
            default -> throw new IllegalStateException("undropObject answered " + result);
        };
    }

    /**
     * Marks a dropped object purged once its undrop window has closed (D25, CMP-5): only a maintenance
     * principal may, at most {@code purges-per-minute} a server, and never a held or frozen object. A
     * refused caller is recorded as a denial.
     */
    @PostMapping(path = PURGE, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<String> purge(@PathVariable("catalog") String prefix, @PathVariable String id, HttpServletRequest http) {
        CatalogRecord catalog = management.catalog(prefix);
        CatalogObject object = existing(catalog, id);
        Request request = management.request(http);
        List<String> levels = management.levels(catalog.id(), object.namespaceId()).orElse(List.of());
        fidice.catalog.audit.Securable audit = Management.audit(catalog, levels, object.name().display(), object);
        guard.requireMaintenance(request, audit, "purge");
        if (object.state() != CatalogObject.State.DROPPED) {
            throw notDropped(id);
        }
        Management.requireUnfrozen(GovernanceChain.of(store, object), "The namespace of " + id);
        Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        if (!takePurgeSlot(now)) {
            return ResponseEntity.status(429).header("Retry-After", "60").contentType(MediaType.APPLICATION_JSON)
                    .body(fidice.catalog.iceberg.Wire.error(429, "TooManyRequestsException", "More than "
                            + settings.purgesPerMinute() + " purges in a minute; retry later"));
        }
        WriteResult result = Management.write("Purging " + id, () -> store.purgeObject(object.id(),
                        management.change(now, new Purge(audit, request.actor()))),
                () -> store.readObject(object.id()).filter(o -> o.state() == CatalogObject.State.PURGED).isPresent());
        return switch (result) {
            case WriteResult.Applied applied -> ResponseEntity.noContent().build();
            case WriteResult.Missing missing -> throw notDropped(id);
            case WriteResult.Refused refused when refused.reason() == WriteResult.Reason.HELD ->
                    throw CatalogError.of(403, "ForbiddenException", id + " is under a legal hold and cannot be purged");
            case WriteResult.Refused refused when refused.reason() == WriteResult.Reason.NOT_DUE ->
                    throw CatalogError.of(409, "PurgeNotDueException", id + " may not be purged before "
                            + object.purgeAfter().orElseThrow());
            default -> throw new IllegalStateException("purgeObject answered " + result);
        };
    }

    /** Dropped objects whose undrop window has closed and that no hold covers: the maintenance worker's queue. */
    @GetMapping(path = PURGE_ELIGIBLE, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<String> purgeEligible(@RequestParam(required = false) String pageToken,
                                         @RequestParam(required = false) Integer pageSize,
                                         HttpServletRequest http) {
        guard.requireMaintenance(management.request(http), null, "listPurgeEligible");
        PageRequest page = new PageRequest(Optional.ofNullable(pageToken).filter(t -> !t.isEmpty()),
                pageSize == null ? PAGE : Math.clamp(pageSize, 1, 1000));
        Page<CatalogObject> listed;
        try {
            listed = store.listPurgeEligible(clock.instant(), page);
        } catch (IllegalArgumentException e) {
            throw CatalogError.badRequest("Invalid page token: " + e.getMessage());
        }
        ObjectNode body = Management.JSON.createObjectNode();
        ArrayNode objects = body.putArray("objects");
        listed.items().forEach(o -> objects.add(object(o)));
        listed.nextToken().ifPresent(token -> body.put("next-page-token", token));
        return GovernanceRoutes.json(200, body);
    }

    /**
     * The object's current snapshot and its whole commit journal (D9): its metadata JSON, checked
     * against the pointer's hash, and every journal entry, oldest first. Works for dropped objects too.
     */
    @GetMapping(path = EXPORT, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<String> export(@PathVariable("catalog") String prefix, @PathVariable String id, HttpServletRequest http) {
        CatalogRecord catalog = management.catalog(prefix);
        CatalogObject object = existing(catalog, id);
        List<String> levels = management.levels(catalog.id(), object.namespaceId()).orElse(List.of());
        Request request = management.request(http);
        guard.require(request, Authorizer.Target.of(object),
                Management.audit(catalog, levels, object.name().display(), object), "exportObject", Privilege.SELECT);
        ObjectNode body = Management.JSON.createObjectNode();
        body.set("object", object(object));
        Optional<Pointer> pointer = store.readPointer(object.mainKey());
        if (pointer.isPresent()) {
            TableFiles.Read read = storage.forCatalog(catalog.id()).files().read(pointer.get().metadataLocation(), pointer.get().sha256());
            if (!read.intact()) {
                throw CatalogError.of(500, "MetadataIntegrityException", "The metadata file of " + id
                        + " does not match its recorded hash");
            }
            ObjectNode node = body.putObject("pointer");
            node.put("metadata-location", pointer.get().metadataLocation());
            node.put("version", pointer.get().version());
            node.put("sha256", pointer.get().sha256());
            body.set("metadata", Management.JSON.readTree(read.json()));
            trail.record(new ObjectRead(Management.audit(catalog, levels, object.name().display(), object),
                    PointerKey.MAIN, ReadAudit.state(pointer.get()), request.actor()));
        }
        ArrayNode journal = body.putArray("journal");
        PageRequest page = PageRequest.first(PAGE);
        Instant to = clock.instant().plusSeconds(1);
        while (true) {
            Page<JournalRecord> entries = store.readJournal(object.mainKey(), Instant.EPOCH, to, page);
            entries.items().forEach(entry -> journal.add(journal(entry)));
            if (entries.nextToken().isEmpty()) {
                break;
            }
            page = page.after(entries);
        }
        return GovernanceRoutes.json(200, body);
    }

    /** Takes one purge from this minute's allowance; false when it is spent. */
    private synchronized boolean takePurgeSlot(Instant now) {
        Instant windowStart = now.minus(RATE_WINDOW);
        while (!recentPurges.isEmpty() && !recentPurges.peekFirst().isAfter(windowStart)) {
            recentPurges.removeFirst();
        }
        if (recentPurges.size() >= settings.purgesPerMinute()) {
            return false;
        }
        recentPurges.addLast(now);
        return true;
    }

    private CatalogObject existing(CatalogRecord catalog, String id) {
        UUID objectId;
        try {
            objectId = UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            throw CatalogError.badRequest("Not an object id: " + id);
        }
        return store.readObject(objectId).filter(o -> o.catalogId().equals(catalog.id())).orElseThrow(
                () -> CatalogError.of(404, "NoSuchObjectException", "No table or view " + id + " in " + catalog.name()));
    }

    private static fidice.catalog.audit.Securable namespaceAudit(CatalogRecord catalog, UUID namespaceId,
                                                                 List<String> levels) {
        return new fidice.catalog.audit.Securable(fidice.catalog.audit.Securable.Kind.NAMESPACE, namespaceId,
                catalog.name(), levels);
    }

    private static CatalogError notDropped(String id) {
        return CatalogError.of(409, "NotDroppedException", id + " is not a dropped table or view");
    }

    private static ObjectNode object(CatalogObject object) {
        ObjectNode node = Management.JSON.createObjectNode();
        node.put("id", object.id().toString());
        node.put("kind", object.kind().name().toLowerCase(java.util.Locale.ROOT));
        node.put("name", object.name().display());
        node.put("namespace-id", object.namespaceId().toString());
        node.put("state", object.state().name().toLowerCase(java.util.Locale.ROOT));
        object.droppedAt().ifPresent(at -> node.put("dropped-at", at.toString()));
        object.purgeAfter().ifPresent(at -> node.put("purge-after", at.toString()));
        node.put("location", object.locationRoot());
        return node;
    }

    private static ObjectNode journal(JournalRecord record) {
        ObjectNode node = Management.JSON.createObjectNode();
        node.put("commit-id", record.commitId().toString());
        node.put("committed-at", record.committedAt().toString());
        node.put("principal", record.entry().principal());
        if (record.entry().endUser() != null) {
            node.put("end-user", record.entry().endUser());
        }
        if (record.entry().engine() != null) {
            node.put("engine", record.entry().engine());
        }
        record.oldLocation().ifPresent(location -> node.put("old-location", location));
        node.put("new-location", record.newLocation());
        node.put("new-version", record.newVersion());
        node.set("requirements", Management.JSON.readTree(record.entry().requirementsJson()));
        node.set("updates", Management.JSON.readTree(record.entry().updatesJson()));
        return node;
    }
}
