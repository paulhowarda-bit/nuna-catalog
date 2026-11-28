package nuna.catalog.security;

import static org.assertj.core.api.Assertions.assertThat;

import nuna.catalog.core.principal.Caller;
import nuna.catalog.core.principal.Principal;
import nuna.catalog.security.Authorizer.Decision;
import nuna.catalog.security.Authorizer.Operation;
import nuna.catalog.security.Authorizer.Reason;
import nuna.catalog.security.Authorizer.Target;
import nuna.catalog.store.CatalogObject;
import nuna.catalog.store.GovernanceAttribute;
import nuna.catalog.store.GovernanceStore;
import nuna.catalog.store.Grant;
import nuna.catalog.store.GrantRecord;
import nuna.catalog.store.GrantStore;
import nuna.catalog.store.Name;
import nuna.catalog.store.NamespaceRecord;
import nuna.catalog.store.NamespaceStore;
import nuna.catalog.store.Securable;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * A table closed until classified (D27) is closed to the maintenance principal too (catalog-security, 2026-11-28;
 * backlog 334): acting as itself, it is refused {@code select} and {@code write} with {@code UNCLASSIFIED}, as anyone
 * is. The maintenance exemption is from a row/column policy (D20, D30), never from the closure. The grants and
 * governance live in memory, so this needs only Java; AuthorizerTest decides the closure against PostgreSQL.
 */
class ClosedToMaintenanceTest {

    private static final Instant AT = Instant.parse("2026-11-28T09:00:00Z");
    private static final Operation LOAD = new Operation("loadTable", Privilege.SELECT, true);
    private static final Operation APPEND = new Operation("commitTable", Privilege.WRITE, true);
    private static final Operation LIST = new Operation("listTables", Privilege.DESCRIBE, false);

    private final UUID catalogId = UUID.randomUUID();
    private final NamespaceRecord party = new NamespaceRecord(catalogId, UUID.randomUUID(), catalogId,
            new Name("party", "party"), Map.of(), 1);
    private final CatalogObject owners = CatalogObject.active(catalogId, UUID.randomUUID(), party.id(),
            new Name("beneficial_owners", "beneficial_owners"), CatalogObject.Kind.TABLE, "s3://b/owners");
    private final List<GovernanceAttribute> attributes = new ArrayList<>(List.of(new GovernanceAttribute(
            new Securable(Securable.Kind.NAMESPACE, party.id()), GovernanceAttribute.Kind.REGULATED, "true", "console",
            "cora", AT)));
    private final Authorizer authorizer = new Authorizer(grants(), governance(), namespaces(),
            new Separation(Set.of("grant-admins"), Set.of("platform-ops")), false, Set.of("svc-maintenance"));
    private final Caller worker = new Caller(new Principal("svc-maintenance", Set.of("maintainers")),
            "nuna-maintenance", false, Optional.empty());

    @Test
    void theMaintenancePrincipalActingAsItselfIsRefusedAClosedTable() {
        assertThat(authorizer.maintenance(worker)).isTrue();
        for (Operation operation : List.of(LOAD, APPEND)) {
            assertThat(authorizer.decide(worker, Target.of(owners), operation)).as(operation.name())
                    .isInstanceOfSatisfying(Decision.Denied.class,
                            denied -> assertThat(denied.reason()).isEqualTo(Reason.UNCLASSIFIED));
        }
        assertThat(authorizer.decide(worker, Target.of(owners), LIST)).as("its name is not closed")
                .isInstanceOf(Decision.Allowed.class);
    }

    @Test
    void onceClassifiedTheTableIsOpenToIt() {
        attributes.add(new GovernanceAttribute(owners.securable(), GovernanceAttribute.Kind.CLASSIFICATION,
                "restricted", "console", "stella", AT));

        assertThat(authorizer.decide(worker, Target.of(owners), LOAD)).isInstanceOf(Decision.Allowed.class);
        assertThat(authorizer.decide(worker, Target.of(owners), APPEND)).isInstanceOf(Decision.Allowed.class);
    }

    /** The maintainers hold write on the catalog, so only the closure can refuse the worker. */
    private GrantStore grants() {
        GrantRecord write = new GrantRecord(new Grant(catalogId, new Securable(Securable.Kind.CATALOG, catalogId),
                "maintainers", Privilege.WRITE.wireName()), "setup", AT);
        return proxy(GrantStore.class, "grantsFor", args -> ((Set<?>) args[1]).contains("maintainers")
                ? List.of(write) : List.of());
    }

    private GovernanceStore governance() {
        return proxy(GovernanceStore.class, "attributesOf", args -> List.copyOf(attributes));
    }

    private NamespaceStore namespaces() {
        return proxy(NamespaceStore.class, "readNamespace", args -> Optional.of(party).filter(n -> n.id()
                .equals(args[0])));
    }

    /** A store answering {@code method} alone. */
    private static <T> T proxy(Class<T> type, String method, java.util.function.Function<Object[], Object> answer) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (p, called, args) -> {
            if (called.getName().equals(method)) {
                return answer.apply(args);
            }
            throw new UnsupportedOperationException(called.getName());
        }));
    }
}
