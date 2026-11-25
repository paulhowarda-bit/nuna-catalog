package nuna.catalog.server.governance;

import static nuna.catalog.server.governance.Management.CATALOG;

import nuna.catalog.objectstore.ObjectLocation;
import nuna.catalog.objectstore.StoreKind;
import nuna.catalog.security.Privilege;
import nuna.catalog.server.governance.Management.Request;
import nuna.catalog.server.governance.Management.Target;
import nuna.catalog.server.storage.CatalogStorage;
import nuna.catalog.store.CatalogStore;
import nuna.catalog.store.MaintenanceScan;
import nuna.catalog.store.Securable;
import nuna.catalog.store.WorkerStorage;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SortedSet;
import java.util.stream.Collectors;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Whether the maintenance worker can maintain a catalog, step by step (backlog 251), for the console's checklist
 * (backlog 252): its groups hold {@code write} on the catalog, its own storage identity covers every allowed prefix of
 * the catalog's storage profile, the store lets it list and delete there, and the catalog's policy turns a job on. The
 * first three come from the newest scan the worker reported with what it reaches (backlog 250); before one, they are
 * unknown. Needs {@code describe} on the catalog, as its other governance reads do; names (the worker's groups, the
 * catalog's profile and prefixes, what covers them) go only to those who may {@code modify} the catalog or manage its
 * grants, and to platform operators. Like those reads, it writes no audit record; a refusal is audited.
 */
@RestController
class ReadinessRoutes {

    static final String READINESS = CATALOG + "/maintenance/readiness";
    private static final String OPERATION = "loadMaintenanceReadiness";

    /** How one step stands. */
    enum State { DONE, MISSING, REFUSED, UNKNOWN }

    private final CatalogStore store;
    private final Management management;
    private final ManagementGuard guard;
    private final CatalogStorage storage;
    private final GovernanceSettings settings;

    ReadinessRoutes(CatalogStore store, Management management, ManagementGuard guard, CatalogStorage storage,
                    GovernanceSettings settings) {
        this.store = store;
        this.management = management;
        this.guard = guard;
        this.storage = storage;
        this.settings = settings;
    }

    /**
     * The four steps, each {@code done}, {@code missing}, {@code refused} or {@code unknown}, and when the worker last
     * reported what it reaches (docs/observability/table-health.md, "Readiness"). The listing is judged by the newest
     * scan that reported storage; the deletes by the newest such scan that has ended, since a scan reports its deletes
     * only at its end.
     */
    @GetMapping(path = READINESS, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<String> readiness(@PathVariable("catalog") String prefix, HttpServletRequest http) {
        Request request = management.request(http);
        Target target = management.target(request, OPERATION, Privilege.DESCRIBE, prefix, null, null, null);
        guard.require(request, target, OPERATION, Privilege.DESCRIBE);
        boolean names = guard.allows(request, target, OPERATION, Privilege.MODIFY)
                || guard.allows(request, target, OPERATION, Privilege.MANAGE_GRANTS) || guard.operator(request);
        List<MaintenanceScan> reached = store.listScans().stream().filter(scan -> scan.storage().isPresent()).toList();
        Optional<MaintenanceScan> last = reached.stream().findFirst();
        Optional<MaintenanceScan> ended = reached.stream().filter(scan -> scan.endedAt().isPresent()).findFirst();
        CatalogStorage.Bound profile = storage.forCatalog(target.catalog().id());
        List<String> prefixes = profile.profile().allowedPrefixes().stream().map(ObjectLocation::toString).toList();

        ObjectNode body = Management.JSON.createObjectNode();
        body.put("catalog", target.catalog().name());
        body.put("names", names);
        last.ifPresent(scan -> body.put("reported-at", scan.endedAt().orElse(scan.startedAt()).toString()));
        body.set("grant", grantStep(target, last, names));
        body.set("storage", storageStep(profile, prefixes, last, names));
        body.set("store", storeStep(prefixes, last, ended, names));
        body.set("policy", policyStep(target));
        return GovernanceRoutes.json(200, body);
    }

    /**
     * {@code done} when a group the worker's token last carried holds a privilege giving {@code write} on the catalog
     * itself, which reaches every table in it: the grants the authorizer reads for the catalog, in one read.
     */
    private ObjectNode grantStep(Target target, Optional<MaintenanceScan> last, boolean names) {
        Optional<SortedSet<String>> groups = last.flatMap(MaintenanceScan::groups);
        if (groups.isEmpty()) {
            return state(State.UNKNOWN);
        }
        Set<String> writers = groups.get().isEmpty() ? Set.of() : store.grantsFor(
                        Set.of(new Securable(Securable.Kind.CATALOG, target.catalog().id())), groups.get()).stream()
                .filter(held -> writes(held.grant().privilege())).map(held -> held.grant().group())
                .collect(Collectors.toSet());
        ObjectNode node = state(writers.isEmpty() ? State.MISSING : State.DONE);
        if (names) {
            ArrayNode named = node.putArray("groups");
            groups.get().forEach(group -> named.addObject().put("group", group).put("write", writers.contains(group)));
        }
        return node;
    }

    /** Whether a stored privilege gives {@code write}; one from a newer version gives nothing, as to the authorizer. */
    private static boolean writes(String privilege) {
        try {
            return Privilege.fromWireName(privilege).implies(Privilege.WRITE);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * {@code done} when every allowed prefix of the catalog's profile is, or lies beneath, a prefix the worker reported,
     * matched as the worker matches the locations it lists and deletes ({@link MaintenanceScan#covering}).
     */
    private static ObjectNode storageStep(CatalogStorage.Bound profile, List<String> prefixes,
                                          Optional<MaintenanceScan> last, boolean names) {
        ObjectNode node = state(last.isEmpty() ? State.UNKNOWN
                : prefixes.stream().allMatch(prefix -> last.get().covering(prefix).isPresent()) ? State.DONE
                : State.MISSING);
        if (names) {
            node.put("profile", profile.name());
            if (profile.profile().kind() != StoreKind.S3) {
                node.put("endpoint", profile.profile().endpoint().toString());
            }
            ArrayNode named = node.putArray("prefixes");
            for (String prefix : prefixes) {
                ObjectNode each = named.addObject().put("prefix", prefix);
                last.flatMap(scan -> scan.covering(prefix)).ifPresent(covering -> {
                    ObjectNode by = each.putObject("covered-by");
                    by.put("kind", covering.store().kind());
                    covering.store().endpoint().ifPresent(endpoint -> by.put("endpoint", endpoint));
                    by.put("prefix", covering.prefix().location());
                });
            }
        }
        return node;
    }

    /**
     * {@code done} when the store let the worker list beneath every allowed prefix and refused none of its deletes
     * there; {@code refused} with the store's code when it refused either; {@code unknown} while a prefix is not
     * covered, or when the store did not answer the listing, with the client's failure as the code. A refusal
     * anywhere outweighs what is unknown elsewhere.
     */
    private static ObjectNode storeStep(List<String> prefixes, Optional<MaintenanceScan> last,
                                        Optional<MaintenanceScan> ended, boolean names) {
        ArrayNode named = Management.JSON.createArrayNode();
        List<Judged> judged = new ArrayList<>();
        for (String prefix : prefixes) {
            Optional<WorkerStorage.Prefix> listed = last.flatMap(scan -> scan.covering(prefix))
                    .map(WorkerStorage.Covering::prefix);
            Optional<WorkerStorage.Prefix> deleted = ended.flatMap(scan -> scan.covering(prefix))
                    .map(WorkerStorage.Covering::prefix);
            ObjectNode each = named.addObject().put("prefix", prefix);
            listed.ifPresent(reached -> {
                each.put("listed", label(reached.listed()));
                reached.listCode().ifPresent(code -> each.put("list-code", code));
            });
            deleted.ifPresent(reached -> {
                each.put("deleted", reached.deleted());
                each.put("delete-refused", reached.deleteRefused());
                each.put("delete-failed", reached.deleteFailed());
                reached.deleteCode().ifPresent(code -> each.put("delete-code", code));
            });
            if (listed.isEmpty()) {
                judged.add(new Judged(State.UNKNOWN, Optional.empty()));
            } else if (listed.get().listed() == WorkerStorage.Listed.REFUSED) {
                judged.add(new Judged(State.REFUSED, listed.get().listCode()));
            } else if (deleted.isPresent() && deleted.get().deleteRefused() > 0) {
                judged.add(new Judged(State.REFUSED, deleted.get().deleteCode()));
            } else if (listed.get().listed() == WorkerStorage.Listed.FAILED) {
                judged.add(new Judged(State.UNKNOWN, listed.get().listCode()));
            }
        }
        State state = judged.stream().anyMatch(each -> each.state() == State.REFUSED) ? State.REFUSED
                : judged.isEmpty() ? State.DONE : State.UNKNOWN;
        ObjectNode node = state(state);
        judged.stream().filter(each -> each.state() == state).flatMap(each -> each.code().stream()).findFirst()
                .ifPresent(code -> node.put("code", code));
        if (names) {
            node.set("prefixes", named);
        }
        return node;
    }

    /** How the store step stands beneath one prefix, and the code that says why. */
    private record Judged(State state, Optional<String> code) {
    }

    /** {@code done} when the catalog's resolved maintenance policy turns at least one job on, with the jobs it does. */
    private ObjectNode policyStep(Target target) {
        Map<String, MaintenancePolicy.Setting> resolved = GovernanceRoutes.resolvePolicy(settings, target.chain());
        List<String> on = MaintenanceRoutes.JOBS.stream().filter(job -> "true".equals(resolved.get(job).value()))
                .map(job -> job.substring(0, job.indexOf('.'))).toList();
        ObjectNode node = state(on.isEmpty() ? State.MISSING : State.DONE);
        ArrayNode jobs = node.putArray("jobs");
        on.forEach(jobs::add);
        return node;
    }

    private static ObjectNode state(State state) {
        return Management.JSON.createObjectNode().put("state", label(state));
    }

    private static String label(Enum<?> constant) {
        return constant.name().toLowerCase(Locale.ROOT);
    }
}
