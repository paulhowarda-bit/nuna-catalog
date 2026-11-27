package nuna.catalog.server.rest;

import nuna.audittables.EventsColumns;
import nuna.catalog.audit.Actor;
import nuna.catalog.core.Names;
import nuna.catalog.core.commit.CommitEngine;
import nuna.catalog.core.commit.CommitReply;
import nuna.catalog.core.commit.ObjectName;
import nuna.catalog.core.principal.Caller;
import nuna.catalog.core.principal.Principal;
import nuna.catalog.server.security.AuditCatalogSettings;
import nuna.catalog.store.CatalogObject;
import nuna.catalog.store.CatalogRecord;
import nuna.catalog.store.CatalogStore;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Gives the audit table the columns the audit table writer added after its first (D23, amended 2026-11-27): once the
 * server is ready, it reads {@code <nuna.audit.tables.catalog>.<nuna.audit.tables.namespace>.events} and, if the table
 * lacks any of them, adds them through its own commit path, as the deployment's configuration
 * ({@value #BY}), journaled and audited as any commit. Adding a column needs {@code modify}, which the writers' group
 * does not hold, so a writer of a newer version finds its columns there instead of being refused. No table yet, or
 * every column there, changes nothing; a table that cannot be read or changed is logged and the server serves on, and
 * the writer is refused on opening it until an operator adds them.
 */
@Component
public class AuditTableColumns {

    /** Who commits: the deployment's configuration, as for the audit catalog's grants. */
    static final String BY = "nuna-configuration";
    static final String TABLE = "events";
    private static final Actor CONFIGURATION = new Actor(BY, null, "catalog-server", "startup");
    private static final Caller CALLER = new Caller(new Principal(BY, Set.of()), "catalog-server", false,
            Optional.empty());
    private static final Logger LOG = LoggerFactory.getLogger(AuditTableColumns.class);

    /** What {@link #addAbsent} did. */
    public enum Outcome {
        /** No audit catalog is configured, or the table is not there yet: the writer creates it with every column. */
        NO_TABLE,
        /** The table has every column. */
        COMPLETE,
        /** The absent columns were added. */
        ADDED
    }

    private final AuditCatalogSettings tables;
    private final String namespace;
    private final CatalogStore store;
    private final Engines engines;

    AuditTableColumns(AuditCatalogSettings tables, @Value("${nuna.audit.tables.namespace:audit}") String namespace,
                      CatalogStore store, Engines engines) {
        this.tables = tables;
        this.namespace = namespace;
        this.store = store;
        this.engines = engines;
    }

    @EventListener(ApplicationReadyEvent.class)
    void ready() {
        try {
            Outcome outcome = addAbsent(namespace);
            if (outcome == Outcome.ADDED) {
                LOG.info("added the audit table writer's newer columns to the audit table {}.{}.{}", tables.catalog(),
                        namespace, TABLE);
            }
        } catch (RuntimeException e) {
            LOG.warn("the audit table {}.{}.{} lacks columns the audit table writer adds, and adding them failed: the"
                    + " writer will be refused until they are added", tables.catalog(), namespace, TABLE, e);
        }
    }

    /**
     * Adds the columns the audit table in {@code namespace} lacks.
     *
     * @throws IllegalStateException when the table cannot be read, is not the writer's table, or the commit is refused
     */
    public Outcome addAbsent(String namespace) {
        if (tables.catalog() == null || tables.catalog().isBlank()) {
            return Outcome.NO_TABLE;
        }
        Optional<CatalogRecord> found = store.findCatalog(Names.normalize(tables.catalog()));
        if (found.isEmpty()) {
            return Outcome.NO_TABLE;
        }
        CatalogRecord catalog = found.get();
        List<String> levels = Arrays.stream(namespace.split("\\.")).map(Names::normalize).toList();
        Optional<CatalogObject> table = store.findNamespace(catalog.id(), levels)
                .flatMap(ns -> store.findObject(ns.id(), TABLE))
                .filter(o -> o.kind() == CatalogObject.Kind.TABLE);
        if (table.isEmpty()) {
            return Outcome.NO_TABLE;
        }
        String qualified = catalog.name() + "." + namespace + "." + TABLE;
        String metadata = switch (engines.reader(catalog).readCurrent(table.get())) {
            case CommitEngine.Current.Loaded loaded -> loaded.json();
            case CommitEngine.Current.Refused refused -> throw new IllegalStateException("cannot read " + qualified
                    + ": " + refused.error().message());
        };
        Optional<String> request = EventsColumns.update(metadata);
        if (request.isEmpty()) {
            return Outcome.COMPLETE;
        }
        ObjectName name = new ObjectName(catalog.id(), table.get().namespaceId(), table.get().name(),
                Http.qualified(levels, TABLE));
        CommitReply reply = engines.forServer(catalog, AuditEvents.path(levels, TABLE), CONFIGURATION)
                .commitTable(name, request.get(), CALLER, Optional.empty());
        return switch (reply) {
            case CommitReply.Success success -> Outcome.ADDED;
            case CommitReply.Applied applied -> Outcome.ADDED;
            case CommitReply.Failure failure -> throw new IllegalStateException("adding the audit table writer's"
                    + " columns to " + qualified + " answered " + failure.status() + " " + failure.message());
            case CommitReply.ServerError error -> throw new IllegalStateException("adding the audit table writer's"
                    + " columns to " + qualified + " failed: " + error.type() + " " + error.message());
            case CommitReply.Unavailable unavailable -> throw new IllegalStateException("adding the audit table"
                    + " writer's columns to " + qualified + " failed: " + unavailable.message());
        };
    }
}
