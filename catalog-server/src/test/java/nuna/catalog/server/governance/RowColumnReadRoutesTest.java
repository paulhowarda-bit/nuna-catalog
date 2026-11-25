package nuna.catalog.server.governance;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Proxy;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import nuna.catalog.audit.CatalogEvent;
import nuna.catalog.audit.Denial;
import nuna.catalog.audit.publish.EventPublisher;
import nuna.catalog.audit.ObjectRead;
import nuna.catalog.core.CatalogError;
import nuna.catalog.core.Names;
import nuna.catalog.core.commit.TableFiles;
import nuna.catalog.core.principal.Caller;
import nuna.catalog.core.principal.Principal;
import nuna.catalog.security.Authorizer;
import nuna.catalog.security.Privilege;
import nuna.catalog.security.Separation;
import nuna.catalog.server.rest.AccessDenied;
import nuna.catalog.server.rest.AuditPublisher;
import nuna.catalog.server.rest.RestSettings;
import nuna.catalog.server.security.AuditCatalogSettings;
import nuna.catalog.server.security.Denials;
import nuna.catalog.server.security.DenialsForTests;
import nuna.catalog.server.security.Holdings;
import nuna.catalog.server.telemetry.RequestIds;
import nuna.catalog.store.CatalogObject;
import nuna.catalog.store.CatalogObject.Kind;
import nuna.catalog.store.CatalogRecord;
import nuna.catalog.store.CatalogStore;
import nuna.catalog.store.GovernanceAttribute;
import nuna.catalog.store.Grant;
import nuna.catalog.store.GrantRecord;
import nuna.catalog.store.Name;
import nuna.catalog.store.NamespaceRecord;
import nuna.catalog.store.Page;
import nuna.catalog.store.PageRequest;
import nuna.catalog.store.PageToken;
import nuna.catalog.store.Pointer;
import nuna.catalog.store.RowColumnRule;
import nuna.catalog.store.Securable;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import tools.jackson.databind.JsonNode;

/**
 * What the console's row filters and column masks read (D20, backlog 242), each decided for its caller: a flagged
 * table's schema for anyone who may describe it; a reader's own view, without the expressions, for anyone who may
 * select it; the rule count and groups in a table's governance for its steward and grant admin only; the flagged tables
 * and their rule counts in the catalog's summary; and the export of every rule, with the expressions for a grant admin
 * alone and without them for an audit reader. Every refusal is audited, and a caller that holds nothing on the table
 * or the catalog is answered as if it did not exist (D18). The routes, guard and authorizer are the real ones over a
 * store double, so this needs only Java.
 */
class RowColumnReadRoutesTest {

    private static final Instant NOW = Instant.parse("2026-11-24T12:00:00Z");
    private static final String FILTER = "desk = 'equity'";
    private static final String MASK = "'***'";
    private static final String METADATA = """
            {"format-version":2,"table-uuid":"9d1f","location":"s3://gate/positions",
             "current-schema-id":1,
             "schemas":[
               {"type":"struct","schema-id":0,"fields":[{"id":1,"name":"desk","required":false,"type":"string"}]},
               {"type":"struct","schema-id":1,"fields":[{"id":1,"name":"desk","required":false,"type":"string"},
                 {"id":2,"name":"trader","required":false,"type":"string"},
                 {"id":3,"name":"account","required":false,"type":"string"}]}],
             "properties":{"owner":"desk-7","write.format.default":"parquet"},
             "current-snapshot-id":11,
             "snapshots":[{"snapshot-id":11,"manifest-list":"s3://gate/positions/metadata/snap-11.avro"}]}""";

    /** Select on markets: the equity desk's rows, each trader masked. */
    private static final Caller ANN = person("ann", "analysts");
    /** Select on markets: every row, the account masked. */
    private static final Caller RIKO = person("riko", "risk");
    /** Select on markets, and no rule on positions: no row. */
    private static final Caller RITA = person("rita", "traders");
    /** Describe on the catalog. */
    private static final Caller DORA = person("dora", "describers");
    /** Manage-tags on the catalog, the steward. */
    private static final Caller STELLA = person("stella", "stewards");
    /** A grant admin, by configuration. */
    private static final Caller GINA = person("gina", "grant-admins");
    /** Select on hr.staff only. */
    private static final Caller TOM = person("tom", "staff-readers");
    /** Select on the audit catalog only. */
    private static final Caller AUDREY = person("audrey", "auditors");
    /** Nothing anywhere. */
    private static final Caller NED = person("ned", "newcomers");

    private final CatalogRecord catalog = new CatalogRecord(UUID.randomUUID(), "gate", "gate");
    private final CatalogRecord auditCatalog = new CatalogRecord(UUID.randomUUID(), "nuna_audit", "nuna_audit");
    private final Securable root = new Securable(Securable.Kind.CATALOG, catalog.id());
    private final Map<UUID, NamespaceRecord> namespaces = new LinkedHashMap<>();
    private final Map<UUID, CatalogObject> objects = new LinkedHashMap<>();
    private final List<GrantRecord> grants = new ArrayList<>();
    private final List<GovernanceAttribute> attributes = new ArrayList<>();
    private final Map<UUID, List<RowColumnRule.Stored>> rules = new LinkedHashMap<>();
    private final List<CatalogEvent> audit = new ArrayList<>();

    private final NamespaceRecord markets = namespace(null, "markets");
    private final NamespaceRecord desks = namespace(markets, "desks");
    /** Flagged, with rules for analysts and risk. */
    private final CatalogObject positions = object(desks, "positions");
    /** Not flagged. */
    private final CatalogObject trades = object(desks, "trades");
    private final NamespaceRecord hr = namespace(null, "hr");
    private final CatalogObject staff = object(hr, "staff");

    private final MockHttpServletRequest http = new MockHttpServletRequest();
    private final CatalogStore store = store();
    private final Management management;
    private final ManagementGuard guard;
    private final Authorizer authorizer;
    private final AuditTrail trail;
    private Caller caller = ANN;
    private boolean intact = true;

    RowColumnReadRoutesTest() {
        http.setAttribute(RequestIds.class.getName(), "request-1");
        grant(catalog, namespaceOf(markets), "analysts", Privilege.SELECT);
        grant(catalog, namespaceOf(markets), "risk", Privilege.SELECT);
        grant(catalog, namespaceOf(markets), "traders", Privilege.SELECT);
        grant(catalog, root, "describers", Privilege.DESCRIBE);
        grant(catalog, root, "stewards", Privilege.MANAGE_TAGS);
        grant(catalog, staff.securable(), "staff-readers", Privilege.SELECT);
        grant(auditCatalog, new Securable(Securable.Kind.CATALOG, auditCatalog.id()), "auditors", Privilege.SELECT);
        attributes.add(new GovernanceAttribute(positions.securable(), GovernanceAttribute.Kind.ROW_COLUMN_POLICY,
                "true", "management-api", "stella", NOW.minus(Duration.ofDays(3))));
        rule(positions, "analysts", Optional.of(FILTER), Map.of("trader", MASK));
        rule(positions, "risk", Optional.empty(), Map.of("account", "NULL"));

        RestSettings settings = new RestSettings(100, 1000, 1_048_576, 1000, 268_435_456, Duration.ofDays(30),
                Duration.ofMinutes(5), "the identity provider", URI.create("urn:nuna:test"), 100, null);
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        AuditPublisher publisher = audit::add;
        Separation separation = new Separation(Set.of("grant-admins"), Set.of("platform-ops"));
        authorizer = new Authorizer(store, store, store, separation, false, Set.of());
        Denials denials = DenialsForTests.denials(publisher, settings, clock);
        Holdings holdings = new Holdings(authorizer, store, settings, separation, denials);
        management = new Management(store, request -> caller, settings, holdings);
        guard = new ManagementGuard(authorizer, separation, null, denials, holdings);
        trail = new AuditTrail(Optional.<EventPublisher>of(audit::add), URI.create("urn:nuna:test"), clock,
                Duration.ofMinutes(1));
    }

    // ---- a table's schema ----

    @Test
    void anyoneWhoMayDescribeTheTableGetsItsCurrentSchemaAndPropertiesAndNoFiles() {
        for (Caller reader : List.of(DORA, ANN, STELLA, GINA)) {
            caller = reader;
            JsonNode body = json(routes().schema("gate", "markets\u001fdesks", "positions", http));

            assertThat(body.get("target").get("id").asString()).isEqualTo(positions.id().toString());
            assertThat(body.get("format-version").asInt()).isEqualTo(2);
            assertThat(body.get("schema").get("schema-id").asInt()).as("the current schema").isEqualTo(1);
            assertThat(body.get("schema").get("fields").valueStream().map(f -> f.get("name").asString()).toList())
                    .containsExactly("desk", "trader", "account");
            assertThat(body.get("properties").get("owner").asString()).isEqualTo("desk-7");
            assertThat(body.propertyNames()).containsExactly("target", "format-version", "schema", "properties");
            assertThat(body.toString()).doesNotContain("s3://", "snapshot");
        }
        assertThat(denials()).isEmpty();
        List<ObjectRead> reads = audit.stream().map(CatalogEvent::data).filter(ObjectRead.class::isInstance)
                .map(ObjectRead.class::cast).toList();
        assertThat(reads).as("each read of the metadata file is audited, as the export's is").hasSize(4);
        assertThat(reads.getFirst().object().path()).containsExactly("markets", "desks", "positions");
        assertThat(reads.getFirst().actor().principal()).isEqualTo("dora");
    }

    @Test
    void aCallerThatHoldsNothingOnTheTableIsAnsweredAsIfItDidNotExistAndTheRefusalAudited() {
        caller = TOM;

        assertThat(error(() -> routes().schema("gate", "markets\u001fdesks", "positions", http)).type())
                .isEqualTo("NoSuchTableException");

        assertThat(denials()).extracting(Denial::operation).containsExactly("loadTableSchema");
        Denial denial = denials().getFirst();
        assertThat(denial.privilege()).isEqualTo("describe");
        assertThat(denial.securable().path()).containsExactly("markets", "desks", "positions");
        assertThat(denial.actor().principal()).isEqualTo("tom");
        assertThat(audit.stream().map(CatalogEvent::data).filter(ObjectRead.class::isInstance)).isEmpty();
    }

    @Test
    void aMetadataFileThatDoesNotMatchItsHashIsNotServed() {
        caller = DORA;
        intact = false;

        assertThat(error(() -> routes().schema("gate", "markets\u001fdesks", "positions", http)).status())
                .isEqualTo(500);
    }

    // ---- a reader's own view ----

    @Test
    void aReaderLearnsWhetherTheirRowsAreFilteredAndWhichColumnsAreMaskedButNeverTheExpressions() {
        Map<Caller, String> rows = Map.of(ANN, "filtered", RIKO, "all", RITA, "none");
        Map<Caller, List<String>> masked = Map.of(ANN, List.of("trader"), RIKO, List.of("account"), RITA, List.of());
        for (Caller reader : List.of(ANN, RIKO, RITA)) {
            caller = reader;
            JsonNode body = json(routes().mine("gate", "markets\u001fdesks", "positions", http));

            assertThat(body.get("target").get("id").asString()).isEqualTo(positions.id().toString());
            assertThat(body.get("flagged").asBoolean()).isTrue();
            assertThat(body.get("rows").asString()).as("rows for %s", reader.principal().id())
                    .isEqualTo(rows.get(reader));
            assertThat(body.get("masked-columns").valueStream().map(JsonNode::asString).toList())
                    .as("masked for %s", reader.principal().id()).isEqualTo(masked.get(reader));
            assertThat(body.toString()).doesNotContain(FILTER, MASK, "NULL", "analysts", "risk");
        }
        assertThat(denials()).isEmpty();
    }

    @Test
    void onATableThatIsNotFlaggedEveryRowAndColumnIsThere() {
        JsonNode body = json(routes().mine("gate", "markets\u001fdesks", "trades", http));

        assertThat(body.get("flagged").asBoolean()).isFalse();
        assertThat(body.get("rows").asString()).isEqualTo("all");
        assertThat(body.get("masked-columns")).isEmpty();
    }

    @Test
    void aCallerWhoMayNotSelectTheTableIsRefusedAndTheRefusalAudited() {
        caller = DORA;
        assertThat(answer(() -> routes().mine("gate", "markets\u001fdesks", "positions", http))).isEqualTo(403);
        caller = TOM;
        assertThat(error(() -> routes().mine("gate", "markets\u001fdesks", "positions", http)).type())
                .isEqualTo("NoSuchTableException");
        caller = NED;
        assertThat(error(() -> routes().mine("gate", "markets\u001fdesks", "positions", http)).type())
                .isEqualTo("NoSuchWarehouseException");

        assertThat(denials()).extracting(Denial::operation).containsExactly("loadOwnRowColumnRules",
                "loadOwnRowColumnRules", "loadOwnRowColumnRules");
        assertThat(denials().getFirst().privilege()).isEqualTo("select");
        assertThat(denials().getFirst().actor().principal()).isEqualTo("dora");
    }

    // ---- the rule count in a table's governance ----

    @Test
    void theStewardAndTheGrantAdminSeeHowManyRulesATableHasAndForWhichGroupsWithoutTheExpressions() {
        for (Caller steward : List.of(STELLA, GINA)) {
            caller = steward;
            JsonNode body = json(governance().governance("gate", "markets\u001fdesks", "positions", null, http));

            JsonNode counted = body.get("row-column-rules");
            assertThat(counted.get("count").asInt()).as("for %s", steward.principal().id()).isEqualTo(2);
            assertThat(counted.get("groups").valueStream().map(JsonNode::asString).toList())
                    .containsExactly("analysts", "risk");
            assertThat(body.toString()).doesNotContain(FILTER, MASK);
        }
        caller = STELLA;
        JsonNode unflagged = json(governance().governance("gate", "markets\u001fdesks", "trades", null, http));
        assertThat(unflagged.get("row-column-rules").get("count").asInt()).isZero();
        assertThat(json(governance().governance("gate", "markets\u001fdesks", null, null, http))
                .has("row-column-rules")).as("tables only").isFalse();
        assertThat(denials()).isEmpty();
    }

    @Test
    void aReaderOrADescriberSeesATablesGovernanceWithoutItsRules() {
        for (Caller reader : List.of(ANN, DORA)) {
            caller = reader;
            JsonNode body = json(governance().governance("gate", "markets\u001fdesks", "positions", null, http));

            assertThat(body.has("row-column-rules")).as("for %s", reader.principal().id()).isFalse();
            assertThat(body.toString()).doesNotContain("analysts", "risk");
        }
        assertThat(denials()).as("an answer with less in it refuses nothing").isEmpty();
    }

    // ---- the flagged tables in the summary ----

    @Test
    void theSummaryCountsTheFlaggedTablesAndNamesEachWithItsRuleCount() {
        caller = DORA;
        JsonNode summary = json(governance().summary("gate", http));

        assertThat(summary.get("tables").get("flagged").asInt()).isEqualTo(1);
        assertThat(summary.get("flagged")).hasSize(1);
        JsonNode flagged = summary.get("flagged").get(0);
        assertThat(flagged.get("on").get("id").asString()).isEqualTo(positions.id().toString());
        assertThat(flagged.get("on").get("path").valueStream().map(JsonNode::asString).toList())
                .containsExactly("markets", "desks", "positions");
        assertThat(flagged.get("rules").asInt()).isEqualTo(2);
        assertThat(summary.toString()).doesNotContain("analysts", FILTER, MASK);
    }

    // ---- the export for access reviews ----

    @Test
    void aGrantAdminExportsEveryRuleInTheCatalogWithItsExpressions() {
        caller = GINA;
        JsonNode body = json(routes().export("gate", http));

        assertThat(body.get("catalog").asString()).isEqualTo("gate");
        assertThat(body.get("expressions").asBoolean()).isTrue();
        JsonNode exported = body.get("rules");
        assertThat(exported).hasSize(2);
        JsonNode analysts = exported.get(0);
        assertThat(analysts.get("table").get("path").valueStream().map(JsonNode::asString).toList())
                .containsExactly("markets", "desks", "positions");
        assertThat(analysts.get("group").asString()).isEqualTo("analysts");
        assertThat(analysts.get("rows").asString()).isEqualTo("filtered");
        assertThat(analysts.get("row-filter").asString()).isEqualTo(FILTER);
        assertThat(analysts.get("column-masks").get("trader").asString()).isEqualTo(MASK);
        assertThat(analysts.get("set-by").asString()).isEqualTo("gina");
        JsonNode risk = exported.get(1);
        assertThat(risk.get("rows").asString()).isEqualTo("all");
        assertThat(risk.has("row-filter")).isFalse();
        assertThat(risk.get("column-masks").get("account").asString()).isEqualTo("NULL");
        assertThat(denials()).isEmpty();
    }

    @Test
    void anAuditReaderExportsEveryRuleWithoutTheExpressions() {
        caller = AUDREY;
        JsonNode body = json(routes(new AuditCatalogSettings("nuna_audit", "auditors", null)).export("gate", http));

        assertThat(body.get("expressions").asBoolean()).isFalse();
        JsonNode exported = body.get("rules");
        assertThat(exported.valueStream().map(rule -> rule.get("group").asString()).toList())
                .containsExactly("analysts", "risk");
        assertThat(exported.get(0).get("rows").asString()).isEqualTo("filtered");
        assertThat(exported.get(0).get("masked-columns").valueStream().map(JsonNode::asString).toList())
                .containsExactly("trader");
        assertThat(exported.get(0).has("row-filter")).isFalse();
        assertThat(exported.get(0).has("column-masks")).isFalse();
        assertThat(body.toString()).doesNotContain(FILTER, MASK, "NULL");
        assertThat(denials()).isEmpty();
    }

    @Test
    void anyoneElseIsRefusedTheExportAndTheRefusalAudited() {
        caller = STELLA;
        assertThat(answer(() -> routes(new AuditCatalogSettings("nuna_audit", "auditors", null))
                .export("gate", http))).isEqualTo(403);
        caller = AUDREY;
        assertThat(answer(() -> routes().export("gate", http))).as("no audit catalog configured: no audit reader")
                .isEqualTo(404);
        caller = NED;
        assertThat(error(() -> routes().export("gate", http)).type()).isEqualTo("NoSuchWarehouseException");

        assertThat(denials()).extracting(Denial::operation).containsExactly("exportRowColumnRules",
                "exportRowColumnRules", "exportRowColumnRules");
        Denial first = denials().getFirst();
        assertThat(first.privilege()).isEqualTo("manage-grants");
        assertThat(first.securable().kind()).isEqualTo(nuna.catalog.audit.Securable.Kind.CATALOG);
        assertThat(first.actor().principal()).isEqualTo("stella");
    }

    // ---- helpers ----

    private RowColumnReadRoutes routes() {
        return routes(new AuditCatalogSettings(null, null, null));
    }

    private RowColumnReadRoutes routes(AuditCatalogSettings auditTables) {
        return new RowColumnReadRoutes(store, management, guard, authorizer, auditTables, trail,
                (catalogId, location, sha256) -> {
                    assertThat(catalogId).isEqualTo(catalog.id());
                    return new TableFiles.Read(METADATA, sha256, intact);
                });
    }

    private GovernanceRoutes governance() {
        return new GovernanceRoutes(store, management, guard, null, new HoldHistory(store, Clock.fixed(NOW,
                ZoneOffset.UTC)), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static JsonNode json(ResponseEntity<String> response) {
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        return Management.JSON.readTree(response.getBody());
    }

    private static int answer(java.util.concurrent.Callable<ResponseEntity<String>> call) {
        try {
            return call.call().getStatusCode().value();
        } catch (CatalogError e) {
            return e.status();
        } catch (AccessDenied e) {
            return 403;
        } catch (Exception e) {
            throw new AssertionError("the route failed unexpectedly", e);
        }
    }

    private static CatalogError error(java.util.concurrent.Callable<ResponseEntity<String>> call) {
        try {
            call.call();
        } catch (CatalogError e) {
            return e;
        } catch (Exception e) {
            throw new AssertionError("the route failed unexpectedly", e);
        }
        throw new AssertionError("the route answered");
    }

    private List<Denial> denials() {
        return audit.stream().map(CatalogEvent::data).filter(Denial.class::isInstance).map(Denial.class::cast)
                .toList();
    }

    private static Caller person(String id, String... groups) {
        return new Caller(new Principal(id, Set.of(groups)), "console", false, Optional.empty());
    }

    private NamespaceRecord namespace(NamespaceRecord parent, String name) {
        NamespaceRecord record = new NamespaceRecord(catalog.id(), UUID.randomUUID(),
                parent == null ? catalog.id() : parent.id(), new Name(Names.normalize(name), name), Map.of(), 1);
        namespaces.put(record.id(), record);
        return record;
    }

    private static Securable namespaceOf(NamespaceRecord namespace) {
        return new Securable(Securable.Kind.NAMESPACE, namespace.id());
    }

    private CatalogObject object(NamespaceRecord namespace, String name) {
        UUID id = UUID.randomUUID();
        CatalogObject object = CatalogObject.active(catalog.id(), id, namespace.id(),
                new Name(Names.normalize(name), name), Kind.TABLE, "s3://gate/" + id);
        objects.put(id, object);
        return object;
    }

    private void grant(CatalogRecord in, Securable on, String group, Privilege privilege) {
        grants.add(new GrantRecord(new Grant(in.id(), on, group, privilege.wireName()), "test", Instant.EPOCH));
    }

    private void rule(CatalogObject table, String group, Optional<String> filter, Map<String, String> masks) {
        rules.computeIfAbsent(table.id(), id -> new ArrayList<>()).add(new RowColumnRule.Stored(
                RowColumnRule.of(catalog.id(), table.id(), group, filter, masks), "gina",
                NOW.minus(Duration.ofDays(1)), 1));
    }

    private Optional<NamespaceRecord> findNamespace(UUID catalogId, List<String> path) {
        UUID parent = catalogId;
        NamespaceRecord found = null;
        for (String level : path) {
            UUID above = parent;
            found = namespaces.values().stream()
                    .filter(ns -> ns.parentId().equals(above) && ns.name().normalized().equals(level))
                    .findFirst().orElse(null);
            if (found == null) {
                return Optional.empty();
            }
            parent = found.id();
        }
        return Optional.ofNullable(found);
    }

    private static <T> Page<T> page(List<T> items, PageRequest page) {
        int from = page.token().map(token -> Integer.parseInt(PageToken.decode(token, 1).getFirst())).orElse(0);
        int to = Math.min(items.size(), from + page.size());
        return new Page<>(List.copyOf(items.subList(from, to)),
                to < items.size() ? Optional.of(PageToken.encode(Integer.toString(to))) : Optional.empty());
    }

    /** The estate above, its grants, attributes and rules, and each table's pointer; any other call fails the test. */
    @SuppressWarnings("unchecked")
    private CatalogStore store() {
        return (CatalogStore) Proxy.newProxyInstance(CatalogStore.class.getClassLoader(),
                new Class<?>[] {CatalogStore.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "findCatalog" -> List.of(catalog, auditCatalog).stream()
                            .filter(c -> c.normalizedName().equals(args[0])).findFirst();
                    case "findNamespace" -> findNamespace((UUID) args[0], (List<String>) args[1]);
                    case "readNamespace" -> Optional.ofNullable(namespaces.get((UUID) args[0]));
                    case "readObject" -> Optional.ofNullable(objects.get((UUID) args[0]));
                    case "findObject" -> objects.values().stream().filter(o -> o.namespaceId().equals(args[0])
                            && o.name().normalized().equals(args[1])).findFirst();
                    case "listNamespaces" -> page(namespaces.values().stream()
                            .filter(ns -> ns.parentId().equals(args[0]))
                            .sorted(Comparator.comparing(ns -> ns.name().normalized())).toList(), (PageRequest) args[1]);
                    case "listObjects" -> page(objects.values().stream()
                            .filter(o -> o.namespaceId().equals(args[0]) && o.kind() == args[1]
                                    && o.state() == args[2])
                            .sorted(Comparator.comparing(o -> o.name().normalized())).toList(), (PageRequest) args[3]);
                    case "grantsFor" -> grants.stream()
                            .filter(g -> ((Set<Securable>) args[0]).contains(g.grant().securable())
                                    && ((Set<String>) args[1]).contains(g.grant().group()))
                            .toList();
                    case "listGrantsOfGroups" -> new Page<>(grants.stream()
                            .filter(g -> g.grant().catalogId().equals(args[0])
                                    && ((Set<String>) args[1]).contains(g.grant().group()))
                            .toList(), Optional.empty());
                    case "attributesOf" -> attributes.stream()
                            .filter(a -> ((Set<Securable>) args[0]).contains(a.target())).toList();
                    case "holdsOn" -> List.of();
                    case "rulesOn" -> List.copyOf(rules.getOrDefault((UUID) args[0], List.of()));
                    case "readPointer" -> Optional.of(new Pointer("s3://gate/positions/metadata/00001.metadata.json",
                            1, "ab12", new byte[0]));
                    case "toString" -> "the test's store";
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }
}
