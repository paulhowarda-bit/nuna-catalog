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
import nuna.catalog.audit.archive.ObjectLockArchive;
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
 * {@code GET /management/v1/audit/archive} and a logged record's place in its run's hash chain at
 * {@code GET /management/v1/audit/chains/{run}/records/{number}} (backlog 272). Each needs {@code select} on the audit
 * catalog, as the tables themselves do (D23, amended 2026-11-22: "Audit readers only"); a refusal is a 403, audited as
 * a {@code Denial} on the audit catalog. A feed page, a chain lookup and a summary not kept from the last minute each
 * take one of the caller's audit reads for the minute; the feed and the summary are audited as an {@link AuditRead} of
 * the catalog, a lookup as one of the audit catalog. catalog-audit-tables' README describes the routes.
 */
@RestController
class AuditReadRoutes {

    private static final Logger LOG = LoggerFactory.getLogger(AuditReadRoutes.class);
    private static final String AUDIT = "/audit";
    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 200;
    /** The longest ref name the feed is asked for, as RefRoutes takes one. */
    private static final int MAX_BRANCH = 1000;
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
                                @RequestParam(required = false) String branch,
                                @RequestParam(required = false) String subject,
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
        String about = subject == null || subject.isEmpty() ? null : uuid("subject", subject).toString();
        // Each filter only narrows this catalog's records, which select on the audit catalog lets the caller read
        // whole: none reaches another catalog's (backlog 169).
        EventsReader.Query query = new EventsReader.Query(catalog.name(), types(type),
                principal == null || principal.isBlank() ? null : principal,
                levels(path), branch(branch), about, since, until,
                Optional.ofNullable(pageToken).filter(t -> !t.isEmpty()).map(AuditReadRoutes::cursor), limit(limit));
        Optional<ResponseEntity<String>> limited = limited(request);
        if (limited.isPresent()) {
            return limited.get();
        }
        AuditFeed.Read read = readTable(() -> feed.page(query));
        trail.record(new AuditRead(catalogSecurable(catalog), read.page().rows().size(), request.actor()));
        Map<String, ArchiveCheck.Verified> verified = check.configured() ? check.verified() : Map.of();
        Map<String, List<ArchiveCheck.Range>> missing = check.configured() ? check.missing() : Map.of();
        ObjectNode body = Management.JSON.createObjectNode();
        body.put("catalog", catalog.name());
        ArrayNode records = body.putArray("records");
        read.page().rows().forEach(row -> records.add(record(row, verified, missing)));
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
        Optional<ArchiveCheck.Result> last = check.last();
        if (last.isEmpty()) {
            body.putNull("check");
            return GovernanceRoutes.json(200, body);
        }
        body.set("check", checkStatus(last.get()));
        return GovernanceRoutes.json(200, body);
    }

    /**
     * Where record {@code number} of server run {@code run} lies in the run's hash chain (backlog 272): the segment
     * holding it, its hash and its neighbours', whether its link holds, and whether the check has verified the chain
     * past it. {@code event-id} is the record's, so a record of another id under that number is not taken for it.
     */
    @GetMapping(path = ROOT + AUDIT + "/chains/{run}/records/{number}", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<String> chain(@PathVariable String run, @PathVariable String number,
                                 @RequestParam(name = "event-id", required = false) String eventId,
                                 HttpServletRequest http) {
        Request request = management.request(http);
        CatalogRecord audit = requireAuditReader(request, "readAuditChain");
        UUID chain = uuid("run", run);
        long n;
        try {
            n = Long.parseLong(number);
        } catch (NumberFormatException e) {
            n = 0;
        }
        if (n < 1 || !number.matches("[0-9]{1,18}")) {
            throw CatalogError.badRequest("number must be the record's number in its run, a whole number from 1, not "
                    + number);
        }
        UUID id = uuid("event-id", eventId == null ? "" : eventId);
        if (!check.configured()) {
            throw CatalogError.of(503, "ServiceUnavailableException",
                    "The audit archive is not configured on this server (nuna.audit.archive.bucket)");
        }
        Optional<ResponseEntity<String>> limited = limited(request);
        if (limited.isPresent()) {
            return limited.get();
        }
        Optional<ObjectLockArchive.Placement> placed;
        try {
            placed = check.place(chain, n, id);
        } catch (RuntimeException e) {
            LOG.warn("audit archive could not be read for run {} record {}", chain, n, e);
            throw CatalogError.of(503, "ServiceUnavailableException", "The audit archive cannot be read now; retry later");
        }
        trail.record(new AuditRead(catalogSecurable(audit), placed.isPresent() ? 1 : 0, request.actor()));
        ObjectNode body = Management.JSON.createObjectNode();
        body.put("chain", chain.toString());
        body.put("log-run", chain.toString());
        body.put("log-seq", n);
        body.put("event-id", id.toString());
        body.put("archived", placed.isPresent());
        if (placed.isEmpty()) {
            return GovernanceRoutes.json(200, body);
        }
        ObjectLockArchive.Placement place = placed.get();
        body.put("position", place.position());
        body.put("hash", place.hash());
        body.put("previous-hash", place.previous());
        body.put("next-hash", place.next().orElse(null));
        body.put("link-holds", place.holds());
        ArchiveCheck.Verified verified = check.verified().get(chain.toString());
        body.put("verified", verified != null && place.position() <= verified.head().sequence());
        body.put("checked-at", verified == null ? null : verified.at().toString());
        ObjectNode segment = body.putObject("segment");
        segment.put("key", place.segment().key());
        segment.put("first-position", place.segment().start().sequence() + 1);
        segment.put("last-position", place.segment().end().sequence());
        return GovernanceRoutes.json(200, body);
    }

    private static UUID uuid(String name, String value) {
        try {
            UUID parsed = UUID.fromString(value);
            if (parsed.toString().equals(value.toLowerCase(java.util.Locale.ROOT))) {
                return parsed;
            }
        } catch (IllegalArgumentException e) {
            // answered below
        }
        throw CatalogError.badRequest(name + " must be a UUID, not " + value);
    }

    /**
     * A check's outcome: its checkpoints, how many runs and records it knows of, and each chain with something to
     * show: one not yet done, or one with missing numbers, a problem or a failure. A run done and intact is counted, not
     * listed, so the answer stays small however many runs the archive holds.
     */
    static ObjectNode checkStatus(ArchiveCheck.Result result) {
        ObjectNode run = Management.JSON.createObjectNode();
        run.put("started-at", result.startedAt().toString());
        run.put("ended-at", result.endedAt().toString());
        run.put("intact", result.intact());
        run.put("complete", result.complete());
        ArchiveCheck.Checkpoints checkpoints = result.checkpoints();
        ObjectNode written = run.putObject("checkpoints");
        written.put("latest", checkpoints.latest().orElse(null));
        written.put("at", checkpoints.at().map(Instant::toString).orElse(null));
        written.put("interval", checkpoints.interval().toString());
        written.put("late", checkpoints.late());
        ArrayNode checkpointProblems = written.putArray("problems");
        checkpoints.problems().forEach(checkpointProblems::add);
        run.put("runs", result.chains().stream().filter(ArchiveCheck.ChainResult::run).count());
        run.put("runs-done", result.chains().stream().filter(c -> c.run() && c.verified().done()).count());
        run.put("records-verified", result.chains().stream().mapToLong(c -> c.verified().head().sequence()).sum());
        ArrayNode chains = run.putArray("chains");
        for (ArchiveCheck.ChainResult chain : result.chains()) {
            ArchiveCheck.Verified verified = chain.verified();
            if (verified.done() && chain.missing().isEmpty() && chain.problems().isEmpty()
                    && chain.failed().isEmpty()) {
                continue;
            }
            ObjectNode each = chains.addObject();
            each.put("chain", chain.chain());
            each.put("run", chain.run());
            each.put("records-verified", chain.records());
            each.put("sequence", verified.head().sequence());
            each.put("hash", verified.head().hash());
            each.put("segment", verified.segment());
            each.put("highest", verified.highest());
            each.put("through", verified.through());
            each.put("repeats", verified.repeats());
            each.put("ended", verified.ended());
            each.put("done", verified.done());
            each.put("advanced-at", verified.advancedAt().toString());
            ArrayNode missing = each.putArray("missing");
            chain.missing().forEach(range -> missing.addObject().put("from", range.from()).put("to", range.to()));
            each.put("unended", chain.unended());
            ArrayNode problems = each.putArray("problems");
            chain.problems().forEach(problems::add);
            each.put("failed", chain.failed().orElse(null));
        }
        return run;
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

    /**
     * A feed row: its columns, its place in the topic, where it was logged, its chain's status, and the record itself.
     * The status is {@code verified} when the check has verified every number of the row's run up to the row's,
     * {@code unverified} when it has not (yet), {@code no-place} for a row that does not say where it was logged, and
     * {@code no-archive} where no archive is configured. An unverified row whose run the last check found missing a
     * record at or below its number gives that gap as {@code missing}: the range holding its own number, else the lowest
     * below it. Such a row stays unverified until the missing records reach the archive, which may be never.
     */
    private ObjectNode record(AuditRow row, Map<String, ArchiveCheck.Verified> verified,
                              Map<String, List<ArchiveCheck.Range>> missing) {
        ObjectNode node = Management.JSON.createObjectNode();
        node.put("event-id", row.eventId());
        node.put("time", row.time().toString());
        node.put("type", row.type());
        node.put("principal", row.principal());
        node.put("end-user", row.endUser());
        node.put("engine", row.engine());
        node.put("request-id", row.requestId());
        node.put("branch", row.branch());
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
        node.put("log-run", row.logRun());
        node.put("log-seq", row.logSequence());
        ObjectNode chain = node.putObject("chain");
        if (!check.configured()) {
            chain.put("status", "no-archive");
        } else if (row.logRun() == null) {
            // From the topic, or from a server that stamped none: the table cannot say which chain holds it.
            chain.put("status", "no-place");
        } else {
            ArchiveCheck.Verified run = verified.get(row.logRun());
            long n = row.logSequence();
            boolean through = run != null && n <= run.through();
            chain.put("status", through ? "verified" : "unverified");
            chain.put("checked-at", run == null ? null : run.at().toString());
            List<ArchiveCheck.Range> gaps = through ? List.of() : missing.getOrDefault(row.logRun(), List.of());
            gaps.stream().filter(r -> r.from() <= n && n <= r.to()).findFirst()
                    .or(() -> gaps.stream().filter(r -> r.to() < n).min(Comparator.comparingLong(ArchiveCheck.Range::from)))
                    .ifPresent(r -> chain.putObject("missing").put("from", r.from()).put("to", r.to()));
        }
        try {
            node.set("event", Management.JSON.readTree(EventCodec.encode(AuditRows.event(row))));
        } catch (IllegalArgumentException e) {
            // A type or payload this server does not know, from a newer server: its columns still say what it was.
            node.putNull("event");
        }
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

    /** A catalog ref's name, or main, as a prefix names it after its {@code @}; null for any. */
    private static String branch(String branch) {
        if (branch == null || branch.isEmpty()) {
            return null;
        }
        if (branch.isBlank() || branch.indexOf('@') >= 0 || branch.length() > MAX_BRANCH) {
            throw CatalogError.badRequest("branch must be a branch's or tag's name, or main, without its @, not "
                    + branch);
        }
        return branch;
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
