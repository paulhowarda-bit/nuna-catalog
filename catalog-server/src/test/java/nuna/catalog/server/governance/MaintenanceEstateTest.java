package nuna.catalog.server.governance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.lang.reflect.Proxy;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import nuna.catalog.core.CatalogError;
import nuna.catalog.core.Names;
import nuna.catalog.core.principal.Caller;
import nuna.catalog.core.principal.Principal;
import nuna.catalog.security.Authorizer;
import nuna.catalog.security.Privilege;
import nuna.catalog.security.Separation;
import nuna.catalog.server.rest.RestSettings;
import nuna.catalog.server.security.Denials;
import nuna.catalog.server.security.DenialsForTests;
import nuna.catalog.server.security.Holdings;
import nuna.catalog.server.telemetry.RequestIds;
import nuna.catalog.store.CatalogObject;
import nuna.catalog.store.CatalogObject.Kind;
import nuna.catalog.store.CatalogStore;
import nuna.catalog.store.GovernanceAttribute;
import nuna.catalog.store.Grant;
import nuna.catalog.store.GrantRecord;
import nuna.catalog.store.LegalHold;
import nuna.catalog.store.MaintenanceJob;
import nuna.catalog.store.MaintenanceNight;
import nuna.catalog.store.Name;
import nuna.catalog.store.NamespaceRecord;
import nuna.catalog.store.Page;
import nuna.catalog.store.PageRequest;
import nuna.catalog.store.PageToken;
import nuna.catalog.store.Securable;
import nuna.catalog.store.TableAssessment;
import nuna.catalog.store.TableHealth;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import tools.jackson.databind.JsonNode;

/**
 * Every table, one row each, for the console's ice line (D45, backlog 127): a row for each table the caller may
 * select and none for any other, with its assessment, whether a hold or freeze covers it from any level, whether it is
 * closed until classified, and whether last night's compaction of it succeeded; paged, and bounded in the tables one
 * page looks at. The route and the authorizer are the real ones over a store double, so this needs only Java.
 */
class MaintenanceEstateTest {

    private static final Instant NOW = Instant.parse("2026-11-22T07:00:00Z");
    private static final LocalDate LAST_NIGHT = LocalDate.parse("2026-11-21");
    private static final LocalDate NIGHT_BEFORE = LocalDate.parse("2026-11-20");

    /** Select on markets, reference and finance; nothing on sandbox. */
    private static final Caller RITA = person("rita", "readers");
    /** Describe on the catalog: names, but no select. */
    private static final Caller DAN = person("dan", "describers");
    /** Nothing in the catalog. */
    private static final Caller NED = person("ned", "newcomers");

    private final UUID catalogId = UUID.randomUUID();
    private final Securable root = new Securable(Securable.Kind.CATALOG, catalogId);
    private final Map<UUID, NamespaceRecord> namespaces = new LinkedHashMap<>();
    private final Map<UUID, CatalogObject> objects = new LinkedHashMap<>();
    private final Map<UUID, TableAssessment> assessments = new LinkedHashMap<>();
    private final List<GrantRecord> grants = new ArrayList<>();
    private final List<GovernanceAttribute> attributes = new ArrayList<>();
    private final List<LegalHold> holds = new ArrayList<>();
    private final List<MaintenanceJob> jobs = new ArrayList<>();

    private final NamespaceRecord markets = namespace(null, "markets");
    private final NamespaceRecord eu = namespace(markets, "eu");
    /** Small files, held through markets.eu; compacted the night before last, not last night. */
    private final CatalogObject trades = table(eu, "trades");
    /** Equality deletes; last night's compaction of it was cut off. */
    private final CatalogObject orders = table(eu, "orders");
    /** In shape since last night's compaction of it succeeded. */
    private final CatalogObject quotes = table(eu, "quotes");
    /** Never assessed. */
    private final CatalogObject fx = table(markets, "fx");
    /** Frozen with reference. */
    private final NamespaceRecord reference = namespace(null, "reference");
    private final CatalogObject currencies = table(reference, "currencies");
    /** Regulated: journal is classified, accounts is closed until classified. */
    private final NamespaceRecord finance = namespace(null, "finance");
    private final NamespaceRecord ledger = namespace(finance, "ledger");
    private final CatalogObject journal = table(ledger, "journal");
    private final CatalogObject accounts = table(ledger, "accounts");
    /** No grant of rita's reaches it; compacted last night. */
    private final NamespaceRecord sandbox = namespace(null, "sandbox");
    private final CatalogObject scratch = table(sandbox, "scratch");

    private final CatalogStore store = store();
    private final MockHttpServletRequest http = new MockHttpServletRequest();
    private final MaintenanceRoutes routes;
    private Caller caller = RITA;

    MaintenanceEstateTest() {
        http.setAttribute(RequestIds.class.getName(), "request-1");
        for (NamespaceRecord readable : List.of(markets, reference, finance)) {
            grant(new Securable(Securable.Kind.NAMESPACE, readable.id()), "readers", Privilege.SELECT);
        }
        grant(root, "describers", Privilege.DESCRIBE);
        attribute(namespaceOf(finance), GovernanceAttribute.Kind.REGULATED, "true");
        attribute(journal.securable(), GovernanceAttribute.Kind.CLASSIFICATION, "confidential");
        attribute(namespaceOf(reference), GovernanceAttribute.Kind.DR_FREEZE, "DR drill 2026-11-21");
        holds.add(new LegalHold(UUID.randomUUID(), namespaceOf(eu), "Matter 2026-114", "stella", NOW));

        assess(trades, 2316, 17, 0, Set.of("small-files"), 3.4);
        assess(orders, 640, 30, 5, Set.of("equality-deletes", "small-files"), 6.1);
        assess(quotes, 120, 0, 0, Set.of(), 0);
        assess(currencies, 9, 0, 0, Set.of(), 0);
        assess(journal, 204, 6, 0, Set.of("small-files"), 1.2);
        assess(accounts, 22, 0, 0, Set.of(), 0);
        assess(scratch, 31, 7, 0, Set.of("small-files"), 1.4);
        compaction(LAST_NIGHT, quotes, MaintenanceJob.Outcome.SUCCEEDED);
        compaction(LAST_NIGHT, orders, MaintenanceJob.Outcome.CUT_OFF);
        compaction(LAST_NIGHT, scratch, MaintenanceJob.Outcome.SUCCEEDED);
        compaction(NIGHT_BEFORE, trades, MaintenanceJob.Outcome.SUCCEEDED);

        RestSettings settings = new RestSettings(100, 1000, 1_048_576, 1000, 268_435_456, Duration.ofDays(30),
                Duration.ofMinutes(5), "the identity provider", URI.create("urn:nuna:test"), 100, null);
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        Separation separation = new Separation(Set.of("grant-admins"), Set.of("platform-ops"));
        Authorizer authorizer = new Authorizer(store, store, store, separation, false, Set.of());
        Denials denials = DenialsForTests.denials(event -> { }, settings, clock);
        Holdings holdings = new Holdings(authorizer, store, settings, separation, denials);
        Management management = new Management(store, request -> caller, settings, holdings);
        CatalogNames catalogs = new CatalogNames(null, null) {
            @Override
            String name(UUID id) {
                return id.equals(catalogId) ? "gate" : null;
            }
        };
        routes = new MaintenanceRoutes(store, management, null, null, catalogs, null, null, clock, authorizer);
    }

    @Test
    void eachTableTheCallerMaySelectIsOneRowWithItsAssessmentHoldFreezeAndLastNight() {
        JsonNode body = estate(null, null);

        assertThat(body.get("night").asString()).isEqualTo(LAST_NIGHT.toString());
        assertThat(body.has("next-page-token")).isFalse();
        Map<String, JsonNode> rows = rows(body);
        assertThat(rows.keySet()).as("scratch, in sandbox, is not rita's to see").containsExactlyInAnyOrder(
                "markets.eu.trades", "markets.eu.orders", "markets.eu.quotes", "markets.fx", "reference.currencies",
                "finance.ledger.journal", "finance.ledger.accounts");

        JsonNode tradesRow = rows.get("markets.eu.trades");
        assertThat(tradesRow.get("id").asString()).isEqualTo(trades.id().toString());
        assertThat(tradesRow.get("catalog").asString()).isEqualTo("gate");
        JsonNode assessed = tradesRow.get("assessment");
        assertThat(assessed.get("assessed-at").asString()).isEqualTo(NOW.minusSeconds(600).toString());
        assertThat(assessed.get("needs-compaction").asBoolean()).isTrue();
        assertThat(assessed.get("reasons").valueStream().map(JsonNode::asString).toList())
                .containsExactly("small-files");
        assertThat(assessed.get("score").asDouble()).isEqualTo(3.4);
        assertThat(assessed.get("data-files").asLong()).isEqualTo(2316);
        assertThat(flags(tradesRow)).as("held through markets.eu; compacted the night before, not last night")
                .containsExactly(true, false, false, false);

        assertThat(rows.get("markets.eu.orders").get("assessment").get("reasons").valueStream()
                .map(JsonNode::asString).toList()).containsExactly("equality-deletes", "small-files");
        assertThat(flags(rows.get("markets.eu.orders"))).as("a compaction cut off fixed nothing")
                .containsExactly(true, false, false, false);
        assertThat(flags(rows.get("markets.eu.quotes"))).containsExactly(true, false, false, true);
        assertThat(rows.get("markets.eu.quotes").get("assessment").get("needs-compaction").asBoolean()).isFalse();
        assertThat(rows.get("markets.fx").has("assessment")).as("never assessed").isFalse();
        assertThat(flags(rows.get("markets.fx"))).containsExactly(false, false, false, false);
        assertThat(flags(rows.get("reference.currencies"))).as("frozen with reference")
                .containsExactly(false, true, false, false);
        assertThat(flags(rows.get("finance.ledger.journal"))).containsExactly(false, false, false, false);
    }

    /** Closed until classified (D27): it counts for a caller whose grants give select, and no other. */
    @Test
    void aTableClosedUntilClassifiedCountsForItsGranteesWithClosedSet() {
        Map<String, JsonNode> rows = rows(estate(null, null));
        assertThat(flags(rows.get("finance.ledger.accounts"))).containsExactly(false, false, true, false);
        assertThat(rows.get("finance.ledger.accounts").get("assessment").get("data-files").asLong()).isEqualTo(22);

        for (Caller other : List.of(DAN, NED)) {
            caller = other;
            JsonNode body = estate(null, null);
            assertThat(body.get("tables")).as("%s may select nothing", other.principal().id()).isEmpty();
            assertThat(body.has("next-page-token")).isFalse();
        }
    }

    @Test
    void pagesWalkEveryRowOnceAndAPageHoldsAtLeastOneRow() {
        List<String> all = rows(estate(null, null)).values().stream().map(row -> row.get("id").asString()).toList();

        List<String> paged = new ArrayList<>();
        String token = null;
        do {
            JsonNode page = estate(token, 2);
            assertThat(page.get("tables").size()).isLessThanOrEqualTo(2);
            page.get("tables").forEach(row -> paged.add(row.get("id").asString()));
            token = page.has("next-page-token") ? page.get("next-page-token").asString() : null;
        } while (token != null);
        assertThat(paged).containsExactlyElementsOf(all).hasSize(7);

        JsonNode one = estate(null, 0);
        assertThat(one.get("tables")).hasSize(1);
        assertThat(one.has("next-page-token")).isTrue();
    }

    @Test
    void aPageHoldsAtMostFiveHundredRows() {
        for (int i = 0; i < 600; i++) {
            table(markets, "t" + i);
        }

        JsonNode page = estate(null, 100_000);

        assertThat(page.get("tables")).hasSize(MaintenanceEstate.MAX_PAGE);
        assertThat(page.has("next-page-token")).isTrue();
    }

    /** A caller who may select few tables gets pages that stop after 2,000 tables looked at, with a token. */
    @Test
    void aPageLooksAtTwoThousandTablesAtMost() {
        objects.clear();
        for (int i = 0; i < MaintenanceEstate.MAX_EXAMINED + 100; i++) {
            table(sandbox, "s" + i, new UUID(0, i));
        }
        CatalogObject last = table(markets, "last", new UUID(Long.MAX_VALUE, Long.MAX_VALUE));

        JsonNode first = estate(null, null);
        assertThat(first.get("tables")).as("all 2,000 looked at are hidden").isEmpty();
        assertThat(first.has("next-page-token")).isTrue();

        JsonNode second = estate(first.get("next-page-token").asString(), null);
        assertThat(second.get("tables")).hasSize(1);
        assertThat(second.get("tables").get(0).get("id").asString()).isEqualTo(last.id().toString());
        assertThat(second.has("next-page-token")).isFalse();
    }

    @Test
    void withNoNightYetNothingWasFixedLastNight() {
        jobs.clear();

        JsonNode body = estate(null, null);

        assertThat(body.has("night")).isFalse();
        assertThat(body.get("tables")).allSatisfy(row -> assertThat(row.get("fixed-last-night").asBoolean())
                .isFalse());
    }

    @Test
    void aTokenTheRouteDidNotIssueIsABadRequest() {
        Throwable refused = catchThrowable(() -> routes.estate("nope", null, http));

        assertThat(refused).isInstanceOfSatisfying(CatalogError.class, error -> {
            assertThat(error.status()).isEqualTo(400);
            assertThat(error.type()).isEqualTo("BadRequestException");
        });
    }

    // ---- helpers ----

    private JsonNode estate(String token, Integer size) {
        ResponseEntity<String> response = routes.estate(token, size, http);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        return Management.JSON.readTree(response.getBody());
    }

    /** The rows by namespace levels and name, joined by dots. */
    private static Map<String, JsonNode> rows(JsonNode body) {
        Map<String, JsonNode> rows = new LinkedHashMap<>();
        for (JsonNode row : body.get("tables")) {
            List<String> path = new ArrayList<>(row.get("namespace").valueStream().map(JsonNode::asString).toList());
            path.add(row.get("name").asString());
            rows.put(String.join(".", path), row);
        }
        return rows;
    }

    /** Held, frozen, closed and fixed last night. */
    private static List<Boolean> flags(JsonNode row) {
        return List.of(row.get("held").asBoolean(), row.get("frozen").asBoolean(), row.get("closed").asBoolean(),
                row.get("fixed-last-night").asBoolean());
    }

    private static Caller person(String id, String... groups) {
        return new Caller(new Principal(id, Set.of(groups)), "console", false, Optional.empty());
    }

    private NamespaceRecord namespace(NamespaceRecord parent, String name) {
        NamespaceRecord record = new NamespaceRecord(catalogId, UUID.randomUUID(),
                parent == null ? catalogId : parent.id(), new Name(Names.normalize(name), name), Map.of(), 1);
        namespaces.put(record.id(), record);
        return record;
    }

    private static Securable namespaceOf(NamespaceRecord namespace) {
        return new Securable(Securable.Kind.NAMESPACE, namespace.id());
    }

    private CatalogObject table(NamespaceRecord namespace, String name) {
        return table(namespace, name, UUID.randomUUID());
    }

    private CatalogObject table(NamespaceRecord namespace, String name, UUID id) {
        CatalogObject object = CatalogObject.active(catalogId, id, namespace.id(), new Name(Names.normalize(name), name),
                Kind.TABLE, "s3://gate/" + id);
        objects.put(id, object);
        return object;
    }

    private void grant(Securable on, String group, Privilege privilege) {
        grants.add(new GrantRecord(new Grant(catalogId, on, group, privilege.wireName()), "test", Instant.EPOCH));
    }

    private void attribute(Securable on, GovernanceAttribute.Kind kind, String value) {
        attributes.add(new GovernanceAttribute(on, kind, value, "management-api", "stella", NOW.minusSeconds(3600)));
    }

    private void assess(CatalogObject table, long data, long small, long equality, Set<String> reasons, double score) {
        assessments.put(table.id(), new TableAssessment(NOW.minusSeconds(600), data, small, 0, 0, equality, reasons,
                score));
    }

    private void compaction(LocalDate night, CatalogObject table, MaintenanceJob.Outcome outcome) {
        Instant opens = night.atTime(22, 30).toInstant(ZoneOffset.UTC);
        jobs.add(new MaintenanceJob(UUID.randomUUID(), "maintenance", night, opens, opens.plus(Duration.ofHours(5)),
                MaintenanceJob.Kind.COMPACTION, table.id(), Optional.of(1), opens, Optional.of(opens.plusSeconds(600)),
                outcome, 10, 1, 0, 0, 0));
    }

    /** The newest nights with a job, on or before {@code through}, at most {@code limit}; only dates matter here. */
    private List<MaintenanceNight> nights(LocalDate through, int limit) {
        return jobs.stream().map(MaintenanceJob::night).distinct().filter(night -> !night.isAfter(through))
                .sorted(Comparator.reverseOrder()).limit(limit)
                .map(night -> new MaintenanceNight(night, NOW, NOW, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)).toList();
    }

    /** One page of {@code items}, {@code page.size()} at a time, as a store pages them. */
    private static <T> Page<T> page(List<T> items, PageRequest page) {
        int from = page.token().map(token -> Integer.parseInt(PageToken.decode(token, 1).getFirst())).orElse(0);
        int to = Math.min(items.size(), from + page.size());
        return new Page<>(List.copyOf(items.subList(from, to)),
                to < items.size() ? Optional.of(PageToken.encode(Integer.toString(to))) : Optional.empty());
    }

    /** The estate above, its grants, attributes, holds, assessments and jobs; any other call fails the test. */
    @SuppressWarnings("unchecked")
    private CatalogStore store() {
        return (CatalogStore) Proxy.newProxyInstance(CatalogStore.class.getClassLoader(),
                new Class<?>[] {CatalogStore.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "readNamespace" -> Optional.ofNullable(namespaces.get((UUID) args[0]));
                    case "listActiveObjects" -> page(objects.values().stream().filter(o -> o.kind() == args[0])
                            .sorted(Comparator.comparing(CatalogObject::id)).toList(), (PageRequest) args[1]);
                    case "readTableHealth" -> Optional.ofNullable(objects.get((UUID) args[0])).map(table ->
                            new TableHealth(table, Optional.ofNullable(assessments.get(table.id())),
                                    Optional.empty()));
                    case "grantsFor" -> grants.stream()
                            .filter(g -> ((Set<Securable>) args[0]).contains(g.grant().securable())
                                    && ((Set<String>) args[1]).contains(g.grant().group()))
                            .toList();
                    case "attributesOf" -> attributes.stream()
                            .filter(a -> ((Set<Securable>) args[0]).contains(a.target())).toList();
                    case "holdsOn" -> holds.stream()
                            .filter(h -> ((Set<Securable>) args[0]).contains(h.target())).toList();
                    case "listMaintenanceNights" -> nights((LocalDate) args[0], (int) args[1]);
                    case "listMaintenanceJobs" -> jobs.stream().filter(job -> job.night().equals(args[0])
                            && ((Set<MaintenanceJob.Kind>) args[1]).contains(job.kind())).toList();
                    case "toString" -> "the test's store";
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }
}
