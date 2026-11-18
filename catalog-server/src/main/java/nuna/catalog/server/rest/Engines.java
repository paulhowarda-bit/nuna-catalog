package nuna.catalog.server.rest;

import nuna.catalog.core.commit.CommitEngine;
import nuna.catalog.core.commit.CommitEvents;
import nuna.catalog.core.commit.CommitRule;
import nuna.catalog.core.commit.CommitSettings;
import nuna.catalog.core.commit.MetadataCache;
import nuna.catalog.core.commit.Rules;
import nuna.catalog.server.storage.CatalogStorage;
import nuna.catalog.store.CatalogObject;
import nuna.catalog.store.CatalogRecord;
import nuna.catalog.store.CatalogStore;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Builds the commit engine (M3) for one request, whose audit events name the object as the request does.
 * Each catalog's engine writes through its own storage profile (D28) and also applies that profile's rules.
 * Construction is cheap; the metadata cache and the rules are shared.
 */
@Component
class Engines {

    /** D9: a commit whose timestamps are further than this ahead of the server clock is refused. */
    private static final Duration CLOCK_SKEW = Duration.ofMinutes(1);

    private record Profile(CatalogStorage.Bound bound, List<CommitRule> rules, CommitEngine reader) {
    }

    private final CatalogStore store;
    private final CatalogStorage storage;
    private final MetadataCache cache;
    private final ServerAlerts alerts;
    private final AuditEvents audit;
    private final Clock clock;
    private final ObjectProvider<CommitRule> extraRules;
    private final Map<UUID, Profile> profiles = new ConcurrentHashMap<>();

    /** @param extraRules rules other milestones add after the standard ones, such as retention classes (M7) */
    Engines(CatalogStore store, CatalogStorage storage, MetadataCache cache, ServerAlerts alerts, AuditEvents audit,
            Clock clock, ObjectProvider<CommitRule> extraRules) {
        this.store = store;
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

    private CommitEngine forRequest(CatalogRecord catalog, CommitEvents events) {
        Profile profile = profile(catalog);
        return new CommitEngine(store, profile.bound().files(), cache, profile.rules(), events, alerts,
                CommitSettings.DEFAULTS, clock, CommitEngine.Sleeper.THREAD);
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
