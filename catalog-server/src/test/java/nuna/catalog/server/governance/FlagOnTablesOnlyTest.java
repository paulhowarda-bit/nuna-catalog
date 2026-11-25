package nuna.catalog.server.governance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Proxy;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import nuna.catalog.audit.EventCodec;
import nuna.catalog.audit.GovernanceChange;
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
import nuna.catalog.store.CatalogRecord;
import nuna.catalog.store.CatalogStore;
import nuna.catalog.store.Change;
import nuna.catalog.store.GovernanceAttribute;
import nuna.catalog.store.Grant;
import nuna.catalog.store.GrantRecord;
import nuna.catalog.store.Name;
import nuna.catalog.store.NamespaceRecord;
import nuna.catalog.store.Page;
import nuna.catalog.store.PageRequest;
import nuna.catalog.store.Securable;
import nuna.catalog.store.WriteResult;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import tools.jackson.databind.JsonNode;

/**
 * The row/column-policy flag goes on a table only (D20, backlog 239): only a table's own flag is enforced, so the set
 * route refuses it on a catalog, namespace or view with 400; the governance answer never reports a flag from above as
 * a table's own; and the flags set above a table before are removed at startup, each with its audit record. The routes,
 * guard and authorizer are the real ones over a store double, so this needs only Java.
 */
class FlagOnTablesOnlyTest {

    private static final String FLAG = "{\"value\":\"true\",\"provenance\":\"collibra:asset/7\"}";
    private static final Instant NOW = Instant.parse("2026-11-24T12:00:00Z");
    /** Manage-tags on the catalog, so on everything in it. */
    private static final Caller STELLA = new Caller(new Principal("stella", Set.of("stewards")), "console", false,
            Optional.empty());

    private final CatalogRecord catalog = new CatalogRecord(UUID.randomUUID(), "gate", "gate");
    private final Map<UUID, NamespaceRecord> namespaces = new LinkedHashMap<>();
    private final Map<UUID, CatalogObject> objects = new LinkedHashMap<>();
    private final List<GrantRecord> grants = new ArrayList<>();
    private final List<GovernanceAttribute> attributes = new ArrayList<>();
    private final List<Change> changes = new ArrayList<>();

    private final NamespaceRecord hr = namespace(null, "hr");
    private final NamespaceRecord payroll = namespace(hr, "payroll");
    private final CatalogObject staff = object(hr, "staff", Kind.TABLE);
    private final CatalogObject roster = object(hr, "roster", Kind.VIEW);
    private final CatalogObject slips = object(payroll, "slips", Kind.TABLE);

    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final Management management;
    private final GovernanceRoutes governance;
    private final MockHttpServletRequest http = new MockHttpServletRequest();

    FlagOnTablesOnlyTest() {
        http.setAttribute(RequestIds.class.getName(), "request-1");
        grants.add(new GrantRecord(new Grant(catalog.id(), new Securable(Securable.Kind.CATALOG, catalog.id()),
                "stewards", Privilege.MANAGE_TAGS.wireName()), "test", Instant.EPOCH));
        CatalogStore store = store();
        RestSettings settings = new RestSettings(100, 1000, 1_048_576, 1000, 268_435_456, Duration.ofDays(30),
                Duration.ofMinutes(5), "the identity provider", URI.create("urn:nuna:test"), 100, null);
        Separation separation = new Separation(Set.of("grant-admins"), Set.of("platform-ops"));
        Authorizer authorizer = new Authorizer(store, store, store, separation, false, Set.of());
        Denials denials = DenialsForTests.denials(event -> { }, settings, clock);
        Holdings holdings = new Holdings(authorizer, store, settings, separation, denials);
        management = new Management(store, request -> STELLA, settings, holdings);
        ManagementGuard guard = new ManagementGuard(authorizer, separation, null, denials, holdings);
        governance = new GovernanceRoutes(store, management, guard, null, null, clock);
    }

    @Test
    void theFlagIsRefusedOnACatalogANamespaceAndAView() {
        List<ThrowingSet> elsewhere = List.of(
                () -> governance.setAttribute("gate", null, null, null, "row-column-policy", FLAG, http),
                () -> governance.setAttribute("gate", "hr", null, null, "row-column-policy", FLAG, http),
                () -> governance.setAttribute("gate", "hr\u001Fpayroll", null, null, "row-column-policy", FLAG, http),
                () -> governance.setAttribute("gate", "hr", null, "roster", "row-column-policy", FLAG, http));
        for (ThrowingSet set : elsewhere) {
            assertThatThrownBy(set::call).isInstanceOfSatisfying(CatalogError.class, error -> {
                assertThat(error.status()).isEqualTo(400);
                assertThat(error.getMessage()).contains("goes on a table");
            });
        }
        assertThat(attributes).as("nothing was set").isEmpty();

        assertThat(governance.setAttribute("gate", "hr", "staff", null, "row-column-policy", FLAG, http)
                .getStatusCode().value()).isEqualTo(200);
        assertThat(attributes).extracting(GovernanceAttribute::target).containsExactly(staff.securable());
        // Other attributes still go on any level.
        assertThat(governance.setAttribute("gate", "hr", null, null, "owner",
                "{\"value\":\"people-office\",\"provenance\":\"hr feed\"}", http).getStatusCode().value())
                .isEqualTo(200);
    }

    @Test
    void aTableNeverReportsAFlagFromAboveAsItsOwn() {
        attributes.add(flag(new Securable(Securable.Kind.NAMESPACE, hr.id()), "true"));

        JsonNode answer = json(governance.governance("gate", "hr", "staff", null, http));
        assertThat(answer.get("effective").has("row-column-policy")).as("an inherited flag is not in force").isFalse();
        assertThat(answer.get("attributes")).hasSize(1);

        attributes.add(flag(staff.securable(), "false"));
        answer = json(governance.governance("gate", "hr", "staff", null, http));
        assertThat(answer.get("effective").get("row-column-policy").asString()).isEqualTo("false");
    }

    @Test
    void startupRemovesEveryFlagAboveATableAndRecordsEach() {
        attributes.add(flag(new Securable(Securable.Kind.CATALOG, catalog.id()), "true"));
        attributes.add(flag(new Securable(Securable.Kind.NAMESPACE, hr.id()), "true"));
        attributes.add(flag(new Securable(Securable.Kind.NAMESPACE, payroll.id()), "false"));
        attributes.add(flag(roster.securable(), "true"));
        attributes.add(flag(slips.securable(), "true"));

        List<nuna.catalog.audit.Securable> removed = FlagsAboveTables.remove(store(), management, clock, catalog);

        assertThat(removed).extracting(on -> on.kind().name() + ":" + String.join(".", on.path()))
                .containsExactlyInAnyOrder("CATALOG:", "NAMESPACE:hr", "NAMESPACE:hr.payroll", "VIEW:hr.roster");
        assertThat(attributes).extracting(GovernanceAttribute::target).containsExactly(slips.securable());
        List<GovernanceChange> recorded = changes.stream().flatMap(change -> change.outbox().stream())
                .map(event -> EventCodec.decode(event.payloadJson().getBytes(StandardCharsets.UTF_8)).data())
                .map(GovernanceChange.class::cast).toList();
        assertThat(recorded).hasSize(4).allSatisfy(change -> {
            assertThat(change.action()).isEqualTo(GovernanceChange.Action.REMOVED);
            assertThat(change.attribute()).isEqualTo("row-column-policy");
            assertThat(change.actor()).isEqualTo(FlagsAboveTables.SERVER);
        });

        assertThat(FlagsAboveTables.remove(store(), management, clock, catalog)).as("a second start").isEmpty();
    }

    // ---- helpers ----

    @FunctionalInterface
    private interface ThrowingSet {
        ResponseEntity<String> call();
    }

    private static JsonNode json(ResponseEntity<String> response) {
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        return Management.JSON.readTree(response.getBody());
    }

    private static GovernanceAttribute flag(Securable on, String value) {
        return new GovernanceAttribute(on, GovernanceAttribute.Kind.ROW_COLUMN_POLICY, value, "before 239", "stella",
                NOW.minusSeconds(3600));
    }

    private NamespaceRecord namespace(NamespaceRecord parent, String name) {
        NamespaceRecord record = new NamespaceRecord(catalog.id(), UUID.randomUUID(),
                parent == null ? catalog.id() : parent.id(), new Name(Names.normalize(name), name), Map.of(), 1);
        namespaces.put(record.id(), record);
        return record;
    }

    private CatalogObject object(NamespaceRecord namespace, String name, Kind kind) {
        UUID id = UUID.randomUUID();
        CatalogObject object = CatalogObject.active(catalog.id(), id, namespace.id(),
                new Name(Names.normalize(name), name), kind, "s3://gate/" + id);
        objects.put(id, object);
        return object;
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

    private WriteResult put(GovernanceAttribute attribute, Change change) {
        attributes.removeIf(a -> a.target().equals(attribute.target()) && a.kind() == attribute.kind());
        attributes.add(attribute);
        changes.add(change);
        return WriteResult.APPLIED;
    }

    private WriteResult remove(Securable target, GovernanceAttribute.Kind kind, Change change) {
        if (!attributes.removeIf(a -> a.target().equals(target) && a.kind() == kind)) {
            return new WriteResult.Missing(WriteResult.Subject.ATTRIBUTE);
        }
        changes.add(change);
        return WriteResult.APPLIED;
    }

    /** The estate above, the stewards' grant, and the attributes as a store keeps them; any other call fails. */
    @SuppressWarnings("unchecked")
    private CatalogStore store() {
        return (CatalogStore) Proxy.newProxyInstance(CatalogStore.class.getClassLoader(),
                new Class<?>[] {CatalogStore.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "findCatalog" -> Optional.of(catalog).filter(c -> c.normalizedName().equals(args[0]));
                    case "findNamespace" -> findNamespace((List<String>) args[1]);
                    case "readNamespace" -> Optional.ofNullable(namespaces.get((UUID) args[0]));
                    case "listNamespaces" -> new Page<>(namespaces.values().stream()
                            .filter(ns -> ns.parentId().equals(args[0])).toList(), Optional.empty());
                    case "findObject" -> objects.values().stream()
                            .filter(o -> o.namespaceId().equals(args[0]) && o.name().normalized().equals(args[1]))
                            .findFirst();
                    case "readObject" -> Optional.ofNullable(objects.get((UUID) args[0]));
                    case "listObjects" -> new Page<>(objects.values().stream()
                            .filter(o -> o.namespaceId().equals(args[0]) && o.kind() == args[1]).toList(),
                            Optional.empty());
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
                    case "putAttribute" -> put((GovernanceAttribute) args[0], (Change) args[1]);
                    case "removeAttribute" -> remove((Securable) args[0], (GovernanceAttribute.Kind) args[1],
                            (Change) args[3]);
                    case "toString" -> "the test's store";
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }
}
