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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import nuna.catalog.core.CatalogError;
import nuna.catalog.core.principal.Caller;
import nuna.catalog.core.principal.Principal;
import nuna.catalog.security.Authorizer;
import nuna.catalog.security.Privilege;
import nuna.catalog.security.Separation;
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
import nuna.catalog.store.Grant;
import nuna.catalog.store.GrantRecord;
import nuna.catalog.store.JournalEntry;
import nuna.catalog.store.JournalRecord;
import nuna.catalog.store.Name;
import nuna.catalog.store.NamespaceRecord;
import nuna.catalog.store.Page;
import nuna.catalog.store.PageRequest;
import nuna.catalog.store.PageToken;
import nuna.catalog.store.Pointer;
import nuna.catalog.store.PointerKey;
import nuna.catalog.store.Ref;
import nuna.catalog.store.RefEntry;
import nuna.catalog.store.RefPointers;
import nuna.catalog.store.Securable;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import tools.jackson.databind.JsonNode;

/**
 * A table's metadata log read through a catalog ref (D33, backlog 109): through a branch, the versions it committed of
 * its own come from its own journal and the rest from main's, below and at the cut, never main's commits since; through
 * a tag, main's log as the cut left it. The route, guard and authorizer are the real ones over a store double, so this
 * needs only Java.
 */
class MetadataLogRefTest {

    private static final Instant NOW = Instant.parse("2026-11-23T12:00:00Z");
    private static final Caller ANN = new Caller(new Principal("ann", Set.of("readers")), "console", false,
            Optional.empty());

    private final CatalogRecord catalog = new CatalogRecord(UUID.randomUUID(), "gate", "gate");
    private final NamespaceRecord hr = new NamespaceRecord(catalog.id(), UUID.randomUUID(), catalog.id(),
            new Name("hr", "hr"), Map.of(), 1);
    private final UUID staffId = UUID.randomUUID();
    private final CatalogObject staff = new CatalogObject(catalog.id(), staffId, hr.id(), new Name("staff", "staff"),
            CatalogObject.Kind.TABLE, CatalogObject.State.ACTIVE, Optional.empty(), Optional.empty(),
            "s3://gate/" + staffId, Optional.empty());
    private final List<GrantRecord> grants = List.of(new GrantRecord(new Grant(catalog.id(),
            new Securable(Securable.Kind.NAMESPACE, hr.id()), "readers", Privilege.SELECT.wireName()), "test",
            Instant.EPOCH));
    /** Made when main was at version 3; it committed 4 to 6 of its own. Main went on to 5. */
    private final Ref dev = ref("dev", Ref.Kind.BRANCH);
    /** Made when main was at version 3. */
    private final Ref v1 = ref("v1", Ref.Kind.TAG);
    /** Its cut does not hold staff, as for a table made after it. */
    private final Ref later = ref("later", Ref.Kind.BRANCH);
    private final Map<PointerKey, List<JournalRecord>> journals = new HashMap<>();

    private final LifecycleRoutes lifecycle;
    private final MockHttpServletRequest http = new MockHttpServletRequest();

    MetadataLogRefTest() {
        http.setAttribute(RequestIds.class.getName(), "request-1");
        journals.put(staff.mainKey(), journal(staff.mainKey(), "main", 1, 5));
        journals.put(dev.key(staffId), journal(dev.key(staffId), "dev", 4, 6));
        CatalogStore store = store();
        RestSettings settings = new RestSettings(100, 1000, 1_048_576, 1000, 268_435_456, Duration.ofDays(30),
                Duration.ofMinutes(5), "the identity provider", URI.create("urn:nuna:test"), 100, null);
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        AuditPublisher publisher = event -> { };
        Separation separation = new Separation(Set.of("grant-admins"), Set.of("platform-ops"));
        Authorizer authorizer = new Authorizer(store, store, store, separation, false, Set.of());
        Denials denials = DenialsForTests.denials(publisher, settings, clock);
        Holdings holdings = new Holdings(authorizer, store, settings, separation, denials);
        Management management = new Management(store, request -> ANN, settings, holdings);
        SecuritySettings security = new SecuritySettings(List.of(new SecuritySettings.Issuer("https://idp.test",
                URI.create("https://idp.test/keys"), Set.of("nuna"), Set.of(), "sub", "groups", "azp")),
                Duration.ofSeconds(60), Set.of("grant-admins"), Set.of("platform-ops"), Set.of("nuna-maintenance"),
                false);
        ManagementGuard guard = new ManagementGuard(authorizer, separation, security, denials, holdings);
        // No governance settings, audit trail, storage or catalog names: the log needs none of them.
        lifecycle = new LifecycleRoutes(store, management, guard, null, null, null, null, clock);
    }

    @Test
    void onMainTheLogIsMainsWhateverTheRefsDid() {
        JsonNode log = log(null, null, null);

        assertThat(log.get("pointer").get("version").asLong()).isEqualTo(5);
        assertThat(log.has("ref")).isFalse();
        assertThat(locations(log)).containsExactly("main-5", "main-4", "main-3", "main-2", "main-1");
    }

    @Test
    void throughABranchItsOwnVersionsComeFromItsJournalAndTheRestFromMainsAtTheCut() {
        JsonNode log = log("DEV", null, null);

        assertThat(log.get("pointer").get("version").asLong()).isEqualTo(6);
        assertThat(log.get("pointer").get("metadata-location").asString()).isEqualTo("dev-6");
        assertThat(log.get("ref").get("name").asString()).isEqualTo("dev");
        assertThat(log.get("ref").get("kind").asString()).isEqualTo("branch");
        assertThat(log.get("ref").get("cut-version").asLong()).isEqualTo(3);
        assertThat(locations(log)).as("main's 4 and 5, made since the cut, are not the branch's")
                .containsExactly("dev-6", "dev-5", "dev-4", "main-3", "main-2", "main-1");
    }

    @Test
    void throughABranchThePagesCrossTheCutWithoutAGapOrARepeat() {
        List<String> read = new ArrayList<>();
        String token = null;
        int pages = 0;
        do {
            JsonNode log = log("dev", token, 4);
            read.addAll(locations(log));
            token = log.has("next-page-token") ? log.get("next-page-token").asString() : null;
            pages++;
        } while (token != null);

        assertThat(read).containsExactly("dev-6", "dev-5", "dev-4", "main-3", "main-2", "main-1");
        assertThat(pages).isEqualTo(2);
    }

    @Test
    void throughATagTheLogIsMainsAsTheCutLeftIt() {
        JsonNode log = log("v1", null, null);

        assertThat(log.get("pointer").get("version").asLong()).isEqualTo(3);
        assertThat(log.get("ref").get("kind").asString()).isEqualTo("tag");
        assertThat(locations(log)).containsExactly("main-3", "main-2", "main-1");
    }

    @Test
    void aRefThatIsNotLiveOrDoesNotHoldTheTableAnswers404() {
        assertThatThrownBy(() -> log("nope", null, null)).isInstanceOfSatisfying(CatalogError.class, e -> {
            assertThat(e.status()).isEqualTo(404);
            assertThat(e.type()).isEqualTo("NoSuchRefException");
        });
        assertThatThrownBy(() -> log("later", null, null)).isInstanceOfSatisfying(CatalogError.class, e -> {
            assertThat(e.status()).isEqualTo(404);
            assertThat(e.type()).isEqualTo("NoSuchObjectException");
        });
    }

    private JsonNode log(String ref, String token, Integer size) {
        return Management.JSON.readTree(lifecycle.metadataLog("gate", staffId.toString(), token, size, ref, http)
                .getBody());
    }

    private static List<String> locations(JsonNode log) {
        return log.get("journal").valueStream().map(entry -> entry.get("new-location").asString()).toList();
    }

    private Ref ref(String name, Ref.Kind kind) {
        return new Ref(catalog.id(), UUID.randomUUID(), new Name(name, name), kind, NOW.minus(Duration.ofDays(1)),
                "bea", Optional.empty(), Ref.MAIN, kind == Ref.Kind.BRANCH
                        ? Optional.of(NOW.plus(Duration.ofDays(29))) : Optional.empty());
    }

    /** Versions {@code from} to {@code to} of one pointer, each at the location {@code <who>-<version>}. */
    private static List<JournalRecord> journal(PointerKey key, String who, long from, long to) {
        List<JournalRecord> records = new ArrayList<>();
        for (long version = from; version <= to; version++) {
            records.add(new JournalRecord(UUID.randomUUID(), key, NOW.minusSeconds(3600 - version), new JournalEntry(
                    "ann", null, null, "[]", "[]"), version == 1 ? Optional.empty()
                    : Optional.of(who + "-" + (version - 1)), who + "-" + version, version));
        }
        return records;
    }

    private Page<JournalRecord> readJournal(PointerKey key, PageRequest request) {
        long after = request.token().map(t -> Long.parseLong(PageToken.decode(t, 1).getFirst())).orElse(0L);
        List<JournalRecord> page = journals.getOrDefault(key, List.of()).stream()
                .filter(record -> record.newVersion() > after).limit(request.size()).toList();
        return new Page<>(page, page.size() < request.size() ? Optional.empty()
                : Optional.of(PageToken.encode(Long.toString(page.getLast().newVersion()))));
    }

    private List<RefPointers> refPointers(UUID objectId) {
        RefEntry.At cut = new RefEntry.At(3, "main-3", "sha-main-3");
        return objectId.equals(staffId)
                ? List.of(new RefPointers(dev, cut, Optional.of(new RefEntry.At(6, "dev-6", "sha-dev-6"))),
                        new RefPointers(v1, cut, Optional.empty()))
                : List.of();
    }

    /** The catalog gate, its namespace hr, the table staff, the refs dev, v1 and later, and the journals. */
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
                    case "attributesOf", "holdsOn" -> List.of();
                    case "listGrantsOfGroups" -> new Page<>(grants.stream()
                            .filter(g -> ((Set<String>) args[1]).contains(g.grant().group())).toList(),
                            Optional.empty());
                    case "readPointer" -> Optional.of(new Pointer("main-5", 5, "sha-main-5", new byte[0]))
                            .filter(p -> args[0].equals(staff.mainKey()));
                    case "findRef" -> List.of(dev, v1, later).stream()
                            .filter(r -> r.catalogId().equals(args[0]) && r.name().normalized().equals(args[1]))
                            .findFirst();
                    case "listRefPointers" -> refPointers((UUID) args[0]);
                    case "readJournal" -> readJournal((PointerKey) args[0], (PageRequest) args[3]);
                    case "toString" -> "the test's store";
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }
}
