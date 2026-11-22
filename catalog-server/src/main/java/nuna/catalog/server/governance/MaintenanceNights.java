package nuna.catalog.server.governance;

import nuna.catalog.core.CatalogError;
import nuna.catalog.core.principal.Caller;
import nuna.catalog.security.Authorizer;
import nuna.catalog.security.Authorizer.Decision;
import nuna.catalog.security.Privilege;
import nuna.catalog.store.CatalogObject;
import nuna.catalog.store.CatalogStore;
import nuna.catalog.store.MaintenanceJob;
import nuna.catalog.store.MaintenanceNight;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The maintenance worker's nights, for the console's overview and maintenance pages (D45, backlog 126;
 * docs/observability/maintenance-nights.md): the worker's report of each job, and each night's totals with the jobs
 * drawn on its slots. {@link MaintenanceRoutes} serves both; this reads a report and writes a night.
 *
 * <p>A night's totals are counts of the worker's work, readable by anyone signed in. Each job of a night names its table
 * only to a caller who may {@code select} it; to anyone else it shows where it ran, when and how it ended, and not what
 * it ran on.
 */
final class MaintenanceNights {

    /**
     * Nights the catalog keeps, by the catalog's clock: today's date in UTC and the 31 before it, so at least 30 whole
     * nights in any zone. Recording a job deletes those of older nights.
     */
    static final int KEEP_NIGHTS = 32;
    /** Nights a read answers unless it asks: a month. */
    static final int DAYS = 30;
    /** The jobs a night lists: those the console draws, on the slots and in the orphan scans' lane. */
    static final Set<MaintenanceJob.Kind> DRAWN = EnumSet.of(MaintenanceJob.Kind.COMPACTION,
            MaintenanceJob.Kind.ORPHAN_SCAN);

    private static final int MAX_NAME = 1000;
    private static final Authorizer.Operation NAME = new Authorizer.Operation("listMaintenanceNights", Privilege.SELECT,
            false);

    private final CatalogStore store;
    private final Management management;
    private final Authorizer authorizer;
    private final CatalogNames catalogs;
    private final Clock clock;

    MaintenanceNights(CatalogStore store, Management management, Authorizer authorizer, CatalogNames catalogs,
                      Clock clock) {
        this.store = store;
        this.management = management;
        this.authorizer = authorizer;
        this.catalogs = catalogs;
        this.clock = clock;
    }

    // ---- the worker's report ----

    /**
     * A job's report as its body gives it (docs/observability/maintenance-nights.md), reported by {@code worker}: the
     * night, its window, the kind, the object, the slot of a compaction, the start, the end and outcome, and the
     * counts, each 0 when left out. A 400 when it is not one.
     */
    static MaintenanceJob job(UUID id, String worker, JsonNode json) {
        try {
            return new MaintenanceJob(id, worker, date(json, "night"), instant(json, "opens"), instant(json, "closes"),
                    MaintenanceJob.Kind.valueOf(constant(text(json, "kind"))), uuid(text(json, "object-id")),
                    slot(json), instant(json, "started-at"), optionalInstant(json, "ended-at"),
                    MaintenanceJob.Outcome.valueOf(constant(text(json, "outcome"))), count(json, "files-in"),
                    count(json, "files-out"), count(json, "snapshots-expired"), count(json, "files-deleted"),
                    count(json, "bytes-deleted"));
        } catch (IllegalArgumentException e) {
            throw CatalogError.badRequest("Not a maintenance job report: " + e.getMessage());
        }
    }

    /**
     * Keeps the report, once its object is one the catalog has or had: an active, dropped or purged table or view
     * (404 otherwise). Then deletes the jobs of nights older than the catalog keeps.
     */
    void record(MaintenanceJob job) {
        if (store.readObject(job.objectId()).isEmpty()) {
            throw CatalogError.of(404, "NoSuchObjectException", "No table or view " + job.objectId());
        }
        store.recordMaintenanceJob(job, today().minusDays(KEEP_NIGHTS - 1));
    }

    // ---- the read ----

    /**
     * The newest {@code days} nights that have a job, up to tomorrow by the catalog's clock (a night's date is in the
     * worker's zone), each with its totals, and the first {@code withJobs} of them with their drawn jobs.
     */
    ObjectNode nights(Caller caller, int days, int withJobs) {
        List<MaintenanceNight> nights = store.listMaintenanceNights(today().plusDays(1), days);
        ObjectNode body = Management.JSON.createObjectNode();
        ArrayNode list = body.putArray("nights");
        for (int i = 0; i < nights.size(); i++) {
            ObjectNode night = night(nights.get(i));
            if (i < withJobs) {
                ArrayNode jobs = night.putArray("jobs");
                named(caller, store.listMaintenanceJobs(nights.get(i).night(), DRAWN)).forEach(jobs::add);
            }
            list.add(night);
        }
        return body;
    }

    static ObjectNode night(MaintenanceNight night) {
        ObjectNode node = Management.JSON.createObjectNode();
        node.put("night", night.night().toString());
        node.put("opens", night.opens().toString());
        node.put("closes", night.closes().toString());
        node.put("tables-compacted", night.tablesCompacted());
        node.put("files-compacted", night.filesCompacted());
        node.put("files-written", night.filesWritten());
        node.put("snapshots-expired", night.snapshotsExpired());
        node.put("files-deleted", night.filesDeleted());
        node.put("bytes-deleted", night.bytesDeleted());
        node.put("jobs-running", night.running());
        node.put("jobs-succeeded", night.succeeded());
        node.put("jobs-cut-off", night.cutOff());
        node.put("jobs-failed", night.failed());
        return node;
    }

    /** The jobs as a night lists them, each naming its table only when the caller may select it. */
    private List<ObjectNode> named(Caller caller, List<MaintenanceJob> jobs) {
        Map<UUID, Optional<CatalogObject>> objects = new LinkedHashMap<>();
        jobs.forEach(job -> objects.computeIfAbsent(job.objectId(), store::readObject));
        List<CatalogObject> found = objects.values().stream().flatMap(Optional::stream).toList();
        List<Decision> decisions = found.isEmpty() ? List.of() : authorizer.decide(caller, found.stream()
                .map(object -> new Authorizer.Request(new Authorizer.Target.TableOrView(object), NAME)).toList());
        Map<UUID, ObjectNode> tables = new HashMap<>();
        Map<UUID, Optional<List<String>>> levels = new HashMap<>();
        for (int i = 0; i < found.size(); i++) {
            CatalogObject object = found.get(i);
            String catalog = catalogs.name(object.catalogId());
            Optional<List<String>> namespace = levels.computeIfAbsent(object.namespaceId(),
                    id -> management.levels(object.catalogId(), id));
            if (decisions.get(i).allowed() && catalog != null && namespace.isPresent()) {
                ObjectNode table = Management.JSON.createObjectNode();
                table.put("id", object.id().toString());
                table.put("catalog", catalog);
                ArrayNode path = table.putArray("namespace");
                namespace.get().forEach(path::add);
                table.put("name", object.name().display());
                tables.put(object.id(), table);
            }
        }
        List<ObjectNode> named = new ArrayList<>();
        for (MaintenanceJob job : jobs) {
            ObjectNode node = Management.JSON.createObjectNode();
            node.put("id", job.id().toString());
            node.put("kind", label(job.kind()));
            job.slot().ifPresent(slot -> node.put("slot", slot));
            node.put("started-at", job.startedAt().toString());
            job.endedAt().ifPresent(at -> node.put("ended-at", at.toString()));
            node.put("outcome", label(job.outcome()));
            node.put("files-in", job.filesIn());
            node.put("files-out", job.filesOut());
            Optional.ofNullable(tables.get(job.objectId())).ifPresent(table -> node.set("table", table));
            named.add(node);
        }
        return named;
    }

    /** Today's date by the catalog's clock, in UTC. */
    private LocalDate today() {
        return LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    /** An enum constant as the routes write it: {@code CUT_OFF} is {@code cut-off}. */
    static String label(Enum<?> constant) {
        return constant.name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    /** The constant a label names: {@code cut-off} is {@code CUT_OFF}. */
    private static String constant(String label) {
        return label.toUpperCase(Locale.ROOT).replace('-', '_');
    }

    private static String text(JsonNode json, String field) {
        JsonNode value = json.get(field);
        if (value == null || !value.isString() || value.asString().isEmpty() || value.asString().length() > MAX_NAME) {
            throw new IllegalArgumentException(field + " is not a string of 1 to " + MAX_NAME + " characters");
        }
        return value.asString();
    }

    private static UUID uuid(String text) {
        return UUID.fromString(text);
    }

    private static LocalDate date(JsonNode json, String field) {
        try {
            return LocalDate.parse(text(json, field));
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(field + " is not a date such as 2026-11-21");
        }
    }

    /** A required time, kept to the millisecond as every store keeps it. */
    private static Instant instant(JsonNode json, String field) {
        return optionalInstant(json, field).orElseThrow(() -> new IllegalArgumentException("missing " + field));
    }

    private static Optional<Instant> optionalInstant(JsonNode json, String field) {
        JsonNode value = json.get(field);
        if (value == null || value.isNull()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Instant.parse(text(json, field)).truncatedTo(ChronoUnit.MILLIS));
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(field + " is not an ISO-8601 instant");
        }
    }

    private static Optional<Integer> slot(JsonNode json) {
        JsonNode value = json.get("slot");
        if (value == null || value.isNull()) {
            return Optional.empty();
        }
        if (!value.isIntegralNumber() || !value.canConvertToInt()) {
            throw new IllegalArgumentException("slot is not a whole number");
        }
        return Optional.of(value.intValue());
    }

    /** A count, 0 when left out. */
    private static long count(JsonNode json, String field) {
        JsonNode value = json.get(field);
        if (value == null || value.isNull()) {
            return 0;
        }
        if (!value.isIntegralNumber() || !value.canConvertToLong()) {
            throw new IllegalArgumentException(field + " is not a whole number");
        }
        return value.longValue();
    }
}
