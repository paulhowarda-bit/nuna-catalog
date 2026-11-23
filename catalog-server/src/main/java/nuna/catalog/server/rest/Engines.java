package nuna.catalog.server.rest;

import nuna.catalog.audit.RefChange;
import nuna.catalog.audit.Securable;
import nuna.catalog.core.commit.CommitEngine;
import nuna.catalog.core.commit.CommitEvents;
import nuna.catalog.core.commit.CommitRule;
import nuna.catalog.core.commit.CommitSettings;
import nuna.catalog.core.commit.Merge;
import nuna.catalog.core.commit.CommitReply;
import nuna.catalog.core.commit.MetadataCache;
import nuna.catalog.core.commit.ObjectName;
import nuna.catalog.core.commit.Rules;
import nuna.catalog.server.governance.RefSettings;
import nuna.catalog.server.storage.CatalogStorage;
import nuna.catalog.store.CatalogObject;
import nuna.catalog.store.CatalogRecord;
import nuna.catalog.store.CatalogStore;
import nuna.catalog.store.Change;
import nuna.catalog.store.Ref;
import nuna.catalog.store.RefEntry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Builds the commit engine (M3) for one request, whose audit events name the object as the request does.
 * Each catalog's engine writes through its own storage profile (D28) and also applies that profile's rules.
 * Construction is cheap; the metadata cache and the rules are shared. An engine for a commit through a branch commits on
 * the branch's own pointers (D33), and the branch's expiry moves after each commit that lands (item 26's choice 11).
 * A catalog's merges of a branch into main take its profile's files and rules as its commits do (backlog 107).
 */
@Component
class Engines implements Merge.Factory {

    /** D9: a commit whose timestamps are further than this ahead of the server clock is refused. */
    private static final Duration CLOCK_SKEW = Duration.ofMinutes(1);
    /** The least a commit on a branch moves its expiry by; a smaller move waits for a later commit. */
    static final Duration RENEWAL_STEP = Duration.ofHours(1);
    private static final Logger LOG = LoggerFactory.getLogger(Engines.class);

    private record Profile(CatalogStorage.Bound bound, List<CommitRule> rules, CommitEngine reader) {
    }

    private final CatalogStore store;
    private final CatalogStorage storage;
    private final MetadataCache cache;
    private final ServerAlerts alerts;
    private final AuditEvents audit;
    private final Clock clock;
    private final ObjectProvider<CommitRule> extraRules;
    private final RefSettings refs;
    private final Map<UUID, Profile> profiles = new ConcurrentHashMap<>();

    /** @param extraRules rules other milestones add after the standard ones, such as retention classes (M7) */
    Engines(CatalogStore store, CatalogStorage storage, MetadataCache cache, ServerAlerts alerts, AuditEvents audit,
            Clock clock, ObjectProvider<CommitRule> extraRules, RefSettings refs) {
        this.store = store;
        this.refs = refs;
        this.storage = storage;
        this.cache = cache;
        this.extraRules = extraRules;
        this.alerts = alerts;
        this.audit = audit;
        this.clock = clock;
    }

    CommitEngine forRequest(CatalogRecord catalog, List<String> path, RequestContext request) {
        return forRequest(catalog, audit.commits(catalog.name(), path, request.actor()));
    }

    /** For a transaction, whose audit events name each table by {@code path}. */
    CommitEngine forTransaction(CatalogRecord catalog, Function<CatalogObject, List<String>> path,
                                RequestContext request) {
        return forRequest(catalog, audit.commits(catalog.name(), path, request.actor()));
    }

    /**
     * The engine for a commit or a view's replace through {@code on}, whose audit events name the object by
     * {@code path}: on main, {@link #forRequest}'s; on a branch, that engine on the branch, for the object
     * {@code objects} names by the cut entry the route found it under (D33).
     */
    CommitEngine forCommit(OnRef on, List<String> path, RequestContext request, Map<ObjectName, RefEntry> objects) {
        return on(on, forRequest(on.catalog(), path, request), objects);
    }

    /** {@link #forCommit} for a transaction, whose audit events name each table by {@code path}. */
    CommitEngine forTransaction(OnRef on, Function<CatalogObject, List<String>> path, RequestContext request,
                                Map<ObjectName, RefEntry> objects) {
        return on(on, forTransaction(on.catalog(), path, request), objects);
    }

    private CommitEngine on(OnRef on, CommitEngine engine, Map<ObjectName, RefEntry> objects) {
        return on.ref().map(ref -> engine.on(new CommitEngine.Branch(ref, refs.maxMergeTables(), objects)))
                .orElse(engine);
    }

    /**
     * After a commit through {@code on} that landed, moves a branch's expiry {@code nuna.refs.branch-idle-expiry}
     * ahead, never past its whole life (item 26's choice 11), with a {@code ref} event (renewed) naming who committed.
     * It runs after the commit, not in its transaction (item 103's choice), and only once the move gains at least
     * {@link #RENEWAL_STEP}, so a busy branch is not rewritten, nor an event sent, on every commit. A renewal that
     * fails, or finds the branch gone, leaves the commit as it is: it is logged, and the branch keeps its expiry.
     */
    void committed(OnRef on, CommitReply reply, RequestContext request) {
        boolean landed = reply instanceof CommitReply.Success success && success.how() == CommitReply.How.COMMITTED
                || reply instanceof CommitReply.Applied applied && applied.how() == CommitReply.How.COMMITTED;
        if (on.ref().isEmpty() || !landed) {
            return;
        }
        Ref ref = on.ref().get();
        Instant now = clock.instant();
        Instant expiry = refs.expiryAfter(ref.createdAt(), now);
        if (expiry.isBefore(ref.expiresAt().orElseThrow().plus(RENEWAL_STEP))) {
            return;
        }
        RefChange renewed = new RefChange(RefChange.Action.RENEWED, ref.id(), ref.name().display(),
                RefChange.Kind.BRANCH, new Securable(Securable.Kind.CATALOG, on.catalog().id(), on.catalog().name(),
                List.of()), ref.fromName(), expiry, List.of(), request.actor());
        try {
            store.renewRef(ref.id(), expiry, new Change(UUID.randomUUID(), now, List.of(audit.event(now, renewed)),
                    Optional.empty()));
        } catch (RuntimeException e) {
            LOG.warn("branch {} keeps its expiry: renewing it after a commit failed", ref.id(), e);
        }
    }

    private CommitEngine forRequest(CatalogRecord catalog, CommitEvents events) {
        Profile profile = profile(catalog);
        return new CommitEngine(store, profile.bound().files(), cache, profile.rules(), events, alerts,
                CommitSettings.DEFAULTS, clock, CommitEngine.Sleeper.THREAD);
    }

    @Override
    public Merge of(CatalogRecord catalog, CommitEvents events) {
        return forRequest(catalog, events).merging();
    }

    /** For reading current metadata only. */
    CommitEngine reader(CatalogRecord catalog) {
        return profile(catalog).reader();
    }

    /** Where a new table of {@code catalog} goes. */
    String newLocation(CatalogRecord catalog, UUID tableId) {
        return profile(catalog).bound().files().newLocation(tableId);
    }

    private Profile profile(CatalogRecord catalog) {
        CatalogStorage.Bound bound = storage.forCatalog(catalog.id());
        return profiles.computeIfAbsent(bound.id(), id -> {
            List<CommitRule> rules = new ArrayList<>(Rules.standard(bound.files(), CLOCK_SKEW));
            rules.addAll(bound.rules());
            extraRules.orderedStream().forEach(rules::add);
            CommitEngine reader = new CommitEngine(store, bound.files(), cache, List.copyOf(rules), commit -> {
                throw new IllegalStateException("the reader engine never commits");
            }, alerts, CommitSettings.DEFAULTS, clock, CommitEngine.Sleeper.THREAD);
            return new Profile(bound, List.copyOf(rules), reader);
        });
    }
}
