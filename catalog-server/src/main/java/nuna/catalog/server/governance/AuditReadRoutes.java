package nuna.catalog.server.governance;

import static nuna.catalog.server.governance.Management.CATALOG;
import static nuna.catalog.server.governance.Management.NAMESPACE;
import static nuna.catalog.server.governance.Management.TABLE;
import static nuna.catalog.server.governance.Management.VIEW;

import nuna.catalog.audit.AuditRead;
import nuna.catalog.core.CatalogError;
import nuna.catalog.security.Privilege;
import nuna.catalog.server.governance.Management.Request;
import nuna.catalog.server.governance.Management.Target;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.Comparator;
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
 * The reasons are in build-plan.md, UI v1.
 */
@RestController
class AuditReadRoutes {

    private static final Logger LOG = LoggerFactory.getLogger(AuditReadRoutes.class);
    private static final String AUDIT = "/audit";
    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 200;

    private final Management management;
    private final ManagementGuard guard;
    private final RecentAudit recent;
    private final AuditTrail trail;

    AuditReadRoutes(Management management, ManagementGuard guard, RecentAudit recent, AuditTrail trail) {
        this.management = management;
        this.guard = guard;
        this.recent = recent;
        this.trail = trail;
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
