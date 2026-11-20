package nuna.catalog.server.governance;

import static nuna.catalog.server.governance.Management.CATALOG;
import static nuna.catalog.server.governance.Management.NAMESPACE;
import static nuna.catalog.server.governance.Management.TABLE;
import static nuna.catalog.server.governance.Management.VIEW;

import nuna.catalog.audit.EventData;
import nuna.catalog.audit.GovernanceChange;
import nuna.catalog.audit.HoldChange;
import nuna.catalog.audit.LabelChange;
import nuna.catalog.core.CatalogError;
import nuna.catalog.core.commit.GovernanceChain;
import nuna.catalog.security.Privilege;
import nuna.catalog.server.governance.Management.Request;
import nuna.catalog.server.governance.Management.Target;
import nuna.catalog.store.CatalogStore;
import nuna.catalog.store.GovernanceAttribute;
import nuna.catalog.store.LegalHold;
import nuna.catalog.store.Securable;
import nuna.catalog.store.WriteResult;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The governance management API (D24, D27, D30, D37) on a catalog, namespace, table or view: the
 * attributes the governance tool pushes, with their provenance; legal holds; the maintenance policy;
 * and the DR freeze. Every change writes its audit record to the outbox with the change itself.
 * Each route asks {@link ManagementGuard} once its target is resolved, before reading the body.
 */
@RestController
class GovernanceRoutes {

    private static final String GOVERNANCE = "/governance";
    private static final String ATTRIBUTE = "/attributes/{attribute}";
    private static final String HOLDS = "/holds";
    private static final String HOLD = "/holds/{holdId}";
    private static final String POLICY = "/maintenance-policy";
    private static final String FREEZE = "/dr-freeze";

    /** The attributes the governance tool pushes (D27), by their path name. */
    static final Map<String, GovernanceAttribute.Kind> PUSHED = Map.of(
            "classification", GovernanceAttribute.Kind.CLASSIFICATION,
            "owner", GovernanceAttribute.Kind.OWNER,
            "retention-class", GovernanceAttribute.Kind.RETENTION_CLASS,
            "regulated", GovernanceAttribute.Kind.REGULATED,
            "row-column-policy", GovernanceAttribute.Kind.ROW_COLUMN_POLICY);

    private static final String POLICY_ATTRIBUTE = "maintenance-policy";
    private static final String FREEZE_ATTRIBUTE = "dr-freeze";
    private static final String API_PROVENANCE = "management-api";
    private static final int MAX_VALUE = 1000;

    private final CatalogStore store;
    private final Management management;
    private final ManagementGuard guard;
    private final GovernanceSettings settings;
    private final Clock clock;

    GovernanceRoutes(CatalogStore store, Management management, ManagementGuard guard, GovernanceSettings settings,
                     Clock clock) {
        this.store = store;
        this.management = management;
        this.guard = guard;
        this.settings = settings;
        this.clock = clock;
    }

    @GetMapping(path = {CATALOG + GOVERNANCE, NAMESPACE + GOVERNANCE, TABLE + GOVERNANCE, VIEW + GOVERNANCE},
            produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<String> governance(@PathVariable("catalog") String prefix,
                                      @PathVariable(required = false) String namespace,
                                      @PathVariable(required = false) String table,
                                      @PathVariable(required = false) String view,
                                      HttpServletRequest http) {
        Target target = management.target(prefix, namespace, table, view);
        guard.require(management.request(http), target, "loadGovernance", Privilege.DESCRIBE);
        ObjectNode body = Management.JSON.createObjectNode();
        body.set("target", securable(target.securable()));
        ArrayNode attributes = body.putArray("attributes");
        ObjectNode effective = Management.JSON.createObjectNode();
        for (GovernanceAttribute attribute : target.chain().attributes()) {
            attributes.add(attribute(attribute));
            effective.putIfAbsent(name(attribute.kind()), effective.stringNode(attribute.value()));
        }
        body.set("effective", effective);
        ArrayNode holds = body.putArray("holds");
        target.chain().holds().forEach(hold -> holds.add(hold(hold)));
        return json(200, body);
    }

    /** Sets one pushed attribute (D27): {@code {"value": ..., "provenance": ...}}; provenance is required. */
    @PutMapping(path = {CATALOG + ATTRIBUTE, NAMESPACE + ATTRIBUTE, TABLE + ATTRIBUTE, VIEW + ATTRIBUTE},
            produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<String> setAttribute(@PathVariable("catalog") String prefix,
                                        @PathVariable(required = false) String namespace,
                                        @PathVariable(required = false) String table,
                                        @PathVariable(required = false) String view,
                                        @PathVariable String attribute,
                                        @RequestBody(required = false) String body,
                                        HttpServletRequest http) {
        GovernanceAttribute.Kind kind = pushed(attribute);
        Target target = management.target(prefix, namespace, table, view);
        Request request = management.request(http);
        guard.require(request, target, "setGovernanceAttribute", Privilege.MANAGE_TAGS);
        JsonNode json = Management.body(body);
        String value = Management.text(json, "value", MAX_VALUE);
        String provenance = Management.text(json, "provenance", MAX_VALUE);
        check(kind, value);
        return put(target, kind, attribute, value, provenance, request);
    }

    @DeleteMapping({CATALOG + ATTRIBUTE, NAMESPACE + ATTRIBUTE, TABLE + ATTRIBUTE, VIEW + ATTRIBUTE})
    ResponseEntity<String> removeAttribute(@PathVariable("catalog") String prefix,
                                           @PathVariable(required = false) String namespace,
                                           @PathVariable(required = false) String table,
                                           @PathVariable(required = false) String view,
                                           @PathVariable String attribute,
                                           HttpServletRequest http) {
        GovernanceAttribute.Kind kind = pushed(attribute);
        Target target = management.target(prefix, namespace, table, view);
        Request request = management.request(http);
        guard.require(request, target, "removeGovernanceAttribute", Privilege.MANAGE_TAGS);
        return remove(target, kind, attribute, request);
    }

    /** Places a legal hold (D24): {@code {"reason": ...}}. The hold covers everything under the target. */
    @PostMapping(path = {CATALOG + HOLDS, NAMESPACE + HOLDS, TABLE + HOLDS, VIEW + HOLDS},
            produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<String> placeHold(@PathVariable("catalog") String prefix,
                                     @PathVariable(required = false) String namespace,
                                     @PathVariable(required = false) String table,
                                     @PathVariable(required = false) String view,
                                     @RequestBody(required = false) String body,
                                     HttpServletRequest http) {
        Target target = management.target(prefix, namespace, table, view);
        Request request = management.request(http);
        guard.require(request, target, "placeHold", Privilege.MANAGE_TAGS);
        String reason = Management.text(Management.body(body), "reason", MAX_VALUE);
        Instant now = now();
        LegalHold hold = new LegalHold(UUID.randomUUID(), target.securable(), reason, request.actor().principal(), now);
        WriteResult result = Management.write("Placing a hold", () -> store.placeHold(hold, management.change(now,
                        new HoldChange(HoldChange.Action.PLACED, hold.id(), target.audit(), reason, request.actor()))),
                () -> store.holdsOn(java.util.Set.of(target.securable())).stream()
                        .anyMatch(h -> h.id().equals(hold.id())));
        return switch (result) {
            case WriteResult.Applied applied -> json(201, hold(hold));
            case WriteResult.Missing missing -> throw gone(target);
            default -> throw new IllegalStateException("placeHold answered " + result);
        };
    }

    /** Releases a hold placed on this target; a hold on another level is released there. */
    @DeleteMapping({CATALOG + HOLD, NAMESPACE + HOLD, TABLE + HOLD, VIEW + HOLD})
    ResponseEntity<String> releaseHold(@PathVariable("catalog") String prefix,
                                       @PathVariable(required = false) String namespace,
                                       @PathVariable(required = false) String table,
                                       @PathVariable(required = false) String view,
                                       @PathVariable String holdId,
                                       HttpServletRequest http) {
        Target target = management.target(prefix, namespace, table, view);
        Request request = management.request(http);
        guard.require(request, target, "releaseHold", Privilege.MANAGE_TAGS);
        UUID id = uuid(holdId);
        LegalHold hold = store.holdsOn(java.util.Set.of(target.securable())).stream()
                .filter(h -> h.id().equals(id))
                .findFirst()
                .orElseThrow(() -> CatalogError.of(404, "NoSuchHoldException",
                        "No hold " + holdId + " on this " + kind(target.securable())));
        Instant now = now();
        WriteResult result = Management.write("Releasing a hold", () -> store.releaseHold(id, management.change(now,
                        new HoldChange(HoldChange.Action.RELEASED, id, target.audit(), hold.reason(), request.actor()))),
                () -> store.holdsOn(java.util.Set.of(target.securable())).stream().noneMatch(h -> h.id().equals(id)));
        return switch (result) {
            case WriteResult.Applied applied -> ResponseEntity.noContent().build();
            case WriteResult.Missing missing -> throw CatalogError.of(404, "NoSuchHoldException",
                    "No hold " + holdId + " on this " + kind(target.securable()));
            default -> throw new IllegalStateException("releaseHold answered " + result);
        };
    }

    /**
     * The maintenance policy of the target: its own keys, and every key resolved through the levels
     * above it, the deployment's defaults and the guardrails, each with where its value came from.
     */
    @GetMapping(path = {CATALOG + POLICY, NAMESPACE + POLICY, TABLE + POLICY, VIEW + POLICY},
            produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<String> policy(@PathVariable("catalog") String prefix,
                                  @PathVariable(required = false) String namespace,
                                  @PathVariable(required = false) String table,
                                  @PathVariable(required = false) String view,
                                  HttpServletRequest http) {
        Target target = management.target(prefix, namespace, table, view);
        guard.require(management.request(http), target, "loadMaintenancePolicy", Privilege.DESCRIBE);
        Map<String, String> own = Map.of();
        for (GovernanceAttribute attribute : target.chain().attributes()) {
            if (attribute.kind() == GovernanceAttribute.Kind.MAINTENANCE_POLICY
                    && attribute.target().equals(target.securable())) {
                own = parsePolicy(attribute.value());
            }
        }
        Map<String, MaintenancePolicy.Setting> resolved = resolvePolicy(settings, target.chain());
        ObjectNode body = Management.JSON.createObjectNode();
        body.set("target", securable(target.securable()));
        ObjectNode ownNode = body.putObject("policy");
        own.forEach(ownNode::put);
        ObjectNode resolvedNode = body.putObject("resolved");
        resolved.forEach((key, setting) -> {
            ObjectNode node = resolvedNode.putObject(key);
            node.put("value", setting.value());
            node.put("source", setting.source());
            if (setting.reason() != null) {
                node.put("reason", setting.reason());
            }
        });
        return json(200, body);
    }

    /**
     * Sets the target's own maintenance policy, replacing its previous keys: a JSON object of keys to
     * values. Keys it leaves out are inherited. Values the guardrails forbid outright are refused.
     */
    @PutMapping(path = {CATALOG + POLICY, NAMESPACE + POLICY, TABLE + POLICY, VIEW + POLICY},
            produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<String> setPolicy(@PathVariable("catalog") String prefix,
                                     @PathVariable(required = false) String namespace,
                                     @PathVariable(required = false) String table,
                                     @PathVariable(required = false) String view,
                                     @RequestBody(required = false) String body,
                                     HttpServletRequest http) {
        Target target = management.target(prefix, namespace, table, view);
        Request request = management.request(http);
        guard.require(request, target, "setMaintenancePolicy", Privilege.MODIFY);
        JsonNode json = Management.body(body);
        Map<String, String> keys = new TreeMap<>();
        for (Map.Entry<String, JsonNode> field : json.properties()) {
            JsonNode value = field.getValue();
            if (!value.isValueNode() || value.isNull()) {
                throw CatalogError.badRequest("Maintenance policy key " + field.getKey() + " needs a plain value");
            }
            keys.put(field.getKey(), value.asString());
        }
        if (keys.isEmpty()) {
            throw CatalogError.badRequest("A maintenance policy sets at least one key; DELETE removes the policy");
        }
        String canonical = policyJson(MaintenancePolicy.canonical(keys, settings));
        return put(target, GovernanceAttribute.Kind.MAINTENANCE_POLICY, POLICY_ATTRIBUTE, canonical, API_PROVENANCE,
                request);
    }

    @DeleteMapping({CATALOG + POLICY, NAMESPACE + POLICY, TABLE + POLICY, VIEW + POLICY})
    ResponseEntity<String> removePolicy(@PathVariable("catalog") String prefix,
                                        @PathVariable(required = false) String namespace,
                                        @PathVariable(required = false) String table,
                                        @PathVariable(required = false) String view,
                                        HttpServletRequest http) {
        Target target = management.target(prefix, namespace, table, view);
        Request request = management.request(http);
        guard.require(request, target, "removeMaintenancePolicy", Privilege.MODIFY);
        return remove(target, GovernanceAttribute.Kind.MAINTENANCE_POLICY, POLICY_ATTRIBUTE, request);
    }

    /**
     * Freezes writes and cleanup under the target for a DR reconcile (D37): {@code {"reason": ...}}.
     * Commits, creates, drops, renames, undrops and purges are refused until the freeze is lifted.
     */
    @PutMapping(path = {CATALOG + FREEZE, NAMESPACE + FREEZE, TABLE + FREEZE, VIEW + FREEZE},
            produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<String> freeze(@PathVariable("catalog") String prefix,
                                  @PathVariable(required = false) String namespace,
                                  @PathVariable(required = false) String table,
                                  @PathVariable(required = false) String view,
                                  @RequestBody(required = false) String body,
                                  HttpServletRequest http) {
        Target target = management.target(prefix, namespace, table, view);
        Request request = management.request(http);
        guard.requireOperatorOn(request, target, "setDrFreeze");
        String reason = Management.text(Management.body(body), "reason", MAX_VALUE);
        return put(target, GovernanceAttribute.Kind.DR_FREEZE, FREEZE_ATTRIBUTE, reason, API_PROVENANCE, request);
    }

    @DeleteMapping({CATALOG + FREEZE, NAMESPACE + FREEZE, TABLE + FREEZE, VIEW + FREEZE})
    ResponseEntity<String> thaw(@PathVariable("catalog") String prefix,
                                @PathVariable(required = false) String namespace,
                                @PathVariable(required = false) String table,
                                @PathVariable(required = false) String view,
                                HttpServletRequest http) {
        Target target = management.target(prefix, namespace, table, view);
        Request request = management.request(http);
        guard.requireOperatorOn(request, target, "liftDrFreeze");
        return remove(target, GovernanceAttribute.Kind.DR_FREEZE, FREEZE_ATTRIBUTE, request);
    }

    private ResponseEntity<String> put(Target target, GovernanceAttribute.Kind kind, String attribute, String value,
                                       String provenance, Request request) {
        Instant now = now();
        GovernanceAttribute record = new GovernanceAttribute(target.securable(), kind, value, provenance,
                request.actor().principal(), now);
        WriteResult result = Management.write("Setting " + attribute, () -> store.putAttribute(record,
                        management.change(now, events(new GovernanceChange(GovernanceChange.Action.SET, target.audit(),
                                attribute, value, provenance, request.actor()), kind, value))),
                () -> own(target, kind).filter(a -> a.value().equals(value) && a.setAt().equals(now)).isPresent());
        return switch (result) {
            case WriteResult.Applied applied -> json(200, attribute(record));
            case WriteResult.Unchanged unchanged -> json(200, attribute(own(target, kind).orElse(record)));
            case WriteResult.Missing missing -> throw gone(target);
            default -> throw new IllegalStateException("putAttribute answered " + result);
        };
    }

    private ResponseEntity<String> remove(Target target, GovernanceAttribute.Kind kind, String attribute,
                                          Request request) {
        if (own(target, kind).isEmpty()) {
            throw notSet(target, attribute);
        }
        Instant now = now();
        WriteResult result = Management.write("Removing " + attribute, () -> store.removeAttribute(target.securable(),
                        kind, management.change(now, events(new GovernanceChange(GovernanceChange.Action.REMOVED,
                                target.audit(), attribute, null, null, request.actor()), kind, null))),
                () -> own(target, kind).isEmpty());
        return switch (result) {
            case WriteResult.Applied applied -> ResponseEntity.noContent().build();
            case WriteResult.Unchanged unchanged -> ResponseEntity.noContent().build();
            case WriteResult.Missing missing -> throw notSet(target, attribute);
            default -> throw new IllegalStateException("removeAttribute answered " + result);
        };
    }

    /**
     * The audit record of an attribute change, and for an attribute load responses carry as a label (D27,
     * amended 2026-11-12) a label change for the change stream too: the level, the label and its new value
     * there (null when removed), without the provenance the audit record keeps.
     */
    private static List<EventData> events(GovernanceChange change, GovernanceAttribute.Kind kind, String value) {
        String label = GovernanceChain.LABELS.get(kind);
        return label == null ? List.of(change) : List.of(change, new LabelChange(change.target(), label, value,
                change.actor()));
    }

    /** The target's own value of {@code kind}, read fresh. */
    private Optional<GovernanceAttribute> own(Target target, GovernanceAttribute.Kind kind) {
        return store.attributesOf(java.util.Set.of(target.securable())).stream()
                .filter(a -> a.kind() == kind && a.target().equals(target.securable()))
                .findFirst();
    }

    private void check(GovernanceAttribute.Kind kind, String value) {
        switch (kind) {
            case RETENTION_CLASS -> {
                if (!settings.retentionClasses().containsKey(value)) {
                    throw CatalogError.badRequest("Unknown retention class " + value + "; configured classes: "
                            + new TreeMap<>(settings.retentionClasses()).keySet());
                }
            }
            case REGULATED, ROW_COLUMN_POLICY -> {
                if (!value.equals("true") && !value.equals("false")) {
                    throw CatalogError.badRequest(name(kind) + " is true or false, not " + value);
                }
            }
            default -> {
            }
        }
    }

    private static GovernanceAttribute.Kind pushed(String attribute) {
        GovernanceAttribute.Kind kind = PUSHED.get(attribute);
        if (kind == null) {
            throw CatalogError.of(404, "NoSuchAttributeException", "Unknown governance attribute " + attribute
                    + "; known attributes: " + new TreeMap<>(PUSHED).keySet());
        }
        return kind;
    }

    static String name(GovernanceAttribute.Kind kind) {
        return kind.name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    static String kind(Securable securable) {
        return securable.kind().name().toLowerCase(Locale.ROOT);
    }

    /**
     * The maintenance policy in force at the chain's target: every level's own keys, outermost first, over the
     * deployment's defaults, then the guardrails of its holds, DR freeze and retention class (D30).
     */
    static Map<String, MaintenancePolicy.Setting> resolvePolicy(GovernanceSettings settings, GovernanceChain chain) {
        List<Map.Entry<String, Map<String, String>>> levels = new ArrayList<>();
        for (GovernanceAttribute attribute : chain.attributes()) {
            if (attribute.kind() == GovernanceAttribute.Kind.MAINTENANCE_POLICY) {
                levels.add(new AbstractMap.SimpleEntry<>(label(attribute.target()), parsePolicy(attribute.value())));
            }
        }
        Collections.reverse(levels);
        Duration retentionMinimum = chain.nearest(GovernanceAttribute.Kind.RETENTION_CLASS)
                .flatMap(a -> settings.minimumAgeOf(a.value())).orElse(null);
        return MaintenancePolicy.resolve(settings, levels, chain.held(),
                chain.nearest(GovernanceAttribute.Kind.DR_FREEZE).isPresent(), retentionMinimum);
    }

    static String label(Securable securable) {
        return kind(securable) + ":" + securable.id();
    }

    static ObjectNode securable(Securable securable) {
        ObjectNode node = Management.JSON.createObjectNode();
        node.put("kind", kind(securable));
        node.put("id", securable.id().toString());
        return node;
    }

    private static ObjectNode attribute(GovernanceAttribute attribute) {
        ObjectNode node = Management.JSON.createObjectNode();
        node.put("attribute", name(attribute.kind()));
        node.put("level", label(attribute.target()));
        node.put("value", attribute.value());
        node.put("provenance", attribute.provenance());
        node.put("set-by", attribute.setBy());
        node.put("set-at", attribute.setAt().toString());
        return node;
    }

    private static ObjectNode hold(LegalHold hold) {
        ObjectNode node = Management.JSON.createObjectNode();
        node.put("id", hold.id().toString());
        node.put("level", label(hold.target()));
        node.put("reason", hold.reason());
        node.put("placed-by", hold.placedBy());
        node.put("placed-at", hold.placedAt().toString());
        return node;
    }

    static Map<String, String> parsePolicy(String json) {
        Map<String, String> keys = new LinkedHashMap<>();
        Management.JSON.readTree(json).properties().forEach(e -> keys.put(e.getKey(), e.getValue().asString()));
        return keys;
    }

    private static String policyJson(Map<String, String> keys) {
        ObjectNode node = Management.JSON.createObjectNode();
        keys.forEach(node::put);
        return Management.JSON.writeValueAsString(node);
    }

    private static UUID uuid(String id) {
        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            throw CatalogError.badRequest("Not an id: " + id);
        }
    }

    private static CatalogError gone(Target target) {
        return CatalogError.of(404, "NoSuchObjectException", "The " + kind(target.securable()) + " no longer exists");
    }

    private static CatalogError notSet(Target target, String attribute) {
        return CatalogError.of(404, "NoSuchAttributeException", attribute + " is not set on this "
                + kind(target.securable()));
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MILLIS);
    }

    static ResponseEntity<String> json(int status, JsonNode body) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON)
                .body(Management.JSON.writeValueAsString(body));
    }
}
