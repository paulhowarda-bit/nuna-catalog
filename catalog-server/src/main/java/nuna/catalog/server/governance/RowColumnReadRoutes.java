package nuna.catalog.server.governance;

import static nuna.catalog.server.governance.Management.CATALOG;
import static nuna.catalog.server.governance.Management.TABLE;

import nuna.catalog.audit.Export;
import nuna.catalog.audit.ObjectRead;
import nuna.catalog.core.CatalogError;
import nuna.catalog.core.Names;
import nuna.catalog.core.commit.TableFiles;
import nuna.catalog.core.principal.Caller;
import nuna.catalog.security.Authorizer;
import nuna.catalog.security.Privilege;
import nuna.catalog.security.RowColumnPolicy;
import nuna.catalog.server.governance.Management.Request;
import nuna.catalog.server.governance.Management.Target;
import nuna.catalog.server.security.AuditCatalogSettings;
import nuna.catalog.server.storage.CatalogStorage;
import nuna.catalog.store.CatalogRecord;
import nuna.catalog.store.CatalogStore;
import nuna.catalog.store.GovernanceAttribute;
import nuna.catalog.store.Pointer;
import nuna.catalog.store.PointerKey;
import nuna.catalog.store.RowColumnRule;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * What the console's row filters and column masks read (D20, backlog 242), besides the grant admin's list of a
 * table's rules ({@code RuleRoutes}). Each answer is decided for the caller; a refusal is audited as a denial and a
 * caller that holds nothing on the table is answered as if it did not exist (D18), as on every management route. No
 * filter or mask expression reaches a caller without {@code manage-grants}.
 * <ul>
 *     <li>{@code GET .../tables/{table}/schema}: the table's current schema and properties, for anyone who may
 *     describe it, flagged or not; no location, snapshot or file, and no storage credentials, so it is no storage
 *     access and a flagged table answers it as any other (a mask editor picks its columns from it). The metadata
 *     file is read by the catalog itself, checked against the pointer's hash, and the read is audited as an
 *     {@link ObjectRead}, as the export's is.</li>
 *     <li>{@code GET .../tables/{table}/row-column-rules/mine}: what the caller's own rules do to them on the table,
 *     for anyone who may select it: whether their rows are all there, filtered or none, and which columns are masked
 *     for them, combined as Trino gets them ({@link RowColumnPolicy}), without the expressions.</li>
 *     <li>{@code GET /management/v1/catalogs/{catalog}/row-column-rules/export}: every rule in the catalog, for access
 *     reviews. A caller with {@code manage-grants} on the catalog gets the expressions; an audit reader
 *     ({@code select} on the audit catalog, D23 as amended 2026-11-22) gets each rule without them, as the audit feed
 *     answers them for any catalog; anyone else is refused as on the grant routes.</li>
 * </ul>
 */
@RestController
class RowColumnReadRoutes {

    static final String SCHEMA = TABLE + "/schema";
    static final String MINE = TABLE + "/row-column-rules/mine";
    static final String EXPORT = CATALOG + "/row-column-rules/export";

    /** The catalog's own read of a table's metadata file, by its catalog. */
    interface Metadata {
        TableFiles.Read read(UUID catalogId, String location, String sha256);
    }

    private final CatalogStore store;
    private final Management management;
    private final ManagementGuard guard;
    private final Authorizer authorizer;
    private final AuditCatalogSettings auditTables;
    private final AuditTrail trail;
    private final Metadata metadata;

    @Autowired
    RowColumnReadRoutes(CatalogStore store, Management management, ManagementGuard guard, Authorizer authorizer,
                        AuditCatalogSettings auditTables, AuditTrail trail, CatalogStorage storage) {
        this(store, management, guard, authorizer, auditTables, trail,
                (catalogId, location, sha256) -> storage.forCatalog(catalogId).files().read(location, sha256));
    }

    RowColumnReadRoutes(CatalogStore store, Management management, ManagementGuard guard, Authorizer authorizer,
                        AuditCatalogSettings auditTables, AuditTrail trail, Metadata metadata) {
        this.store = store;
        this.management = management;
        this.guard = guard;
        this.authorizer = authorizer;
        this.auditTables = auditTables;
        this.trail = trail;
        this.metadata = metadata;
    }

    /**
     * {@code {"target": {"kind", "id"}, "format-version", "schema": <the current Iceberg schema>, "properties": {...}}}.
     * Needs describe on the table.
     */
    @GetMapping(path = SCHEMA, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<String> schema(@PathVariable("catalog") String prefix, @PathVariable String namespace,
                                  @PathVariable String table, HttpServletRequest http) {
        Request request = management.request(http);
        Target target = management.target(request, "loadTableSchema", Privilege.DESCRIBE, prefix, namespace, table,
                null);
        guard.require(request, target, "loadTableSchema", Privilege.DESCRIBE);
        Pointer pointer = store.readPointer(target.object().orElseThrow().mainKey()).orElseThrow(target::missing);
        TableFiles.Read read = metadata.read(target.catalog().id(), pointer.metadataLocation(), pointer.sha256());
        if (!read.intact()) {
            throw CatalogError.of(500, "MetadataIntegrityException", "The metadata file of the table does not match"
                    + " its recorded hash");
        }
        JsonNode file = Management.JSON.readTree(read.json());
        trail.record(new ObjectRead(target.audit(), PointerKey.MAIN, ReadAudit.state(pointer), request.actor()));
        ObjectNode body = Management.JSON.createObjectNode();
        body.set("target", GovernanceRoutes.securable(target.securable()));
        body.put("format-version", file.path("format-version").asInt(1));
        body.set("schema", currentSchema(file));
        JsonNode properties = file.path("properties");
        body.set("properties", properties.isObject() ? properties : Management.JSON.createObjectNode());
        return GovernanceRoutes.json(200, body);
    }

    /**
     * {@code {"target": {"kind", "id"}, "flagged": bool, "rows": "all" | "filtered" | "none",
     * "masked-columns": [...]}} for the caller's own groups. Needs select on the table.
     */
    @GetMapping(path = MINE, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<String> mine(@PathVariable("catalog") String prefix, @PathVariable String namespace,
                                @PathVariable String table, HttpServletRequest http) {
        Request request = management.request(http);
        Target target = management.target(request, "loadOwnRowColumnRules", Privilege.SELECT, prefix, namespace,
                table, null);
        guard.require(request, target, "loadOwnRowColumnRules", Privilege.SELECT);
        List<RowColumnRule> rules = store.rulesOn(target.securable().id()).stream().map(RowColumnRule.Stored::rule)
                .toList();
        RowColumnPolicy.Applied applied = RowColumnPolicy.forUser(rules, request.caller().subject().groups());
        ObjectNode body = Management.JSON.createObjectNode();
        body.set("target", GovernanceRoutes.securable(target.securable()));
        body.put("flagged", flagged(target));
        body.put("rows", none(rules, request.caller()) ? "none"
                : applied.rowFilter().isEmpty() ? "all" : "filtered");
        ArrayNode masked = body.putArray("masked-columns");
        applied.columnMasks().keySet().forEach(masked::add);
        return GovernanceRoutes.json(200, body);
    }

    /**
     * {@code {"catalog", "expressions": bool, "rules": [{"table": {"kind", "id", "path"}, "group", "rows": "all" |
     * "filtered", "masked-columns": [...], "set-by", "set-at"}]}}, each rule with {@code "row-filter"} (when it has
     * one) and {@code "column-masks"} too when {@code expressions} is true. Tables in the catalog's walk order, each
     * table's rules by group.
     */
    @GetMapping(path = EXPORT, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<String> export(@PathVariable("catalog") String prefix, HttpServletRequest http) {
        Request request = management.request(http);
        CatalogRecord catalog;
        boolean expressions;
        if (auditReader(request.caller())) {
            catalog = store.findCatalog(Names.normalize(prefix)).orElseThrow(() ->
                    CatalogError.noSuchWarehouse("The given warehouse does not exist: " + prefix));
            expressions = authorizer.decide(request.caller(), new Authorizer.Target.Catalog(catalog.id()),
                    new Authorizer.Operation("exportRowColumnRules", Privilege.MANAGE_GRANTS, false)).allowed();
        } else {
            Target target = management.target(request, "exportRowColumnRules", Privilege.MANAGE_GRANTS, prefix, null,
                    null, null);
            guard.require(request, target, "exportRowColumnRules", Privilege.MANAGE_GRANTS);
            catalog = target.catalog();
            expressions = true;
        }
        ObjectNode body = Management.JSON.createObjectNode();
        body.put("catalog", catalog.name());
        body.put("expressions", expressions);
        ArrayNode out = body.putArray("rules");
        for (GovernanceSummary.Flagged table : GovernanceSummary.read(store, catalog).flagged()) {
            for (RowColumnRule.Stored stored : store.rulesOn(table.id())) {
                RowColumnRule rule = stored.rule();
                ObjectNode node = out.addObject();
                node.set("table", table.on());
                node.put("group", rule.group());
                node.put("rows", rule.rowFilter().isPresent() ? "filtered" : "all");
                ArrayNode masked = node.putArray("masked-columns");
                rule.columnMasks().keySet().forEach(masked::add);
                if (expressions) {
                    rule.rowFilter().ifPresent(filter -> node.put("row-filter", filter));
                    ObjectNode masks = node.putObject("column-masks");
                    rule.columnMasks().forEach(masks::put);
                }
                node.put("set-by", stored.setBy());
                node.put("set-at", stored.setAt().toString());
            }
        }
        // As the grants' export is (backlog 312): who took the catalog's rules, and how many.
        trail.record(new Export(new nuna.catalog.audit.Securable(nuna.catalog.audit.Securable.Kind.CATALOG,
                catalog.id(), catalog.name(), List.of()), Export.ROW_COLUMN_RULES, out.size(), request.actor()));
        return GovernanceRoutes.json(200, body);
    }

    /** Whether the table's own row/column-policy flag is true (D20). */
    private static boolean flagged(Target target) {
        return target.chain().attributes().stream().anyMatch(a -> a.target().equals(target.securable())
                && a.kind() == GovernanceAttribute.Kind.ROW_COLUMN_POLICY && "true".equalsIgnoreCase(a.value()));
    }

    /** Whether the table has rules and none of them is for one of the caller's groups: they get no row. */
    private static boolean none(List<RowColumnRule> rules, Caller caller) {
        return !rules.isEmpty() && rules.stream().noneMatch(rule -> caller.subject().groups().contains(rule.group()));
    }

    /** Whether the caller reads the audit tables: select on the audit catalog, as the audit feed's route asks. */
    private boolean auditReader(Caller caller) {
        if (auditTables == null || auditTables.catalog() == null || auditTables.catalog().isBlank()) {
            return false;
        }
        return store.findCatalog(Names.normalize(auditTables.catalog()))
                .map(audit -> authorizer.decide(caller, new Authorizer.Target.Catalog(audit.id()),
                        new Authorizer.Operation("readAuditFeed", Privilege.SELECT, false)).allowed())
                .orElse(false);
    }

    /** The current schema of a metadata file: the one {@code current-schema-id} names, or a v1 file's {@code schema}. */
    private static JsonNode currentSchema(JsonNode file) {
        JsonNode id = file.path("current-schema-id");
        for (JsonNode schema : file.path("schemas")) {
            if (id.isNumber() && schema.path("schema-id").asInt() == id.asInt()) {
                return schema;
            }
        }
        JsonNode schema = file.path("schema");
        return schema.isObject() ? schema : Management.JSON.createObjectNode();
    }
}
