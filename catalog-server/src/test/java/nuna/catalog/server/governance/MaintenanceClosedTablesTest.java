package nuna.catalog.server.governance;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Proxy;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import nuna.catalog.core.principal.Caller;
import nuna.catalog.core.principal.Principal;
import nuna.catalog.security.Authorizer;
import nuna.catalog.security.Privilege;
import nuna.catalog.security.Separation;
import nuna.catalog.server.rest.RestSettings;
import nuna.catalog.server.security.Denials;
import nuna.catalog.server.security.DenialsForTests;
import nuna.catalog.server.security.Holdings;
import nuna.catalog.server.security.SecuritySettings;
import nuna.catalog.server.storage.StorageSettings;
import nuna.catalog.server.telemetry.RequestIds;
import nuna.catalog.store.CatalogObject;
import nuna.catalog.store.CatalogRecord;
import nuna.catalog.store.CatalogStore;
import nuna.catalog.store.GovernanceAttribute;
import nuna.catalog.store.Grant;
import nuna.catalog.store.GrantRecord;
import nuna.catalog.store.MaintenanceRequest;
import nuna.catalog.store.Name;
import nuna.catalog.store.NamespaceRecord;
import nuna.catalog.store.Page;
import nuna.catalog.store.Securable;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import tools.jackson.databind.JsonNode;

/**
 * The work list marks a table closed until classified (D27; backlog 334): a table in a regulated area with no
 * classification, whose load the authorizer refuses the maintenance principal with {@code UNCLASSIFIED}, carries
 * {@code "closed": true} in the tables section and in the requests section, so the worker skips it without loading it.
 * A classified table in the same area does not. Asking the authorizer refuses nothing, so no denial is published. The
 * routes, guard and authorizer are the real ones over a store double, as in MaintenanceRefsTest, so this needs only
 * Java.
 */
class MaintenanceClosedTablesTest {

    private static final Instant NOW = Instant.parse("2026-11-28T12:00:00Z");
    private static final Caller WORKER = new Caller(new Principal("nuna-maintenance", Set.of("maintainers")),
            "worker", false, Optional.empty());

    private final CatalogRecord catalog = new CatalogRecord(UUID.randomUUID(), "brokerage", "brokerage");
    private final NamespaceRecord party = new NamespaceRecord(catalog.id(), UUID.randomUUID(), catalog.id(),
            new Name("party", "party"), Map.of(), 1);
    private final CatalogObject closed = CatalogObject.active(catalog.id(), UUID.randomUUID(), party.id(),
            new Name("beneficial_owners", "beneficial_owners"), CatalogObject.Kind.TABLE, "s3://b/owners");
    private final CatalogObject open = CatalogObject.active(catalog.id(), UUID.randomUUID(), party.id(),
            new Name("customers", "customers"), CatalogObject.Kind.TABLE, "s3://b/customers");
    private final Map<UUID, CatalogObject> objects = Map.of(closed.id(), closed, open.id(), open);
    private final List<Object> published = Collections.synchronizedList(new ArrayList<>());
    private final MockHttpServletRequest http = new MockHttpServletRequest();
    private final MaintenanceRoutes routes;

    MaintenanceClosedTablesTest() {
        http.setAttribute(RequestIds.class.getName(), "request-1");
        CatalogStore store = store();
        RestSettings rest = new RestSettings(100, 1000, 1_048_576, 1000, 268_435_456, Duration.ofDays(30),
                Duration.ofMinutes(5), "the identity provider", URI.create("urn:nuna:test"), 100, null);
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        Separation separation = new Separation(Set.of("grant-admins"), Set.of("platform-ops"));
        Authorizer authorizer = new Authorizer(store, store, store, separation, false, Set.of("nuna-maintenance"));
        Denials denials = DenialsForTests.denials(published::add, rest, clock);
        Holdings holdings = new Holdings(authorizer, store, rest, separation, denials);
        Management management = new Management(store, request -> WORKER, rest, holdings);
        SecuritySettings security = new SecuritySettings(List.of(new SecuritySettings.Issuer("https://idp.test",
                URI.create("https://idp.test/keys"), Set.of("nuna"), Set.of(), "sub", "groups", "azp")),
                Duration.ofSeconds(60), Set.of("grant-admins"), Set.of("platform-ops"), Set.of("nuna-maintenance"),
                false, Set.of());
        ManagementGuard guard = new ManagementGuard(authorizer, separation, security, denials, holdings);
        CatalogNames names = new CatalogNames(null, null) {
            @Override
            String name(UUID id) {
                return id.equals(catalog.id()) ? "brokerage" : null;
            }
        };
        StorageSettings storage = new StorageSettings(Map.of(), Map.of("brokerage", new StorageSettings.Catalog(
                "warehouse", null, null)), Duration.ofHours(1), 100, false);
        GovernanceSettings settings = new GovernanceSettings(10, 60, Map.of(), Duration.ofDays(7), Duration.ofHours(1),
                Map.of(MaintenancePolicy.EXPIRY_ENABLED, "true", MaintenancePolicy.COMPACTION_ENABLED, "true"));
        routes = new MaintenanceRoutes(store, management, guard, settings, names, new RefSettings(2, 3,
                Duration.ofDays(30), Duration.ofDays(180), Duration.ofDays(7)), storage, clock, authorizer);
    }

    @Test
    void aClosedTableIsMarkedClosedAndAnOpenOneIsNot() {
        JsonNode tables = work("tables").get("tables");

        assertThat(tables).hasSize(2);
        assertThat(entry(tables, closed).path("closed").asBoolean()).as("closed until classified").isTrue();
        assertThat(entry(tables, open).has("closed")).as("classified, so open").isFalse();
        assertThat(published).as("foretelling the refusal refuses nothing").isEmpty();
    }

    @Test
    void aRequestForAClosedTableIsMarkedClosedToo() {
        JsonNode requests = work("requests").get("requests");

        assertThat(requests).hasSize(2);
        assertThat(entry(requests, closed).path("closed").asBoolean()).isTrue();
        assertThat(entry(requests, closed).path("job").asString()).isEqualTo("compaction");
        assertThat(entry(requests, open).has("closed")).isFalse();
        assertThat(published).isEmpty();
    }

    private JsonNode work(String section) {
        ResponseEntity<String> response = routes.work(section, null, null, http);
        assertThat(response.getStatusCode().value()).as(response.getBody()).isEqualTo(200);
        return Management.JSON.readTree(response.getBody());
    }

    private static JsonNode entry(JsonNode entries, CatalogObject table) {
        for (JsonNode entry : entries) {
            if (entry.path("id").asString().equals(table.id().toString())) {
                return entry;
            }
        }
        throw new AssertionError("no entry for " + table.name().display() + " in " + entries);
    }

    /**
     * The estate: party is regulated, customers classified and beneficial_owners not, and the maintainers hold
     * write on the catalog, so only the closure refuses the worker's load of beneficial_owners.
     */
    private CatalogStore store() {
        Securable catalogLevel = new Securable(Securable.Kind.CATALOG, catalog.id());
        Securable partyLevel = new Securable(Securable.Kind.NAMESPACE, party.id());
        List<GrantRecord> grants = List.of(new GrantRecord(new Grant(catalog.id(), catalogLevel, "maintainers",
                Privilege.WRITE.wireName()), "setup", NOW));
        List<GovernanceAttribute> attributes = List.of(
                new GovernanceAttribute(partyLevel, GovernanceAttribute.Kind.REGULATED, "true", "console", "cora", NOW),
                new GovernanceAttribute(open.securable(), GovernanceAttribute.Kind.CLASSIFICATION, "confidential",
                        "console", "stella", NOW));
        List<MaintenanceRequest> waiting = List.of(request(closed), request(open));
        return (CatalogStore) Proxy.newProxyInstance(CatalogStore.class.getClassLoader(),
                new Class<?>[] {CatalogStore.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "findCatalog" -> Optional.of(catalog).filter(c -> c.normalizedName().equals(args[0]));
                    case "readNamespace" -> Optional.of(party).filter(n -> n.id().equals(args[0]));
                    case "readObject" -> Optional.ofNullable(objects.get((UUID) args[0]));
                    case "grantsFor" -> grants;
                    case "attributesOf" -> attributes;
                    case "holdsOn", "listRefPointers" -> List.of();
                    case "listActiveObjects" -> new Page<>(List.of(closed, open), Optional.empty());
                    case "listWaitingRequests" -> new Page<>(waiting, Optional.empty());
                    case "toString" -> "the test's store";
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private static MaintenanceRequest request(CatalogObject table) {
        return new MaintenanceRequest(table.id(), MaintenanceRequest.Job.COMPACTION, "stella",
                NOW.minus(Duration.ofMinutes(5)), Optional.empty());
    }
}
