package nuna.catalog.server.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Proxy;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import nuna.catalog.audit.CatalogEvent;
import nuna.catalog.audit.Denial;
import nuna.catalog.core.Names;
import nuna.catalog.core.principal.Caller;
import nuna.catalog.core.principal.Principal;
import nuna.catalog.security.Authorizer;
import nuna.catalog.security.Privilege;
import nuna.catalog.security.Separation;
import nuna.catalog.server.security.Denials;
import nuna.catalog.server.security.DenialsForTests;
import nuna.catalog.server.security.Holdings;
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
import nuna.catalog.store.Securable;
import org.junit.jupiter.api.Test;

/**
 * A drop or rename a DR freeze refuses (D37) is audited as a denial, as every refusal is (D18; backlog 132): the
 * Denial names the operation and the table, its reason is {@code dr_freeze} with the freeze's own reason, and the
 * answer is the 403 it always was. The routes, guard and authorizer are the real ones over a store double that fails
 * any write, so this needs only Java.
 */
class FrozenObjectRefusalTest {

    /** Modify and create on sales and archive: every refusal here is the freeze's. */
    private static final Caller MO = new Caller(new Principal("mo", Set.of("owners")), "spark", false,
            Optional.empty());
    private static final Instant NOW = Instant.parse("2026-11-22T12:00:00Z");

    private final CatalogRecord catalog = new CatalogRecord(UUID.randomUUID(), "desks", "desks");
    private final NamespaceRecord sales = namespace("sales");
    private final NamespaceRecord archive = namespace("archive");
    private final CatalogObject orders = CatalogObject.active(catalog.id(), UUID.randomUUID(), sales.id(),
            new Name(Names.normalize("orders"), "orders"), Kind.TABLE, "s3://warehouse/tables/orders");
    private final List<GrantRecord> grants = new ArrayList<>();
    private final Set<UUID> frozen = new HashSet<>();
    private final List<CatalogEvent> audit = new ArrayList<>();

    private final ObjectRoutes objects;
    private final RequestContext request = new RequestContext(MO, "request-1");

    FrozenObjectRefusalTest() {
        for (NamespaceRecord namespace : List.of(sales, archive)) {
            for (Privilege privilege : List.of(Privilege.MODIFY, Privilege.CREATE)) {
                grants.add(new GrantRecord(new Grant(catalog.id(), new Securable(Securable.Kind.NAMESPACE,
                        namespace.id()), "owners", privilege.wireName()), "test", Instant.EPOCH));
            }
        }
        CatalogStore store = store();
        RestSettings settings = new RestSettings(100, 1000, 1_048_576, 1000, 268_435_456, Duration.ofDays(30),
                Duration.ofMinutes(5), "the identity provider", URI.create("urn:nuna:test"), 100, null);
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        AuditPublisher publisher = audit::add;
        Separation separation = new Separation(Set.of("grant-admins"), Set.of("platform-ops"));
        Authorizer authorizer = new Authorizer(store, store, store, separation, false, Set.of());
        Denials denials = DenialsForTests.denials(publisher, settings, clock);
        Holdings holdings = new Holdings(authorizer, store, settings, separation, denials);
        Guard guard = new Guard(authorizer, denials, publisher, settings, clock, holdings);
        Lookup lookup = new Lookup(store, settings, new Prefixes(holdings, store, clock));
        objects = new ObjectRoutes(store, lookup, new Idempotency(store, clock), new AuditEvents(settings), settings,
                guard, clock);
    }

    @Test
    void aDropUnderAFreezeIsAuditedAsADenial() {
        frozen.add(sales.id());

        assertThatThrownBy(() -> objects.drop("desks", "sales", "orders", Kind.TABLE, Optional.empty(), request))
                .isInstanceOf(AccessDenied.class)
                .hasMessage("Table sales.orders is frozen for DR reconcile: drill");

        assertThat(denials()).singleElement().satisfies(denial -> {
            assertThat(denial.operation()).isEqualTo("dropTable");
            assertThat(denial.securable().id()).isEqualTo(orders.id());
            assertThat(denial.securable().path()).containsExactly("sales", "orders");
            assertThat(denial.reason()).isEqualTo("dr_freeze: Table sales.orders is frozen for DR reconcile: drill");
            assertThat(denial.actor().principal()).isEqualTo("mo");
        });
    }

    @Test
    void aRenameOfAFrozenTableIsAuditedAsADenial() {
        frozen.add(sales.id());

        assertThatThrownBy(() -> objects.rename("desks", rename("archive"), Kind.TABLE, Optional.empty(), request))
                .isInstanceOf(AccessDenied.class)
                .hasMessage("Table sales.orders is frozen for DR reconcile: drill");

        assertThat(denials()).singleElement().satisfies(denial -> {
            assertThat(denial.operation()).isEqualTo("renameTable");
            assertThat(denial.securable().id()).isEqualTo(orders.id());
            assertThat(denial.reason()).isEqualTo("dr_freeze: Table sales.orders is frozen for DR reconcile: drill");
        });
    }

    @Test
    void aRenameIntoAFrozenNamespaceIsAuditedAsADenial() {
        frozen.add(archive.id());

        assertThatThrownBy(() -> objects.rename("desks", rename("archive"), Kind.TABLE, Optional.empty(), request))
                .isInstanceOf(AccessDenied.class)
                .hasMessage("Namespace archive is frozen for DR reconcile: drill");

        assertThat(denials()).singleElement().satisfies(denial -> {
            assertThat(denial.operation()).isEqualTo("renameTable");
            assertThat(denial.securable().id()).as("the table whose rename was refused").isEqualTo(orders.id());
            assertThat(denial.reason()).isEqualTo("dr_freeze: Namespace archive is frozen for DR reconcile: drill");
        });
    }

    // ---- helpers ----

    private static String rename(String into) {
        return """
                {"source":{"namespace":["sales"],"name":"orders"},"destination":{"namespace":["%s"],"name":"orders"}}"""
                .formatted(into);
    }

    private List<Denial> denials() {
        return audit.stream().map(CatalogEvent::data).filter(Denial.class::isInstance).map(Denial.class::cast)
                .toList();
    }

    private NamespaceRecord namespace(String name) {
        return new NamespaceRecord(catalog.id(), UUID.randomUUID(), catalog.id(), new Name(name, name), Map.of(), 1);
    }

    private List<GovernanceAttribute> attributes(Set<Securable> levels) {
        return frozen.stream().map(id -> new Securable(Securable.Kind.NAMESPACE, id)).filter(levels::contains)
                .map(level -> new GovernanceAttribute(level, GovernanceAttribute.Kind.DR_FREEZE, "drill", "api",
                        "olga", NOW))
                .toList();
    }

    /** The catalog desks, its namespaces sales and archive, the table sales.orders and the grants; nothing else. */
    @SuppressWarnings("unchecked")
    private CatalogStore store() {
        return (CatalogStore) Proxy.newProxyInstance(CatalogStore.class.getClassLoader(),
                new Class<?>[] {CatalogStore.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "findCatalog" -> Optional.of(catalog).filter(c -> c.normalizedName().equals(args[0]));
                    case "findNamespace" -> List.of(sales, archive).stream()
                            .filter(ns -> args[1].equals(List.of(ns.name().normalized()))).findFirst();
                    case "readNamespace" -> List.of(sales, archive).stream()
                            .filter(ns -> ns.id().equals(args[0])).findFirst();
                    case "findObject" -> Optional.of(orders)
                            .filter(o -> o.namespaceId().equals(args[0]) && o.name().normalized().equals(args[1]));
                    case "readObject" -> Optional.of(orders).filter(o -> o.id().equals(args[0]));
                    case "grantsFor" -> grants.stream()
                            .filter(g -> ((Set<Securable>) args[0]).contains(g.grant().securable())
                                    && ((Set<String>) args[1]).contains(g.grant().group()))
                            .toList();
                    case "attributesOf" -> attributes((Set<Securable>) args[0]);
                    case "holdsOn" -> List.of();
                    case "listGrantsOfGroups" -> new Page<>(grants.stream()
                            .filter(g -> ((Set<String>) args[1]).contains(g.grant().group())).toList(),
                            Optional.empty());
                    case "toString" -> "the test's store";
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }
}
