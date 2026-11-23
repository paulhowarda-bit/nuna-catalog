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
import java.util.function.Supplier;
import nuna.catalog.audit.CatalogEvent;
import nuna.catalog.audit.Denial;
import nuna.catalog.audit.HoldChange;
import nuna.catalog.core.CatalogError;
import nuna.catalog.core.Names;
import nuna.catalog.core.principal.Caller;
import nuna.catalog.core.principal.Principal;
import nuna.catalog.security.Authorizer;
import nuna.catalog.security.Privilege;
import nuna.catalog.security.Separation;
import nuna.catalog.server.rest.AccessDenied;
import nuna.catalog.server.rest.AuditPublisher;
import nuna.catalog.server.rest.RestSettings;
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
import nuna.catalog.store.LegalHold;
import nuna.catalog.store.Name;
import nuna.catalog.store.NamespaceRecord;
import nuna.catalog.store.Page;
import nuna.catalog.store.PageRequest;
import nuna.catalog.store.PageToken;
import nuna.catalog.store.Securable;
import org.apache.kafka.common.errors.TimeoutException;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import tools.jackson.databind.JsonNode;

/**
 * A catalog's governance summary and its holds and freezes (D45, item 128), and the retention classes a maintenance
 * policy names for the console to offer (item 140): the counts the console's governance page
 * shows, with a table closed until classified exactly when the authorizer closes it (D27); the holds and freezes
 * standing now, from the store, and those lifted, from the audit topic; and who may read them, describe on the
 * catalog, refusals audited and names hidden as on every management route (D18). The routes, guard and authorizer
 * are the real ones over a store double that keeps attributes and holds, and the audit topic is Kafka's
 * {@code MockConsumer} ({@link HoldHistoryTest}), so this needs only Java.
 */
class GovernanceSummaryTest {

    private static final Instant NOW = Instant.parse("2026-11-22T12:00:00Z");

    /** Describe on the catalog. */
    private static final Caller ANN = person("ann", "analysts");
    /** Manage-tags on the catalog, the tag steward. */
    private static final Caller STELLA = person("stella", "stewards");
    private static final Caller GINA = person("gina", "grant-admins");
    /** Select on finance.ledger.journal only. */
    private static final Caller TOM = person("tom", "journal-readers");
    /** Nothing in the catalog. */
    private static final Caller NED = person("ned", "newcomers");
    private static final Caller OLGA = person("olga", "platform-ops");

    private final CatalogRecord catalog = new CatalogRecord(UUID.randomUUID(), "gate", "gate");
    private final Securable root = new Securable(Securable.Kind.CATALOG, catalog.id());
    private final Map<UUID, NamespaceRecord> namespaces = new LinkedHashMap<>();
    private final Map<UUID, CatalogObject> objects = new LinkedHashMap<>();
    private final List<GrantRecord> grants = new ArrayList<>();
    private final List<GovernanceAttribute> attributes = new ArrayList<>();
    private final List<LegalHold> holds = new ArrayList<>();
    private final List<CatalogEvent> audit = new ArrayList<>();

    private final NamespaceRecord finance = namespace(null, "finance");
    private final NamespaceRecord ledger = namespace(finance, "ledger");
    /** Classified here, and held. */
    private final CatalogObject journal = object(ledger, "journal", Kind.TABLE);
    /** No classification in a regulated area: closed. */
    private final CatalogObject accounts = object(ledger, "accounts", Kind.TABLE);
    /** Inherits archive's classification, which labels it but does not open it: closed. */
    private final NamespaceRecord archive = namespace(ledger, "archive");
    private final CatalogObject cash = object(archive, "cash", Kind.TABLE);
    /** Frozen, classification inherited from markets, held through markets.eu. */
    private final NamespaceRecord markets = namespace(null, "Markets");
    private final NamespaceRecord eu = namespace(markets, "eu");
    private final CatalogObject trades = object(eu, "trades", Kind.TABLE);
    private final CatalogObject daily = object(eu, "daily", Kind.VIEW);
    /** Unclassified, but in no regulated area: open. */
    private final NamespaceRecord sandbox = namespace(null, "sandbox");
    private final CatalogObject scratch = object(sandbox, "scratch", Kind.TABLE);

    private final LegalHold journalHold = hold(journal.securable(), "Matter 2026-097: year-end audit sample", 3);
    private final LegalHold euHold = hold(namespaceOf(eu), "Matter 2026-114: trade surveillance review", 2);
    private final LegalHold viewHold = hold(daily.securable(), "Matter 2026-120: daily report", 0);

    private final HoldHistoryTest topic = new HoldHistoryTest();
    private final Authorizer authorizer;
    private final Management management;
    private final ManagementGuard guard;
    private final CatalogStore store = store();
    private final MockHttpServletRequest http = new MockHttpServletRequest();
    private Caller caller = ANN;

    GovernanceSummaryTest() {
        http.setAttribute(RequestIds.class.getName(), "request-1");
        grant(root, "analysts", Privilege.DESCRIBE);
        grant(root, "stewards", Privilege.MANAGE_TAGS);
        grant(journal.securable(), "journal-readers", Privilege.SELECT);
        attribute(namespaceOf(finance), GovernanceAttribute.Kind.REGULATED, "true", 9);
        attribute(namespaceOf(archive), GovernanceAttribute.Kind.CLASSIFICATION, "restricted", 9);
        attribute(journal.securable(), GovernanceAttribute.Kind.CLASSIFICATION, "confidential", 8);
        attribute(namespaceOf(markets), GovernanceAttribute.Kind.CLASSIFICATION, "internal", 9);
        attribute(namespaceOf(markets), GovernanceAttribute.Kind.DR_FREEZE, "DR drill 2026-11-21", 1);
        attribute(namespaceOf(sandbox), GovernanceAttribute.Kind.OWNER, "desk-7", 9);

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
    }

    // ---- the summary ----

    @Test
    void theSummaryCountsTheCatalogsTablesAndNamesItsRegulatedAreasAndClosedTables() {
        JsonNode summary = json(routes(topic::history).summary("gate", http));

        assertThat(summary.get("catalog").asString()).isEqualTo("gate");
        JsonNode tables = summary.get("tables");
        assertThat(tables.get("total").asInt()).as("tables, not views").isEqualTo(5);
        assertThat(tables.get("classified").asInt()).as("journal here, cash and trades inherited").isEqualTo(3);
        assertThat(tables.get("closed").asInt()).isEqualTo(2);
        assertThat(tables.get("held").asInt()).as("journal, and trades through markets.eu").isEqualTo(2);
        assertThat(tables.get("frozen").asInt()).as("trades, through markets").isEqualTo(1);
        assertThat(summary.get("holds").asInt()).isEqualTo(3);
        assertThat(summary.get("freezes").asInt()).isEqualTo(1);
        assertThat(summary.get("regulated")).hasSize(1);
        JsonNode area = summary.get("regulated").get(0);
        assertThat(area.get("on").get("kind").asString()).isEqualTo("namespace");
        assertThat(area.get("on").get("id").asString()).isEqualTo(finance.id().toString());
        assertThat(paths(List.of(area.get("on")))).containsExactly("finance");
        assertThat(area.get("tables").asInt()).isEqualTo(3);
        assertThat(area.get("closed").asInt()).isEqualTo(2);
        assertThat(paths(summary.get("closed").valueStream().toList()))
                .containsExactly("finance.ledger.accounts", "finance.ledger.archive.cash");
        assertThat(summary.get("closed").get(0).get("id").asString()).isEqualTo(accounts.id().toString());
    }

    /** A table is closed in the summary exactly when the authorizer refuses a load of it as unclassified (D27). */
    @Test
    void aTableIsClosedInTheSummaryExactlyWhenTheAuthorizerClosesIt() {
        grant(root, "readers", Privilege.SELECT);
        Caller reader = person("rita", "readers");
        List<String> refused = new ArrayList<>();
        for (CatalogObject object : objects.values()) {
            if (object.kind() == Kind.TABLE && authorizer.decide(reader, new Authorizer.Target.TableOrView(object),
                    new Authorizer.Operation("loadTable", Privilege.SELECT, false))
                    instanceof Authorizer.Decision.Denied denied && denied.reason() == Authorizer.Reason.UNCLASSIFIED) {
                refused.add(object.id().toString());
            }
        }

        JsonNode closed = json(routes(topic::history).summary("gate", http)).get("closed");

        assertThat(closed.valueStream().map(on -> on.get("id").asString()).toList())
                .containsExactlyInAnyOrderElementsOf(refused).hasSize(2);
    }

    @Test
    void aCatalogWithNothingInItCountsNothing() {
        namespaces.clear();
        objects.clear();
        attributes.clear();
        holds.clear();

        JsonNode summary = json(routes(topic::history).summary("gate", http));

        assertThat(summary.get("tables").get("total").asInt()).isZero();
        assertThat(summary.get("holds").asInt()).isZero();
        assertThat(summary.get("regulated")).isEmpty();
        assertThat(summary.get("closed")).isEmpty();
    }

    // ---- the holds and freezes ----

    @Test
    void theHoldsAndFreezesStandingAreListedNewestFirstAndTheLiftedOnesFromTheAuditTopic() {
        UUID released = UUID.randomUUID();
        nuna.catalog.audit.Securable lineage = new nuna.catalog.audit.Securable(
                nuna.catalog.audit.Securable.Kind.TABLE, UUID.randomUUID(), "gate", List.of("m8", "t_lineage"));
        topic.send(0, NOW.minus(Duration.ofDays(9)), new HoldChange(HoldChange.Action.PLACED, released, lineage,
                "M8 sign-off evidence", HoldHistoryTest.actor("stella")));
        topic.send(0, NOW.minus(Duration.ofDays(4)), new HoldChange(HoldChange.Action.RELEASED, released, lineage,
                "M8 sign-off evidence", HoldHistoryTest.actor("sam")));

        JsonNode body = json(routes(topic::history).holdsAndFreezes("gate", http));

        assertThat(body.get("catalog").asString()).isEqualTo("gate");
        JsonNode standing = body.get("standing");
        assertThat(standing.valueStream().map(each -> each.get("kind").asString()).toList())
                .containsExactly("hold", "freeze", "hold", "hold");
        assertThat(standing.valueStream().map(each -> each.get("reason").asString()).toList()).containsExactly(
                viewHold.reason(), "DR drill 2026-11-21", euHold.reason(), journalHold.reason());
        JsonNode hold = standing.get(3);
        assertThat(hold.get("id").asString()).isEqualTo(journalHold.id().toString());
        assertThat(hold.get("on").get("kind").asString()).isEqualTo("table");
        assertThat(hold.get("on").get("id").asString()).isEqualTo(journal.id().toString());
        assertThat(paths(List.of(hold.get("on")))).containsExactly("finance.ledger.journal");
        assertThat(hold.get("placed-by").asString()).isEqualTo("stella");
        assertThat(hold.get("placed-at").asString()).isEqualTo(journalHold.placedAt().toString());
        JsonNode freeze = standing.get(1);
        assertThat(freeze.has("id")).isFalse();
        assertThat(paths(List.of(freeze.get("on")))).containsExactly("Markets");
        assertThat(freeze.get("placed-by").asString()).isEqualTo("oscar");
        assertThat(freeze.get("placed-at").asString()).isEqualTo(NOW.minus(Duration.ofDays(1)).toString());
        assertThat(paths(List.of(standing.get(0).get("on")))).containsExactly("Markets.eu.daily");

        assertThat(body.get("lifted")).hasSize(1);
        JsonNode lifted = body.get("lifted").get(0);
        assertThat(lifted.get("kind").asString()).isEqualTo("hold");
        assertThat(lifted.get("id").asString()).isEqualTo(released.toString());
        assertThat(lifted.get("on").get("id").asString()).isEqualTo(lineage.id().toString());
        assertThat(paths(List.of(lifted.get("on")))).containsExactly("m8.t_lineage");
        assertThat(lifted.get("reason").asString()).isEqualTo("M8 sign-off evidence");
        assertThat(lifted.get("placed-by").asString()).isEqualTo("stella");
        assertThat(lifted.get("placed-at").asString()).isEqualTo(NOW.minus(Duration.ofDays(9)).toString());
        assertThat(lifted.get("lifted-by").asString()).isEqualTo("sam");
        assertThat(lifted.get("lifted-at").asString()).isEqualTo(NOW.minus(Duration.ofDays(4)).toString());
        JsonNode history = body.get("history");
        assertThat(history.get("available").asBoolean()).isTrue();
        assertThat(history.get("complete").asBoolean()).isTrue();
        assertThat(history.get("since").isNull()).isTrue();
        assertThat(history.get("scan-limit").asInt()).isEqualTo(HoldHistory.SCAN_LIMIT);
        assertThat(history.get("partitions")).hasSize(2);
        assertThat(history.get("partitions").get(0).get("to-offset").asLong()).isEqualTo(2);
    }

    @Test
    void withoutTheAuditTopicTheStandingOnesAreStillListed() {
        GovernanceRoutes unconfigured = new GovernanceRoutes(store, management, guard, null,
                new HoldHistory(HoldHistoryTest.settings(null), () -> {
                    throw new AssertionError("no consumer without the audit topic");
                }), Clock.fixed(NOW, ZoneOffset.UTC));

        JsonNode body = json(unconfigured.holdsAndFreezes("gate", http));

        assertThat(body.get("standing")).hasSize(4);
        assertThat(body.get("lifted")).isEmpty();
        assertThat(body.get("history").get("available").asBoolean()).isFalse();
        assertThat(body.get("history").get("reason").asString()).contains("not configured");
    }

    @Test
    void whenTheAuditTopicCannotBeReadTheStandingOnesAreStillListed() {
        JsonNode body = json(routes(() -> new HoldHistory(HoldHistoryTest.settings("localhost:9092"), () -> {
            throw new TimeoutException("Kafka did not answer");
        })).holdsAndFreezes("gate", http));

        assertThat(body.get("standing")).hasSize(4);
        assertThat(body.get("history").get("available").asBoolean()).isFalse();
        assertThat(body.get("history").get("reason").asString()).contains("cannot be read");
    }

    // ---- the retention classes to choose from ----

    /**
     * The maintenance policy names the retention classes the console offers (D27, item 140): each configured one, by
     * name, with its minimum snapshot age.
     */
    @Test
    void theMaintenancePolicyNamesEveryConfiguredRetentionClassWithItsMinimumAge() {
        GovernanceSettings settings = new GovernanceSettings(10, 60, Map.of(
                "trade-records", new GovernanceSettings.RetentionClass(Duration.ofDays(30)),
                "audit-evidence", new GovernanceSettings.RetentionClass(Duration.ofDays(400)),
                "scratch", new GovernanceSettings.RetentionClass(null)),
                Duration.ofDays(7), Duration.ofHours(1), Map.of());
        GovernanceRoutes routes = new GovernanceRoutes(store, management, guard, settings, topic.history(),
                Clock.fixed(NOW, ZoneOffset.UTC));

        for (JsonNode policy : List.of(json(routes.policy("gate", null, null, null, http)),
                json(routes.policy("gate", "finance\u001fledger", "journal", null, http)))) {
            JsonNode classes = policy.get("retention-classes");
            assertThat(classes.valueStream().map(c -> c.get("name").asString()).toList())
                    .containsExactly("audit-evidence", "scratch", "trade-records");
            assertThat(classes.get(0).get("minimum-snapshot-age").asString()).isEqualTo("PT9600H");
            assertThat(classes.get(1).get("minimum-snapshot-age").isNull()).as("a class with no minimum").isTrue();
            assertThat(classes.get(2).get("minimum-snapshot-age").asString()).isEqualTo("PT720H");
        }
    }

    @Test
    void withNoRetentionClassConfiguredThereIsNoneToChoose() {
        GovernanceSettings settings = new GovernanceSettings(10, 60, Map.of(), Duration.ofDays(7), Duration.ofHours(1),
                Map.of());
        GovernanceRoutes routes = new GovernanceRoutes(store, management, guard, settings, topic.history(),
                Clock.fixed(NOW, ZoneOffset.UTC));

        assertThat(json(routes.policy("gate", "sandbox", null, null, http)).get("retention-classes")).isEmpty();
    }

    // ---- who may read them ----

    @Test
    void describeOnTheCatalogReadsThemAsDoTheStewardAndAGrantAdmin() {
        GovernanceRoutes routes = routes(topic::history);
        for (Caller reader : List.of(ANN, STELLA, GINA)) {
            caller = reader;
            assertThat(answer(() -> routes.summary("gate", http))).as("summary for %s", reader.principal().id())
                    .isEqualTo(200);
            assertThat(answer(() -> routes.holdsAndFreezes("gate", http))).as("holds for %s",
                    reader.principal().id()).isEqualTo(200);
        }
        assertThat(denials()).isEmpty();
    }

    @Test
    void aCallerWithoutDescribeOnTheCatalogIsRefusedAndTheRefusalAudited() {
        GovernanceRoutes routes = routes(topic::history);
        for (Caller refused : List.of(TOM, OLGA)) {
            caller = refused;
            assertThat(answer(() -> routes.summary("gate", http))).as("summary for %s", refused.principal().id())
                    .isEqualTo(403);
            assertThat(answer(() -> routes.holdsAndFreezes("gate", http))).as("holds for %s",
                    refused.principal().id()).isEqualTo(403);
        }

        assertThat(denials()).extracting(Denial::operation).containsExactly("loadGovernanceSummary",
                "listHoldsAndFreezes", "loadGovernanceSummary", "listHoldsAndFreezes");
        Denial first = denials().getFirst();
        assertThat(first.privilege()).isEqualTo("describe");
        assertThat(first.reason()).startsWith("no_privilege");
        assertThat(first.securable().kind()).isEqualTo(nuna.catalog.audit.Securable.Kind.CATALOG);
        assertThat(first.securable().id()).isEqualTo(catalog.id());
        assertThat(first.actor().principal()).isEqualTo("tom");
    }

    @Test
    void aCallerThatHoldsNothingInTheCatalogIsAnsweredAsForOneThatDoesNotExist() {
        caller = NED;
        GovernanceRoutes routes = routes(topic::history);

        assertThat(error(() -> routes.summary("gate", http)).type()).isEqualTo("NoSuchWarehouseException");
        assertThat(error(() -> routes.holdsAndFreezes("gate", http)).type()).isEqualTo("NoSuchWarehouseException");
        assertThat(error(() -> routes.summary("nope", http)).type()).isEqualTo("NoSuchWarehouseException");
        assertThat(denials()).extracting(Denial::operation).containsExactly("loadGovernanceSummary",
                "listHoldsAndFreezes");
    }

    // ---- helpers ----

    private GovernanceRoutes routes(Supplier<HoldHistory> history) {
        return new GovernanceRoutes(store, management, guard, null, history.get(), Clock.fixed(NOW, ZoneOffset.UTC));
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

    /** Each level's namespace levels and name, joined by dots. */
    private static List<String> paths(List<JsonNode> levels) {
        return levels.stream().map(on -> String.join(".", on.get("path").valueStream().map(JsonNode::asString)
                .toList())).toList();
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

    private CatalogObject object(NamespaceRecord namespace, String name, Kind kind) {
        UUID id = UUID.randomUUID();
        CatalogObject object = CatalogObject.active(catalog.id(), id, namespace.id(),
                new Name(Names.normalize(name), name), kind, "s3://gate/" + id);
        objects.put(id, object);
        return object;
    }

    private void grant(Securable on, String group, Privilege privilege) {
        grants.add(new GrantRecord(new Grant(catalog.id(), on, group, privilege.wireName()), "test", Instant.EPOCH));
    }

    private void attribute(Securable on, GovernanceAttribute.Kind kind, String value, int daysAgo) {
        attributes.add(new GovernanceAttribute(on, kind, value, "management-api",
                kind == GovernanceAttribute.Kind.DR_FREEZE ? "oscar" : "stella", NOW.minus(Duration.ofDays(daysAgo))));
    }

    private LegalHold hold(Securable on, String reason, int daysAgo) {
        LegalHold hold = new LegalHold(UUID.randomUUID(), on, reason, "stella", NOW.minus(Duration.ofDays(daysAgo))
                .minusSeconds(60));
        holds.add(hold);
        return hold;
    }

    private Optional<NamespaceRecord> findNamespace(List<String> path) {
        UUID parent = catalog.id();
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

    /** One page of {@code items}, {@code page.size()} at a time, as a store pages them. */
    private static <T> Page<T> page(List<T> items, PageRequest page) {
        int from = page.token().map(token -> Integer.parseInt(PageToken.decode(token, 1).getFirst())).orElse(0);
        int to = Math.min(items.size(), from + page.size());
        return new Page<>(List.copyOf(items.subList(from, to)),
                to < items.size() ? Optional.of(PageToken.encode(Integer.toString(to))) : Optional.empty());
    }

    /** The estate above, the groups' grants on it, and its attributes and holds; any other call fails the test. */
    @SuppressWarnings("unchecked")
    private CatalogStore store() {
        return (CatalogStore) Proxy.newProxyInstance(CatalogStore.class.getClassLoader(),
                new Class<?>[] {CatalogStore.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "findCatalog" -> Optional.of(catalog).filter(c -> c.normalizedName().equals(args[0]));
                    case "findNamespace" -> findNamespace((List<String>) args[1]);
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
                    case "holdsOn" -> holds.stream()
                            .filter(h -> ((Set<Securable>) args[0]).contains(h.target())).toList();
                    case "toString" -> "the test's store";
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }
}
