package nuna.catalog.server.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import nuna.catalog.objectstore.StoreKind;
import nuna.catalog.server.rest.RestSettings;
import nuna.catalog.store.CatalogStore;
import nuna.catalog.store.WriteResult;
import io.micrometer.observation.ObservationRegistry;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A configured catalog's name holding {@code @} stops the server at startup, before anything is written to the store:
 * the REST prefix {@code catalog@ref} keeps {@code @} for a branch or tag, so a prefix parses one way (D33, item 26's
 * choice 4; item 104). Java only: the store is a double that records every call.
 */
class CatalogNameTest {

    private static final RestSettings REST = new RestSettings(100, 1000, 1_048_576, 1000, 268_435_456,
            Duration.ofDays(30), Duration.ofMinutes(5), "the test's identity provider", URI.create("urn:nuna:test"),
            100, null);
    private static final StorageSettings.Profile PROFILE = new StorageSettings.Profile(StoreKind.MINIO,
            "http://127.0.0.1:9", "us-east-1", "s3://lake/tables", List.of("s3://lake/tables"), null, "catalog",
            "catalog-secret", null, null, null);

    private final List<String> calls = new CopyOnWriteArrayList<>();

    @Test
    @DisplayName("a configured catalog named trading@dev stops startup, naming it, and nothing reaches the store")
    void aCatalogNameHoldingAnAtSignStopsStartup() {
        for (String name : List.of("trading@dev", "@trading", "trading@")) {
            assertThatThrownBy(() -> start(name)).as(name)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("nuna.storage.catalogs." + name + ": a catalog's name holds no @, which the REST"
                            + " prefix catalog@ref keeps for a branch or tag");
        }
        assertThat(calls).as("nothing was read from or written to the store").isEmpty();
    }

    @Test
    @DisplayName("a catalog named without @ is declared as before")
    void aNameWithoutAnAtSignIsDeclared() throws Exception {
        try (CatalogStorage storage = start("trading")) {
            assertThat(storage.profiles()).extracting(CatalogStorage.Bound::name).containsExactly("lake");
        }
        assertThat(calls).contains("findCatalog", "createCatalog");
    }

    private CatalogStorage start(String catalog) {
        return new CatalogStorage(new StorageSettings(Map.of("lake", PROFILE),
                Map.of(catalog, new StorageSettings.Catalog("lake", "test-keys", "test-entity")),
                Duration.ofMinutes(30), 100, false), store(), REST, Clock.systemUTC(), ObservationRegistry.NOOP);
    }

    /** Holds nothing; a create answers that it was applied. */
    private CatalogStore store() {
        return (CatalogStore) Proxy.newProxyInstance(CatalogStore.class.getClassLoader(),
                new Class<?>[] {CatalogStore.class}, (proxy, method, args) -> {
                    if (method.getName().equals("toString")) {
                        return "the test's store";
                    }
                    calls.add(method.getName());
                    return switch (method.getName()) {
                        case "readStorageProfile", "findCatalog" -> Optional.empty();
                        case "putStorageProfile", "createCatalog" -> WriteResult.APPLIED;
                        default -> throw new UnsupportedOperationException(method.getName());
                    };
                });
    }
}
