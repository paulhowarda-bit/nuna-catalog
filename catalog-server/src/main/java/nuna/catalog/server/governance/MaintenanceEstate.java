package nuna.catalog.server.governance;

import nuna.catalog.core.principal.Caller;
import nuna.catalog.security.Authorizer;
import nuna.catalog.security.Authorizer.Decision;
import nuna.catalog.security.Privilege;
import nuna.catalog.store.CatalogObject;
import nuna.catalog.store.CatalogStore;
import nuna.catalog.store.GovernanceAttribute;
import nuna.catalog.store.LegalHold;
import nuna.catalog.store.MaintenanceJob;
import nuna.catalog.store.MaintenanceNight;
import nuna.catalog.store.NamespaceRecord;
import nuna.catalog.store.Page;
import nuna.catalog.store.PageRequest;
import nuna.catalog.store.Securable;
import nuna.catalog.store.TableHealth;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Every table the caller may {@code select}, one row each, for the console's ice line and the tree's marks (D45,
 * backlog 127; docs/observability/table-health.md, "Every table"): its latest assessment, whether a hold or a DR
 * freeze covers it, whether it is closed until classified, and whether last night's compaction of it succeeded.
 * {@link MaintenanceRoutes} serves it.
 *
 * <p>The rows walk the active tables of every catalog by id, a page at a time. A table counts as one the caller may
 * select when the authorizer allows {@code select} on it, or refuses it only because it is closed until classified
 * (D27): the caller holds the grant, and the closure keeps its data closed, not its name. Every other table is
 * skipped, never named.
 */
final class MaintenanceEstate {

    /** Rows a page holds unless it asks. */
    static final int PAGE = 200;
    /** Rows a page holds at most. */
    static final int MAX_PAGE = 500;
    /** Tables one page looks at, at most; a page that finds fewer the caller may select ends early, with a token. */
    static final int MAX_EXAMINED = 2000;
    /** Levels whose holds and attributes are read at once. */
    private static final int BATCH = 500;
    private static final Authorizer.Operation LIST = new Authorizer.Operation("listMaintenanceEstate", Privilege.SELECT,
            false);

    /** A table kept for the page: its catalog's name, its namespaces from the outermost in, and whether it is closed. */
    private record Row(CatalogObject table, String catalog, List<NamespaceRecord> namespaces, boolean closed) {

        /** The table, its namespaces, then its catalog. */
        List<Securable> chain() {
            List<Securable> chain = new ArrayList<>();
            chain.add(table.securable());
            namespaces.reversed().forEach(ns -> chain.add(new Securable(Securable.Kind.NAMESPACE, ns.id())));
            chain.add(new Securable(Securable.Kind.CATALOG, table.catalogId()));
            return chain;
        }
    }

    private final CatalogStore store;
    private final Authorizer authorizer;
    private final CatalogNames catalogs;
    private final Clock clock;

    MaintenanceEstate(CatalogStore store, Authorizer authorizer, CatalogNames catalogs, Clock clock) {
        this.store = store;
        this.authorizer = authorizer;
        this.catalogs = catalogs;
        this.clock = clock;
    }

    /**
     * One page of rows, at most {@code size}, after {@code token}; {@code next-page-token} is there while more tables
     * may follow. A token the store did not issue is an IllegalArgumentException.
     */
    ObjectNode tables(Caller caller, Optional<String> token, int size) {
        Optional<LocalDate> night = store.listMaintenanceNights(today().plusDays(1), 1).stream().findFirst()
                .map(MaintenanceNight::night);
        Set<UUID> fixed = night.map(this::compacted).orElse(Set.of());

        List<Row> rows = new ArrayList<>();
        Map<UUID, Optional<NamespaceRecord>> namespaces = new HashMap<>();
        Optional<String> cursor = token;
        Optional<String> next;
        int examined = 0;
        while (true) {
            Page<CatalogObject> page = store.listActiveObjects(CatalogObject.Kind.TABLE,
                    new PageRequest(cursor, Math.min(size - rows.size(), MAX_EXAMINED - examined)));
            List<CatalogObject> tables = page.items();
            examined += tables.size();
            List<Decision> decisions = tables.isEmpty() ? List.of() : authorizer.decide(caller, tables.stream()
                    .map(table -> new Authorizer.Request(new Authorizer.Target.TableOrView(table), LIST)).toList());
            for (int i = 0; i < tables.size(); i++) {
                Decision decision = decisions.get(i);
                boolean closed = decision instanceof Decision.Denied denied
                        && denied.reason() == Authorizer.Reason.UNCLASSIFIED;
                if (decision.allowed() || closed) {
                    row(tables.get(i), closed, namespaces).ifPresent(rows::add);
                }
            }
            next = page.nextToken();
            if (next.isEmpty() || rows.size() == size || examined >= MAX_EXAMINED) {
                break;
            }
            cursor = next;
        }

        Set<Securable> levels = new LinkedHashSet<>();
        rows.forEach(row -> levels.addAll(row.chain()));
        Set<Securable> held = new HashSet<>();
        Set<Securable> frozen = new HashSet<>();
        List<Securable> all = List.copyOf(levels);
        for (int from = 0; from < all.size(); from += BATCH) {
            Set<Securable> batch = Set.copyOf(all.subList(from, Math.min(all.size(), from + BATCH)));
            store.holdsOn(batch).stream().map(LegalHold::target).forEach(held::add);
            store.attributesOf(batch).stream().filter(a -> a.kind() == GovernanceAttribute.Kind.DR_FREEZE)
                    .map(GovernanceAttribute::target).forEach(frozen::add);
        }

        ObjectNode body = Management.JSON.createObjectNode();
        night.ifPresent(date -> body.put("night", date.toString()));
        ArrayNode list = body.putArray("tables");
        for (Row row : rows) {
            List<Securable> chain = row.chain();
            list.add(node(row, store.readTableHealth(row.table().id()),
                    chain.stream().anyMatch(held::contains), chain.stream().anyMatch(frozen::contains),
                    fixed.contains(row.table().id())));
        }
        next.ifPresent(t -> body.put("next-page-token", t));
        return body;
    }

    /** The tables whose compaction Job of the night succeeded. */
    private Set<UUID> compacted(LocalDate night) {
        return store.listMaintenanceJobs(night, EnumSet.of(MaintenanceJob.Kind.COMPACTION)).stream()
                .filter(job -> job.outcome() == MaintenanceJob.Outcome.SUCCEEDED).map(MaintenanceJob::objectId)
                .collect(Collectors.toSet());
    }

    /** The table as a row; none when its catalog is not declared or a namespace above it is gone. */
    private Optional<Row> row(CatalogObject table, boolean closed, Map<UUID, Optional<NamespaceRecord>> namespaces) {
        String catalog = catalogs.name(table.catalogId());
        if (catalog == null) {
            return Optional.empty();
        }
        List<NamespaceRecord> path = new ArrayList<>();
        UUID next = table.namespaceId();
        while (!next.equals(table.catalogId())) {
            Optional<NamespaceRecord> namespace = namespaces.computeIfAbsent(next, store::readNamespace);
            if (namespace.isEmpty()) {
                return Optional.empty();
            }
            path.addFirst(namespace.get());
            next = namespace.get().parentId();
        }
        return Optional.of(new Row(table, catalog, path, closed));
    }

    private static ObjectNode node(Row row, Optional<TableHealth> health, boolean held, boolean frozen,
                                   boolean fixed) {
        ObjectNode node = Management.JSON.createObjectNode();
        node.put("id", row.table().id().toString());
        node.put("catalog", row.catalog());
        ArrayNode namespace = node.putArray("namespace");
        row.namespaces().forEach(ns -> namespace.add(ns.name().display()));
        node.put("name", row.table().name().display());
        health.flatMap(TableHealth::assessment).ifPresent(assessment -> {
            ObjectNode assessed = node.putObject("assessment");
            assessed.put("assessed-at", assessment.assessedAt().toString());
            assessed.put("needs-compaction", assessment.needsCompaction());
            ArrayNode reasons = assessed.putArray("reasons");
            assessment.reasons().stream().sorted().forEach(reasons::add);
            assessed.put("score", assessment.score());
            assessed.put("data-files", assessment.dataFiles());
        });
        node.put("held", held);
        node.put("frozen", frozen);
        node.put("closed", row.closed());
        node.put("fixed-last-night", fixed);
        return node;
    }

    /** Today's date by the catalog's clock, in UTC, as the nights read counts it. */
    private LocalDate today() {
        return LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
    }
}
