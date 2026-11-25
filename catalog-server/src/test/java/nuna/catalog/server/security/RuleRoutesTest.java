package nuna.catalog.server.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Proxy;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import nuna.catalog.audit.CatalogEvent;
import nuna.catalog.core.CatalogError;
import nuna.catalog.core.Names;
import nuna.catalog.core.principal.Caller;
import nuna.catalog.core.principal.Principal;
import nuna.catalog.security.Authorizer;
import nuna.catalog.security.Separation;
import nuna.catalog.server.rest.AuditPublisher;
import nuna.catalog.server.rest.RestSettings;
import nuna.catalog.server.rest.TableColumnsForTests;
import nuna.catalog.server.telemetry.RequestIds;
import nuna.catalog.store.CatalogObject;
import nuna.catalog.store.CatalogRecord;
import nuna.catalog.store.CatalogStore;
import nuna.catalog.store.Name;
import nuna.catalog.store.NamespaceRecord;
import nuna.catalog.store.RowColumnRule;
import nuna.catalog.store.WriteResult;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Setting and removing row filters and column masks (D20, backlog 244): each expression is parsed as Trino parses it
 * and refused, saying where, when it does not parse, a subquery on another table included in what parses; and each
 * rule's version, which the list gives, guards a set or removal that names it in If-Match, a stale one answering 412
 * with nothing written. The routes and authorizer are the real ones over a store double that keeps versions as the
 * stores do (GovernanceSuite checks the four stores), so this needs only Java.
 */
class RuleRoutesTest {

    private static final Instant NOW = Instant.parse("2026-11-24T12:00:00Z");
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String TABLE = "{\"type\": \"table\", \"namespace\": [\"markets\"], \"name\": \"positions\"}";
    /** A grant admin, by configuration. */
    private static final Caller GINA = new Caller(new Principal("gina", Set.of("grant-admins")), "console", false,
            Optional.empty());

    private final CatalogRecord catalog = new CatalogRecord(UUID.randomUUID(), "gate", "gate");
    private final NamespaceRecord markets = new NamespaceRecord(catalog.id(), UUID.randomUUID(), catalog.id(),
            new Name(Names.normalize("markets"), "markets"), Map.of(), 1);
    private final CatalogObject positions = CatalogObject.active(catalog.id(), UUID.randomUUID(), markets.id(),
            new Name("positions", "positions"), CatalogObject.Kind.TABLE, "s3://gate/positions");
    private final Map<String, RowColumnRule.Stored> rules = new LinkedHashMap<>();
    /** The If-Match each write passed to the store; empty for none. */
    private final List<OptionalLong> expected = new ArrayList<>();
    private final List<CatalogEvent> audit = new ArrayList<>();
    private Instant clock = NOW;
    private final RuleRoutes routes;

    RuleRoutesTest() {
        RestSettings settings = new RestSettings(100, 1000, 1_048_576, 1000, 268_435_456, Duration.ofDays(30),
                Duration.ofMinutes(5), "the identity provider", URI.create("urn:nuna:test"), 100, null);
        Clock fixed = Clock.fixed(NOW, ZoneOffset.UTC);
        AuditPublisher publisher = audit::add;
        Separation separation = new Separation(Set.of("grant-admins"), Set.of("platform-ops"));
        CatalogStore store = store();
        Authorizer authorizer = new Authorizer(store, store, store, separation, false, Set.of());
        Denials denials = DenialsForTests.denials(publisher, settings, fixed);
        Holdings holdings = new Holdings(authorizer, store, settings, separation, denials);
        routes = new RuleRoutes(store, authorizer, request -> GINA, denials, holdings, settings,
                TableColumnsForTests.of(table -> List.of("desk", "Trader", "quantity")), fixed);
    }

    // ---- expressions ----

    @Test
    void aFilterThatDoesNotParseIsRefusedSayingWhereAndNothingIsWritten() {
        CatalogError error = error(() -> set("analysts", "desk = 'EQ' AND", null, null));

        assertThat(error.status()).isEqualTo(400);
        assertThat(error.getMessage()).startsWith("row-filter is not a Trino SQL expression: at line 1, column 16, ");
        assertThat(rules).isEmpty();
        assertThat(expected).as("the store was never asked").isEmpty();
    }

    @Test
    void aMaskThatDoesNotParseIsRefusedNamingItsColumn() {
        CatalogError error = error(() -> set("analysts", "true", Map.of("trader", "concat(trader, '***'"), null));

        assertThat(error.status()).isEqualTo(400);
        assertThat(error.getMessage()).startsWith("The mask of trader is not a Trino SQL expression: at line 1, ");
        assertThat(rules).isEmpty();
    }

    @Test
    void aStatementInPlaceOfAnExpressionIsRefused() {
        assertThat(error(() -> set("analysts", "desk = 'EQ'; DROP TABLE positions", null, null)).status())
                .isEqualTo(400);
        assertThat(error(() -> set("analysts", "SELECT desk FROM positions", null, null)).status()).isEqualTo(400);
        assertThat(rules).isEmpty();
    }

    @Test
    void aSubqueryReadingAnotherTableIsAccepted() {
        String filter = "desk IN (SELECT desk FROM lake.ref.entitlements WHERE user_name = current_user)";

        assertThat(answer(() -> set("analysts", filter, Map.of("trader",
                "CASE WHEN EXISTS (SELECT 1 FROM lake.ref.desk_heads h WHERE h.desk = desk) THEN trader END"),
                null))).isEqualTo(204);

        assertThat(rules.get("analysts").rule().rowFilter()).contains(filter);
        assertThat(rules.get("analysts").rule().columnMasks()).containsOnlyKeys("Trader");
    }

    // ---- versions ----

    @Test
    void theListGivesEachRulesVersion() {
        assertThat(answer(() -> set("analysts", "desk = 'EQ'", null, null))).isEqualTo(204);
        assertThat(answer(() -> set("risk", null, Map.of("quantity", "0"), null))).isEqualTo(204);

        JsonNode listed = list();
        assertThat(listed.get("rules")).hasSize(2);
        for (JsonNode rule : listed.get("rules")) {
            assertThat(rule.get("version").asLong()).as(rule.get("group").asString())
                    .isEqualTo(rules.get(rule.get("group").asString()).version()).isPositive();
        }
    }

    @Test
    void aSetWithTheVersionItReadReplacesTheRuleAndAStaleOneAnswers412WritingNothing() {
        assertThat(answer(() -> set("analysts", "desk = 'EQ'", null, null))).isEqualTo(204);
        long read = version();

        assertThat(answer(() -> set("analysts", "desk IN ('EQ', 'FX')", null, "\"" + read + "\""))).isEqualTo(204);
        long now = version();
        assertThat(now).isGreaterThan(read);

        CatalogError stale = error(() -> set("analysts", "desk = 'FX'", null, "\"" + read + "\""));
        assertThat(stale.status()).isEqualTo(412);
        assertThat(stale.getMessage()).contains("is at version " + now);
        assertThat(rules.get("analysts").rule().rowFilter()).contains("desk IN ('EQ', 'FX')");
        assertThat(expected).containsExactly(OptionalLong.empty(), OptionalLong.of(read), OptionalLong.of(read));
    }

    @Test
    void aRemovalWithAStaleVersionAnswers412AndOneWithTheCurrentVersionRemoves() {
        assertThat(answer(() -> set("analysts", "desk = 'EQ'", null, null))).isEqualTo(204);
        long read = version();
        assertThat(answer(() -> set("analysts", "desk = 'FX'", null, null))).isEqualTo(204);

        assertThat(error(() -> remove("analysts", "\"" + read + "\"")).status()).isEqualTo(412);
        assertThat(rules).containsKey("analysts");
        assertThat(answer(() -> remove("analysts", Long.toString(version())))).as("unquoted").isEqualTo(204);
        assertThat(rules).isEmpty();
    }

    @Test
    void aSetNamingTheVersionOfARuleRemovedSinceAnswers412() {
        assertThat(answer(() -> set("analysts", "desk = 'EQ'", null, null))).isEqualTo(204);
        long read = version();
        assertThat(answer(() -> remove("analysts", null))).isEqualTo(204);
        assertThat(answer(() -> set("analysts", "desk = 'FX'", null, null))).isEqualTo(204);
        assertThat(version()).as("a rule set again never takes a version read before").isNotEqualTo(read);

        assertThat(error(() -> set("analysts", "true", null, "\"" + read + "\"")).status()).isEqualTo(412);
        assertThat(answer(() -> remove("analysts", null))).isEqualTo(204);
        CatalogError gone = error(() -> set("analysts", "true", null, "\"" + read + "\""));
        assertThat(gone.status()).isEqualTo(412);
        assertThat(gone.getMessage()).contains("has been removed");
        assertThat(rules).isEmpty();
    }

    @Test
    void anIfMatchThatNamesNoVersionIsRefused() {
        for (String tag : List.of("W/\"1\"", "*", "\"abc\"", "\"1\", \"2\"", "", "99999999999999999999")) {
            assertThat(error(() -> set("analysts", "true", null, tag)).status()).as(tag).isEqualTo(400);
            assertThat(error(() -> remove("analysts", tag)).status()).as(tag).isEqualTo(400);
        }
        assertThat(expected).isEmpty();
    }

    // ---- helpers ----

    private ResponseEntity<String> set(String group, String filter, Map<String, String> masks, String ifMatch) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("table", JSON.readTree(TABLE));
        body.put("group", group);
        if (filter != null) {
            body.put("row-filter", filter);
        }
        if (masks != null) {
            body.put("column-masks", masks);
        }
        return routes.set("gate", JSON.writeValueAsString(body), http(ifMatch));
    }

    private ResponseEntity<String> remove(String group, String ifMatch) {
        return routes.remove("gate", "{\"table\": " + TABLE + ", \"group\": \"" + group + "\"}", http(ifMatch));
    }

    private JsonNode list() {
        ResponseEntity<String> response = routes.list("gate", List.of("markets"), "positions", http(null));
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        return JSON.readTree(response.getBody());
    }

    private long version() {
        return rules.get("analysts").version();
    }

    private MockHttpServletRequest http(String ifMatch) {
        MockHttpServletRequest http = new MockHttpServletRequest();
        http.setAttribute(RequestIds.class.getName(), "request-1");
        if (ifMatch != null) {
            http.addHeader("If-Match", ifMatch);
        }
        clock = clock.plusMillis(1);
        return http;
    }

    private static int answer(Callable<ResponseEntity<String>> call) {
        try {
            return call.call().getStatusCode().value();
        } catch (CatalogError e) {
            return e.status();
        } catch (Exception e) {
            throw new AssertionError("the route failed unexpectedly", e);
        }
    }

    private static CatalogError error(Callable<ResponseEntity<String>> call) {
        try {
            call.call();
        } catch (CatalogError e) {
            return e;
        } catch (Exception e) {
            throw new AssertionError("the route failed unexpectedly", e);
        }
        throw new AssertionError("the route answered");
    }

    /** As a store puts and removes rules: the version check first, then the write at the rule's next version. */
    private WriteResult put(RowColumnRule rule, OptionalLong expect) {
        expected.add(expect);
        RowColumnRule.Stored was = rules.get(rule.group());
        long version = was == null ? 0 : was.version();
        if (expect.isPresent() && expect.getAsLong() != version) {
            return new WriteResult.VersionMismatch(version);
        }
        if (was != null && was.rule().equals(rule)) {
            return WriteResult.UNCHANGED;
        }
        rules.put(rule.group(), new RowColumnRule.Stored(rule, "gina", clock, RowColumnRule.nextVersion(
                was == null ? OptionalLong.empty() : OptionalLong.of(version), clock)));
        return WriteResult.APPLIED;
    }

    private WriteResult removed(String group, OptionalLong expect) {
        expected.add(expect);
        RowColumnRule.Stored was = rules.get(group);
        if (was == null) {
            return new WriteResult.Missing(WriteResult.Subject.RULE);
        }
        if (expect.isPresent() && expect.getAsLong() != was.version()) {
            return new WriteResult.VersionMismatch(was.version());
        }
        rules.remove(group);
        return WriteResult.APPLIED;
    }

    /** The one table and its rules; any other call fails the test. */
    @SuppressWarnings("unchecked")
    private CatalogStore store() {
        return (CatalogStore) Proxy.newProxyInstance(CatalogStore.class.getClassLoader(),
                new Class<?>[] {CatalogStore.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "findCatalog" -> catalog.normalizedName().equals(args[0]) ? Optional.of(catalog)
                            : Optional.empty();
                    case "findNamespace" -> ((List<String>) args[1]).equals(List.of("markets"))
                            ? Optional.of(markets) : Optional.empty();
                    case "readNamespace" -> markets.id().equals(args[0]) ? Optional.of(markets) : Optional.empty();
                    case "readObject" -> positions.id().equals(args[0]) ? Optional.of(positions) : Optional.empty();
                    case "findObject" -> markets.id().equals(args[0]) && "positions".equals(args[1])
                            ? Optional.of(positions) : Optional.empty();
                    case "grantsFor", "attributesOf", "holdsOn" -> List.of();
                    case "putRule" -> put((RowColumnRule) args[0], (OptionalLong) args[1]);
                    case "removeRule" -> removed((String) args[2], (OptionalLong) args[3]);
                    case "rulesOn" -> List.copyOf(rules.values());
                    case "toString" -> "the test's store";
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }
}
