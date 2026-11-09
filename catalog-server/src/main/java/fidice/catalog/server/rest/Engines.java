package fidice.catalog.server.rest;

import fidice.catalog.core.commit.CommitEngine;
import fidice.catalog.core.commit.CommitRule;
import fidice.catalog.core.commit.CommitSettings;
import fidice.catalog.core.commit.MetadataCache;
import fidice.catalog.core.commit.Rules;
import fidice.catalog.core.commit.TableFiles;
import fidice.catalog.store.CatalogStore;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Builds the commit engine (M3) for one request, whose audit events name the object as the request does.
 * Construction is cheap; the metadata cache and the rules are shared.
 */
@Component
class Engines {

    /** D9: a commit whose timestamps are further than this ahead of the server clock is refused. */
    private static final Duration CLOCK_SKEW = Duration.ofMinutes(1);

    private final CatalogStore store;
    private final TableFiles files;
    private final MetadataCache cache;
    private final List<CommitRule> rules;
    private final ServerAlerts alerts;
    private final AuditEvents audit;
    private final Clock clock;
    private final CommitEngine reader;

    /** @param extraRules rules other milestones add after the standard ones, such as retention classes (M7) */
    Engines(CatalogStore store, TableFiles files, MetadataCache cache, ServerAlerts alerts, AuditEvents audit,
            Clock clock, ObjectProvider<CommitRule> extraRules) {
        this.store = store;
        this.files = files;
        this.cache = cache;
        List<CommitRule> rules = new ArrayList<>(Rules.standard(files, CLOCK_SKEW));
        extraRules.orderedStream().forEach(rules::add);
        this.rules = List.copyOf(rules);
        this.alerts = alerts;
        this.audit = audit;
        this.clock = clock;
        this.reader = new CommitEngine(store, files, cache, rules, commit -> {
            throw new IllegalStateException("the reader engine never commits");
        }, alerts, CommitSettings.DEFAULTS, clock, CommitEngine.Sleeper.THREAD);
    }

    CommitEngine forRequest(String catalog, List<String> path, RequestContext request) {
        return new CommitEngine(store, files, cache, rules, audit.commits(catalog, path, request.actor()), alerts,
                CommitSettings.DEFAULTS, clock, CommitEngine.Sleeper.THREAD);
    }

    /** For reading current metadata only. */
    CommitEngine reader() {
        return reader;
    }
}
