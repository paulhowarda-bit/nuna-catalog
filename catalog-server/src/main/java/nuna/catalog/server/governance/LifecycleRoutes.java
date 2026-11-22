package nuna.catalog.server.governance;

import static nuna.catalog.server.governance.Management.CATALOG;
import static nuna.catalog.server.governance.Management.NAMESPACE;
import static nuna.catalog.server.governance.Management.ROOT;

import nuna.catalog.audit.ObjectRead;
import nuna.catalog.audit.Purge;
import nuna.catalog.audit.PurgeClaim;
import nuna.catalog.audit.Undrop;
import nuna.catalog.core.CatalogError;
import nuna.catalog.core.Names;
import nuna.catalog.core.commit.GovernanceChain;
import nuna.catalog.core.commit.TableFiles;
import nuna.catalog.security.Authorizer;
import nuna.catalog.security.Privilege;
import nuna.catalog.server.storage.CatalogStorage;
import nuna.catalog.server.governance.Management.Request;
import nuna.catalog.store.CatalogObject;
import nuna.catalog.store.CatalogRecord;
import nuna.catalog.store.CatalogStore;
import nuna.catalog.store.JournalRecord;
import nuna.catalog.store.JournalStore;
import nuna.catalog.store.Name;
import nuna.catalog.store.Page;
import nuna.catalog.store.PageRequest;
import nuna.catalog.store.PageToken;
import nuna.catalog.store.Pointer;
import nuna.catalog.store.PointerKey;
import nuna.catalog.store.WriteResult;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
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
 * Dropped tables and views (D25): listing them, undrop within the window, purge after it by a maintenance
 * principal only and at a bounded rate, the snapshot-and-journal export of any object, and its metadata log a page
 * at a time. A purge is a claim,
 * the deletion of the object's files by the maintenance worker with its own storage identity, then its
 * completion (D30). Each route asks {@link ManagementGuard} once the object or namespace it names is found.
 */
@RestController
class LifecycleRoutes {

    static final String DROPPED = NAMESPACE + "/dropped";
    static final String OBJECT = CATALOG + "/objects/{id}";
    static final String UNDROP = OBJECT + "/undrop";
    static final String PURGE_CLAIM = OBJECT + "/purge-claim";
    static final String PURGE = OBJECT + "/purge";
    static final String EXPORT = OBJECT + "/export";
    static final String METADATA_LOG = OBJECT + "/metadata-log";
    static final String PURGE_ELIGIBLE = ROOT + "/maintenance/purge-eligible";

    private static final int PAGE = 200;
    private static final int LOG_PAGE = 25;
    private static final int LOG_PAGE_MAX = 100;
    private static final Duration RATE_WINDOW = Duration.ofMinutes(1);

    private final CatalogStore store;
    private final Management management;
    private final ManagementGuard guard;
    private final GovernanceSettings settings;
    private final AuditTrail trail;
    private final CatalogStorage storage;
    private final Clock clock;
    private final CatalogNames catalogs;
    private final Deque<Instant> recentPurges = new ArrayDeque<>();

    LifecycleRoutes(CatalogStore store, Management management, ManagementGuard guard, GovernanceSettings settings,
                    AuditTrail trail, CatalogStorage storage, CatalogNames catalogs, Clock clock) {
        this.store = store;
        this.catalogs = catalogs;
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
        Request request = management.request(http);
        Management.Target target = management.target(request, "listDropped", Privilege.DESCRIBE,
                prefix, namespace, null, null);
        guard.require(request, target, "listDropped", Privilege.DESCRIBE);
        ObjectNode body = Management.JSON.createObjectNode();
        ArrayNode objects = body.putArray("dropped");
        for (CatalogObject.Kind kind : CatalogObject.Kind.values()) {
            PageRequest page = PageRequest.first(PAGE);
            while (true) {
                Page<CatalogObject> listed = store.listObjects(target.securable().id(), kind,
                        CatalogObject.State.DROPPED, page);
                listed.items().forEach(o -> objects.add(object(target.catalog(), o)));
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
        Request request = management.request(http);
        CatalogRecord catalog = management.catalog(prefix, request, "undropObject", Privilege.CREATE);
        CatalogObject object = existing(catalog, id);
        List<String> levels = management.levels(catalog.id(), object.namespaceId()).orElseThrow(
                () -> CatalogError.of(404, "NoSuchNamespaceException", "The namespace of " + id + " no longer exists"));
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
                    object(catalog, store.readObject(object.id()).orElseThrow()));
            case WriteResult.NameTaken taken -> throw CatalogError.of(409, "AlreadyExistsException",
                    "The name " + name.display() + " is taken in its namespace; undrop it under another name");
            case WriteResult.Missing missing when missing.subject() == WriteResult.Subject.NAMESPACE ->
                    throw CatalogError.of(404, "NoSuchNamespaceException", "The namespace of " + id + " no longer exists");
            case WriteResult.Missing missing -> throw notDropped(id);
            default -> throw new IllegalStateException("undropObject answered " + result);
        };
    }

    /**
     * Claims a dropped object's purge for the calling maintenance principal once its undrop window has closed
     * (D25, D30): from the claim on, the object can no longer be undropped, and a hold placed after it does not
     * stop the purge. Only a maintenance principal may claim, at most {@code purges-per-minute} a server (each
     * replica counts its own), and never a held or frozen object. The claimant then deletes the object's files
     * with its own storage identity and completes the purge; its claim asked again answers the same, so a worker
     * that stopped part way resumes. A refused caller is recorded as a denial.
     */
    @PostMapping(path = PURGE_CLAIM, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<String> claimPurge(@PathVariable("catalog") String prefix, @PathVariable String id,
                                      HttpServletRequest http) {
        Request request = management.request(http);
        CatalogRecord catalog = management.catalog(prefix, request, "claimPurge", null);
        CatalogObject object = existing(catalog, id);
        List<String> levels = management.levels(catalog.id(), object.namespaceId()).orElse(List.of());
        nuna.catalog.audit.Securable audit = Management.audit(catalog, levels, object.name().display(), object);
        guard.requireMaintenance(request, audit, "claimPurge");
        String claimant = request.caller().principal().id();
        if (object.state() == CatalogObject.State.PURGING) {
            if (object.purgeClaimant().orElseThrow().equals(claimant)) {
                return GovernanceRoutes.json(200, object(catalog, object));
            }
            throw claimedByAnother(id);
        }
        if (object.state() != CatalogObject.State.DROPPED) {
            throw notDropped(id);
        }
        Management.requireUnfrozen(GovernanceChain.of(store, object), "The namespace of " + id);
        Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        if (!takePurgeSlot(now)) {
            return ResponseEntity.status(429).header("Retry-After", "60").contentType(MediaType.APPLICATION_JSON)
                    .body(nuna.catalog.iceberg.Wire.error(429, "TooManyRequestsException", "More than "
                            + settings.purgesPerMinute() + " purges in a minute; retry later"));
        }
        WriteResult result = Management.write("Claiming the purge of " + id, () -> store.claimPurge(object.id(),
                        claimant, management.change(now, new PurgeClaim(audit, request.actor()))),
                () -> store.readObject(object.id()).flatMap(CatalogObject::purgeClaimant).filter(claimant::equals)
                        .isPresent());
        return switch (result) {
            case WriteResult.Applied applied -> GovernanceRoutes.json(200,
                    object(catalog, store.readObject(object.id()).orElseThrow()));
            case WriteResult.Unchanged unchanged -> GovernanceRoutes.json(200,
                    object(catalog, store.readObject(object.id()).orElseThrow()));
            case WriteResult.Missing missing -> throw notDropped(id);
            case WriteResult.Refused refused when refused.reason() == WriteResult.Reason.HELD ->
                    throw CatalogError.of(403, "ForbiddenException", id + " is under a legal hold and cannot be purged");
            case WriteResult.Refused refused when refused.reason() == WriteResult.Reason.FROZEN ->
                    throw CatalogError.of(403, "ForbiddenException", "The namespace of " + id
                            + " is frozen for DR reconcile");
            case WriteResult.Refused refused when refused.reason() == WriteResult.Reason.NOT_DUE ->
                    throw CatalogError.of(409, "PurgeNotDueException", id + " may not be purged before "
                            + object.purgeAfter().orElseThrow());
            case WriteResult.Refused refused when refused.reason() == WriteResult.Reason.NOT_HOLDER ->
                    throw claimedByAnother(id);
            default -> throw new IllegalStateException("claimPurge answered " + result);
        };
    }

    /**
     * Completes a purge the caller claimed, once it has deleted the object's files (D25, D30): the object is
     * marked purged, its grants and pending deletions are deleted, and its record is kept as history. Only the
     * maintenance principal that holds the claim may; a hold placed after the claim does not stop it.
     */
    @PostMapping(path = PURGE, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<String> purge(@PathVariable("catalog") String prefix, @PathVariable String id, HttpServletRequest http) {
        Request request = management.request(http);
        CatalogRecord catalog = management.catalog(prefix, request, "purge", null);
        CatalogObject object = existing(catalog, id);
        List<String> levels = management.levels(catalog.id(), object.namespaceId()).orElse(List.of());
        nuna.catalog.audit.Securable audit = Management.audit(catalog, levels, object.name().display(), object);
        guard.requireMaintenance(request, audit, "purge");
        String claimant = request.caller().principal().id();
        if (object.state() == CatalogObject.State.DROPPED) {
            throw CatalogError.of(409, "PurgeNotClaimedException", id + " is not claimed for purge; claim it first,"
                    + " then delete its files");
        }
        if (object.state() != CatalogObject.State.PURGING) {
            throw notDropped(id);
        }
        Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        WriteResult result = Management.write("Purging " + id, () -> store.completePurge(object.id(), claimant,
                        management.change(now, new Purge(audit, request.actor()))),
                () -> store.readObject(object.id()).filter(o -> o.state() == CatalogObject.State.PURGED).isPresent());
        return switch (result) {
            case WriteResult.Applied applied -> ResponseEntity.noContent().build();
            case WriteResult.Missing missing -> throw notDropped(id);
            case WriteResult.Refused refused when refused.reason() == WriteResult.Reason.NOT_HOLDER ->
                    throw claimedByAnother(id);
            default -> throw new IllegalStateException("completePurge answered " + result);
        };
    }

    /**
     * Dropped objects whose undrop window has closed and that no hold or DR freeze covers, and every claimed
     * object: the maintenance worker's purge queue.
     */
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
        listed.items().forEach(o -> objects.add(object(catalogs.name(o.catalogId()), o)));
        listed.nextToken().ifPresent(token -> body.put("next-page-token", token));
        return GovernanceRoutes.json(200, body);
    }

    /**
     * The object's current snapshot and its whole commit journal (D9): its metadata JSON, checked
     * against the pointer's hash, and every journal entry, oldest first. Works for dropped objects too.
     */
    @GetMapping(path = EXPORT, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<String> export(@PathVariable("catalog") String prefix, @PathVariable String id, HttpServletRequest http) {
        Request request = management.request(http);
        CatalogRecord catalog = management.catalog(prefix, request, "exportObject", Privilege.SELECT);
        CatalogObject object = existing(catalog, id);
        List<String> levels = management.levels(catalog.id(), object.namespaceId()).orElse(List.of());
        guard.require(request, Authorizer.Target.of(object),
                Management.audit(catalog, levels, object.name().display(), object), "exportObject", Privilege.SELECT);
        ObjectNode body = Management.JSON.createObjectNode();
        body.set("object", object(catalog, object));
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

    /**
     * The object's metadata log, newest first, a page at a time (D45): its commit journal entries in the export's
     * form, each the metadata file one commit wrote, and the pointer the newest leads to. Needs select on the object,
     * as the export does, and works for dropped objects too. {@code pageSize} is 25 by default, at most 100. A page's
     * token names the version it stopped before, so commits made meanwhile never shift the pages after the first; a
     * page holds fewer entries only where versions are missing, and the log goes on while {@code next-page-token} is
     * present.
     */
    @GetMapping(path = METADATA_LOG, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<String> metadataLog(@PathVariable("catalog") String prefix, @PathVariable String id,
                                       @RequestParam(required = false) String pageToken,
                                       @RequestParam(required = false) Integer pageSize, HttpServletRequest http) {
        Request request = management.request(http);
        CatalogRecord catalog = management.catalog(prefix, request, "readMetadataLog", Privilege.SELECT);
        CatalogObject object = existing(catalog, id);
        List<String> levels = management.levels(catalog.id(), object.namespaceId()).orElse(List.of());
        guard.require(request, Authorizer.Target.of(object),
                Management.audit(catalog, levels, object.name().display(), object), "readMetadataLog", Privilege.SELECT);
        int size = pageSize == null ? LOG_PAGE : Math.clamp(pageSize, 1, LOG_PAGE_MAX);
        Optional<Long> before = Optional.ofNullable(pageToken).filter(t -> !t.isEmpty()).map(LifecycleRoutes::before);
        ObjectNode body = Management.JSON.createObjectNode();
        Optional<Pointer> pointer = store.readPointer(object.mainKey());
        pointer.ifPresent(p -> {
            ObjectNode node = body.putObject("pointer");
            node.put("metadata-location", p.metadataLocation());
            node.put("version", p.version());
            node.put("sha256", p.sha256());
        });
        ArrayNode journal = body.putArray("journal");
        Optional<Long> top = before.or(() -> pointer.map(p -> p.version() + 1));
        if (top.isPresent()) {
            LogPage page = logPage(top.get(), size, next -> store.readJournal(object.mainKey(), JournalStore.EARLIEST,
                    JournalStore.LATEST, next));
            page.entries().forEach(entry -> journal.add(journal(entry)));
            page.before().ifPresent(next -> body.put("next-page-token", PageToken.encode(Long.toString(next))));
        }
        return GovernanceRoutes.json(200, body);
    }

    /** A page of the metadata log, newest first, and the version the page after it stops before, if there is one. */
    record LogPage(List<JournalRecord> entries, Optional<Long> before) {
    }

    /**
     * The log's entries below version {@code before} and at or above {@code before - size}, newest first: one read of
     * at most {@code size} entries, in the ascending pages the store keeps ({@link JournalStore#readJournal}).
     */
    static LogPage logPage(long before, int size, Function<PageRequest, Page<JournalRecord>> read) {
        long low = Math.max(1, before - size);
        List<JournalRecord> entries = new ArrayList<>(read.apply(JournalStore.afterVersion(low - 1, size)).items()
                .stream().filter(entry -> entry.newVersion() < before).toList());
        Collections.reverse(entries);
        return new LogPage(entries, low > 1 ? Optional.of(low) : Optional.empty());
    }

    private static long before(String token) {
        long version;
        try {
            version = Long.parseLong(PageToken.decode(token, 1).getFirst());
        } catch (IllegalArgumentException e) {
            throw CatalogError.badRequest("Invalid page token: " + token);
        }
        if (version < 1) {
            throw CatalogError.badRequest("Invalid page token: " + token);
        }
        return version;
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

    private static nuna.catalog.audit.Securable namespaceAudit(CatalogRecord catalog, UUID namespaceId,
                                                                 List<String> levels) {
        return new nuna.catalog.audit.Securable(nuna.catalog.audit.Securable.Kind.NAMESPACE, namespaceId,
                catalog.name(), levels);
    }

    private static CatalogError notDropped(String id) {
        return CatalogError.of(409, "NotDroppedException", id + " is not a dropped table or view");
    }

    static CatalogError claimedByAnother(String id) {
        return CatalogError.of(409, "PurgeClaimedException", id + " is claimed for purge by another maintenance"
                + " principal");
    }

    private static ObjectNode object(CatalogRecord catalog, CatalogObject object) {
        return object(catalog.name(), object);
    }

    /** An object as the lifecycle and maintenance routes show it; {@code catalog} is its catalog's name. */
    static ObjectNode object(String catalog, CatalogObject object) {
        ObjectNode node = Management.JSON.createObjectNode();
        node.put("id", object.id().toString());
        if (catalog != null) {
            node.put("catalog", catalog);
        }
        node.put("kind", object.kind().name().toLowerCase(java.util.Locale.ROOT));
        node.put("name", object.name().display());
        node.put("namespace-id", object.namespaceId().toString());
        node.put("state", object.state().name().toLowerCase(java.util.Locale.ROOT));
        object.droppedAt().ifPresent(at -> node.put("dropped-at", at.toString()));
        object.purgeAfter().ifPresent(at -> node.put("purge-after", at.toString()));
        object.purgeClaimant().ifPresent(claimant -> node.put("purge-claimant", claimant));
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
