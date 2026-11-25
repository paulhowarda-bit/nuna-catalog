package nuna.catalog.server.security;

import nuna.catalog.audit.CatalogEvent;
import nuna.catalog.audit.EventCodec;
import nuna.catalog.audit.RowColumnRuleChange;
import nuna.catalog.core.CatalogError;
import nuna.catalog.security.Authorizer;
import nuna.catalog.security.Authorizer.Decision;
import nuna.catalog.security.Authorizer.Operation;
import nuna.catalog.security.Privilege;
import nuna.catalog.security.RuleColumns;
import nuna.catalog.security.RuleExpressions;
import nuna.catalog.server.rest.AccessDenied;
import nuna.catalog.server.rest.Callers;
import nuna.catalog.server.rest.RequestContext;
import nuna.catalog.server.rest.RestSettings;
import nuna.catalog.server.rest.TableColumns;
import nuna.catalog.store.CatalogObject;
import nuna.catalog.store.CatalogRecord;
import nuna.catalog.store.CatalogStore;
import nuna.catalog.store.Change;
import nuna.catalog.store.CommitOutcomeUnknownException;
import nuna.catalog.store.OutboxEvent;
import nuna.catalog.store.RowColumnRule;
import nuna.catalog.store.WriteResult;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Row filters and column masks (D20, backlog 115): each group's rule on a table, which nuna serves to trusted engines
 * to apply (OpaRoutes). The grant admin sets, removes and lists them, needing {@code manage-grants} on the
 * table or above (D20, amended 2026-11-24), and never for a group they belong to, as with a grant (D18). Every
 * refusal is audited, and one on a table the caller holds nothing on is answered as if it did not exist, as on the
 * grant routes; every change goes through the outbox in the same transaction (D23). A rule needs the table's
 * row/column-policy flag, which a steward sets (409 without it), so writing the rules and flagging the table take
 * two people. A rule names only columns the table's current schema has, and a schema change that would take one away
 * is refused ({@link RuleColumnGuard}): a mask is served by its column's name, so a rename would unmask it (backlog
 * 240). Each expression must parse as Trino SQL, with Trino's own parser, or the set is refused saying where; a
 * subquery, and so another table, is allowed (D20, amended 2026-11-24; backlog 244).
 *
 * <p>Each rule has a version, which the list gives. A set or removal may send it back as {@code If-Match: "<version>"}
 * to act only on the rule as it was read: when the group's rule is at another version, or a set's rule has been
 * removed, the answer is 412 and nothing is written, so one grant admin cannot replace another's edit unseen (backlog
 * 244). Without If-Match a set or removal acts on whatever rule there is.
 *
 * <p>The body of a set: {@code {"table": {"type": "table", "namespace": [...], "name": ...}, "group": ...,
 * "row-filter": <SQL predicate>, "column-masks": {<column>: <SQL expression>}}}, with a filter, masks or both; of a
 * removal, the table and the group.
 */
@RestController
class RuleRoutes {

    static final String RULES = "/management/v1/catalogs/{catalog}/row-column-rules";
    /** The longest filter or mask expression kept, as Oracle's column allows. */
    static final int MAX_EXPRESSION = 4000;
    /** The most columns one rule masks. */
    static final int MAX_MASKS = 500;

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Operation SET = new Operation("setRowColumnRule", Privilege.MANAGE_GRANTS, false);
    private static final Operation REMOVE = new Operation("removeRowColumnRule", Privilege.MANAGE_GRANTS, false);
    private static final Operation LIST = new Operation("listRowColumnRules", Privilege.MANAGE_GRANTS, false);

    private final CatalogStore store;
    private final Authorizer authorizer;
    private final Callers callers;
    private final Denials denials;
    private final Holdings holdings;
    private final RestSettings settings;
    private final TableColumns columns;
    private final Clock clock;

    RuleRoutes(CatalogStore store, Authorizer authorizer, Callers callers, Denials denials, Holdings holdings,
               RestSettings settings, TableColumns columns, Clock clock) {
        this.store = store;
        this.authorizer = authorizer;
        this.callers = callers;
        this.denials = denials;
        this.holdings = holdings;
        this.settings = settings;
        this.columns = columns;
        this.clock = clock;
    }

    /** Sets a group's rule on a table, replacing the one it had. */
    @PostMapping(RULES)
    ResponseEntity<String> set(@PathVariable String catalog, @RequestBody(required = false) String body,
                               HttpServletRequest http) {
        RequestContext request = RequestContext.of(http, callers);
        OptionalLong expected = ifMatch(http);
        CatalogRecord record = holdings.catalog(catalog, request.caller(), request.actor(), SET.name(),
                SET.privilege());
        JsonNode json = body(body);
        Securables.Resolved on = table(record, json);
        String group = group(json);
        Optional<String> filter = json.hasNonNull("row-filter")
                ? Optional.of(expression(json.get("row-filter"), "row-filter")) : Optional.empty();
        Map<String, String> asked = masks(json.get("column-masks"));
        if (filter.isEmpty() && asked.isEmpty()) {
            throw CatalogError.badRequest("A rule filters rows (row-filter), masks columns (column-masks), or both;"
                    + " to give a group every row, set row-filter to true");
        }
        require(request, on, SET);
        if (request.caller().subject().groups().contains(group)) {
            throw denials.refuse(request.actor(), SET.name(), on.named(), SET.privilege().wireName(),
                    "separation-of-duties", "no one sets row filters or column masks for a group they belong to: "
                            + group);
        }
        parses(asked);
        Map<String, String> masks = schemaColumns(record, on, filter, asked);
        RowColumnRule rule = RowColumnRule.of(record.id(), on.stored().id(), group, filter, masks);
        Change change = change(new RowColumnRuleChange(RowColumnRuleChange.Action.SET, on.named(), group,
                filter.orElse(null), masks, request.actor()));
        WriteResult result = write("setting the rule", () -> store.putRule(rule, expected,
                request.caller().subject().id(), change));
        return switch (result) {
            case WriteResult.Applied applied -> ResponseEntity.noContent().build();
            case WriteResult.Unchanged unchanged -> ResponseEntity.noContent().build();
            case WriteResult.Refused refused when refused.reason() == WriteResult.Reason.NOT_FLAGGED ->
                    throw CatalogError.of(409, "NotFlaggedException", "The table does not carry the row/column-policy"
                            + " flag; a steward sets it first, so that only trusted engines read its files (D20)");
            case WriteResult.Missing missing -> throw on.missing();
            case WriteResult.VersionMismatch stale -> throw stale(group, stale);
            default -> throw new IllegalStateException("putRule answered " + result);
        };
    }

    /** Removes a group's rule from a table. */
    @PostMapping(RULES + "/remove")
    ResponseEntity<String> remove(@PathVariable String catalog, @RequestBody(required = false) String body,
                                  HttpServletRequest http) {
        RequestContext request = RequestContext.of(http, callers);
        OptionalLong expected = ifMatch(http);
        CatalogRecord record = holdings.catalog(catalog, request.caller(), request.actor(), REMOVE.name(),
                REMOVE.privilege());
        JsonNode json = body(body);
        Securables.Resolved on = table(record, json);
        String group = group(json);
        require(request, on, REMOVE);
        Change change = change(new RowColumnRuleChange(RowColumnRuleChange.Action.REMOVED, on.named(), group, null,
                Map.of(), request.actor()));
        WriteResult result = write("removing the rule", () -> store.removeRule(record.id(), on.stored().id(), group,
                expected, change));
        return switch (result) {
            case WriteResult.Applied applied -> ResponseEntity.noContent().build();
            case WriteResult.Missing missing -> throw CatalogError.of(404, "NotFoundException",
                    "No row filter or column masks for " + group + " on the table");
            case WriteResult.VersionMismatch stale -> throw stale(group, stale);
            default -> throw new IllegalStateException("removeRule answered " + result);
        };
    }

    /** Every group's rule on a table: {@code ?namespace=a&namespace=b&name=t}. */
    @GetMapping(RULES)
    ResponseEntity<String> list(@PathVariable String catalog,
                                @RequestParam(required = false) List<String> namespace,
                                @RequestParam(required = false) String name,
                                HttpServletRequest http) {
        RequestContext request = RequestContext.of(http, callers);
        CatalogRecord record = holdings.catalog(catalog, request.caller(), request.actor(), LIST.name(),
                LIST.privilege());
        Securables securables = new Securables(store, record);
        Securables.Resolved on = securables.resolve(new Securables.Spec("table", namespace, name));
        require(request, on, LIST);
        List<Map<String, Object>> rules = new ArrayList<>();
        for (RowColumnRule.Stored stored : store.rulesOn(on.stored().id())) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("group", stored.rule().group());
            stored.rule().rowFilter().ifPresent(filter -> out.put("row-filter", filter));
            out.put("column-masks", stored.rule().columnMasks());
            out.put("set-by", stored.setBy());
            out.put("set-at", stored.setAt().toString());
            out.put("version", stored.version());
            rules.add(out);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("table", securables.render(on.stored()));
        out.put("rules", rules);
        return GrantRoutes.json(out);
    }

    private static JsonNode body(String body) {
        try {
            JsonNode json = JSON.readTree(body == null ? "" : body);
            if (json == null || !json.isObject()) {
                throw CatalogError.badRequest("A JSON object body is needed");
            }
            return json;
        } catch (JacksonException e) {
            throw CatalogError.badRequest("Invalid JSON body: " + e.getOriginalMessage());
        }
    }

    private Securables.Resolved table(CatalogRecord record, JsonNode json) {
        JsonNode table = json.get("table");
        if (table == null || !table.isObject()) {
            throw CatalogError.badRequest("A rule names its table: {\"table\": {\"type\": \"table\", \"namespace\":"
                    + " [...], \"name\": ...}}");
        }
        Securables.Spec spec;
        try {
            spec = JSON.treeToValue(table, Securables.Spec.class);
        } catch (JacksonException e) {
            throw CatalogError.badRequest("Invalid table: " + e.getOriginalMessage());
        }
        if (spec.type() == null || !spec.type().toLowerCase(Locale.ROOT).equals("table")) {
            throw CatalogError.badRequest("Row filters and column masks go on tables only");
        }
        return new Securables(store, record).resolve(spec);
    }

    private static String group(JsonNode json) {
        JsonNode group = json.get("group");
        if (group == null || !group.isString() || group.asString().isBlank()) {
            throw CatalogError.badRequest("A rule names the group it is for; rules never go to individuals (D19)");
        }
        return group.asString();
    }

    private static String expression(JsonNode node, String what) {
        if (!node.isString() || node.asString().isBlank()) {
            throw CatalogError.badRequest(what + " is an SQL expression, as text");
        }
        String text = node.asString();
        if (text.length() > MAX_EXPRESSION || text.indexOf('\0') >= 0) {
            throw CatalogError.badRequest(what + " is at most " + MAX_EXPRESSION + " characters, with no NUL");
        }
        return text;
    }

    private static Map<String, String> masks(JsonNode node) {
        Map<String, String> masks = new TreeMap<>();
        if (node == null || node.isNull()) {
            return masks;
        }
        if (!node.isObject()) {
            throw CatalogError.badRequest("column-masks is an object from column name to SQL expression");
        }
        for (Map.Entry<String, JsonNode> mask : node.properties()) {
            if (mask.getKey().isBlank() || mask.getKey().length() > MAX_EXPRESSION) {
                throw CatalogError.badRequest("column-masks names each column it masks");
            }
            masks.put(mask.getKey(), expression(mask.getValue(), "The mask of " + mask.getKey()));
        }
        if (masks.size() > MAX_MASKS) {
            throw CatalogError.badRequest("A rule masks at most " + MAX_MASKS + " columns");
        }
        return masks;
    }

    /**
     * The version an If-Match header names, in the form the list gives it, quoted as an entity tag or not; empty
     * without the header (backlog 244). A weak tag, a list of tags or {@code *} is refused 400: a rule has one version,
     * compared exactly.
     */
    static OptionalLong ifMatch(HttpServletRequest http) {
        String header = http.getHeader("If-Match");
        if (header == null) {
            return OptionalLong.empty();
        }
        String tag = header.strip();
        if (tag.length() >= 2 && tag.startsWith("\"") && tag.endsWith("\"")) {
            tag = tag.substring(1, tag.length() - 1);
        }
        if (tag.isEmpty() || tag.length() > 19 || !tag.chars().allMatch(c -> c >= '0' && c <= '9')) {
            throw CatalogError.badRequest("If-Match names the rule's version as the list gives it, as \"<version>\"");
        }
        try {
            return OptionalLong.of(Long.parseLong(tag));
        } catch (NumberFormatException e) {
            throw CatalogError.badRequest("If-Match names the rule's version as the list gives it, as \"<version>\"");
        }
    }

    /** 412: the group's rule is not at the version If-Match named, so nothing was written (backlog 244). */
    private static CatalogError stale(String group, WriteResult.VersionMismatch stale) {
        return CatalogError.of(412, "PreconditionFailedException", stale.actual() == 0
                ? "The rule for " + group + " named in If-Match has been removed; list the rules again"
                : "The rule for " + group + " is at version " + stale.actual() + ", not the one named in If-Match;"
                        + " another change came first: list the rules again");
    }

    /**
     * Each mask parsed as Trino parses it when it applies the rule, else 400 saying which and where (backlog 244).
     * The filter is parsed by {@link RuleColumns#check}, which reads its columns from the tree. Asked after
     * authorization, as the schema is.
     */
    private static void parses(Map<String, String> masks) {
        masks.forEach((column, sql) -> {
            if (RuleExpressions.parse(sql, "The mask of " + column) instanceof RuleExpressions.Parsed.Fails fails) {
                throw CatalogError.badRequest(fails.message());
            }
        });
    }

    /**
     * The masks, keyed by their columns as the table's current schema spells them, when the filter parses as Trino
     * SQL (backlog 244) and every column the filter and masks name is in the schema; otherwise 400, saying where the
     * filter does not parse or naming what the schema lacks (backlog 240). Asked after authorization, so the answer
     * tells no one without {@code manage-grants} what columns the table has.
     */
    private Map<String, String> schemaColumns(CatalogRecord record, Securables.Resolved on, Optional<String> filter,
                                              Map<String, String> masks) {
        CatalogObject table = ((Authorizer.Target.TableOrView) on.target()).object();
        return switch (RuleColumns.check(columns.current(record, table), filter, masks)) {
            case RuleColumns.Checked.Fits fits -> fits.masks();
            case RuleColumns.Checked.Unfit unfit -> throw CatalogError.badRequest(unfit.message());
        };
    }

    private Change change(RowColumnRuleChange data) {
        Instant now = clock.instant();
        CatalogEvent event = CatalogEvent.create(settings.auditSource(), now, data);
        return new Change(UUID.randomUUID(), now, List.of(new OutboxEvent(event.type().typeName(),
                new String(EventCodec.encode(event), StandardCharsets.UTF_8))), Optional.empty());
    }

    private static WriteResult write(String what, java.util.function.Supplier<WriteResult> write) {
        try {
            return write.get();
        } catch (CommitOutcomeUnknownException e) {
            // Both are safe to repeat: a repeated set answers unchanged, a repeated removal not found.
            throw CatalogError.of(500, "ServiceFailureException", "The catalog may or may not have finished "
                    + what + "; repeat it: " + e.getMessage());
        }
    }

    /**
     * A refusal is audited, then answered 403 to a caller that holds something on the table, and as if it did not
     * exist to any other (D18, amended 2026-11-19), as on the grant routes.
     */
    private void require(RequestContext request, Securables.Resolved on, Operation operation) {
        if (authorizer.decide(request.caller(), on.target(), operation) instanceof Decision.Denied denied) {
            AccessDenied refused = denials.refuse(request.actor(), operation.name(), on.named(),
                    denied.privilege() == null ? null : denied.privilege().wireName(),
                    denied.reason().name().toLowerCase(Locale.ROOT), denied.message());
            throw holdings.something(request.caller(), on.target()) ? refused : on.missing();
        }
    }
}
