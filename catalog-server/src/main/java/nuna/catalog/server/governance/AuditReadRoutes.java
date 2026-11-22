package nuna.catalog.server.governance;

import static nuna.catalog.server.governance.Management.CATALOG;
import static nuna.catalog.server.governance.Management.NAMESPACE;
import static nuna.catalog.server.governance.Management.ROOT;
import static nuna.catalog.server.governance.Management.TABLE;
import static nuna.catalog.server.governance.Management.VIEW;

import nuna.audittables.EventsReader;
import nuna.catalog.audit.AuditRead;
import nuna.catalog.audit.AuditRow;
import nuna.catalog.audit.AuditRows;
import nuna.catalog.audit.EventCodec;
import nuna.catalog.audit.EventType;
import nuna.catalog.audit.archive.ArchiveCheck;
import nuna.catalog.audit.archive.ObjectLockArchive.Placement;
import nuna.catalog.core.CatalogError;
import nuna.catalog.iceberg.Wire;
import nuna.catalog.security.Authorizer;
import nuna.catalog.security.Privilege;
import nuna.catalog.server.governance.Management.Request;
import nuna.catalog.server.governance.Management.Target;
import nuna.catalog.store.CatalogRecord;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.apache.kafka.common.KafkaException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * {@code GET .../audit}: the recent audit records of a catalog, namespace, table or view, newest first by record
 * time, read
 * back from the audit topic (D23; see {@link RecentAudit} for what it can and cannot see). Needs
 * {@code manage-grants} on the target or above: the trail shows who used the object and who was refused,
 * which is the evidence an access review needs (D19), and separation of duties keeps grant admins away from
 * the data itself (D18). Each read is audited as an {@link AuditRead}; a refusal is a {@code Denial}.
 * Each read opens a Kafka consumer, so one caller may read at most {@code audit-reads-per-minute} times a minute
 * on a server (each replica counts its own); beyond that the route answers 429 with {@code Retry-After}, as a
 * purge claim over its limit is, and like that one the 429 is not audited: it is no authorization decision and
 * reads nothing (backlog 19). The reasons are in build-plan.md, UI v1.
 *
 * <p>The console's catalog-wide feed (D45; backlog 125) reads the queryable audit table instead ({@link AuditFeed}):
 * {@code GET .../catalogs/{catalog}/audit/feed} and {@code .../audit/summary}, with the archive check's status at
 * {@code GET /management/v1/audit/archive} and a record's place in its hash chain at
 * {@code GET /management/v1/audit/chains/{partition}/records/{offset}}. Each needs {@code select} on the audit catalog,
 * as the tables themselves do (D23, amended 2026-11-22: "Audit readers only"); a refusal is a 403, audited as a
 * {@code Denial} on the audit catalog. A feed page, a chain lookup and a summary not kept from the last minute each
 * take one of the caller's audit reads for the minute. The feed and the summary are audited as an {@link AuditRead}
 * of the catalog, a chain lookup as one of the audit catalog. catalog-audit-tables' README describes the routes.
 */
@RestController
class AuditReadRoutes {

    private static final Logger LOG = LoggerFactory.getLogger(AuditReadRoutes.class);
    private static final String AUDIT = "/audit";
    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 200;
    private static final Duration RATE_WINDOW = Duration.ofMinutes(1);
    /** The audit record types the feed may be filtered by. */
    private static final Set<String> TYPES = Arrays.stream(EventType.values()).map(EventType::typeName)
            .collect(Collectors.toUnmodifiableSet());

    private final Management management;
    private final ManagementGuard guard;
    private final RecentAudit recent;
    private final AuditTrail trail;
    private final GovernanceSettings settings;
    private final AuditFeed feed;
    private final AuditArchiveCheck check;
    private final Clock clock;
    /** Each caller's reads in the last minute, oldest first; a caller with none is not kept. */
    private final Map<String, Deque<Instant>> recentReads = new HashMap<>();

    AuditReadRoutes(Management management, ManagementGuard guard, RecentAudit recent, AuditTrail trail,
                    GovernanceSettings settings, AuditFeed feed, AuditArchiveCheck check, Clock clock) {
        this.management = management;
        this.guard = guard;
        this.recent = recent;
        this.trail = trail;
        this.settings = settings;
        this.feed = feed;
        this.check = check;
        this.clock = clock;
    }

    @GetMapping(path = {CATALOG + AUDIT, NAMESPACE + AUDIT, TABLE + AUDIT, VIEW + AUDIT},
            produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<String> recent(@PathVariable("catalog") String prefix,
                                  @PathVariable(required = false) String namespace,
                                  @PathVariable(required = false) String table,
                                  @PathVariable(required = false) String view,
                                  @RequestParam(required = false) String limit,
                                  HttpServletRequest http) {
        Request request = management.request(http);
        Target target = management.target(request, "readAudit", Privilege.MANAGE_GRANTS,
                prefix, namespace, table, view);
        guard.require(request, target, "readAudit", Privilege.MANAGE_GRANTS);
        int wanted = limit(limit);
        if (!recent.configured()) {
            throw CatalogError.of(503, "ServiceUnavailableException",
                    "The audit topic is not configured on this server (nuna.audit.kafka-bootstrap-servers)");
        }
        Optional<ResponseEntity<String>> limited = limited(request);
        if (limited.isPresent()) {
            return limited.get();
        }
        RecentAudit.Result result;
        try {
            result = recent.recent(target.securable().id().toString(), wanted);
        } catch (KafkaException | IllegalStateException e) {
            LOG.warn("audit topic could not be read for {}: {}", target.securable().id(), e.getMessage());
            throw CatalogError.of(503, "ServiceUnavailableException", "The audit topic cannot be read now; retry later");
        }
        trail.record(new AuditRead(target.audit(), result.records().size(), request.actor()));
        ObjectNode body = Management.JSON.createObjectNode();
        body.set("target", GovernanceRoutes.securable(target.securable()));
        // The topic holds changes in relay order, after the synchronous records published meanwhile; a reader
        // wants them by when they happened.
        ArrayNode records = body.putArray("records");
        result.records().stream().map(Management.JSON::readTree)
                .sorted(Comparator.comparing((JsonNode record) -> Instant.parse(record.get("time").asString())).reversed())
                .forEach(records::add);
        ObjectNode window = body.putObject("window");
        window.put("topic", result.window().topic());
        window.put("partition", result.window().partition());
        window.put("earliest-offset", result.window().earliest());
        window.put("from-offset", result.window().from());
        window.put("to-offset", result.window().to());
        window.put("complete", result.window().complete());
        window.put("scan-limit", RecentAudit.SCAN_LIMIT);
        return GovernanceRoutes.json(200, body);
    }

    // ---- the catalog-wide feed (backlog 125) ----

    @GetMapping(path = CATALOG + AUDIT + "/feed", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<String> feed(@PathVariable("catalog") String prefix,
                                @RequestParam(required = false) String type,
                                @RequestParam(required = false) String principal,
                                @RequestParam(required = false) String path,
                                @RequestParam(required = false) String from,
                                @RequestParam(required = false) String to,
                                @RequestParam(required = false) String limit,
                                @RequestParam(name = "pageToken", required = false) String pageToken,
                                HttpServletRequest http) {
        Request request = management.request(http);
        requireAuditReader(request, "readAuditFeed");
        CatalogRecord catalog = catalog(prefix);
        Instant since = instant("from", from);
        Instant until = instant("to", to);
        if (since != null && until != null && !since.isBefore(until)) {
            throw CatalogError.badRequest("from must be before to");
        }
        EventsReader.Query query = new EventsReader.Query(catalog.name(), types(type),
                principal == null || principal.isBlank() ? null : principal,
                levels(path), since, until,
                Optional.ofNullable(pageToken).filter(t -> !t.isEmpty()).map(AuditReadRoutes::cursor), limit(limit));
        Optional<ResponseEntity<String>> limited = limited(request);
        if (limited.isPresent()) {
            return limited.get();
        }
        AuditFeed.Read read = readTable(() -> feed.page(query));
        trail.record(new AuditRead(catalogSecurable(catalog), read.page().rows().size(), request.actor()));
        Map<Integer, ArchiveCheck.Checked> verified = check.configured() ? check.verified() : Map.of();
        ObjectNode body = Management.JSON.createObjectNode();
        body.put("catalog", catalog.name());
        ArrayNode records = body.putArray("records");
        read.page().rows().forEach(row -> records.add(record(row, verified)));
        read.page().next().ifPresent(next -> body.put("next-page-token", next.token()));
        read.page().searchedTo().ifPresent(at -> body.put("searched-to", at.toString()));
        body.set("table", table(read.table()));
        return GovernanceRoutes.json(200, body);
    }

    @GetMapping(path = CATALOG + AUDIT + "/summary", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<String> summary(@PathVariable("catalog") String prefix, HttpServletRequest http) {
        Request request = management.request(http);
        requireAuditReader(request, "readAuditSummary");
        CatalogRecord catalog = catalog(prefix);
        Optional<AuditFeed.Counted> kept = feed.kept();
        AuditFeed.Counted counted;
        if (kept.isPresent()) {
            counted = kept.get();
        } else {
            Optional<ResponseEntity<String>> limited = limited(request);
            if (limited.isPresent()) {
                return limited.get();
            }
            counted = readTable(feed::count);
        }
        trail.record(new AuditRead(catalogSecurable(catalog), 0, request.actor()));
        EventsReader.Summary summary = counted.summary();
        EventsReader.CatalogCounts counts = summary.catalogs().getOrDefault(catalog.normalizedName(),
                new EventsReader.CatalogCounts(Map.of(), List.of()));
        ObjectNode body = Management.JSON.createObjectNode();
        body.put("catalog", catalog.name());
        body.put("from", summary.from().toString());
        body.put("to", summary.to().toString());
        body.set("by-type", byType(counts.byType()));
        ArrayNode busiest = body.putArray("busiest");
        for (EventsReader.Busy busy : counts.busiest()) {
            ObjectNode each = busiest.addObject();
            each.put("kind", busy.kind());
            each.put("id", busy.id());
            ArrayNode levels = each.putArray("path");
            busy.path().forEach(levels::add);
            each.put("records", busy.records());
            each.put("reads", busy.reads());
            each.put("writer", busy.writer());
        }
        body.set("deployment-by-type", byType(summary.byType()));
        body.set("table", table(counted.table()));
        return GovernanceRoutes.json(200, body);
    }

    @GetMapping(path = ROOT + AUDIT + "/archive", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<String> archive(HttpServletRequest http) {
        Request request = management.request(http);
        requireAuditReader(request, "readAuditArchive");
        ObjectNode body = Management.JSON.createObjectNode();
        body.put("configured", check.configured());
        if (!check.configured()) {
            return GovernanceRoutes.json(200, body);
        }
        AuditSettings.Archive archive = check.archiveSettings();
        body.put("bucket", archive.bucket());
        body.put("prefix", archive.prefix());
        body.put("retention", archive.retention().toString());
        ArrayNode verified = body.putArray("verified");
        check.verified().entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(e -> verified.add(checked(check.chain(e.getKey()), e.getKey(), e.getValue())));
        Optional<ArchiveCheck.Result> last = check.last();
        if (last.isEmpty()) {
            body.putNull("check");
            return GovernanceRoutes.json(200, body);
        }
        ObjectNode run = body.putObject("check");
        run.put("started-at", last.get().startedAt().toString());
        run.put("ended-at", last.get().endedAt().toString());
        run.put("intact", last.get().intact());
        ArrayNode chains = run.putArray("chains");
        for (ArchiveCheck.ChainResult chain : last.get().chains()) {
            ObjectNode each = chains.addObject();
            each.put("chain", chain.chain());
            each.put("partition", chain.partition());
            each.put("records-verified", chain.records());
            if (chain.verified().isPresent()) {
                each.set("verified", checked(chain.chain(), chain.partition(), chain.verified().get()));
            } else {
                each.putNull("verified");
            }
            each.put("broken", chain.broken().orElse(null));
            each.put("failed", chain.failed().orElse(null));
        }
        return GovernanceRoutes.json(200, body);
    }

    @GetMapping(path = ROOT + AUDIT + "/chains/{partition}/records/{offset}",
            produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<String> chain(@PathVariable String partition, @PathVariable String offset,
                                 @RequestParam(name = "event-id", required = false) String eventId,
                                 HttpServletRequest http) {
        Request request = management.request(http);
        CatalogRecord audit = requireAuditReader(request, "readAuditChain");
        int at = (int) whole("partition", partition, Integer.MAX_VALUE);
        long position = whole("offset", offset, Long.MAX_VALUE);
        UUID id;
        try {
            id = UUID.fromString(eventId == null ? "" : eventId);
        } catch (IllegalArgumentException e) {
            throw CatalogError.badRequest("event-id must be the record's event id, a UUID, not " + eventId);
        }
        if (!check.configured()) {
            throw CatalogError.of(503, "ServiceUnavailableException",
                    "The audit archive is not configured on this server (nuna.audit.archive.bucket)");
        }
        Optional<ResponseEntity<String>> limited = limited(request);
        if (limited.isPresent()) {
            return limited.get();
        }
        Optional<Placement> placed;
        try {
            placed = check.place(at, position, id.toString());
        } catch (RuntimeException e) {
            LOG.warn("audit archive could not be read for {} offset {}", check.chain(at), position, e);
            throw CatalogError.of(503, "ServiceUnavailableException", "The audit archive cannot be read now; retry later");
        }
        trail.record(new AuditRead(catalogSecurable(audit), placed.isPresent() ? 1 : 0, request.actor()));
        ObjectNode body = Management.JSON.createObjectNode();
        body.put("chain", check.chain(at));
        body.put("partition", at);
        body.put("offset", position);
        body.put("event-id", id.toString());
        body.put("archived", placed.isPresent());
        if (placed.isEmpty()) {
            return GovernanceRoutes.json(200, body);
        }
        Placement place = placed.get();
        body.put("sequence", place.sequence());
        body.put("hash", place.hash());
        body.put("previous-hash", place.previous());
        body.put("next-hash", place.next().orElse(null));
        body.put("link-holds", place.holds());
        ArchiveCheck.Checked checked = check.verified().get(at);
        body.put("verified", checked != null && position < checked.nextOffset());
        body.put("checked-at", checked == null ? null : checked.at().toString());
        ObjectNode segment = body.putObject("segment");
        segment.put("key", place.segment().key());
        segment.put("first-sequence", place.segment().start().sequence() + 1);
        segment.put("last-sequence", place.segment().end().sequence());
        return GovernanceRoutes.json(200, body);
    }

    /**
     * {@code select} on the audit catalog (D23, amended 2026-11-22), refused as a 403 audited on it; 503 where no
     * audit catalog is configured, since then no one holds it.
     */
    private CatalogRecord requireAuditReader(Request request, String operation) {
        CatalogRecord audit = feed.auditCatalog().orElseThrow(() -> CatalogError.of(503,
                "ServiceUnavailableException",
                "The audit tables are not configured on this server (nuna.audit.tables.catalog)"));
        guard.require(request, new Authorizer.Target.Catalog(audit.id()), catalogSecurable(audit), operation,
                Privilege.SELECT);
        return audit;
    }

    private CatalogRecord catalog(String prefix) {
        return feed.catalog(prefix).orElseThrow(() ->
                CatalogError.noSuchWarehouse("The given warehouse does not exist: " + prefix));
    }

    private static nuna.catalog.audit.Securable catalogSecurable(CatalogRecord catalog) {
        return new nuna.catalog.audit.Securable(nuna.catalog.audit.Securable.Kind.CATALOG, catalog.id(),
                catalog.name(), List.of());
    }

    private static <T> T readTable(Supplier<T> read) {
        try {
            return read.get();
        } catch (AuditFeed.NotReady e) {
            throw CatalogError.of(503, "ServiceUnavailableException", "The audit table cannot be read: "
                    + e.getMessage());
        } catch (CatalogError e) {
            throw e;
        } catch (RuntimeException e) {
            LOG.warn("audit table could not be read", e);
            throw CatalogError.of(503, "ServiceUnavailableException", "The audit table cannot be read now; retry later");
        }
    }

    /** A feed row: its columns, its place in the topic, how far its chain is verified, and the record itself. */
    private ObjectNode record(AuditRow row, Map<Integer, ArchiveCheck.Checked> verified) {
        ObjectNode node = Management.JSON.createObjectNode();
        node.put("event-id", row.eventId());
        node.put("time", row.time().toString());
        node.put("type", row.type());
        node.put("principal", row.principal());
        node.put("end-user", row.endUser());
        node.put("engine", row.engine());
        node.put("request-id", row.requestId());
        if (row.objectKind() == null) {
            node.putNull("object");
        } else {
            ObjectNode object = node.putObject("object");
            object.put("kind", row.objectKind());
            object.put("id", row.objectId());
            object.put("catalog", row.catalog());
            ArrayNode levels = object.putArray("path");
            if (row.objectPath() != null) {
                row.objectPath().forEach(levels::add);
            }
        }
        node.put("operation", row.operation());
        node.put("privilege", row.privilege());
        node.put("reason", row.reason());
        node.put("partition", row.topicPartition());
        node.put("offset", row.topicOffset());
        ObjectNode chain = node.putObject("chain");
        if (!check.configured()) {
            chain.put("status", "no-archive");
        } else {
            ArchiveCheck.Checked checked = verified.get(row.topicPartition());
            chain.put("status", checked != null && row.topicOffset() < checked.nextOffset() ? "verified" : "unverified");
            chain.put("checked-at", checked == null ? null : checked.at().toString());
        }
        try {
            node.set("event", Management.JSON.readTree(EventCodec.encode(AuditRows.event(row))));
        } catch (IllegalArgumentException e) {
            // A type or payload this server does not know, from a newer server: its columns still say what it was.
            node.putNull("event");
        }
        return node;
    }

    private static ObjectNode checked(String chain, int partition, ArchiveCheck.Checked checked) {
        ObjectNode node = Management.JSON.createObjectNode();
        node.put("chain", chain);
        node.put("partition", partition);
        node.put("sequence", checked.head().sequence());
        node.put("hash", checked.head().hash());
        node.put("next-offset", checked.nextOffset());
        node.put("segment", checked.segmentKey());
        node.put("at", checked.at().toString());
        return node;
    }

    private static ObjectNode table(AuditFeed.Snapshot snapshot) {
        ObjectNode node = Management.JSON.createObjectNode();
        node.put("name", snapshot.name());
        node.put("snapshot-id", snapshot.id().orElse(null));
        node.put("committed-at", snapshot.committedAt().map(Instant::toString).orElse(null));
        return node;
    }

    private static ObjectNode byType(Map<String, Long> counts) {
        ObjectNode node = Management.JSON.createObjectNode();
        counts.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e -> node.put(e.getKey(), e.getValue()));
        return node;
    }

    /** Comma-separated CloudEvents types, each one the catalog writes. */
    private static Set<String> types(String types) {
        if (types == null || types.isBlank()) {
            return Set.of();
        }
        Set<String> wanted = Arrays.stream(types.split(",")).map(String::trim).collect(Collectors.toSet());
        for (String type : wanted) {
            if (!TYPES.contains(type)) {
                throw CatalogError.badRequest("type must be audit record types such as nuna.catalog.read, separated"
                        + " by commas, not " + type);
            }
        }
        return wanted;
    }

    /** A namespace's levels, and a table or view name after them, joined by 0x1F as a namespace path segment is. */
    private static List<String> levels(String path) {
        if (path == null || path.isEmpty()) {
            return List.of();
        }
        List<String> levels = Arrays.asList(path.split("\u001f", -1));
        if (levels.contains("")) {
            throw CatalogError.badRequest("path must be namespace levels, and a table or view name, separated by"
                    + " 0x1F, none of them empty");
        }
        return levels;
    }

    private static Instant instant(String name, String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException e) {
            throw CatalogError.badRequest(name + " must be an instant such as 2026-11-22T00:00:00Z, not " + value);
        }
    }

    private static EventsReader.Cursor cursor(String token) {
        try {
            return EventsReader.Cursor.parse(token);
        } catch (IllegalArgumentException e) {
            throw CatalogError.badRequest("pageToken is not one this feed gave: " + token);
        }
    }

    private static long whole(String name, String value, long most) {
        try {
            long parsed = Long.parseLong(value);
            if (parsed >= 0 && parsed <= most) {
                return parsed;
            }
        } catch (NumberFormatException e) {
            // answered below
        }
        throw CatalogError.badRequest(name + " must be a whole number from 0, not " + value);
    }

    /** The 429 for a caller whose audit reads this minute are spent; otherwise empty, and one read is taken. */
    private Optional<ResponseEntity<String>> limited(Request request) {
        String caller = request.caller().subject().id();
        if (takeReadSlot(caller, clock.instant())) {
            return Optional.empty();
        }
        return Optional.of(ResponseEntity.status(429).header("Retry-After", Long.toString(RATE_WINDOW.toSeconds()))
                .contentType(MediaType.APPLICATION_JSON).body(Wire.error(429, "TooManyRequestsException",
                        "More than " + settings.auditReadsPerMinute() + " audit reads in a minute by " + caller
                                + "; retry later")));
    }

    /** Takes one read from the caller's allowance for this minute; false when it is spent. */
    private synchronized boolean takeReadSlot(String caller, Instant now) {
        Instant windowStart = now.minus(RATE_WINDOW);
        for (Deque<Instant> reads : recentReads.values()) {
            while (!reads.isEmpty() && !reads.peekFirst().isAfter(windowStart)) {
                reads.removeFirst();
            }
        }
        recentReads.values().removeIf(Deque::isEmpty);
        Deque<Instant> reads = recentReads.computeIfAbsent(caller, c -> new ArrayDeque<>());
        if (reads.size() >= settings.auditReadsPerMinute()) {
            return false;
        }
        reads.addLast(now);
        return true;
    }

    private static int limit(String limit) {
        if (limit == null || limit.isEmpty()) {
            return DEFAULT_LIMIT;
        }
        try {
            int value = Integer.parseInt(limit);
            if (value >= 1 && value <= MAX_LIMIT) {
                return value;
            }
        } catch (NumberFormatException e) {
            // answered below
        }
        throw CatalogError.badRequest("limit must be a whole number from 1 to " + MAX_LIMIT + ", not " + limit);
    }
}
