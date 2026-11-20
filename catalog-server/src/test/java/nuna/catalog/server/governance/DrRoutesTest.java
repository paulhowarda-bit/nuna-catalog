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
import java.util.Comparator;
import java.util.HashMap;
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
import nuna.catalog.store.CatalogStore;
import nuna.catalog.store.DrState;
import nuna.catalog.store.DrWatermark;
import nuna.catalog.store.JournalEntry;
import nuna.catalog.store.JournalRecord;
import nuna.catalog.store.Name;
import nuna.catalog.store.NamespaceRecord;
import nuna.catalog.store.Page;
import nuna.catalog.store.PageRequest;
import nuna.catalog.store.PageToken;
import nuna.catalog.store.Pointer;
import nuna.catalog.store.PointerKey;
import nuna.catalog.store.WriteResult;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import tools.jackson.databind.JsonNode;

/**
 * The DR watermark's routes (D37, amended 2026-11-20): only the maintenance worker acting as itself reads its work
 * list and reports a watermark, only platform operators read the tables behind, and a report is taken only for a
 * version the journal or the pointer holds, at or below the pointer, with its own metadata location. The routes and
 * guard are the real ones over a store double that keeps the watermark as every store does (DrSuite checks the
 * stores), so this needs only Java.
 */
class DrRoutesTest {

    private static final Instant NOW = Instant.parse("2026-11-20T12:00:00Z");
    private static final Caller WORKER = new Caller(new Principal("nuna-maintenance", Set.of()), "worker", false,
            Optional.empty());
    private static final Caller WORKER_FOR_ANN = new Caller(new Principal("nuna-maintenance", Set.of()), "worker",
            true, Optional.of(new Principal("ann", Set.of("analysts"))));
    private static final Caller OLGA = person("olga", "platform-ops");
    private static final Caller ANN = person("ann", "analysts");

    private final UUID catalogId = UUID.randomUUID();
    private final NamespaceRecord hr = new NamespaceRecord(catalogId, UUID.randomUUID(), catalogId,
            new Name("hr", "hr"), Map.of(), 1);
    private final Map<UUID, CatalogObject> objects = new LinkedHashMap<>();
    private final Map<UUID, List<JournalRecord>> journals = new HashMap<>();
    private final Map<UUID, DrWatermark> watermarks = new HashMap<>();
    private final List<CatalogEvent> audit = new ArrayList<>();

    /** Five versions, committed a minute apart, the last a minute ago; verified to version 2. */
    private final CatalogObject staff = object("Staff", CatalogObject.Kind.TABLE, 5);
    /** One version, ten minutes ago; never verified. */
    private final CatalogObject roster = object("roster", CatalogObject.Kind.VIEW, 1);
    /** Three versions, all verified. */
    private final CatalogObject level = object("level", CatalogObject.Kind.TABLE, 3);

    private final DrRoutes routes;
    private final MockHttpServletRequest http = new MockHttpServletRequest();
    private Caller caller = WORKER;

    DrRoutesTest() {
        http.setAttribute(RequestIds.class.getName(), "request-1");
        watermarks.put(staff.id(), new DrWatermark(2, location(staff, 2), NOW.minusSeconds(3600)));
        watermarks.put(level.id(), new DrWatermark(3, location(level, 3), NOW.minusSeconds(3600)));
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
        CatalogNames catalogs = new CatalogNames(null, null) {
            @Override
            String name(UUID id) {
                return id.equals(catalogId) ? "gate" : null;
            }
        };
        routes = new DrRoutes(store, management, guard, catalogs, clock);
    }

    @Test
    void onlyTheWorkerActingAsItselfReadsItsWorkAndReports() {
        String report = report(4, location(staff, 4));
        for (Caller other : List.of(ANN, OLGA, WORKER_FOR_ANN)) {
            caller = other;
            assertThatThrownBy(() -> routes.work(null, null, http)).as("work list for %s", other)
                    .isInstanceOf(AccessDenied.class);
            assertThatThrownBy(() -> routes.record(staff.id().toString(), report, http)).as("report by %s", other)
                    .isInstanceOf(AccessDenied.class);
        }

        assertThat(denials()).extracting(Denial::operation).containsExactly("listDrWork", "recordDrWatermark",
                "listDrWork", "recordDrWatermark", "listDrWork", "recordDrWatermark");
        assertThat(denials()).extracting(Denial::reason).allMatch(reason -> reason.startsWith("not_maintenance:"));
        assertThat(denials().get(1).securable().id()).as("a refused report names its table").isEqualTo(staff.id());
        assertThat(watermarks.get(staff.id()).version()).isEqualTo(2);
    }

    @Test
    void onlyPlatformOperatorsReadTheWatermarks() {
        for (Caller other : List.of(ANN, WORKER)) {
            caller = other;
            assertThatThrownBy(() -> routes.readout(null, null, http)).as("readout for %s", other)
                    .isInstanceOf(AccessDenied.class);
        }
        assertThat(denials()).extracting(Denial::operation).containsExactly("listDrWatermarks", "listDrWatermarks");

        caller = OLGA;

        assertThat(routes.readout(null, null, http).getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void theWorkListHoldsEachObjectBehindWithTheVersionsAboveItsWatermark() {
        JsonNode work = json(routes.work(null, null, http));

        assertThat(work.get("objects")).hasSize(2);
        JsonNode table = item(work.get("objects"), staff);
        assertThat(table.get("kind").asString()).isEqualTo("table");
        assertThat(table.get("location").asString()).isEqualTo(staff.locationRoot());
        assertThat(table.get("pointer").get("version").asLong()).isEqualTo(5);
        assertThat(table.get("pointer").get("metadata-location").asString()).isEqualTo(location(staff, 5));
        assertThat(table.get("watermark").get("version").asLong()).isEqualTo(2);
        assertThat(table.get("watermark").get("metadata-location").asString()).isEqualTo(location(staff, 2));
        assertThat(table.get("versions").valueStream().map(v -> v.get("version").asLong()).toList())
                .containsExactly(3L, 4L, 5L);
        assertThat(table.get("versions").get(0).get("metadata-location").asString()).isEqualTo(location(staff, 3));
        assertThat(table.get("versions").get(0).get("commit-id").asString())
                .isEqualTo(journals.get(staff.id()).get(2).commitId().toString());
        assertThat(table.get("oldest-unverified-at").asString()).isEqualTo(committed(staff, 3).toString());
        JsonNode view = item(work.get("objects"), roster);
        assertThat(view.get("kind").asString()).isEqualTo("view");
        assertThat(view.has("watermark")).isFalse();
        assertThat(view.get("versions").valueStream().map(v -> v.get("version").asLong()).toList())
                .as("checked whole, from the newest").containsExactly(1L);
        assertThat(view.get("oldest-unverified-at").asString()).isEqualTo(committed(roster, 1).toString());
        assertThat(work.has("next-page-token")).isFalse();
    }

    @Test
    void anObjectWithNoWatermarkListsItsNewestVersionsAndLagsFromItsFirst() {
        CatalogObject stream = object("stream", CatalogObject.Kind.TABLE, DrRoutes.MAX_VERSIONS + 30);

        JsonNode table = item(json(routes.work(null, null, http)).get("objects"), stream);

        assertThat(table.has("watermark")).isFalse();
        assertThat(table.get("versions").valueStream().map(v -> v.get("version").asLong()).toList())
                .as("the newest, up to the pointer's, oldest first").hasSize(DrRoutes.MAX_VERSIONS)
                .startsWith(31L).endsWith((long) DrRoutes.MAX_VERSIONS + 30);
        assertThat(table.get("versions").get(0).get("metadata-location").asString()).isEqualTo(location(stream, 31));
        assertThat(table.get("versions").get(0).get("committed-at").asString())
                .isEqualTo(committed(stream, 31).toString());
        assertThat(table.get("oldest-unverified-at").asString()).as("its first commit")
                .isEqualTo(committed(stream, 1).toString());
    }

    @Test
    void theWorkListIsPaged() {
        JsonNode first = json(routes.work(null, 1, http));
        JsonNode second = json(routes.work(first.get("next-page-token").asString(), 1, http));

        assertThat(List.of(first.get("objects").get(0).get("id").asString(),
                second.get("objects").get(0).get("id").asString()))
                .containsExactlyInAnyOrder(staff.id().toString(), roster.id().toString());
        assertThat(second.has("next-page-token")).isFalse();
        assertThatThrownBy(() -> routes.work("not a token", null, http)).isInstanceOf(CatalogError.class)
                .extracting(e -> ((CatalogError) e).status()).isEqualTo(400);
    }

    @Test
    void aReportRaisesTheWatermarkToAJournaledVersionOrThePointers() {
        assertThat(routes.record(staff.id().toString(), report(4, location(staff, 4)), http).getStatusCode().value())
                .isEqualTo(204);
        assertThat(watermarks.get(staff.id())).isEqualTo(new DrWatermark(4, location(staff, 4), NOW));

        assertThat(routes.record(staff.id().toString(), report(5, location(staff, 5)), http).getStatusCode().value())
                .isEqualTo(204);
        assertThat(routes.record(staff.id().toString(), report(5, location(staff, 5)), http).getStatusCode().value())
                .as("the same again").isEqualTo(204);
        assertThat(routes.record(roster.id().toString(), report(1, location(roster, 1)), http).getStatusCode()
                .value()).as("a view").isEqualTo(204);

        assertThat(watermarks.get(staff.id()).version()).isEqualTo(5);
        assertThat(watermarks.get(roster.id()).version()).isEqualTo(1);
        assertThat(denials()).isEmpty();
    }

    @Test
    void aReportIsRefusedUnlessItNamesAVersionAtOrBelowThePointerWithItsOwnLocation() {
        String id = staff.id().toString();
        assertRefused(() -> routes.record(id, report(6, location(staff, 6)), http), 409, "WatermarkAheadException");
        assertRefused(() -> routes.record(id, report(4, location(staff, 3)), http), 409, "WatermarkMismatchException");
        assertRefused(() -> routes.record(id, report(5, location(staff, 4)), http), 409, "WatermarkMismatchException");
        assertRefused(() -> routes.record(id, report(1, location(staff, 1)), http), 409, "WatermarkBehindException");
        assertRefused(() -> routes.record(id, "{\"metadata-location\":\"" + location(staff, 4) + "\"}", http), 400,
                "BadRequestException");
        assertRefused(() -> routes.record(id, report(0, location(staff, 4)), http), 400, "BadRequestException");
        assertRefused(() -> routes.record(id, "{\"version\":4}", http), 400, "BadRequestException");
        assertRefused(() -> routes.record("not-an-id", report(4, location(staff, 4)), http), 400,
                "BadRequestException");
        assertRefused(() -> routes.record(UUID.randomUUID().toString(), report(1, "s3://gate/x.json"), http), 404,
                "NoSuchTableException");
        objects.put(staff.id(), new CatalogObject(catalogId, staff.id(), hr.id(), staff.name(), staff.kind(),
                CatalogObject.State.DROPPED, Optional.of(NOW), Optional.of(NOW.plusSeconds(3600)),
                staff.locationRoot(), Optional.empty()));
        assertRefused(() -> routes.record(id, report(4, location(staff, 4)), http), 404, "NoSuchTableException");

        assertThat(watermarks.get(staff.id()).version()).isEqualTo(2);
    }

    @Test
    void theReadoutNamesEachObjectBehindWithItsLag() {
        caller = OLGA;

        JsonNode tables = json(routes.readout(null, null, http)).get("tables");

        assertThat(tables).hasSize(2);
        JsonNode table = item(tables, staff);
        assertThat(table.get("catalog").asString()).isEqualTo("gate");
        assertThat(table.get("namespace").valueStream().map(JsonNode::asString).toList()).containsExactly("hr");
        assertThat(table.get("name").asString()).isEqualTo("Staff");
        assertThat(table.get("kind").asString()).isEqualTo("table");
        assertThat(table.get("pointer-version").asLong()).isEqualTo(5);
        assertThat(table.get("watermark-version").asLong()).isEqualTo(2);
        assertThat(table.get("versions-behind").asLong()).isEqualTo(3);
        assertThat(table.get("oldest-unverified-at").asString()).isEqualTo(committed(staff, 3).toString());
        assertThat(table.get("lag-seconds").asLong()).isEqualTo(Duration.between(committed(staff, 3), NOW).toSeconds());
        JsonNode view = item(tables, roster);
        assertThat(view.get("watermark-version").isNull()).isTrue();
        assertThat(view.get("versions-behind").asLong()).isEqualTo(1);
        assertThat(view.get("lag-seconds").asLong()).isEqualTo(600);
    }

    // ---- the estate ----

    private CatalogObject object(String name, CatalogObject.Kind kind, int versions) {
        UUID id = UUID.randomUUID();
        CatalogObject object = CatalogObject.active(catalogId, id, hr.id(), new Name(Names.normalize(name), name),
                kind, "s3://gate/" + id);
        objects.put(id, object);
        List<JournalRecord> journal = new ArrayList<>();
        for (int v = 1; v <= versions; v++) {
            journal.add(new JournalRecord(UUID.randomUUID(), object.mainKey(),
                    NOW.minusSeconds(60L * (versions - v + (kind == CatalogObject.Kind.VIEW ? 10 : 1))),
                    new JournalEntry("ann", null, "spark", "[]", "[]"),
                    v == 1 ? Optional.empty() : Optional.of(location(object, v - 1)), location(object, v), v));
        }
        journals.put(id, journal);
        return object;
    }

    private static String location(CatalogObject object, long version) {
        return object.locationRoot() + "/metadata/" + String.format("%05d", version) + "-"
                + new UUID(object.id().getMostSignificantBits(), version) + ".metadata.json";
    }

    private Instant committed(CatalogObject object, long version) {
        return journals.get(object.id()).get((int) version - 1).committedAt();
    }

    private Optional<Pointer> pointer(UUID id) {
        List<JournalRecord> journal = journals.getOrDefault(id, List.of());
        return journal.isEmpty() ? Optional.empty() : Optional.of(new Pointer(journal.getLast().newLocation(),
                journal.getLast().newVersion(), "sha", new byte[0]));
    }

    /** What every store answers to a report (DrSuite): it only rises, never passes the pointer. */
    private WriteResult record(UUID id, DrWatermark watermark) {
        CatalogObject object = objects.get(id);
        Optional<Pointer> pointer = pointer(id);
        if (object == null || object.state() != CatalogObject.State.ACTIVE || pointer.isEmpty()) {
            return new WriteResult.Missing(WriteResult.Subject.OBJECT);
        }
        if (watermark.version() > pointer.get().version()) {
            return new WriteResult.Refused(WriteResult.Reason.WATERMARK_AHEAD);
        }
        DrWatermark kept = watermarks.get(id);
        if (kept != null && kept.version() >= watermark.version()) {
            return kept.version() == watermark.version() ? WriteResult.UNCHANGED
                    : new WriteResult.Refused(WriteResult.Reason.WATERMARK_BEHIND);
        }
        watermarks.put(id, watermark);
        return WriteResult.APPLIED;
    }

    private Page<DrState> behind(PageRequest page) {
        UUID after = page.token().map(token -> UUID.fromString(PageToken.decode(token, 1).getFirst())).orElse(null);
        List<DrState> states = objects.values().stream()
                .filter(object -> object.state() == CatalogObject.State.ACTIVE)
                .filter(object -> after == null || object.id().compareTo(after) > 0)
                .sorted(Comparator.comparing(CatalogObject::id))
                .flatMap(object -> pointer(object.id()).map(pointer -> new DrState(object, pointer.version(),
                        pointer.metadataLocation(), Optional.ofNullable(watermarks.get(object.id())))).stream())
                .filter(state -> state.behind() > 0)
                .toList();
        List<DrState> items = states.stream().limit(page.size()).toList();
        return new Page<>(items, states.size() > page.size()
                ? Optional.of(PageToken.encode(items.getLast().object().id().toString())) : Optional.empty());
    }

    private Page<JournalRecord> journal(PointerKey key, PageRequest page) {
        long after = page.token().map(token -> Long.parseLong(PageToken.decode(token, 1).getFirst())).orElse(0L);
        List<JournalRecord> records = journals.getOrDefault(key.objectId(), List.of()).stream()
                .filter(record -> record.newVersion() > after).toList();
        List<JournalRecord> items = records.stream().limit(page.size()).toList();
        return new Page<>(items, records.size() > page.size()
                ? Optional.of(PageToken.encode(Long.toString(items.getLast().newVersion()))) : Optional.empty());
    }

    private CatalogStore store() {
        return (CatalogStore) Proxy.newProxyInstance(CatalogStore.class.getClassLoader(),
                new Class<?>[] {CatalogStore.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "readObject" -> Optional.ofNullable(objects.get((UUID) args[0]));
                    case "readNamespace" -> Optional.of(hr).filter(ns -> ns.id().equals(args[0]));
                    case "readPointer" -> pointer(((PointerKey) args[0]).objectId());
                    case "readJournal" -> journal((PointerKey) args[0], (PageRequest) args[3]);
                    case "recordDrWatermark" -> record((UUID) args[0], (DrWatermark) args[1]);
                    case "readDrWatermark" -> Optional.ofNullable(watermarks.get((UUID) args[0]));
                    case "listDrBehind" -> behind((PageRequest) args[0]);
                    case "toString" -> "the test's store";
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    // ---- helpers ----

    private static String report(long version, String location) {
        return "{\"version\":" + version + ",\"metadata-location\":\"" + location + "\"}";
    }

    private static JsonNode json(ResponseEntity<String> response) {
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        return Management.JSON.readTree(response.getBody());
    }

    private static JsonNode item(JsonNode items, CatalogObject object) {
        return items.valueStream().filter(item -> item.get("id").asString().equals(object.id().toString()))
                .findFirst().orElseThrow(() -> new AssertionError(object.name().display() + " is not listed"));
    }

    private static void assertRefused(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, int status,
                                      String type) {
        assertThatThrownBy(call).isInstanceOfSatisfying(CatalogError.class, error -> {
            assertThat(error.status()).isEqualTo(status);
            assertThat(error.type()).isEqualTo(type);
        });
    }

    private List<Denial> denials() {
        return audit.stream().map(CatalogEvent::data).filter(Denial.class::isInstance).map(Denial.class::cast)
                .toList();
    }

    private static Caller person(String id, String... groups) {
        return new Caller(new Principal(id, Set.of(groups)), "console", false, Optional.empty());
    }
}
