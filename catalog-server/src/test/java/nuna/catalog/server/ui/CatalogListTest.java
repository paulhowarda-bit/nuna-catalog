package nuna.catalog.server.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import nuna.catalog.core.Names;
import nuna.catalog.core.principal.Caller;
import nuna.catalog.core.principal.Principal;
import nuna.catalog.security.Separation;
import nuna.catalog.server.security.Holdings;
import nuna.catalog.server.storage.StorageSettings;
import nuna.catalog.store.CatalogRecord;
import nuna.catalog.store.CatalogStore;
import nuna.catalog.store.Grant;
import nuna.catalog.store.GrantRecord;
import nuna.catalog.store.Page;
import nuna.catalog.store.PageRequest;
import nuna.catalog.store.Securable;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

/**
 * {@code GET /management/v1/catalogs} against a store double: it lists only the declared catalogs the caller holds
 * something on, a grant to one of its groups on the catalog or on anything in it (D18, amended 2026-11-19, item 20).
 * Grant admins and platform operators see every catalog. Needs only Java; {@code ManagementAuthorizationTest} checks
 * the same against the running server.
 */
class CatalogListTest {

    private static final String GRANT_ADMINS = "grant-admins";
    private static final String PLATFORM_OPS = "platform-ops";

    /** Three declared catalogs; their names come back sorted without regard to case. */
    private static final List<String> DECLARED = List.of("trading", "Risk", "archive");

    private final Map<String, CatalogRecord> records = new LinkedHashMap<>();
    private final List<GrantRecord> grants = new ArrayList<>();
    private final List<Set<String>> asked = new ArrayList<>();

    CatalogListTest() {
        for (String name : DECLARED) {
            records.put(Names.normalize(name), new CatalogRecord(UUID.randomUUID(), Names.normalize(name), name));
        }
        // Traders hold select on one table in trading, nothing on the catalog or its namespaces.
        grant("trading", Securable.Kind.TABLE, "traders", "select");
        // Stewards hold manage-tags on one namespace in trading.
        grant("trading", Securable.Kind.NAMESPACE, "stewards", "manage-tags");
        // Risk readers hold describe on the Risk catalog itself.
        grant("risk", Securable.Kind.CATALOG, "risk-readers", "describe");
        // Nobody holds anything in archive.
    }

    @Test
    void aCallerWhoHoldsNothingListsNoCatalog() {
        assertThat(list(caller("otto", "outsiders"))).isEmpty();
    }

    @Test
    void aGrantOnATableInACatalogListsThatCatalogOnly() {
        assertThat(list(caller("tia", "traders"))).containsExactly("trading");
    }

    @Test
    void aGrantOnANamespaceOrOnTheCatalogItselfListsIt() {
        assertThat(list(caller("stella", "stewards"))).containsExactly("trading");
        assertThat(list(caller("rita", "risk-readers"))).containsExactly("Risk");
    }

    @Test
    void grantsOfEveryGroupOfTheCallerCount() {
        assertThat(list(caller("tim", "outsiders", "risk-readers", "traders"))).containsExactly("Risk", "trading");
    }

    @Test
    void aGrantAdminListsEveryCatalog() {
        assertThat(list(caller("gina", GRANT_ADMINS))).containsExactly("archive", "Risk", "trading");
    }

    @Test
    void aPlatformOperatorListsEveryCatalog() {
        assertThat(list(caller("ops", PLATFORM_OPS))).containsExactly("archive", "Risk", "trading");
    }

    @Test
    void aTrustedEngineActingForSomeoneListsWhatThatPersonHolds() {
        Principal engine = new Principal("spark", Set.of("traders"));
        assertThat(list(new Caller(engine, "spark", true, Optional.of(new Principal("otto", Set.of("outsiders"))))))
                .isEmpty();
        assertThat(list(new Caller(engine, "spark", true, Optional.of(new Principal("rita", Set.of("risk-readers"))))))
                .containsExactly("Risk");
    }

    @Test
    void aTrustedEngineActingAsItselfListsEveryCatalog() {
        // Item 84 (D18, amended 2026-11-20), as platform operators do; an unregistered client is judged by its groups.
        assertThat(list(new Caller(new Principal("trino", Set.of("engines")), "trino", true, Optional.empty())))
                .containsExactly("archive", "Risk", "trading");
        assertThat(list(new Caller(new Principal("app", Set.of("engines")), "app", false, Optional.empty())))
                .isEmpty();
    }

    @Test
    void aCallerInNoGroupListsNoCatalogWithoutAskingTheStore() {
        assertThat(list(caller("solo"))).isEmpty();
        assertThat(asked).isEmpty();
    }

    // ---- helpers ----

    private List<String> list(Caller caller) {
        CatalogStore store = store();
        // Only the catalog rule is asked here, which needs the store and the separation of duties.
        Holdings holdings = new Holdings(null, store, null, new Separation(Set.of(GRANT_ADMINS), Set.of(PLATFORM_OPS)),
                null);
        UiRoutes routes = new UiRoutes(new UiSettings(false, null), storage(), http -> caller, store, holdings);
        ResponseEntity<String> response = routes.catalogs(null);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        return JsonPath.read(response.getBody(), "$.catalogs[*].name");
    }

    private static Caller caller(String id, String... groups) {
        return new Caller(new Principal(id, Set.of(groups)), "nuna-console", false, Optional.empty());
    }

    private void grant(String catalog, Securable.Kind kind, String group, String privilege) {
        UUID catalogId = records.get(catalog).id();
        UUID securable = kind == Securable.Kind.CATALOG ? catalogId : UUID.randomUUID();
        grants.add(new GrantRecord(new Grant(catalogId, new Securable(kind, securable), group, privilege),
                "test", Instant.EPOCH));
    }

    private static StorageSettings storage() {
        Map<String, StorageSettings.Catalog> catalogs = new LinkedHashMap<>();
        for (String name : DECLARED) {
            catalogs.put(name, new StorageSettings.Catalog("store", "keys", "Test Entity"));
        }
        return new StorageSettings(Map.of(), catalogs, Duration.ofMinutes(30), 10, true);
    }

    /** The store's catalogs and grants; any other call fails the test. */
    @SuppressWarnings("unchecked")
    private CatalogStore store() {
        return (CatalogStore) Proxy.newProxyInstance(CatalogStore.class.getClassLoader(),
                new Class<?>[] {CatalogStore.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "findCatalog" -> Optional.ofNullable(records.get((String) args[0]));
                    case "listGrantsOfGroups" -> {
                        UUID catalogId = (UUID) args[0];
                        Set<String> groups = (Set<String>) args[1];
                        asked.add(groups);
                        List<GrantRecord> found = grants.stream()
                                .filter(g -> g.grant().catalogId().equals(catalogId)
                                        && groups.contains(g.grant().group()))
                                .limit(((PageRequest) args[2]).size())
                                .toList();
                        yield new Page<>(found, Optional.empty());
                    }
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }
}
