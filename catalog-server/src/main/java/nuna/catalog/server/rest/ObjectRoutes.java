package nuna.catalog.server.rest;

import nuna.catalog.audit.Drop;
import nuna.catalog.audit.Rename;
import nuna.catalog.core.CatalogError;
import nuna.catalog.core.Names;
import nuna.catalog.core.commit.CommitEngine;
import nuna.catalog.core.commit.CommitReply;
import nuna.catalog.core.commit.GovernanceChain;
import nuna.catalog.core.commit.ObjectName;
import nuna.catalog.iceberg.RestRequest;
import nuna.catalog.iceberg.RestRequest.Identifier;
import nuna.catalog.iceberg.RestResponse;
import nuna.catalog.security.Authorizer.Operation;
import nuna.catalog.server.rest.Idempotency.Reply;
import nuna.catalog.store.CatalogObject;
import nuna.catalog.store.CatalogObject.Kind;
import nuna.catalog.store.CatalogRecord;
import nuna.catalog.store.CatalogStore;
import nuna.catalog.store.Change;
import nuna.catalog.store.CommitOutcomeUnknownException;
import nuna.catalog.store.GovernanceAttribute;
import nuna.catalog.store.Name;
import nuna.catalog.store.NamespaceRecord;
import nuna.catalog.store.Page;
import nuna.catalog.store.Pointer;
import nuna.catalog.store.WriteResult;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

/**
 * What table and view routes share: listing, existence, soft drop (D25) and rename. Tables and views
 * share one name space per namespace, so a name held by the other kind is reported as such. Listing and existence
 * read through a ref's cut; drops and renames happen on main only (D33, as amended 2026-11-20).
 */
@Component
class ObjectRoutes {

    private final CatalogStore store;
    private final Lookup lookup;
    private final Idempotency idempotency;
    private final AuditEvents audit;
    private final RestSettings settings;
    private final Guard guard;
    private final Clock clock;

    ObjectRoutes(CatalogStore store, Lookup lookup, Idempotency idempotency, AuditEvents audit, RestSettings settings,
                 Guard guard, Clock clock) {
        this.store = store;
        this.lookup = lookup;
        this.idempotency = idempotency;
        this.audit = audit;
        this.settings = settings;
        this.guard = guard;
        this.clock = clock;
    }

    static String word(Kind kind) {
        return kind == Kind.TABLE ? "Table" : "View";
    }

    static CatalogError noSuch(Kind kind, String qualified) {
        return kind == Kind.TABLE ? Http.noSuchTable(qualified) : Http.noSuchView(qualified);
    }

    static ObjectName name(CatalogRecord catalog, NamespaceRecord namespace, List<String> levels, String name) {
        if (name == null || name.isEmpty()) {
            throw CatalogError.badRequest("Invalid name: empty");
        }
        return new ObjectName(catalog.id(), namespace.id(), new Name(Names.normalize(name), name),
                Http.qualified(levels, name));
    }

    /** The active object of {@code kind} the path names on main; a missing namespace is a missing object. */
    CatalogObject existing(CatalogRecord catalog, List<String> levels, String name, Kind kind) {
        return seen(OnRef.main(catalog), levels, name, kind).object();
    }

    /**
     * The object of {@code kind} the path names, as main or the ref sees it ({@link Lookup#seen}); a missing namespace
     * is a missing object.
     */
    Lookup.Seen seen(OnRef on, List<String> levels, String name, Kind kind) {
        String qualified = Http.qualified(levels, name);
        NamespaceRecord namespace = lookup.namespace(on, levels).orElseThrow(() -> noSuch(kind, qualified));
        return lookup.seen(on, namespace, levels, name, kind).orElseThrow(() -> noSuch(kind, qualified));
    }

    ResponseEntity<String> list(String prefix, String namespace, String pageToken, String pageSize, Kind kind,
                                RequestContext request) {
        OnRef on = lookup.onRef(prefix, request, kind == Kind.TABLE ? Guard.LIST_TABLES : Guard.LIST_VIEWS);
        CatalogRecord catalog = on.catalog();
        List<String> levels = Lookup.levels(namespace);
        NamespaceRecord record = lookup.existingNamespace(on, levels);
        guard.onNamespace(request, catalog, record, levels, kind == Kind.TABLE ? Guard.LIST_TABLES : Guard.LIST_VIEWS,
                Http.noSuchNamespace(levels));
        Page<String> page = lookup.listing(pageToken, pageSize,
                pageRequest -> lookup.objectNames(on, record.id(), kind, pageRequest));
        List<Identifier> identifiers = page.items().stream()
                .map(name -> new Identifier(levels, name))
                .toList();
        return Http.json(200, RestResponse.identifiers(identifiers, page.nextToken().orElse(null)));
    }

    /** 404 for an object that does not exist, and for one the caller may not learn exists (D18). */
    ResponseEntity<Void> exists(String prefix, String namespace, String name, Kind kind, RequestContext request) {
        OnRef on = lookup.onRef(prefix, request, kind == Kind.TABLE ? Guard.TABLE_EXISTS : Guard.VIEW_EXISTS);
        CatalogRecord catalog = on.catalog();
        List<String> levels = Lookup.levels(namespace);
        Optional<CatalogObject> found = lookup.namespace(on, levels)
                .flatMap(ns -> lookup.seen(on, ns, levels, name, kind)).map(Lookup.Seen::object);
        if (found.isEmpty() || !guard.allowsOnObject(request, catalog, found.get(), AuditEvents.path(levels, name),
                kind == Kind.TABLE ? Guard.TABLE_EXISTS : Guard.VIEW_EXISTS)) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.noContent().build();
    }

    /** Always a soft drop: the name is free at once, the object stays undroppable until purge (D25). */
    ResponseEntity<String> drop(String prefix, String namespace, String name, Kind kind, Optional<String> key,
                                RequestContext request) {
        CatalogRecord catalog = lookup.catalog(prefix, request,
                kind == Kind.TABLE ? Guard.DROP_TABLE : Guard.DROP_VIEW);
        List<String> levels = Lookup.levels(namespace);
        String qualified = Http.qualified(levels, name);
        return idempotency.run(key, request.caller(), "DROP_" + kind, scope(catalog, levels, name), "", ticket -> {
            CatalogObject object = existing(catalog, levels, name, kind);
            guard.onObject(request, catalog, object, AuditEvents.path(levels, name),
                    kind == Kind.TABLE ? Guard.DROP_TABLE : Guard.DROP_VIEW, noSuch(kind, qualified));
            requireUnfrozen(request, catalog, object, AuditEvents.path(levels, name),
                    kind == Kind.TABLE ? Guard.DROP_TABLE : Guard.DROP_VIEW, GovernanceChain.of(store, object),
                    word(kind) + " " + qualified);
            Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
            Instant purgeAfter = now.plus(settings.dropRetention());
            Change change = ticket.change(now, List.of(audit.event(now, new Drop(
                    AuditEvents.securable(object, catalog.name(), AuditEvents.path(levels, name)), purgeAfter,
                    request.actor()))), 204, "");
            WriteResult result;
            try {
                result = store.dropObject(object.id(), purgeAfter, change);
            } catch (CommitOutcomeUnknownException e) {
                if (!Http.readBack(() -> store.readObject(object.id())
                        .filter(o -> o.droppedAt().equals(Optional.of(now))).isPresent())) {
                    throw Http.outcomeUnknown("Dropping " + qualified, e);
                }
                result = WriteResult.APPLIED;
            }
            return switch (result) {
                case WriteResult.Applied applied -> new Reply(204, "", true);
                case WriteResult.Missing missing -> throw noSuch(kind, qualified);
                case WriteResult.Refused refused when refused.reason() == WriteResult.Reason.HELD ->
                        throw guard.refuse(request, catalog, object, AuditEvents.path(levels, name),
                                kind == Kind.TABLE ? Guard.DROP_TABLE : Guard.DROP_VIEW, "legal_hold",
                                word(kind) + " " + qualified + " is under a legal hold and cannot be dropped");
                default -> throw new IllegalStateException("dropObject answered " + result);
            };
        });
    }

    /**
     * A rename keeps the object's id, so its grants, journal and history go with it (D25). The source is checked
     * before the destination is looked up, so a caller that may not see the source learns nothing of either (D18).
     */
    ResponseEntity<String> rename(String prefix, String body, Kind kind, Optional<String> key, RequestContext request) {
        CatalogRecord catalog = lookup.catalog(prefix, request,
                kind == Kind.TABLE ? Guard.RENAME_TABLE : Guard.RENAME_VIEW);
        return idempotency.run(key, request.caller(), "RENAME_" + kind, catalog.id().toString(), body, ticket -> {
            RestRequest.Rename rename = RestRequest.rename(body == null ? "" : body);
            Identifier from = rename.source();
            Identifier to = rename.destination();
            String fromName = Http.qualified(from.namespace(), from.name());
            String toName = Http.qualified(to.namespace(), to.name());
            CatalogObject object = existing(catalog, from.namespace(), from.name(), kind);
            guard.onObject(request, catalog, object, AuditEvents.path(from.namespace(), from.name()),
                    kind == Kind.TABLE ? Guard.RENAME_TABLE : Guard.RENAME_VIEW, noSuch(kind, fromName));
            NamespaceRecord target = lookup.existingNamespace(catalog, to.namespace());
            guard.onNamespace(request, catalog, target, to.namespace(),
                    kind == Kind.TABLE ? Guard.RENAME_TABLE_INTO : Guard.RENAME_VIEW_INTO,
                    Http.noSuchNamespace(to.namespace()));
            List<String> fromPath = AuditEvents.path(from.namespace(), from.name());
            Operation renaming = kind == Kind.TABLE ? Guard.RENAME_TABLE : Guard.RENAME_VIEW;
            requireUnfrozen(request, catalog, object, fromPath, renaming, GovernanceChain.of(store, object),
                    word(kind) + " " + fromName);
            requireUnfrozen(request, catalog, object, fromPath, renaming, GovernanceChain.of(store, target),
                    "Namespace " + Http.dotted(to.namespace()));
            Optional<CatalogObject> holder = store.findObject(target.id(), Names.normalize(to.name()))
                    .filter(o -> !o.id().equals(object.id()));
            if (holder.isPresent()) {
                throw alreadyExists(fromName, toName, holder.get().kind());
            }
            Name newName = new Name(Names.normalize(to.name()), to.name());
            Instant now = clock.instant();
            Change change = ticket.change(now, List.of(audit.event(now, new Rename(
                    AuditEvents.securable(object, catalog.name(), AuditEvents.path(to.namespace(), to.name())),
                    AuditEvents.path(from.namespace(), from.name()), request.actor()))), 204, "");
            WriteResult result;
            try {
                result = store.renameObject(object.id(), target.id(), newName, change);
            } catch (CommitOutcomeUnknownException e) {
                if (!Http.readBack(() -> store.readObject(object.id())
                        .filter(o -> o.namespaceId().equals(target.id()) && o.name().equals(newName)).isPresent())) {
                    throw Http.outcomeUnknown("Renaming " + fromName + " to " + toName, e);
                }
                result = WriteResult.APPLIED;
            }
            return switch (result) {
                case WriteResult.Applied applied -> new Reply(204, "", true);
                case WriteResult.NameTaken taken -> throw alreadyExists(fromName, toName,
                        store.readObject(taken.holder()).map(CatalogObject::kind).orElse(kind));
                case WriteResult.Missing missing when missing.subject() == WriteResult.Subject.NAMESPACE ->
                        throw Http.noSuchNamespace(to.namespace());
                case WriteResult.Missing missing -> throw noSuch(kind, fromName);
                default -> throw new IllegalStateException("renameObject answered " + result);
            };
        });
    }

    /**
     * What a register asks before it brings a dropped table or view back: the caller must be allowed to undrop
     * it, on the namespace it was dropped from (D13, D25).
     */
    CommitEngine.Undrop undrop(CatalogRecord catalog, RequestContext request) {
        return dropped -> guard.onDropped(request, catalog, dropped, lookup.levelsOf(catalog, dropped.namespaceId()));
    }

    /** A commit-engine reply as a response; {@code success} renders the metadata it carries. */
    static ResponseEntity<String> reply(CommitReply reply,
                                        java.util.function.Function<CommitReply.Success, ResponseEntity<String>> success) {
        return switch (reply) {
            case CommitReply.Success done -> success.apply(done);
            case CommitReply.Applied applied -> ResponseEntity.noContent().build();
            case CommitReply.Failure failure -> Http.error(failure.status(), failure.type(), failure.message());
            case CommitReply.ServerError error -> Http.error(500, error.type(), error.message());
            case CommitReply.Unavailable unavailable -> Http.unavailable(unavailable.message());
        };
    }

    /**
     * The metadata {@code pointer} names for an object, main's or a ref's, or the 500 the engine answers for a file
     * that fails its hash.
     */
    static CommitEngine.Current.Loaded current(CommitEngine reader, CatalogObject object, Optional<Pointer> pointer) {
        return switch (reader.readAt(object, pointer.orElseThrow(() -> new IllegalStateException("object "
                + object.id() + " has no pointer")))) {
            case CommitEngine.Current.Loaded loaded -> loaded;
            case CommitEngine.Current.Refused refused ->
                    throw CatalogError.of(500, refused.error().type(), refused.error().message());
        };
    }

    /**
     * A DR freeze on any level above refuses drops and renames as it refuses commits (D37, M7): audited as a denial
     * of the operation on the object, with reason {@code dr_freeze} (D18), and answered 403.
     */
    private void requireUnfrozen(RequestContext request, CatalogRecord catalog, CatalogObject object,
                                 List<String> path, Operation operation, GovernanceChain chain, String what) {
        chain.nearest(GovernanceAttribute.Kind.DR_FREEZE).ifPresent(freeze -> {
            throw guard.refuse(request, catalog, object, path, operation, "dr_freeze",
                    what + " is frozen for DR reconcile: " + freeze.value());
        });
    }

    private static CatalogError alreadyExists(String from, String to, Kind holder) {
        return CatalogError.of(409, "AlreadyExistsException",
                "Cannot rename " + from + " to " + to + ". " + word(holder) + " already exists");
    }

    private static String scope(CatalogRecord catalog, List<String> levels, String name) {
        return catalog.id() + "/" + String.join("\u001f", levels) + "/" + name;
    }
}
