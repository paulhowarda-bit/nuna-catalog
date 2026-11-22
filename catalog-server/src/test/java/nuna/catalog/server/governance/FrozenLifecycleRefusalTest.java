package nuna.catalog.server.governance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import nuna.catalog.store.GovernanceAttribute;
import nuna.catalog.store.Grant;
import nuna.catalog.store.GrantRecord;
import nuna.catalog.store.Name;
import nuna.catalog.store.NamespaceRecord;
import nuna.catalog.store.Page;
import nuna.catalog.store.Securable;
import nuna.catalog.store.WriteResult;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * An undrop or purge claim a DR freeze refuses (D37) is audited as a denial, as every refusal is (D18; backlog
 * 132): the Denial names the operation and the dropped table, its reason is {@code dr_freeze} with the freeze's own
 * reason, and the answer is the 403 it always was, whether the route saw the freeze or the store's own guard found
 * one placed since. The routes, guard and authorizer are the real ones over a store double, so this needs only Java.
 */
class FrozenLifecycleRefusalTest {

    private static final Instant NOW = Instant.parse("2026-11-22T12:00:00Z");
    /** Create on hr, so only the freeze refuses her undrop. */
    private static final Caller ANN = new Caller(new Principal("ann", Set.of("hr-owners")), "console", false,
            Optional.empty());
    private static final Caller WORKER = new Caller(new Principal("nuna-maintenance", Set.of("maintenance")),
            "worker", false, Optional.empty());

    private final CatalogRecord catalog = new CatalogRecord(UUID.randomUUID(), "gate", "gate");
    private final NamespaceRecord hr = new NamespaceRecord(catalog.id(), UUID.randomUUID(), catalog.id(),
            new Name("hr", "hr"), Map.of(), 1);
    private final UUID staffId = UUID.randomUUID();
    /** Dropped an hour ago, undroppable for a day more. */
    private final CatalogObject staff = new CatalogObject(catalog.id(), staffId, hr.id(), new Name("staff", "staff"),
            CatalogObject.Kind.TABLE, CatalogObject.State.DROPPED, Optional.of(NOW.minusSeconds(3600)),
            Optional.of(NOW.plus(Duration.ofDays(1))), "s3://gate/" + staffId, Optional.empty());
    private final List<GrantRecord> grants = List.of(
            grant("hr-owners", Privilege.CREATE), grant("maintenance", Privilege.DESCRIBE));
    private final List<CatalogEvent> audit = new ArrayList<>();
    private boolean frozen = true;

    private final LifecycleRoutes lifecycle;
    private final MockHttpServletRequest http = new MockHttpServletRequest();
    private Caller caller = ANN;

    FrozenLifecycleRefusalTest() {
        http.setAttribute(RequestIds.class.getName(), "request-1");
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
                Duration.ofHours(1), Map.of());
        // No audit trail, storage or catalog names: every case is answered before them.
        lifecycle = new LifecycleRoutes(store, management, guard, governance, null, null, null, clock);
    }

    @Test
    void anUndropUnderAFreezeIsAuditedAsADenial() {
        assertThatThrownBy(() -> lifecycle.undrop("gate", staffId.toString(), null, http))
                .isInstanceOf(AccessDenied.class)
                .hasMessage("The namespace of " + staffId + " is frozen for DR reconcile: drill");

        assertThat(denials()).singleElement().satisfies(denial -> {
            assertThat(denial.operation()).isEqualTo("undropObject");
            assertThat(denial.securable().id()).isEqualTo(staffId);
            assertThat(denial.reason()).isEqualTo("dr_freeze: The namespace of " + staffId
                    + " is frozen for DR reconcile: drill");
            assertThat(denial.actor().principal()).isEqualTo("ann");
        });
    }

    @Test
    void aPurgeClaimUnderAFreezeIsAuditedAsADenial() {
        caller = WORKER;

        assertThatThrownBy(() -> lifecycle.claimPurge("gate", staffId.toString(), http))
                .isInstanceOf(AccessDenied.class)
                .hasMessage("The namespace of " + staffId + " is frozen for DR reconcile: drill");

        assertThat(denials()).singleElement().satisfies(denial -> {
            assertThat(denial.operation()).isEqualTo("claimPurge");
            assertThat(denial.securable().id()).isEqualTo(staffId);
            assertThat(denial.reason()).isEqualTo("dr_freeze: The namespace of " + staffId
                    + " is frozen for DR reconcile: drill");
            assertThat(denial.actor().principal()).isEqualTo("nuna-maintenance");
        });
    }

    @Test
    void aPurgeClaimTheStoreFindsFrozenIsAuditedAsADenial() {
        caller = WORKER;
        frozen = false;

        assertThatThrownBy(() -> lifecycle.claimPurge("gate", staffId.toString(), http))
                .as("a freeze placed after the route looked, which the store's own guard found")
                .isInstanceOf(AccessDenied.class)
                .hasMessage("The namespace of " + staffId + " is frozen for DR reconcile");

        assertThat(denials()).singleElement().satisfies(denial -> {
            assertThat(denial.operation()).isEqualTo("claimPurge");
            assertThat(denial.securable().id()).isEqualTo(staffId);
            assertThat(denial.reason()).isEqualTo("dr_freeze: The namespace of " + staffId
                    + " is frozen for DR reconcile");
        });
    }

    // ---- helpers ----

    private List<Denial> denials() {
        return audit.stream().map(CatalogEvent::data).filter(Denial.class::isInstance).map(Denial.class::cast)
                .toList();
    }

    private GrantRecord grant(String group, Privilege privilege) {
        return new GrantRecord(new Grant(catalog.id(), new Securable(Securable.Kind.NAMESPACE, hr.id()), group,
                privilege.wireName()), "test", Instant.EPOCH);
    }

    private List<GovernanceAttribute> attributes(Set<Securable> levels) {
        Securable namespace = new Securable(Securable.Kind.NAMESPACE, hr.id());
        return frozen && levels.contains(namespace)
                ? List.of(new GovernanceAttribute(namespace, GovernanceAttribute.Kind.DR_FREEZE, "drill", "api", "olga",
                        NOW))
                : List.of();
    }

    /**
     * The catalog gate, its namespace hr, the dropped table staff and the grants on hr; a purge claim answers that a
     * freeze refused it. Any other call fails the test, writes included.
     */
    @SuppressWarnings("unchecked")
    private CatalogStore store() {
        return (CatalogStore) Proxy.newProxyInstance(CatalogStore.class.getClassLoader(),
                new Class<?>[] {CatalogStore.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "findCatalog" -> Optional.of(catalog).filter(c -> c.normalizedName().equals(args[0]));
                    case "readNamespace" -> Optional.of(hr).filter(ns -> ns.id().equals(args[0]));
                    case "readObject" -> Optional.of(staff).filter(o -> o.id().equals(args[0]));
                    case "grantsFor" -> grants.stream()
                            .filter(g -> ((Set<Securable>) args[0]).contains(g.grant().securable())
                                    && ((Set<String>) args[1]).contains(g.grant().group()))
                            .toList();
                    case "attributesOf" -> attributes((Set<Securable>) args[0]);
                    case "holdsOn" -> List.of();
                    case "listGrantsOfGroups" -> new Page<>(grants.stream()
                            .filter(g -> ((Set<String>) args[1]).contains(g.grant().group())).toList(),
                            Optional.empty());
                    case "claimPurge" -> new WriteResult.Refused(WriteResult.Reason.FROZEN);
                    case "toString" -> "the test's store";
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }
}
