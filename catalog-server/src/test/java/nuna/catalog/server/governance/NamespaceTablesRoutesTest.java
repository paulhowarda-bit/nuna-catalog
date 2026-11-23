package nuna.catalog.server.governance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.lang.reflect.Proxy;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import nuna.catalog.audit.CatalogEvent;
import nuna.catalog.audit.Denial;
import nuna.catalog.core.CatalogError;
import nuna.catalog.core.Names;
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
import nuna.catalog.server.telemetry.RequestIds;
import nuna.catalog.store.CatalogObject;
import nuna.catalog.store.CatalogObject.Kind;
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
import nuna.catalog.store.Securable;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import tools.jackson.databind.JsonNode;

/**
 * A namespace's tables with each one's format, snapshot count and last commit, for the console's Contents (D45,
 * backlog 137): read from the pointer's projection and the journal entry of its version, never from the object store;
 * a table the caller may not describe is left out; a caller who holds nothing on the namespace is answered as for one
 * that does not exist, and audited; paged, and bounded in rows and in the tables one page looks at. The route, guard
 * and authorizer are the real ones over a store double, which fails any call it does not expect, so this needs only
 * Java.
 */
class NamespaceTablesRoutesTest {

    private static final Instant NOW = Instant.parse("2026-11-22T07:00:00Z");

    /** Describe on the namespace desk. */
    private static final Caller RITA = person("rita", "desk-readers");
    /** Select on the table desk.trades only. */
    private static final Caller TOM = person("tom", "trades-readers");
    /** Describe on the namespace lobby only: he holds something in the catalog, nothing on desk. */
    private static final Caller OTTO = person("otto", "outsiders");
    /** Nothing in the catalog. */
    private static final Caller NED = person("ned", "newcomers");

    private final CatalogRecord catalog = new CatalogRecord(UUID.randomUUID(), "gate", "gate");
    private final Map<UUID, NamespaceRecord> namespaces = new LinkedHashMap<>();
    private final Map<UUID, CatalogObject> objects = new LinkedHashMap<>();
    private final Map<PointerKey, Pointer> pointers = new LinkedHashMap<>();
    private final Map<PointerKey, List<JournalRecord>> journal = new LinkedHashMap<>();
    private final List<GrantRecord> grants = new ArrayList<>();
    private final List<CatalogEvent> audit = new ArrayList<>();

    private final NamespaceRecord desk = namespace(null, "desk");
    private final NamespaceRecord lobby = namespace(null, "lobby");
    /** Format 2, three snapshots, four commits: the last by an engine for an end user. */
    private final CatalogObject trades = table(desk, "trades");
    /** Format 3, no snapshot yet, created and not committed to since. */
    private final CatalogObject positions = table(desk, "positions");
    private final CatalogObject daily = object(desk, "daily", Kind.VIEW);
    private final CatalogObject visitors = table(lobby, "visitors");

    private final MockHttpServletRequest http = new MockHttpServletRequest();
    private final NamespaceTablesRoutes routes;
    private Caller caller = RITA;

    NamespaceTablesRoutesTest() {
        http.setAttribute(RequestIds.class.getName(), "request-1");
        grant(new Securable(Securable.Kind.NAMESPACE, desk.id()), "desk-readers", Privilege.DESCRIBE);
        grant(trades.securable(), "trades-readers", Privilege.SELECT);
        grant(new Securable(Securable.Kind.NAMESPACE, lobby.id()), "outsiders", Privilege.DESCRIBE);

        commits(trades, 2, 3, List.of(
                new JournalEntry("etl", null, "spark", "[]", "[]"),
                new JournalEntry("etl", null, "spark", "[]", "[]"),
                new JournalEntry("etl", null, "spark", "[]", "[]"),
                new JournalEntry("trino", "ana", "trino", "[]", "[]")));
        commits(positions, 3, 0, List.of(new JournalEntry("ingest", null, null, "[]", "[]")));
        commits(visitors, 2, 1, List.of(new JournalEntry("ingest", null, null, "[]", "[]")));

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
        // No security settings: they name only the maintenance principals, whom this route never asks for.
        ManagementGuard guard = new ManagementGuard(authorizer, separation, null, denials, holdings);
        routes = new NamespaceTablesRoutes(store, management, guard, holdings, authorizer);
    }

    @Test
    void eachTableIsOneRowWithItsFormatSnapshotsAndLastCommit() {
        JsonNode body = tables("desk", null, null);

        assertThat(body.has("next-page-token")).isFalse();
        assertThat(names(body)).as("by name; the view and the other namespace's table are not listed")
                .containsExactly("positions", "trades");

        JsonNode row = body.get("tables").get(1);
        assertThat(row.get("id").asString()).isEqualTo(trades.id().toString());
        assertThat(row.get("format-version").asInt()).isEqualTo(2);
        assertThat(row.get("snapshots").asLong()).isEqualTo(3);
        JsonNode last = row.get("last-commit");
        assertThat(last.get("version").asLong()).isEqualTo(4);
        assertThat(last.get("committed-at").asString()).isEqualTo(NOW.minusSeconds(60).toString());
        assertThat(last.get("principal").asString()).isEqualTo("trino");
        assertThat(last.get("end-user").asString()).isEqualTo("ana");
        assertThat(last.get("engine").asString()).isEqualTo("trino");

        JsonNode created = body.get("tables").get(0);
        assertThat(created.get("format-version").asInt()).isEqualTo(3);
        assertThat(created.get("snapshots").asLong()).isZero();
        assertThat(created.get("last-commit").get("version").asLong()).isEqualTo(1);
        assertThat(created.get("last-commit").get("principal").asString()).isEqualTo("ingest");
        assertThat(created.get("last-commit").has("end-user")).isFalse();
        assertThat(created.get("last-commit").has("engine")).isFalse();
        assertThat(denials()).as("a listing records nothing per table").isEmpty();
    }

    /** Tom holds select on trades alone: he sees the namespace, and in it only trades. */
    @Test
    void aTableTheCallerMayNotDescribeIsLeftOut() {
        caller = TOM;

        JsonNode body = tables("desk", null, null);

        assertThat(names(body)).containsExactly("trades");
        assertThat(body.has("next-page-token")).isFalse();
        assertThat(denials()).as("a table left out is not a refusal").isEmpty();
    }

    @Test
    void aCallerWhoHoldsNothingOnTheNamespaceIsAnsweredAsForOneThatDoesNotExist() {
        caller = OTTO;
        Throwable refused = catchThrowable(() -> routes.tables("gate", "desk", null, null, http));
        assertThat(refused).isInstanceOfSatisfying(CatalogError.class, error -> {
            assertThat(error.status()).isEqualTo(404);
            assertThat(error.type()).isEqualTo("NoSuchNamespaceException");
            assertThat(error.getMessage()).isEqualTo("Namespace does not exist: desk");
        });
        assertThat(denials()).extracting(Denial::operation).containsExactly("listNamespaceTables");
        assertThat(denials().getFirst().privilege()).isEqualTo("describe");
        assertThat(denials().getFirst().securable().path()).containsExactly("desk");

        caller = NED;
        assertThat(catchThrowable(() -> routes.tables("gate", "desk", null, null, http)))
                .isInstanceOfSatisfying(CatalogError.class, error ->
                        assertThat(error.type()).isEqualTo("NoSuchWarehouseException"));
    }

    @Test
    void pagesWalkEveryRowOnce() {
        for (int i = 0; i < 5; i++) {
            table(desk, "t" + i);
        }
        List<String> paged = new ArrayList<>();
        String token = null;
        do {
            JsonNode page = tables("desk", token, 2);
            assertThat(page.get("tables").size()).isLessThanOrEqualTo(2);
            paged.addAll(names(page));
            token = page.has("next-page-token") ? page.get("next-page-token").asString() : null;
        } while (token != null);

        assertThat(paged).containsExactly("positions", "t0", "t1", "t2", "t3", "t4", "trades");
    }

    @Test
    void aPageHoldsAtMostTwoHundredRows() {
        for (int i = 0; i < NamespaceTablesRoutes.MAX_PAGE + 50; i++) {
            table(desk, "t" + i);
        }

        JsonNode page = tables("desk", null, 100_000);

        assertThat(page.get("tables")).hasSize(NamespaceTablesRoutes.MAX_PAGE);
        assertThat(page.has("next-page-token")).isTrue();
    }

    /** Tom may describe one table of many: a page stops after 1,000 tables looked at, with a token. */
    @Test
    void aPageLooksAtAThousandTablesAtMost() {
        caller = TOM;
        for (int i = 0; i < NamespaceTablesRoutes.MAX_EXAMINED + 100; i++) {
            table(desk, "a" + i);
        }

        JsonNode first = tables("desk", null, null);
        assertThat(first.get("tables")).as("every table looked at is hidden from Tom").isEmpty();
        assertThat(first.has("next-page-token")).isTrue();

        JsonNode second = tables("desk", first.get("next-page-token").asString(), null);
        assertThat(names(second)).containsExactly("trades");
        assertThat(second.has("next-page-token")).isFalse();
    }

    @Test
    void aTokenTheStoreDidNotIssueIsABadRequest() {
        Throwable refused = catchThrowable(() -> routes.tables("gate", "desk", "nope", null, http));

        assertThat(refused).isInstanceOfSatisfying(CatalogError.class, error -> {
            assertThat(error.status()).isEqualTo(400);
            assertThat(error.type()).isEqualTo("BadRequestException");
        });
    }

    // ---- helpers ----

    private JsonNode tables(String namespace, String token, Integer size) {
        ResponseEntity<String> response = routes.tables("gate", namespace, token, size, http);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        return Management.JSON.readTree(response.getBody());
    }

    private static List<String> names(JsonNode body) {
        return body.get("tables").valueStream().map(row -> row.get("name").asString()).toList();
    }

    private List<Denial> denials() {
        return audit.stream().map(CatalogEvent::data).filter(Denial.class::isInstance).map(Denial.class::cast)
                .toList();
    }

    private static Caller person(String id, String... groups) {
        return new Caller(new Principal(id, Set.of(groups)), "console", false, Optional.empty());
    }

    private NamespaceRecord namespace(NamespaceRecord parent, String name) {
        NamespaceRecord record = new NamespaceRecord(catalog.id(), UUID.randomUUID(),
                parent == null ? catalog.id() : parent.id(), new Name(Names.normalize(name), name), Map.of(), 1);
        namespaces.put(record.id(), record);
        return record;
    }

    /** A table with format 2, no snapshot and one commit, which {@link #commits} may replace. */
    private CatalogObject table(NamespaceRecord namespace, String name) {
        CatalogObject table = object(namespace, name, Kind.TABLE);
        commits(table, 2, 0, List.of(new JournalEntry("ingest", null, null, "[]", "[]")));
        return table;
    }

    private CatalogObject object(NamespaceRecord namespace, String name, Kind kind) {
        UUID id = UUID.randomUUID();
        CatalogObject object = CatalogObject.active(catalog.id(), id, namespace.id(),
                new Name(Names.normalize(name), name), kind, "s3://gate/" + id);
        objects.put(id, object);
        return object;
    }

    /**
     * The table's journal, one entry a minute up to a minute ago, and its main pointer at the last version, whose
     * projection gives the format and snapshot count as the commit engine writes it.
     */
    private void commits(CatalogObject table, int format, int snapshots, List<JournalEntry> entries) {
        PointerKey key = table.mainKey();
        List<JournalRecord> records = new ArrayList<>();
        for (int i = 0; i < entries.size(); i++) {
            long version = i + 1;
            records.add(new JournalRecord(UUID.randomUUID(), key, NOW.minusSeconds(60L * (entries.size() - i)),
                    entries.get(i), version == 1 ? Optional.empty() : Optional.of(location(table, version - 1)),
                    location(table, version), version));
        }
        journal.put(key, records);
        String projection = "{\"kind\":\"table\",\"uuid\":\"" + table.id() + "\",\"format-version\":" + format
                + ",\"current-schema-id\":0,\"default-spec-id\":0,\"snapshots\":" + snapshots + "}";
        pointers.put(key, new Pointer(location(table, entries.size()), entries.size(), "0".repeat(64),
                projection.getBytes(StandardCharsets.UTF_8)));
    }

    private static String location(CatalogObject table, long version) {
        return table.locationRoot() + "/metadata/" + version + ".metadata.json";
    }

    private void grant(Securable on, String group, Privilege privilege) {
        grants.add(new GrantRecord(new Grant(catalog.id(), on, group, privilege.wireName()), "test", Instant.EPOCH));
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

    /** One page of {@code items}, {@code page.size()} at a time, as a store pages them. */
    private static <T> Page<T> page(List<T> items, PageRequest page) {
        int from = page.token().map(token -> Integer.parseInt(PageToken.decode(token, 1).getFirst())).orElse(0);
        int to = Math.min(items.size(), from + page.size());
        return new Page<>(List.copyOf(items.subList(from, to)),
                to < items.size() ? Optional.of(PageToken.encode(Integer.toString(to))) : Optional.empty());
    }

    /** The journal of one pointer after the version the request's token names, as every store pages it. */
    private Page<JournalRecord> readJournal(PointerKey key, PageRequest page) {
        long after = page.token().map(token -> Long.parseLong(PageToken.decode(token, 1).getFirst())).orElse(0L);
        List<JournalRecord> items = journal.getOrDefault(key, List.of()).stream()
                .filter(record -> record.newVersion() > after).limit(page.size()).toList();
        return new Page<>(items, Optional.empty());
    }

    /**
     * The catalog above, its grants, pointers and journal, with no attributes or holds. Any other call fails the test,
     * the object store's above all: this store has none.
     */
    @SuppressWarnings("unchecked")
    private CatalogStore store() {
        return (CatalogStore) Proxy.newProxyInstance(CatalogStore.class.getClassLoader(),
                new Class<?>[] {CatalogStore.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "findCatalog" -> Optional.of(catalog).filter(c -> c.normalizedName().equals(args[0]));
                    case "findNamespace" -> findNamespace((List<String>) args[1]);
                    case "readNamespace" -> Optional.ofNullable(namespaces.get((UUID) args[0]));
                    case "readObject" -> Optional.ofNullable(objects.get((UUID) args[0]));
                    case "listObjects" -> page(objects.values().stream()
                            .filter(o -> o.namespaceId().equals(args[0]) && o.kind() == args[1]
                                    && o.state() == args[2])
                            .sorted(Comparator.comparing((CatalogObject o) -> o.name().normalized())
                                    .thenComparing(CatalogObject::id))
                            .toList(), (PageRequest) args[3]);
                    case "readPointer" -> Optional.ofNullable(pointers.get((PointerKey) args[0]));
                    case "readJournal" -> readJournal((PointerKey) args[0], (PageRequest) args[3]);
                    case "grantsFor" -> grants.stream()
                            .filter(g -> ((Set<Securable>) args[0]).contains(g.grant().securable())
                                    && ((Set<String>) args[1]).contains(g.grant().group()))
                            .toList();
                    case "attributesOf", "holdsOn" -> List.of();
                    case "listGrantsOfGroups" -> new Page<>(grants.stream()
                            .filter(g -> g.grant().catalogId().equals(args[0])
                                    && ((Set<String>) args[1]).contains(g.grant().group()))
                            .toList(), Optional.empty());
                    case "toString" -> "the test's store";
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }
}
