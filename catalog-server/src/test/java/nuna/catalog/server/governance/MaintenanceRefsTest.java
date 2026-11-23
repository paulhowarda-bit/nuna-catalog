package nuna.catalog.server.governance;

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
import java.util.Set;
import java.util.UUID;
import nuna.catalog.core.CatalogError;
import nuna.catalog.core.Names;
import nuna.catalog.core.principal.Caller;
import nuna.catalog.core.principal.Principal;
import nuna.catalog.security.Authorizer;
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
import nuna.catalog.store.Name;
import nuna.catalog.store.NamespaceRecord;
import nuna.catalog.store.Page;
import nuna.catalog.store.PendingDeletion;
import nuna.catalog.store.Ref;
import nuna.catalog.store.RefEntry;
import nuna.catalog.store.RefPointers;
import nuna.catalog.store.WriteResult;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import tools.jackson.databind.JsonNode;

/**
 * The catalog's side of cleanup under refs (D33, item 108, item 26's choice 14): the work list names, for each table,
 * the metadata every ref whose cut names it reaches, a tag's cut, a branch's cut and its own head; a record of
 * deletions that does not name every ref now naming the table is refused, with the table's refs now; and a due deletion
 * names the refs made since it was recorded, less an hour, and its live table's name to load it by. The routes and
 * guard are the real ones over a store double, as in RefRoutesTest; RefSuite checks the stores' listRefPointers, so
 * this needs only Java.
 */
class MaintenanceRefsTest {

    private static final Instant NOW = Instant.parse("2026-11-22T12:00:00Z");
    private static final Caller WORKER = new Caller(new Principal("nuna-maintenance", Set.of()), "worker", true,
            Optional.empty());

    private final CatalogRecord catalog = new CatalogRecord(UUID.randomUUID(), "gate", "gate");
    private final NamespaceRecord sales = new NamespaceRecord(catalog.id(), UUID.randomUUID(), catalog.id(),
            new Name("sales", "sales"), Map.of(), 1);
    private final CatalogObject orders = CatalogObject.active(catalog.id(), UUID.randomUUID(), sales.id(),
            new Name(Names.normalize("Orders"), "Orders"), CatalogObject.Kind.TABLE, "s3://gate/orders");
    private final CatalogObject old = new CatalogObject(catalog.id(), UUID.randomUUID(), sales.id(),
            new Name("old", "old"), CatalogObject.Kind.TABLE, CatalogObject.State.DROPPED,
            Optional.of(NOW.minus(Duration.ofDays(3))), Optional.of(NOW.plus(Duration.ofDays(4))), "s3://gate/old",
            Optional.empty());
    private final Map<UUID, CatalogObject> objects = Map.of(orders.id(), orders, old.id(), old);
    /** The refs whose cut names each object, as listRefPointers answers. */
    private final Map<UUID, List<RefPointers>> named = new LinkedHashMap<>();
    private final List<PendingDeletion> due = new ArrayList<>();
    private final List<List<String>> recorded = new ArrayList<>();
    private final MockHttpServletRequest http = new MockHttpServletRequest();
    private final MaintenanceRoutes routes;

    MaintenanceRefsTest() {
        http.setAttribute(RequestIds.class.getName(), "request-1");
        CatalogStore store = store();
        RestSettings rest = new RestSettings(100, 1000, 1_048_576, 1000, 268_435_456, Duration.ofDays(30),
                Duration.ofMinutes(5), "the identity provider", URI.create("urn:nuna:test"), 100, null);
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        Separation separation = new Separation(Set.of("grant-admins"), Set.of("platform-ops"));
        Authorizer authorizer = new Authorizer(store, store, store, separation, false, Set.of());
        Denials denials = DenialsForTests.denials(event -> { }, rest, clock);
        Holdings holdings = new Holdings(authorizer, store, rest, separation, denials);
        Management management = new Management(store, request -> WORKER, rest, holdings);
        SecuritySettings security = new SecuritySettings(List.of(new SecuritySettings.Issuer("https://idp.test",
                URI.create("https://idp.test/keys"), Set.of("nuna"), Set.of(), "sub", "groups", "azp")),
                Duration.ofSeconds(60), Set.of("grant-admins"), Set.of("platform-ops"), Set.of("nuna-maintenance"),
                false);
        ManagementGuard guard = new ManagementGuard(authorizer, separation, security, denials, holdings);
        CatalogNames names = new CatalogNames(null, null) {
            @Override
            String name(UUID id) {
                return id.equals(catalog.id()) ? "gate" : null;
            }
        };
        StorageSettings storage = new StorageSettings(Map.of(), Map.of("gate", new StorageSettings.Catalog(
                "warehouse", null, null)), Duration.ofHours(1), 100, false);
        GovernanceSettings settings = new GovernanceSettings(10, 60, Map.of(), Duration.ofDays(7), Duration.ofHours(1),
                Map.of(MaintenancePolicy.EXPIRY_ENABLED, "true"));
        routes = new MaintenanceRoutes(store, management, guard, settings, names, new RefSettings(2, 3,
                Duration.ofDays(30), Duration.ofDays(180), Duration.ofDays(7)), storage, clock, authorizer);
    }

    @Test
    void aTableNamesWhatEachRefOfItReaches() {
        RefPointers tag = ref(orders, Ref.Kind.TAG, NOW.minus(Duration.ofDays(2)), 1, null);
        RefPointers branch = ref(orders, Ref.Kind.BRANCH, NOW.minus(Duration.ofDays(1)), 1, 3L);

        JsonNode table = work("tables").get("tables").get(0);

        assertThat(table.get("id").asString()).isEqualTo(orders.id().toString());
        assertThat(table.get("refs")).hasSize(2);
        assertRef(table.get("refs").get(0), tag, metadata(orders, 1));
        assertRef(table.get("refs").get(1), branch, metadata(orders, 1), metadata(orders, 3));
    }

    @Test
    void aRecordNotNamingEveryRefOfTheTableIsRefused() {
        RefPointers tag = ref(orders, Ref.Kind.TAG, NOW.minus(Duration.ofMinutes(1)), 1, null);
        String file = "s3://gate/orders/data/a.parquet";

        ResponseEntity<String> refused = routes.record("gate", orders.id().toString(), record(file), http);
        assertThat(refused.getStatusCode().value()).isEqualTo(409);
        JsonNode answer = Management.JSON.readTree(refused.getBody());
        assertThat(answer.get("error").get("type").asString()).isEqualTo("RefsChangedException");
        assertThat(answer.get("refs")).hasSize(1);
        assertRef(answer.get("refs").get(0), tag, metadata(orders, 1));
        assertThat(recorded).as("nothing recorded").isEmpty();

        assertThat(routes.record("gate", orders.id().toString(), record(file, tag.ref().id(), UUID.randomUUID()), http)
                .getStatusCode().value()).as("naming every ref, and one since deleted").isEqualTo(201);
        assertThat(recorded).containsExactly(List.of(file));

        assertThat(status(() -> routes.record("gate", orders.id().toString(), "{\"locations\":[\"" + file + "\"]}",
                http))).as("no refs named").isEqualTo(400);
    }

    @Test
    void aDueDeletionNamesTheRefsMadeSinceItWasRecorded() {
        Instant recordedAt = NOW.minus(Duration.ofDays(8));
        ref(orders, Ref.Kind.TAG, recordedAt.minus(Duration.ofHours(2)), 1, null);
        RefPointers margin = ref(orders, Ref.Kind.TAG, recordedAt.minus(Duration.ofMinutes(30)), 1, null);
        RefPointers branch = ref(orders, Ref.Kind.BRANCH, recordedAt.plus(Duration.ofDays(1)), 1, 2L);
        RefPointers kept = ref(old, Ref.Kind.TAG, recordedAt.plus(Duration.ofDays(1)), 1, null);
        due.add(deletion(orders, "a", recordedAt));
        due.add(deletion(old, "b", recordedAt));
        due.add(deletion(orders, "c", NOW.minus(Duration.ofHours(1))));

        JsonNode deletions = work("deletions").get("deletions");

        JsonNode live = deletions.get(0);
        assertThat(live.get("refs")).as("made since, or within the hour before").hasSize(2);
        assertRef(live.get("refs").get(0), margin, metadata(orders, 1));
        assertRef(live.get("refs").get(1), branch, metadata(orders, 1), metadata(orders, 2));
        assertThat(live.get("namespace").get(0).asString()).isEqualTo("sales");
        assertThat(live.get("name").asString()).isEqualTo("Orders");
        JsonNode dropped = deletions.get(1);
        assertThat(dropped.get("refs")).hasSize(1);
        assertRef(dropped.get("refs").get(0), kept, metadata(old, 1));
        assertThat(dropped.has("name")).as("a dropped table is not loaded").isFalse();
        JsonNode later = deletions.get(2);
        assertThat(later.get("refs")).as("recorded after every ref was made").isEmpty();
        assertThat(later.has("name")).isFalse();
    }

    private JsonNode work(String section) {
        ResponseEntity<String> response = routes.work(section, null, null, http);
        assertThat(response.getStatusCode().value()).as(response.getBody()).isEqualTo(200);
        return Management.JSON.readTree(response.getBody());
    }

    private static void assertRef(JsonNode node, RefPointers ref, String... metadata) {
        assertThat(node.get("id").asString()).isEqualTo(ref.ref().id().toString());
        assertThat(node.get("kind").asString()).isEqualTo(ref.ref().kind() == Ref.Kind.TAG ? "tag" : "branch");
        List<String> locations = new ArrayList<>();
        node.get("metadata-locations").forEach(location -> locations.add(location.asString()));
        assertThat(locations).containsExactly(metadata);
    }

    private static String record(String file, UUID... refs) {
        List<String> ids = new ArrayList<>();
        for (UUID ref : refs) {
            ids.add("\"" + ref + "\"");
        }
        return "{\"locations\":[\"" + file + "\"],\"refs\":[" + String.join(",", ids) + "]}";
    }

    private static int status(Runnable call) {
        try {
            call.run();
            return 200;
        } catch (CatalogError e) {
            return e.status();
        }
    }

    /** A ref whose cut names {@code object} at {@code cut}, a branch's own head at {@code head} when given. */
    private RefPointers ref(CatalogObject object, Ref.Kind kind, Instant createdAt, long cut, Long head) {
        Ref ref = new Ref(catalog.id(), UUID.randomUUID(), new Name("r" + named.size(), "r" + named.size()), kind,
                createdAt, "bea", Optional.empty(), Ref.MAIN, kind == Ref.Kind.BRANCH
                ? Optional.of(createdAt.plus(Duration.ofDays(30))) : Optional.empty());
        RefPointers pointers = new RefPointers(ref, at(object, cut), Optional.ofNullable(head).map(v -> at(object, v)));
        named.computeIfAbsent(object.id(), id -> new ArrayList<>()).add(pointers);
        return pointers;
    }

    private static RefEntry.At at(CatalogObject object, long version) {
        return new RefEntry.At(version, metadata(object, version), "sha-" + version);
    }

    private static String metadata(CatalogObject object, long version) {
        return object.locationRoot() + "/metadata/v" + version + ".metadata.json";
    }

    private PendingDeletion deletion(CatalogObject object, String file, Instant recordedAt) {
        return new PendingDeletion(UUID.randomUUID(), object.id(), catalog.id(), object.locationRoot() + "/data/" + file
                + ".parquet", recordedAt.plus(Duration.ofDays(7)), recordedAt);
    }

    @SuppressWarnings("unchecked")
    private CatalogStore store() {
        return (CatalogStore) Proxy.newProxyInstance(CatalogStore.class.getClassLoader(),
                new Class<?>[] {CatalogStore.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "findCatalog" -> Optional.of(catalog).filter(c -> c.normalizedName().equals(args[0]));
                    case "readNamespace" -> Optional.of(sales).filter(n -> n.id().equals(args[0]));
                    case "readObject" -> Optional.ofNullable(objects.get((UUID) args[0]));
                    case "grantsFor", "attributesOf", "holdsOn" -> List.of();
                    case "listActiveObjects" -> new Page<>(List.of(orders), Optional.empty());
                    case "listDueDeletions" -> new Page<>(List.copyOf(due), Optional.empty());
                    case "listRefPointers" -> named.getOrDefault((UUID) args[0], List.of());
                    case "recordDeletions" -> {
                        recorded.add((List<String>) args[1]);
                        yield new WriteResult.Applied();
                    }
                    case "toString" -> "the test's store";
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }
}
