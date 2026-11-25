package nuna.catalog.server.governance;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
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
import java.util.concurrent.Callable;
import java.util.function.Function;
import nuna.audittables.EventsReader;
import nuna.catalog.audit.Actor;
import nuna.catalog.audit.AuditRead;
import nuna.catalog.audit.AuditRow;
import nuna.catalog.audit.AuditRows;
import nuna.catalog.audit.CatalogEvent;
import nuna.catalog.audit.Denial;
import nuna.catalog.audit.ObjectRead;
import nuna.catalog.audit.PointerState;
import nuna.catalog.audit.archive.ArchiveCheck;
import nuna.catalog.audit.archive.ChainHead;
import nuna.catalog.audit.archive.ObjectLockArchive;
import nuna.catalog.audit.archive.ObjectLockArchive.Placement;
import nuna.catalog.audit.publish.EventPublisher;
import nuna.catalog.core.CatalogError;
import nuna.catalog.core.principal.Caller;
import nuna.catalog.core.principal.Principal;
import nuna.catalog.security.Authorizer;
import nuna.catalog.security.Privilege;
import nuna.catalog.security.Separation;
import nuna.catalog.server.rest.AccessDenied;
import nuna.catalog.server.rest.AuditPublisher;
import nuna.catalog.server.rest.RestSettings;
import nuna.catalog.server.security.AuditCatalogSettings;
import nuna.catalog.server.security.Denials;
import nuna.catalog.server.security.DenialsForTests;
import nuna.catalog.server.security.Holdings;
import nuna.catalog.server.telemetry.RequestIds;
import nuna.catalog.store.CatalogRecord;
import nuna.catalog.store.CatalogStore;
import nuna.catalog.store.Grant;
import nuna.catalog.store.GrantRecord;
import nuna.catalog.store.Securable;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * The catalog-wide audit feed's routes (D45; backlog 125): who may read them, settled as for the audit tables (D23,
 * amended 2026-11-22: {@code select} on the audit catalog, "Audit readers only"), with a test for each refusal; what
 * the feed, the day's counts, the archive check's status and a record's place in its chain answer; the 400s and
 * 503s; and the per-caller audit read limit they share with {@code GET .../audit}. The routes, guard and authorizer
 * are the real ones over a store double; the audit table and the archive are doubles, so this needs only Java.
 * {@code EventsReaderTest} reads a real table and {@code ArchiveCheckTest} a real chain.
 */
class AuditFeedRoutesTest {

    private static final int LIMIT = 3;
    private static final Instant NOW = Instant.parse("2026-11-22T12:00:00Z");
    private static final String TOPIC = "nuna.catalog.audit";
    /** Select on the audit catalog: the audit readers. */
    private static final Caller AUDREY = person("audrey", "auditors");
    /** Select on the catalog the feed is about, not on the audit catalog. */
    private static final Caller TOM = person("tom", "gate-readers");
    /** A grant admin: manage-grants everywhere, which gives no data (D18). */
    private static final Caller GINA = person("gina", "grant-admins");
    /** A platform operator, who holds nothing on the audit catalog. */
    private static final Caller OLIVE = person("olive", "platform-ops");

    private final CatalogRecord gate = new CatalogRecord(UUID.randomUUID(), "gate", "gate");
    private final CatalogRecord audit = new CatalogRecord(UUID.randomUUID(), "nuna_audit", "nuna_audit");
    private final List<GrantRecord> grants = List.of(
            grant(audit, "auditors", Privilege.SELECT),
            grant(gate, "gate-readers", Privilege.SELECT));

    private final List<CatalogEvent> audited = new ArrayList<>();
    private final MockHttpServletRequest http = new MockHttpServletRequest();
    private final AuditSettings auditSettings = new AuditSettings("kafka:9092", TOPIC, "nuna.catalog.changes",
            Duration.ofSeconds(5), Duration.ofSeconds(2), Duration.ofSeconds(10), Duration.ofMillis(250),
            Duration.ofSeconds(30), 500, new AuditSettings.Archive("audit-archive", "store", "audit",
                    Duration.ofDays(2557), 10_000, Duration.ofMinutes(5), "nuna-audit-archive"));
    private final Table table = new Table();
    private final Archive archive = new Archive();
    private AuditCatalogSettings tables = new AuditCatalogSettings("nuna_audit", "auditors", "audit-writers");
    private Caller caller = AUDREY;
    private final AuditReadRoutes routes;

    AuditFeedRoutesTest() {
        http.setAttribute(RequestIds.class.getName(), "request-1");
        CatalogStore store = store();
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        RestSettings rest = new RestSettings(100, 1000, 1_048_576, 1000, 268_435_456, Duration.ofDays(30),
                Duration.ofMinutes(5), "the identity provider", URI.create("urn:nuna:test"), 100, null);
        AuditPublisher publisher = audited::add;
        Separation separation = new Separation(Set.of("grant-admins"), Set.of("platform-ops"));
        Authorizer authorizer = new Authorizer(store, store, store, separation, false, Set.of());
        Denials denials = DenialsForTests.denials(publisher, rest, clock);
        Holdings holdings = new Holdings(authorizer, store, rest, separation, denials);
        Management management = new Management(store, request -> caller, rest, holdings);
        ManagementGuard guard = new ManagementGuard(authorizer, separation, null, denials, holdings);
        GovernanceSettings settings = new GovernanceSettings(10, LIMIT, Map.of(), Duration.ofDays(7),
                Duration.ofHours(1), Map.of());
        AuditTrail trail = new AuditTrail(Optional.<EventPublisher>of(audited::add), URI.create("urn:nuna:test"),
                clock, Duration.ofSeconds(1));
        AuditFeed feed = new Feed(store, clock);
        routes = new AuditReadRoutes(management, guard, null, trail, settings, feed, archive, clock);
    }

    // ---- who may read ----

    @Test
    void anAuditReaderReadsTheFeedAndTheReadIsAudited() {
        table.rows = List.of(row(0, 41, "request-9"), row(1, 7, "request-8"));
        table.next = Optional.of(new EventsReader.Cursor(1_000, 1, 7));

        ResponseEntity<String> answer = feed(Map.of());

        assertThat(answer.getStatusCode().value()).isEqualTo(200);
        String body = answer.getBody();
        assertThat(JsonPath.<String>read(body, "$.catalog")).isEqualTo("gate");
        assertThat(JsonPath.<List<String>>read(body, "$.records[*].request-id")).containsExactly("request-9",
                "request-8");
        assertThat(JsonPath.<Integer>read(body, "$.records[0].partition")).isZero();
        assertThat(JsonPath.<Integer>read(body, "$.records[0].offset")).isEqualTo(41);
        assertThat(JsonPath.<String>read(body, "$.records[0].object.kind")).isEqualTo("TABLE");
        assertThat(JsonPath.<List<String>>read(body, "$.records[0].object.path")).containsExactly("hr", "staff");
        assertThat(JsonPath.<String>read(body, "$.records[0].event.type")).isEqualTo("nuna.catalog.read");
        assertThat(JsonPath.<String>read(body, "$.records[0].event.data.actor.requestId")).isEqualTo("request-9");
        assertThat(JsonPath.<String>read(body, "$.next-page-token")).isEqualTo(table.next.get().token());
        assertThat(JsonPath.<String>read(body, "$.table.name")).isEqualTo("nuna_audit.audit.events");
        assertThat(JsonPath.<String>read(body, "$.table.committed-at")).as("how far behind the feed is")
                .isEqualTo(NOW.minusSeconds(50).toString());
        assertThat(table.asked.getFirst().catalog()).isEqualTo("gate");
        assertThat(audited(AuditRead.class)).singleElement().satisfies(read -> {
            assertThat(read.target().id()).isEqualTo(gate.id());
            assertThat(read.returned()).isEqualTo(2);
            assertThat(read.actor().principal()).isEqualTo("audrey");
        });
    }

    @Test
    void selectOnTheCatalogTheFeedIsAboutIsNotEnough() {
        caller = TOM;
        assertRefusedEverywhere("tom");
    }

    @Test
    void aGrantAdminIsRefused() {
        caller = GINA;
        assertRefusedEverywhere("gina");
    }

    @Test
    void aPlatformOperatorIsRefused() {
        caller = OLIVE;
        assertRefusedEverywhere("olive");
    }

    @Test
    void withoutAnAuditCatalogNoOneMayReadAndEveryRouteIs503() {
        tables = new AuditCatalogSettings(null, null, null);

        assertThat(answer(() -> feed(Map.of()))).isEqualTo(503);
        assertThat(answer(() -> routes.summary("gate", http))).isEqualTo(503);
        assertThat(answer(() -> routes.archive(http))).isEqualTo(503);
        assertThat(answer(() -> routes.chain("0", "1", UUID.randomUUID().toString(), http))).isEqualTo(503);
        assertThat(table.asked).isEmpty();
    }

    // ---- what is asked ----

    @Test
    void theFiltersReachTheTable() {
        feed(Map.of("type", "nuna.catalog.read, nuna.catalog.denial", "principal", "pat", "path", "HR\u001fstaff",
                "from", "2026-11-21T00:00:00Z", "to", "2026-11-22T00:00:00Z", "limit", "20",
                "pageToken", new EventsReader.Cursor(5, 0, 3).token()));

        EventsReader.Query query = table.asked.getFirst();
        assertThat(query.types()).containsExactlyInAnyOrder("nuna.catalog.read", "nuna.catalog.denial");
        assertThat(query.principal()).isEqualTo("pat");
        assertThat(query.path()).containsExactly("hr", "staff");
        assertThat(query.from()).isEqualTo(Instant.parse("2026-11-21T00:00:00Z"));
        assertThat(query.to()).isEqualTo(Instant.parse("2026-11-22T00:00:00Z"));
        assertThat(query.limit()).isEqualTo(20);
        assertThat(query.after()).contains(new EventsReader.Cursor(5, 0, 3));
    }

    @Test
    void aPageThatStoppedShortSaysHowFarItSearched() {
        table.next = Optional.of(new EventsReader.Cursor(9, Integer.MAX_VALUE, Long.MAX_VALUE));
        table.searchedTo = Optional.of(NOW.minus(Duration.ofDays(3)));

        String body = feed(Map.of()).getBody();

        assertThat(JsonPath.<List<?>>read(body, "$.records")).isEmpty();
        assertThat(JsonPath.<String>read(body, "$.searched-to")).isEqualTo(NOW.minus(Duration.ofDays(3)).toString());
        assertThat(JsonPath.<String>read(body, "$.next-page-token")).isNotEmpty();
    }

    @Test
    void anUnknownCatalogIs404() {
        assertThat(answer(() -> routes.feed("nowhere", null, null, null, null, null, null, null, http)))
                .isEqualTo(404);
        assertThat(answer(() -> routes.summary("nowhere", http))).isEqualTo(404);
        assertThat(table.asked).isEmpty();
    }

    @Test
    void aBadlyAskedReadIs400AndReadsNothing() {
        for (Map<String, String> bad : List.of(Map.of("type", "nuna.catalog.nothing"), Map.of("from", "yesterday"),
                Map.of("from", "2026-11-22T00:00:00Z", "to", "2026-11-22T00:00:00Z"), Map.of("limit", "0"),
                Map.of("limit", "201"), Map.of("pageToken", "not-a-token"), Map.of("path", "hr\u001f"))) {
            assertThat(answer(() -> feed(bad))).as(bad.toString()).isEqualTo(400);
        }
        archive.configured = true;
        assertThat(answer(() -> routes.chain("0", "1", null, http))).isEqualTo(400);
        assertThat(answer(() -> routes.chain("0", "1", "not-a-uuid", http))).isEqualTo(400);
        assertThat(answer(() -> routes.chain("-1", "1", UUID.randomUUID().toString(), http))).isEqualTo(400);
        assertThat(answer(() -> routes.chain("0", "x", UUID.randomUUID().toString(), http))).isEqualTo(400);
        assertThat(table.asked).isEmpty();
        assertThat(archive.placed).isZero();
        assertThat(audited(AuditRead.class)).isEmpty();
        readTheLimit();
    }

    @Test
    void anAuditTableNotYetWrittenIs503() {
        table.notReady = true;

        assertThat(answer(() -> feed(Map.of()))).isEqualTo(503);
        assertThat(answer(() -> routes.summary("gate", http))).isEqualTo(503);
        assertThat(audited(AuditRead.class)).isEmpty();
    }

    // ---- the day's counts ----

    @Test
    void theDaysCountsAreTheCatalogsAndTheDeployments() {
        String body = routes.summary("gate", http).getBody();

        assertThat(JsonPath.<String>read(body, "$.from")).isEqualTo(NOW.minus(Duration.ofDays(1)).toString());
        assertThat(JsonPath.<Integer>read(body, "$.by-type['nuna.catalog.read']")).isEqualTo(12);
        assertThat(JsonPath.<Integer>read(body, "$.deployment-by-type['nuna.catalog.read']")).isEqualTo(30);
        assertThat(JsonPath.<String>read(body, "$.busiest[0].writer")).isEqualTo("etl");
        assertThat(JsonPath.<List<String>>read(body, "$.busiest[0].path")).containsExactly("hr", "staff");
        assertThat(JsonPath.<Integer>read(body, "$.busiest[0].reads")).isEqualTo(9);
        assertThat(audited(AuditRead.class)).singleElement()
                .satisfies(read -> assertThat(read.target().id()).isEqualTo(gate.id()));
    }

    // ---- the archive ----

    @Test
    void withoutAnArchiveEveryRowSaysSoAndTheStatusSaysNotConfigured() {
        table.rows = List.of(row(0, 41, "request-9"));

        assertThat(JsonPath.<String>read(feed(Map.of()).getBody(), "$.records[0].chain.status"))
                .isEqualTo("no-archive");
        assertThat(JsonPath.<Boolean>read(routes.archive(http).getBody(), "$.configured")).isFalse();
        assertThat(answer(() -> routes.chain("0", "41", UUID.randomUUID().toString(), http))).isEqualTo(503);
    }

    @Test
    void aRowIsVerifiedWhenTheCheckHasVerifiedItsChainPastIt() {
        archive.configured = true;
        archive.verified = Map.of(0, checked(40));
        table.rows = List.of(row(0, 41, "a"), row(0, 39, "b"), row(1, 2, "c"));

        String body = feed(Map.of()).getBody();

        assertThat(JsonPath.<List<String>>read(body, "$.records[*].chain.status"))
                .containsExactly("unverified", "verified", "unverified");
        assertThat(JsonPath.<String>read(body, "$.records[1].chain.checked-at")).isEqualTo(NOW.toString());
    }

    @Test
    void theArchiveStatusGivesTheLastRun() {
        archive.configured = true;
        archive.verified = Map.of(0, checked(40));
        archive.last = Optional.of(new ArchiveCheck.Result(NOW.minusSeconds(5), NOW, List.of(
                new ArchiveCheck.ChainResult("nuna.catalog.audit-0", 0, Optional.of(checked(40)), 12, Optional.empty(),
                        Optional.empty()),
                new ArchiveCheck.ChainResult("nuna.catalog.audit-1", 1, Optional.empty(), 0,
                        Optional.of("after record 0: HASH_MISMATCH"), Optional.empty()))));

        String body = routes.archive(http).getBody();

        assertThat(JsonPath.<String>read(body, "$.bucket")).isEqualTo("audit-archive");
        assertThat(JsonPath.<Integer>read(body, "$.verified[0].next-offset")).isEqualTo(40);
        assertThat(JsonPath.<Boolean>read(body, "$.check.intact")).isFalse();
        assertThat(JsonPath.<String>read(body, "$.check.ended-at")).isEqualTo(NOW.toString());
        assertThat(JsonPath.<String>read(body, "$.check.chains[1].broken")).contains("HASH_MISMATCH");
        assertThat(JsonPath.<Integer>read(body, "$.check.chains[0].records-verified")).isEqualTo(12);
    }

    @Test
    void aRecordsPlaceInItsChainGivesItsNeighboursAndWhetherItsLinkHolds() {
        archive.configured = true;
        archive.verified = Map.of(0, checked(40));
        String id = UUID.randomUUID().toString();

        String body = routes.chain("0", "39", id, http).getBody();

        assertThat(JsonPath.<Boolean>read(body, "$.archived")).isTrue();
        assertThat(JsonPath.<String>read(body, "$.chain")).isEqualTo("nuna.catalog.audit-0");
        assertThat(JsonPath.<Integer>read(body, "$.sequence")).isEqualTo(40);
        assertThat(JsonPath.<String>read(body, "$.previous-hash")).isEqualTo("a".repeat(64));
        assertThat(JsonPath.<String>read(body, "$.hash")).isEqualTo("b".repeat(64));
        assertThat(JsonPath.<String>read(body, "$.next-hash")).isEqualTo("c".repeat(64));
        assertThat(JsonPath.<Boolean>read(body, "$.link-holds")).isTrue();
        assertThat(JsonPath.<Boolean>read(body, "$.verified")).isTrue();
        assertThat(JsonPath.<Integer>read(body, "$.segment.first-sequence")).isEqualTo(31);
        assertThat(archive.asked).isEqualTo("0:39:" + id);
        assertThat(audited(AuditRead.class)).singleElement()
                .satisfies(read -> assertThat(read.target().id()).isEqualTo(audit.id()));

        archive.placement = Optional.empty();
        assertThat(JsonPath.<Boolean>read(routes.chain("0", "99", id, http).getBody(), "$.archived")).isFalse();
    }

    // ---- the limit ----

    @Test
    void theFeedTheChainAndAFreshSummaryShareTheAuditReadLimit() {
        archive.configured = true;
        feed(Map.of());
        routes.chain("0", "1", UUID.randomUUID().toString(), http);
        routes.summary("gate", http);

        assertThat(feed(Map.of()).getStatusCode().value()).isEqualTo(429);
        assertThat(routes.chain("0", "1", UUID.randomUUID().toString(), http).getStatusCode().value())
                .isEqualTo(429);
        assertThat(routes.summary("gate", http).getStatusCode().value()).as("kept from the last minute: no read")
                .isEqualTo(200);
        assertThat(table.counts).isEqualTo(1);
        table.counted = Optional.empty();
        assertThat(routes.summary("gate", http).getStatusCode().value()).as("no longer kept").isEqualTo(429);
        assertThat(table.asked).hasSize(1);
        assertThat(archive.placed).isEqualTo(1);
    }

    // ---- helpers ----

    private void assertRefusedEverywhere(String who) {
        archive.configured = true;
        assertThat(answer(() -> feed(Map.of()))).isEqualTo(403);
        assertThat(answer(() -> routes.summary("gate", http))).isEqualTo(403);
        assertThat(answer(() -> routes.archive(http))).isEqualTo(403);
        assertThat(answer(() -> routes.chain("0", "1", UUID.randomUUID().toString(), http))).isEqualTo(403);
        assertThat(answer(() -> routes.feed("nowhere", null, null, null, null, null, null, null, http)))
                .as("refused before the catalog is looked up").isEqualTo(403);
        assertThat(audited(Denial.class)).hasSize(5).allSatisfy(denial -> {
            assertThat(denial.securable().id()).as("refused on the audit catalog").isEqualTo(audit.id());
            assertThat(denial.privilege()).isEqualTo("select");
            assertThat(denial.actor().principal()).isEqualTo(who);
        });
        assertThat(table.asked).isEmpty();
        assertThat(table.counts).isZero();
        assertThat(archive.placed).isZero();
        assertThat(audited(AuditRead.class)).isEmpty();
        caller = AUDREY;
        readTheLimit();
    }

    /** A refused or badly asked read took no slot: the caller still has the whole minute's reads. */
    private void readTheLimit() {
        caller = AUDREY;
        for (int i = 0; i < LIMIT; i++) {
            assertThat(feed(Map.of()).getStatusCode().value()).isEqualTo(200);
        }
        assertThat(feed(Map.of()).getStatusCode().value()).isEqualTo(429);
    }

    private ResponseEntity<String> feed(Map<String, String> query) {
        Function<String, String> q = query::get;
        return routes.feed("gate", q.apply("type"), q.apply("principal"), q.apply("path"), q.apply("from"),
                q.apply("to"), q.apply("limit"), q.apply("pageToken"), http);
    }

    private static int answer(Callable<? extends ResponseEntity<?>> call) {
        try {
            return call.call().getStatusCode().value();
        } catch (CatalogError e) {
            return e.status();
        } catch (AccessDenied e) {
            return 403;
        } catch (Exception e) {
            throw new AssertionError("the route failed unexpectedly", e);
        }
    }

    private <T> List<T> audited(Class<T> type) {
        return audited.stream().map(CatalogEvent::data).filter(type::isInstance).map(type::cast).toList();
    }

    /** A read of gate's table hr.staff, as the audit table holds it. */
    private AuditRow row(int partition, long offset, String requestId) {
        nuna.catalog.audit.Securable staff = new nuna.catalog.audit.Securable(
                nuna.catalog.audit.Securable.Kind.TABLE, UUID.randomUUID(), "gate", List.of("hr", "staff"));
        CatalogEvent event = CatalogEvent.create(URI.create("urn:nuna:test"), NOW.minusSeconds(offset),
                new ObjectRead(staff, "main", new PointerState("s3://gate/staff/metadata/1.json", 1, "ab".repeat(32)),
                        new Actor("pat", null, "spark", requestId)));
        return AuditRows.of(event, partition, offset);
    }

    private static ArchiveCheck.Checked checked(long nextOffset) {
        return new ArchiveCheck.Checked(new ChainHead(nextOffset, "d".repeat(64)), nextOffset,
                "audit/nuna.catalog.audit-0/x.log", NOW);
    }

    private static GrantRecord grant(CatalogRecord catalog, String group, Privilege privilege) {
        return new GrantRecord(new Grant(catalog.id(), new Securable(Securable.Kind.CATALOG, catalog.id()), group,
                privilege.wireName()), "test", Instant.EPOCH);
    }

    private static Caller person(String id, String... groups) {
        return new Caller(new Principal(id, Set.of(groups)), "console", false, Optional.empty());
    }

    /** The audit table: what each page and count was asked, and what it answers. */
    private static final class Table {

        final List<EventsReader.Query> asked = new ArrayList<>();
        List<AuditRow> rows = List.of();
        Optional<EventsReader.Cursor> next = Optional.empty();
        Optional<Instant> searchedTo = Optional.empty();
        boolean notReady;
        int counts;
        Optional<AuditFeed.Counted> counted = Optional.empty();
    }

    private final class Feed extends AuditFeed {

        private final AuditFeed.Snapshot snapshot = new AuditFeed.Snapshot("nuna_audit.audit.events", Optional.of(7L),
                Optional.of(NOW.minusSeconds(50)));

        Feed(CatalogStore store, Clock clock) {
            super(null, "audit", store, null, null, clock);
        }

        @Override
        Optional<CatalogRecord> auditCatalog() {
            return tables.catalog() == null ? Optional.empty() : super.catalog(tables.catalog());
        }

        @Override
        Read page(EventsReader.Query query) {
            if (table.notReady) {
                throw new NotReady("the audit table does not exist yet");
            }
            table.asked.add(query);
            return new Read(new EventsReader.Page(table.rows, table.next, table.searchedTo), snapshot);
        }

        @Override
        synchronized Optional<Counted> kept() {
            return table.counted;
        }

        @Override
        Counted count() {
            if (table.notReady) {
                throw new NotReady("the audit table does not exist yet");
            }
            table.counts++;
            EventsReader.Busy staff = new EventsReader.Busy("TABLE", UUID.randomUUID().toString(),
                    List.of("hr", "staff"), 14, 9, "etl");
            Counted counted = new Counted(new EventsReader.Summary(NOW.minus(Duration.ofDays(1)), NOW,
                    Map.of("nuna.catalog.read", 30L), Map.of("gate", new EventsReader.CatalogCounts(
                            Map.of("nuna.catalog.read", 12L), List.of(staff)))), NOW, snapshot);
            table.counted = Optional.of(counted);
            return counted;
        }
    }

    /** The archive check: configured or not, how far it verified, and the places it gives. */
    private final class Archive extends AuditArchiveCheck {

        boolean configured;
        Map<Integer, ArchiveCheck.Checked> verified = Map.of();
        Optional<ArchiveCheck.Result> last = Optional.empty();
        Optional<Placement> placement = Optional.of(new Placement(new ObjectLockArchive.Segment(
                "audit/nuna.catalog.audit-0/0000000000000000031.log", "v1", new ChainHead(30, "e".repeat(64)),
                new ChainHead(45, "f".repeat(64)), 45), 40, "b".repeat(64), "a".repeat(64),
                Optional.of("c".repeat(64)), true));
        int placed;
        String asked;

        Archive() {
            super(auditSettings, Optional.empty(), Clock.fixed(NOW, ZoneOffset.UTC));
        }

        @Override
        boolean configured() {
            return configured;
        }

        @Override
        Map<Integer, ArchiveCheck.Checked> verified() {
            return verified;
        }

        @Override
        Optional<ArchiveCheck.Result> last() {
            return last;
        }

        @Override
        Optional<Placement> place(int partition, long offset, String eventId) {
            placed++;
            asked = partition + ":" + offset + ":" + eventId;
            return placement;
        }
    }

    /** Two catalogs, gate and the audit catalog, and the grants above; any other call fails the test. */
    @SuppressWarnings("unchecked")
    private CatalogStore store() {
        return (CatalogStore) Proxy.newProxyInstance(CatalogStore.class.getClassLoader(),
                new Class<?>[] {CatalogStore.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "findCatalog" -> Optional.of(gate).filter(c -> c.normalizedName().equals(args[0]))
                            .or(() -> Optional.of(audit).filter(c -> c.normalizedName().equals(args[0])));
                    case "grantsFor" -> grants.stream()
                            .filter(g -> ((Set<Securable>) args[0]).contains(g.grant().securable())
                                    && ((Set<String>) args[1]).contains(g.grant().group()))
                            .toList();
                    case "attributesOf", "holdsOn" -> List.of();
                    case "toString" -> "the test's store";
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }
}
