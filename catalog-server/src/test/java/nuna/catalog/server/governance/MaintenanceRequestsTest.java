package nuna.catalog.server.governance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.lang.reflect.Proxy;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import nuna.catalog.audit.CatalogEvent;
import nuna.catalog.audit.Denial;
import nuna.catalog.core.CatalogError;
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
import nuna.catalog.server.security.SecuritySettings;
import nuna.catalog.server.telemetry.RequestIds;
import nuna.catalog.store.CatalogObject;
import nuna.catalog.store.CatalogRecord;
import nuna.catalog.store.CatalogStore;
import nuna.catalog.store.Change;
import nuna.catalog.store.GovernanceAttribute;
import nuna.catalog.store.Grant;
import nuna.catalog.store.GrantRecord;
import nuna.catalog.store.LegalHold;
import nuna.catalog.store.MaintenanceRequest;
import nuna.catalog.store.Name;
import nuna.catalog.store.NamespaceRecord;
import nuna.catalog.store.Page;
import nuna.catalog.store.Securable;
import nuna.catalog.store.WriteResult;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import tools.jackson.databind.JsonNode;

/**
 * A table's maintenance on request (backlog 114): who may ask, and every refusal, each audited. A caller with
 * {@code modify} asks for a compaction or an orphan scan and gets 202 with the request waiting, the one already waiting
 * when there is one; one with {@code select} only is refused (403), one who holds nothing on the table is told it does
 * not exist (404); a DR freeze refuses either job (403), a legal hold the orphan scan (409), and so does a policy that
 * turns the job off. The route, guard and authorizer are the real ones over a store double, so this needs only Java.
 */
class MaintenanceRequestsTest {

    private static final Instant NOW = Instant.parse("2026-11-22T14:00:00Z");
    /** Modify on every namespace. */
    private static final Caller ANN = person("ann", "owners");
    /** Select on every namespace. */
    private static final Caller RITA = person("rita", "readers");
    /** Nothing. */
    private static final Caller NED = person("ned", "newcomers");

    private final CatalogRecord catalog = new CatalogRecord(UUID.randomUUID(), "gate", "gate");
    private final List<NamespaceRecord> namespaces = new ArrayList<>();
    private final List<CatalogObject> tables = new ArrayList<>();
    private final List<GrantRecord> grants = new ArrayList<>();
    private final List<GovernanceAttribute> attributes = new ArrayList<>();
    private final List<LegalHold> holds = new ArrayList<>();
    private final List<CatalogEvent> audit = new ArrayList<>();
    /** The requests the store kept, and the changes that came with them. */
    private final List<MaintenanceRequest> kept = new ArrayList<>();
    private final List<Change> changes = new ArrayList<>();

    private final NamespaceRecord markets = namespace("markets");
    private final CatalogObject trades = table(markets, "trades");
    private final NamespaceRecord legal = namespace("legal");
    private final CatalogObject cases = table(legal, "cases");
    private final NamespaceRecord reference = namespace("reference");
    private final CatalogObject currencies = table(reference, "currencies");
    private final NamespaceRecord archive = namespace("archive");
    private final CatalogObject ledger = table(archive, "ledger");

    private final MockHttpServletRequest http = new MockHttpServletRequest();
    private final MaintenanceRequests routes;
    private Caller caller = ANN;

    MaintenanceRequestsTest() {
        http.setAttribute(RequestIds.class.getName(), "request-1");
        for (NamespaceRecord namespace : namespaces) {
            grant(namespace, "owners", Privilege.MODIFY);
            grant(namespace, "readers", Privilege.SELECT);
        }
        holds.add(new LegalHold(UUID.randomUUID(), namespaceOf(legal), "Matter 2026-114", "stella", NOW));
        attribute(reference, GovernanceAttribute.Kind.DR_FREEZE, "DR drill");
        attribute(archive, GovernanceAttribute.Kind.MAINTENANCE_POLICY, "{\"compaction.enabled\": \"false\"}");

        CatalogStore store = store();
        RestSettings settings = new RestSettings(100, 1000, 1_048_576, 1000, 268_435_456, Duration.ofDays(30),
                Duration.ofMinutes(5), "the identity provider", URI.create("urn:nuna:test"), 100, null);
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        AuditPublisher publisher = audit::add;
        Separation separation = new Separation(Set.of("grant-admins"), Set.of("platform-ops"));
        Authorizer authorizer = new Authorizer(store, store, store, separation, false, Set.of());
        Denials denials = DenialsForTests.denials(publisher, settings, clock);
        Holdings holdings = new Holdings(authorizer, store, settings, separation, denials);
        Management management = new Management(store, request -> caller, settings, holdings);
        SecuritySettings security = new SecuritySettings(List.of(new SecuritySettings.Issuer("https://idp.test",
                URI.create("https://idp.test/keys"), Set.of("nuna"), Set.of(), "sub", "groups", "azp")),
                Duration.ofSeconds(60), Set.of("grant-admins"), Set.of("platform-ops"), Set.of("nuna-maintenance"),
                false);
        ManagementGuard guard = new ManagementGuard(authorizer, separation, security, denials, holdings);
        GovernanceSettings governance = new GovernanceSettings(10, 60, Map.of(), Duration.ofDays(7),
                Duration.ofHours(1), Map.of(MaintenancePolicy.COMPACTION_ENABLED, "true",
                        MaintenancePolicy.ORPHAN_ENABLED, "true"));
        routes = new MaintenanceRequests(store, management, guard, governance, denials, clock);
    }

    @Test
    void aCallerWithModifyAsksAndAskingAgainKeepsTheRequestWaiting() {
        JsonNode asked = request(markets, trades, "compaction");

        assertThat(asked.get("job").asString()).isEqualTo("compaction");
        assertThat(asked.get("requested-by").asString()).isEqualTo("ann");
        assertThat(asked.get("requested-at").asString()).isEqualTo(NOW.toString());
        assertThat(asked.get("state").asString()).isEqualTo("waiting");
        assertThat(kept).containsExactly(new MaintenanceRequest(trades.id(), MaintenanceRequest.Job.COMPACTION, "ann",
                NOW, Optional.empty()));
        assertThat(changes).singleElement().satisfies(change -> assertThat(change.outbox()).singleElement()
                .satisfies(event -> assertThat(event.payloadJson()).contains("maintenance-request")
                        .contains("compaction")));

        caller = person("bob", "owners");
        JsonNode again = request(markets, trades, "compaction");

        assertThat(again.get("requested-by").asString()).as("the one waiting, ann's").isEqualTo("ann");
        assertThat(kept).hasSize(1);
        assertThat(request(markets, trades, "orphan-scan").get("job").asString()).isEqualTo("orphan-scan");
        assertThat(kept).hasSize(2);
        assertThat(denials()).isEmpty();
    }

    @Test
    void aCallerWithSelectOnlyIsRefusedAndTheRefusalAudited() {
        caller = RITA;

        Throwable refused = catchThrowable(() -> post(markets, trades, "compaction"));

        assertThat(refused).isInstanceOf(AccessDenied.class);
        assertThat(denials()).singleElement().satisfies(denial -> {
            assertThat(denial.operation()).isEqualTo(MaintenanceRequests.OPERATION);
            assertThat(denial.securable().id()).isEqualTo(trades.id());
            assertThat(denial.actor().principal()).isEqualTo("rita");
        });
        assertThat(kept).isEmpty();
    }

    @Test
    void aCallerWhoHoldsNothingIsToldThereIsNoSuchTable() {
        caller = NED;

        Throwable refused = catchThrowable(() -> post(markets, trades, "compaction"));

        assertThat(refused).isInstanceOfSatisfying(CatalogError.class,
                error -> assertThat(error.status()).isEqualTo(404));
        assertThat(kept).isEmpty();
    }

    @Test
    void anotherJobIsABadRequest() {
        for (String body : List.of("{\"job\": \"snapshot-expiry\"}", "{}", "{\"job\": 1}")) {
            Throwable refused = catchThrowable(() -> routes.request("gate", "markets", "trades", body, http));
            assertThat(refused).as(body).isInstanceOfSatisfying(CatalogError.class,
                    error -> assertThat(error.status()).isEqualTo(400));
        }
        assertThat(kept).isEmpty();
    }

    @Test
    void aDrFreezeRefusesEitherJob() {
        for (String job : List.of("compaction", "orphan-scan")) {
            Throwable refused = catchThrowable(() -> post(reference, currencies, job));
            assertThat(refused).as(job).isInstanceOf(AccessDenied.class);
        }

        assertThat(denials()).hasSize(2).allSatisfy(denial -> {
            assertThat(denial.securable().id()).isEqualTo(currencies.id());
            assertThat(denial.reason()).startsWith("dr_freeze: ");
        });
        assertThat(kept).isEmpty();
    }

    @Test
    void aLegalHoldRefusesTheOrphanScanButNotTheCompaction() {
        Throwable refused = catchThrowable(() -> post(legal, cases, "orphan-scan"));

        assertThat(refused).isInstanceOfSatisfying(CatalogError.class, error -> {
            assertThat(error.status()).isEqualTo(409);
            assertThat(error.type()).isEqualTo("MaintenanceNotAllowedException");
            assertThat(error.getMessage()).contains("legal hold");
        });
        assertThat(denials()).singleElement().satisfies(denial -> {
            assertThat(denial.operation()).isEqualTo(MaintenanceRequests.OPERATION);
            assertThat(denial.securable().id()).isEqualTo(cases.id());
            assertThat(denial.reason()).startsWith("legal_hold: ");
        });
        assertThat(request(legal, cases, "compaction").get("state").asString()).isEqualTo("waiting");
        assertThat(kept).extracting(MaintenanceRequest::job).containsExactly(MaintenanceRequest.Job.COMPACTION);
    }

    @Test
    void aPolicyThatTurnsTheJobOffRefusesIt() {
        Throwable refused = catchThrowable(() -> post(archive, ledger, "compaction"));

        assertThat(refused).isInstanceOfSatisfying(CatalogError.class, error -> {
            assertThat(error.status()).isEqualTo(409);
            assertThat(error.getMessage()).contains(MaintenancePolicy.COMPACTION_ENABLED + " is false");
        });
        assertThat(denials()).singleElement().satisfies(denial -> assertThat(denial.reason())
                .startsWith("maintenance_policy: "));
        assertThat(request(archive, ledger, "orphan-scan").get("state").asString()).isEqualTo("waiting");
    }

    // ---- helpers ----

    private JsonNode request(NamespaceRecord namespace, CatalogObject table, String job) {
        ResponseEntity<String> response = post(namespace, table, job);
        assertThat(response.getStatusCode().value()).isEqualTo(202);
        return Management.JSON.readTree(response.getBody());
    }

    private ResponseEntity<String> post(NamespaceRecord namespace, CatalogObject table, String job) {
        return routes.request("gate", namespace.name().display(), table.name().display(),
                "{\"job\": \"" + job + "\"}", http);
    }

    private List<Denial> denials() {
        return audit.stream().map(CatalogEvent::data).filter(Denial.class::isInstance).map(Denial.class::cast)
                .toList();
    }

    private static Caller person(String id, String... groups) {
        return new Caller(new Principal(id, Set.of(groups)), "console", false, Optional.empty());
    }

    private NamespaceRecord namespace(String name) {
        NamespaceRecord record = new NamespaceRecord(catalog.id(), UUID.randomUUID(), catalog.id(),
                new Name(name, name), Map.of(), 1);
        namespaces.add(record);
        return record;
    }

    private static Securable namespaceOf(NamespaceRecord namespace) {
        return new Securable(Securable.Kind.NAMESPACE, namespace.id());
    }

    private CatalogObject table(NamespaceRecord namespace, String name) {
        UUID id = UUID.randomUUID();
        CatalogObject object = CatalogObject.active(catalog.id(), id, namespace.id(), new Name(name, name),
                CatalogObject.Kind.TABLE, "s3://gate/" + id);
        tables.add(object);
        return object;
    }

    private void grant(NamespaceRecord on, String group, Privilege privilege) {
        grants.add(new GrantRecord(new Grant(catalog.id(), namespaceOf(on), group, privilege.wireName()), "test",
                Instant.EPOCH));
    }

    private void attribute(NamespaceRecord on, GovernanceAttribute.Kind kind, String value) {
        attributes.add(new GovernanceAttribute(namespaceOf(on), kind, value, "management-api", "olga",
                NOW.minusSeconds(3600)));
    }

    /** A waiting request of the table, for the job; the store keeps one per table and job. */
    private Optional<MaintenanceRequest> waiting(UUID table, MaintenanceRequest.Job job) {
        return kept.stream().filter(r -> r.objectId().equals(table) && r.job() == job).findFirst();
    }

    /**
     * The catalog gate, its namespaces and tables, grants, attributes and holds; requests kept one per table and job.
     * Any other call fails the test.
     */
    @SuppressWarnings("unchecked")
    private CatalogStore store() {
        return (CatalogStore) Proxy.newProxyInstance(CatalogStore.class.getClassLoader(),
                new Class<?>[] {CatalogStore.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "findCatalog" -> Optional.of(catalog).filter(c -> c.normalizedName().equals(args[0]));
                    case "findNamespace" -> namespaces.stream()
                            .filter(ns -> List.of(ns.name().normalized()).equals(args[1])).findFirst();
                    case "readNamespace" -> namespaces.stream().filter(ns -> ns.id().equals(args[0])).findFirst();
                    case "findObject" -> tables.stream().filter(t -> t.namespaceId().equals(args[0])
                            && t.name().normalized().equals(args[1])).findFirst();
                    case "readObject" -> tables.stream().filter(t -> t.id().equals(args[0])).findFirst();
                    case "grantsFor" -> grants.stream()
                            .filter(g -> ((Set<Securable>) args[0]).contains(g.grant().securable())
                                    && ((Set<String>) args[1]).contains(g.grant().group()))
                            .toList();
                    case "attributesOf" -> attributes.stream()
                            .filter(a -> ((Set<Securable>) args[0]).contains(a.target())).toList();
                    case "holdsOn" -> holds.stream()
                            .filter(h -> ((Set<Securable>) args[0]).contains(h.target())).toList();
                    case "listGrantsOfGroups" -> new Page<>(grants.stream()
                            .filter(g -> ((Set<String>) args[1]).contains(g.grant().group())).toList(),
                            Optional.empty());
                    case "requestMaintenance" -> {
                        MaintenanceRequest request = (MaintenanceRequest) args[0];
                        if (waiting(request.objectId(), request.job()).isPresent()) {
                            yield WriteResult.UNCHANGED;
                        }
                        kept.add(request);
                        changes.add((Change) args[1]);
                        yield WriteResult.APPLIED;
                    }
                    case "listMaintenanceRequests" -> kept.stream().filter(r -> r.objectId().equals(args[0]))
                            .toList();
                    case "toString" -> "the test's store";
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }
}
