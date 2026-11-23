package nuna.catalog.server.governance;

import static nuna.catalog.server.governance.Management.TABLE;

import nuna.catalog.audit.GovernanceChange;
import nuna.catalog.core.CatalogError;
import nuna.catalog.security.Privilege;
import nuna.catalog.server.governance.Management.Request;
import nuna.catalog.server.governance.Management.Target;
import nuna.catalog.server.security.Denials;
import nuna.catalog.store.CatalogObject;
import nuna.catalog.store.CatalogStore;
import nuna.catalog.store.MaintenanceRequest;
import nuna.catalog.store.WriteResult;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * A table's maintenance on request (D30, amended 2026-11-20; backlog 114; docs/observability/table-health.md,
 * "Maintenance on request"): a caller with {@code modify} on a table asks for its compaction or its orphan scan to run
 * now, outside the nightly cleanup window too. The catalog keeps the request; the work list carries it in a section of
 * its own, and the worker takes it at its next scan and reports it started, which clears it. One waits per table and
 * job: asking again while it waits changes nothing.
 *
 * <p>The catalog's guards stay: a request is refused when a DR freeze covers the table (403, as every freeze refusal
 * is), or when the table's resolved policy does not run that job, a legal hold's guardrail included (409). A requested
 * compaction runs whether or not the worker's assessment says the table needs one. Every request is audited as a
 * governance change of the table ({@code maintenance-request}, the job as its value) and every refusal as a denial.
 */
@RestController
class MaintenanceRequests {

    static final String REQUESTS = TABLE + "/maintenance/requests";
    static final String OPERATION = "requestTableMaintenance";
    /** The audit record's attribute: a request is a governance change of the table. */
    static final String ATTRIBUTE = "maintenance-request";
    private static final String PROVENANCE = "management-api";

    private final CatalogStore store;
    private final Management management;
    private final ManagementGuard guard;
    private final GovernanceSettings settings;
    private final Denials denials;
    private final Clock clock;

    MaintenanceRequests(CatalogStore store, Management management, ManagementGuard guard, GovernanceSettings settings,
                        Denials denials, Clock clock) {
        this.store = store;
        this.management = management;
        this.guard = guard;
        this.settings = settings;
        this.denials = denials;
        this.clock = clock;
    }

    /**
     * Asks for one of the table's heavy jobs to run now: {@code {"job": "compaction" | "orphan-scan"}}. Answers 202 with
     * the request waiting, the one asked for already when one waits; 400 for another job, 403 without {@code modify}
     * or under a DR freeze, 404 for a caller who holds nothing on the table, 409 when the policy does not run the job.
     */
    @PostMapping(path = REQUESTS, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<String> request(@PathVariable("catalog") String prefix, @PathVariable String namespace,
                                   @PathVariable String table, @RequestBody(required = false) String body,
                                   HttpServletRequest http) {
        Request request = management.request(http);
        Target target = management.target(request, OPERATION, Privilege.MODIFY, prefix, namespace, table, null);
        guard.require(request, target, OPERATION, Privilege.MODIFY);
        MaintenanceRequest.Job job = job(Management.body(body));
        CatalogObject object = target.object().orElseThrow();
        String qualified = String.join(".", target.audit().path());
        guard.requireUnfrozen(request, target.chain(), target.audit(), OPERATION, "Table " + qualified);
        refuseUnlessRun(request, target, job, qualified);
        Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        MaintenanceRequest asked = new MaintenanceRequest(object.id(), job, request.actor().principal(), now,
                Optional.empty());
        WriteResult result = Management.write("Requesting the " + label(job) + " of " + qualified,
                () -> store.requestMaintenance(asked, management.change(now, new GovernanceChange(
                        GovernanceChange.Action.SET, target.audit(), ATTRIBUTE, label(job), PROVENANCE,
                        request.actor()))),
                () -> kept(object, job).filter(kept -> kept.requestedAt().equals(now)).isPresent());
        return switch (result) {
            case WriteResult.Applied applied -> GovernanceRoutes.json(202, node(asked));
            case WriteResult.Unchanged unchanged -> GovernanceRoutes.json(202, node(kept(object, job).orElse(asked)));
            case WriteResult.Missing missing -> throw target.missing();
            default -> throw new IllegalStateException("requestMaintenance answered " + result);
        };
    }

    /**
     * Refuses a job the table's resolved policy does not run (409): turned off at some level, or by a legal hold's
     * guardrail, which stops an orphan scan. Audited as a denial, its reason {@code legal_hold} or
     * {@code maintenance_policy}.
     */
    private void refuseUnlessRun(Request request, Target target, MaintenanceRequest.Job job, String qualified) {
        String key = key(job);
        MaintenancePolicy.Setting setting = GovernanceRoutes.resolvePolicy(settings, target.chain()).get(key);
        if ("true".equals(setting.value())) {
            return;
        }
        boolean held = "guardrail".equals(setting.source());
        String message = held
                ? "Table " + qualified + " is under legal hold: nothing may be removed, so no " + label(job) + " runs"
                : "The maintenance policy of table " + qualified + " does not run " + label(job) + ": " + key
                        + " is false, set by " + setting.source();
        denials.refuse(request.actor(), OPERATION, target.audit(), null, held ? "legal_hold" : "maintenance_policy",
                message);
        throw CatalogError.of(409, "MaintenanceNotAllowedException", message);
    }

    private Optional<MaintenanceRequest> kept(CatalogObject table, MaintenanceRequest.Job job) {
        return store.listMaintenanceRequests(table.id()).stream().filter(kept -> kept.job() == job).findFirst();
    }

    /** The policy key that turns the job on. */
    static String key(MaintenanceRequest.Job job) {
        return job == MaintenanceRequest.Job.COMPACTION ? MaintenancePolicy.COMPACTION_ENABLED
                : MaintenancePolicy.ORPHAN_ENABLED;
    }

    /** A job as the routes write it: {@code compaction}, {@code orphan-scan}. */
    static String label(MaintenanceRequest.Job job) {
        return job.name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    /** The job a label names; none for any other text. */
    static Optional<MaintenanceRequest.Job> job(String label) {
        for (MaintenanceRequest.Job job : MaintenanceRequest.Job.values()) {
            if (label(job).equals(label)) {
                return Optional.of(job);
            }
        }
        return Optional.empty();
    }

    private static MaintenanceRequest.Job job(JsonNode body) {
        JsonNode value = body.get("job");
        return Optional.ofNullable(value).filter(JsonNode::isString).flatMap(text -> job(text.asString()))
                .orElseThrow(() -> CatalogError.badRequest("Field job is compaction or orphan-scan"));
    }

    /** A request as the routes show it: its job, who asked and when, and whether it waits or has started. */
    static ObjectNode node(MaintenanceRequest request) {
        ObjectNode node = Management.JSON.createObjectNode();
        node.put("job", label(request.job()));
        node.put("requested-by", request.requestedBy());
        node.put("requested-at", request.requestedAt().toString());
        node.put("state", request.waiting() ? "waiting" : "started");
        request.startedAt().ifPresent(at -> node.put("started-at", at.toString()));
        return node;
    }

    /** The table's requests as its health shows them, by job. */
    static ArrayNode nodes(List<MaintenanceRequest> requests) {
        ArrayNode list = Management.JSON.createArrayNode();
        requests.forEach(request -> list.add(node(request)));
        return list;
    }
}
