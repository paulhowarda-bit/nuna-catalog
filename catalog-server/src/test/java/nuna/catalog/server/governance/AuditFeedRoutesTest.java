package nuna.catalog.server.governance;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
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
import nuna.catalog.audit.LogStamp;
import nuna.catalog.audit.ObjectRead;
import nuna.catalog.audit.PointerState;
import nuna.catalog.audit.archive.ArchiveCheck;
import nuna.catalog.audit.archive.ChainHead;
import nuna.catalog.audit.archive.ObjectLockArchive;
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
 * the feed, the day's counts and the archive check's status answer; the 400s and
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
            Duration.ofSeconds(30), 500, new AuditSettings.Archive("audit-archive", "store", "audit"));
    private final Table table = new Table();
    private final Archive archive = new Archive();
    private AuditCatalogSettings tables = new AuditCatalogSettings("nuna_audit", "auditors", "audit-writers");
    private Caller caller = AUDREY;
    private final AuditTrail trail;
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
        trail = new AuditTrail(Optional.<EventPublisher>of(stamped -> audited.add(stamped.event())), URI.create("urn:nuna:test"),
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
        assertThat(answer(() -> chain(RUN_GAP, "6", EVENT))).isEqualTo(503);
        assertThat(table.asked).isEmpty();
        assertThat(archive.asked).isEmpty();
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
        assertThat(query.branch()).isNull();
        assertThat(query.subject()).isNull();
    }

    @Test
    void theRefAndSubjectFiltersReachTheTableWithinTheCatalog() {
        // Backlog 169: the records through a ref, and the records about an object or a ref.
        String orders = "7f0c2a52-9d0e-4a8b-9c61-2b1e6f0d4a11";
        table.rows = List.of(row(0, 41, "request-9"));

        String body = feed(Map.of("branch", "Q3-Close", "subject", orders, "type", "nuna.catalog.ref")).getBody();

        EventsReader.Query query = table.asked.getFirst();
        assertThat(query.catalog()).as("only the catalog asked about").isEqualTo("gate");
        assertThat(query.branch()).as("compared as names are").isEqualTo("q3-close");
        assertThat(query.subject()).isEqualTo(orders);
        assertThat(query.types()).containsExactly("nuna.catalog.ref");
        assertThat(JsonPath.<String>read(body, "$.records[0].branch")).as("the ref the record went through")
                .isEqualTo("main");
        assertThat(audited(AuditRead.class)).singleElement().satisfies(read ->
                assertThat(read.target().id()).isEqualTo(gate.id()));
    }

    @Test
    void theRefAndSubjectFiltersAreRefusedToWhoeverMayNotReadTheFeed() {
        // Backlog 169: who may read which records does not change with the filters.
        for (Caller refused : List.of(TOM, GINA, OLIVE)) {
            caller = refused;
            assertThat(answer(() -> feed(Map.of("branch", "q3", "subject", EVENT)))).as(refused.subject().id())
                    .isEqualTo(403);
        }
        assertThat(audited(Denial.class)).hasSize(3).allSatisfy(denial ->
                assertThat(denial.securable().id()).isEqualTo(audit.id()));
        assertThat(table.asked).isEmpty();
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
        assertThat(answer(() -> routes.feed("nowhere", null, null, null, null, null, null, null, null, null, http)))
                .isEqualTo(404);
        assertThat(answer(() -> routes.summary("nowhere", http))).isEqualTo(404);
        assertThat(table.asked).isEmpty();
    }

    @Test
    void aBadlyAskedReadIs400AndReadsNothing() {
        for (Map<String, String> bad : List.of(Map.of("type", "nuna.catalog.nothing"), Map.of("from", "yesterday"),
                Map.of("from", "2026-11-22T00:00:00Z", "to", "2026-11-22T00:00:00Z"), Map.of("limit", "0"),
                Map.of("limit", "201"), Map.of("pageToken", "not-a-token"), Map.of("path", "hr\u001f"),
                Map.of("branch", "@q3"), Map.of("branch", " "), Map.of("subject", "orders"),
                Map.of("subject", EVENT + "0"))) {
            assertThat(answer(() -> feed(bad))).as(bad.toString()).isEqualTo(400);
        }
        assertThat(table.asked).isEmpty();
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
    }

    @Test
    void aRowFromTheLogIsVerifiedWhenEveryNumberOfItsRunUpToItIsAndOneWithoutARunCannotBePlaced() {
        // Backlog 272: the row's run and number place it in its run's chain.
        archive.configured = true;
        archive.last = Optional.of(result(
                new ArchiveCheck.ChainResult(RUN_DONE, verified(12, true), 0, List.of(), false, List.of(),
                        Optional.empty()),
                new ArchiveCheck.ChainResult(RUN_GAP, verified(7, false), 7, List.of(new ArchiveCheck.Range(4, 5)),
                        false, List.of(), Optional.empty()),
                new ArchiveCheck.ChainResult(RUN_BROKEN, verified(3, false), 0, List.of(), false, List.of(),
                        Optional.empty())));
        table.rows = List.of(logged(RUN_DONE, 12), logged(RUN_GAP, 3), logged(RUN_GAP, 4), logged(RUN_GAP, 7),
                logged(RUN_BROKEN, 5), logged("d0000000-0000-4000-8000-000000000004", 1), row(0, 41, "a"),
                row(-1, -1, "b"));

        String body = feed(Map.of()).getBody();

        assertThat(JsonPath.<List<String>>read(body, "$.records[*].chain.status")).containsExactly("verified",
                "verified", "unverified", "unverified", "unverified", "unverified", "no-place", "no-place");
        assertThat(JsonPath.<List<String>>read(body, "$.records[*].log-run")).startsWith(RUN_DONE, RUN_GAP);
        assertThat(JsonPath.<List<Integer>>read(body, "$.records[*].log-seq")).startsWith(12, 3, 4, 7);
        assertThat(JsonPath.<String>read(body, "$.records[0].chain.checked-at")).isEqualTo(NOW.toString());
        assertThat(JsonPath.<Map<String, Integer>>read(body, "$.records[2].chain.missing"))
                .as("its own number is missing from the archive").containsExactly(Map.entry("from", 4),
                        Map.entry("to", 5));
        assertThat(JsonPath.<Map<String, Integer>>read(body, "$.records[3].chain.missing"))
                .as("an earlier number is: it stays unverified until that arrives").containsExactly(
                        Map.entry("from", 4), Map.entry("to", 5));
        assertThat(JsonPath.<Map<String, Object>>read(body, "$.records[1].chain"))
                .as("verified: nothing below is missing").doesNotContainKey("missing");
        for (int i : new int[] {4, 5}) {
            assertThat(JsonPath.<Map<String, Object>>read(body, "$.records[" + i + "].chain"))
                    .as("no gap reported in its run").doesNotContainKey("missing");
        }
        assertThat(JsonPath.<String>read(body, "$.records[5].chain.checked-at")).as("a run no check has reached")
                .isNull();
        assertThat(JsonPath.<Object>read(body, "$.records[6].log-run")).isNull();
    }

    // ---- a record's place in its run's chain ----

    @Test
    void aRecordsPlaceInItsRunsChainIsReadFromTheArchiveAndTheReadIsAudited() {
        archive.configured = true;
        archive.last = Optional.of(result(new ArchiveCheck.ChainResult(RUN_GAP, verified(7, false), 7, List.of(),
                false, List.of(), Optional.empty())));
        archive.placement = Optional.of(placement(6, true));

        ResponseEntity<String> answer = chain(RUN_GAP, "6", EVENT);

        assertThat(answer.getStatusCode().value()).isEqualTo(200);
        String body = answer.getBody();
        assertThat(archive.asked).containsExactly(RUN_GAP + " 6 " + EVENT);
        assertThat(JsonPath.<String>read(body, "$.chain")).isEqualTo(RUN_GAP);
        assertThat(JsonPath.<Integer>read(body, "$.log-seq")).isEqualTo(6);
        assertThat(JsonPath.<String>read(body, "$.event-id")).isEqualTo(EVENT);
        assertThat(JsonPath.<Boolean>read(body, "$.archived")).isTrue();
        assertThat(JsonPath.<Integer>read(body, "$.position")).isEqualTo(6);
        assertThat(JsonPath.<String>read(body, "$.hash")).isEqualTo("6".repeat(64));
        assertThat(JsonPath.<String>read(body, "$.previous-hash")).isEqualTo("5".repeat(64));
        assertThat(JsonPath.<String>read(body, "$.next-hash")).isEqualTo("7".repeat(64));
        assertThat(JsonPath.<Boolean>read(body, "$.link-holds")).isTrue();
        assertThat(JsonPath.<Boolean>read(body, "$.verified")).as("its place is at or below the head verified")
                .isTrue();
        assertThat(JsonPath.<String>read(body, "$.checked-at")).isEqualTo(NOW.toString());
        assertThat(JsonPath.<String>read(body, "$.segment.key")).isEqualTo("audit/runs/" + RUN_GAP
                + "/0000000000000000004.log");
        assertThat(JsonPath.<Integer>read(body, "$.segment.first-position")).isEqualTo(5);
        assertThat(JsonPath.<Integer>read(body, "$.segment.last-position")).isEqualTo(8);
        assertThat(audited(AuditRead.class)).singleElement().satisfies(read -> {
            assertThat(read.target().id()).as("a read of the audit catalog").isEqualTo(audit.id());
            assertThat(read.returned()).isEqualTo(1);
            assertThat(read.actor().principal()).isEqualTo("audrey");
        });
    }

    @Test
    void aRecordPastTheVerifiedHeadOrWithABrokenLinkSaysSo() {
        archive.configured = true;
        archive.last = Optional.of(result(new ArchiveCheck.ChainResult(RUN_GAP, verified(7, false), 7, List.of(),
                false, List.of(), Optional.empty())));
        archive.placement = Optional.of(placement(8, false));

        String body = chain(RUN_GAP, "8", EVENT).getBody();

        assertThat(JsonPath.<Boolean>read(body, "$.link-holds")).isFalse();
        assertThat(JsonPath.<Boolean>read(body, "$.verified")).isFalse();
        assertThat(JsonPath.<Object>read(body, "$.next-hash")).as("the newest in its chain").isNull();
    }

    @Test
    void aRecordNotInTheArchiveIsNotArchivedAndTheReadIsStillAudited() {
        archive.configured = true;

        String body = chain(RUN_BROKEN, "9", EVENT).getBody();

        assertThat(JsonPath.<Boolean>read(body, "$.archived")).isFalse();
        assertThat(JsonPath.<Map<String, Object>>read(body, "$")).doesNotContainKeys("position", "hash", "verified");
        assertThat(audited(AuditRead.class)).singleElement().satisfies(read -> assertThat(read.returned()).isZero());
    }

    @Test
    void aBadlyAskedPlaceIs400WithoutAnArchiveIs503AndAnArchiveThatCannotBeReadIs503() {
        archive.configured = true;
        for (String[] bad : List.of(new String[] {"not-a-run", "6", EVENT}, new String[] {"1-1-1-1-1", "6",
                EVENT}, new String[] {RUN_GAP, "0", EVENT}, new String[] {RUN_GAP, "-1", EVENT},
                new String[] {RUN_GAP, "1e3", EVENT}, new String[] {RUN_GAP, "1234567890123456789", EVENT},
                new String[] {RUN_GAP, "6", null}, new String[] {RUN_GAP, "6", "nope"})) {
            assertThat(answer(() -> chain(bad[0], bad[1], bad[2]))).as(String.join(" ", Arrays.asList(bad)))
                    .isEqualTo(400);
        }
        assertThat(archive.asked).isEmpty();
        assertThat(audited(AuditRead.class)).isEmpty();

        archive.fails = true;
        assertThat(answer(() -> chain(RUN_GAP, "6", EVENT))).isEqualTo(503);
        assertThat(audited(AuditRead.class)).as("nothing was read").isEmpty();

        archive.configured = false;
        assertThat(answer(() -> chain(RUN_GAP, "6", EVENT))).isEqualTo(503);
    }

    @Test
    void aPlaceTakesAnAuditReadFromTheSameLimit() {
        archive.configured = true;
        for (int i = 0; i < LIMIT; i++) {
            assertThat(chain(RUN_GAP, "6", EVENT).getStatusCode().value()).isEqualTo(200);
        }
        assertThat(chain(RUN_GAP, "6", EVENT).getStatusCode().value()).isEqualTo(429);
        assertThat(feed(Map.of()).getStatusCode().value()).isEqualTo(429);
        assertThat(archive.asked).hasSize(LIMIT);
    }

    // ---- what each server exports ----

    @Test
    void theArchiveGaugeGivesTheHighestVerifiedInEachRunNotDoneAndTheServerGivesWhatItLogged() {
        // Backlog 272: set side by side, the two show a running server's records that never reached the archive.
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        archive.last = Optional.of(result(
                new ArchiveCheck.ChainResult(RUN_DONE, verified(12, true), 0, List.of(), false, List.of(),
                        Optional.empty()),
                new ArchiveCheck.ChainResult(RUN_GAP, verified(7, false), 7, List.of(), false, List.of(),
                        Optional.empty()),
                new ArchiveCheck.ChainResult("unstamped-20261122", verified(5, false), 5, List.of(), false, List.of(),
                        Optional.empty())));

        archive.bindTo(registry);
        new GovernanceWiring().auditLoggedMeter(trail).bindTo(registry);
        trail.record(new Denial("listCatalogs", null, null, "no", new Actor("pat", null, null, "request-1")));
        trail.record(new Denial("listCatalogs", null, null, "no", new Actor("pat", null, null, "request-2")));

        assertThat(registry.find(AuditArchiveCheck.ARCHIVED).gauges())
                .as("a done run's chain and the unstamped records are left out")
                .extracting(g -> g.getId().getTag(AuditArchiveCheck.RUN)).containsExactly(RUN_GAP);
        assertThat(registry.get(AuditArchiveCheck.ARCHIVED).tag(AuditArchiveCheck.RUN, RUN_GAP).gauge().value())
                .isEqualTo(7);
        assertThat(registry.get(AuditArchiveCheck.LOGGED).tag(AuditArchiveCheck.RUN, trail.run().toString()).gauge()
                .value()).isEqualTo(2);
    }

    @Test
    void theArchiveStatusGivesTheLastRunAndListsOnlyTheChainsWithSomethingToShow() {
        archive.configured = true;
        ArchiveCheck.Verified done = verified(12, true);
        archive.last = Optional.of(new ArchiveCheck.Result(NOW.minusSeconds(5), NOW,
                new ArchiveCheck.Checkpoints(Optional.of("audit/checkpoints/20261122/115800.000Z.json"),
                        Optional.of(NOW.minusSeconds(120)), Duration.ofMinutes(5), false, List.of()),
                List.of(new ArchiveCheck.ChainResult(RUN_DONE, done, 0, List.of(), false, List.of(), Optional.empty()),
                        new ArchiveCheck.ChainResult(RUN_GAP, verified(7, false), 7,
                                List.of(new ArchiveCheck.Range(4, 5)), false, List.of(), Optional.empty()),
                        new ArchiveCheck.ChainResult(RUN_BROKEN, verified(3, false), 0, List.of(), false,
                                List.of("after record 3: HASH_MISMATCH"), Optional.empty()))));

        String body = routes.archive(http).getBody();

        assertThat(JsonPath.<String>read(body, "$.bucket")).isEqualTo("audit-archive");
        assertThat(JsonPath.<Boolean>read(body, "$.check.intact")).isFalse();
        assertThat(JsonPath.<Boolean>read(body, "$.check.complete")).isFalse();
        assertThat(JsonPath.<String>read(body, "$.check.ended-at")).isEqualTo(NOW.toString());
        assertThat(JsonPath.<String>read(body, "$.check.checkpoints.interval")).isEqualTo("PT5M");
        assertThat(JsonPath.<Boolean>read(body, "$.check.checkpoints.late")).isFalse();
        assertThat(JsonPath.<Integer>read(body, "$.check.runs")).isEqualTo(3);
        assertThat(JsonPath.<Integer>read(body, "$.check.runs-done")).isEqualTo(1);
        assertThat(JsonPath.<Integer>read(body, "$.check.records-verified")).isEqualTo(22);
        assertThat(JsonPath.<List<String>>read(body, "$.check.chains[*].chain")).as("the done run is counted, not listed")
                .containsExactly(RUN_GAP, RUN_BROKEN);
        assertThat(JsonPath.<Integer>read(body, "$.check.chains[0].missing[0].from")).isEqualTo(4);
        assertThat(JsonPath.<Integer>read(body, "$.check.chains[0].records-verified")).isEqualTo(7);
        assertThat(JsonPath.<String>read(body, "$.check.chains[1].problems[0]")).contains("HASH_MISMATCH");
    }

    // ---- the limit ----

    @Test
    void theFeedAndAFreshSummaryShareTheAuditReadLimit() {
        archive.configured = true;
        feed(Map.of());
        feed(Map.of());
        routes.summary("gate", http);

        assertThat(feed(Map.of()).getStatusCode().value()).isEqualTo(429);
        assertThat(routes.summary("gate", http).getStatusCode().value()).as("kept from the last minute: no read")
                .isEqualTo(200);
        assertThat(table.counts).isEqualTo(1);
        table.counted = Optional.empty();
        assertThat(routes.summary("gate", http).getStatusCode().value()).as("no longer kept").isEqualTo(429);
        assertThat(table.asked).hasSize(2);
    }

    // ---- helpers ----

    private void assertRefusedEverywhere(String who) {
        archive.configured = true;
        assertThat(answer(() -> feed(Map.of()))).isEqualTo(403);
        assertThat(answer(() -> routes.summary("gate", http))).isEqualTo(403);
        assertThat(answer(() -> routes.archive(http))).isEqualTo(403);
        assertThat(answer(() -> routes.feed("nowhere", null, null, null, null, null, null, null, null, null, http)))
                .as("refused before the catalog is looked up").isEqualTo(403);
        assertThat(answer(() -> chain(RUN_GAP, "6", EVENT))).isEqualTo(403);
        assertThat(answer(() -> chain("not-a-run", "0", null))).as("refused before what is asked is read")
                .isEqualTo(403);
        assertThat(audited(Denial.class)).hasSize(6).allSatisfy(denial -> {
            assertThat(denial.securable().id()).as("refused on the audit catalog").isEqualTo(audit.id());
            assertThat(denial.privilege()).isEqualTo("select");
            assertThat(denial.actor().principal()).isEqualTo(who);
        });
        assertThat(table.asked).isEmpty();
        assertThat(table.counts).isZero();
        assertThat(archive.asked).isEmpty();
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
        return routes.feed("gate", q.apply("type"), q.apply("principal"), q.apply("path"), q.apply("branch"),
                q.apply("subject"), q.apply("from"),
                q.apply("to"), q.apply("limit"), q.apply("pageToken"), http);
    }

    private ResponseEntity<String> chain(String run, String number, String eventId) {
        return routes.chain(run, number, eventId, http);
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

    /** A read of hr.staff as server run {@code run} logged it, record {@code number} there. */
    private AuditRow logged(String run, long number) {
        AuditRow row = row(-1, number, "request-" + number);
        return AuditRows.of(AuditRows.event(row), Optional.of(new LogStamp(UUID.fromString(run), number)), -1,
                number);
    }

    private static ArchiveCheck.Result result(ArchiveCheck.ChainResult... chains) {
        return new ArchiveCheck.Result(NOW.minusSeconds(5), NOW, new ArchiveCheck.Checkpoints(Optional.empty(),
                Optional.empty(), Duration.ofMinutes(5), false, List.of()), List.of(chains));
    }

    /** Record {@code position} of RUN_GAP's chain, in the segment holding positions 5 to 8. */
    private static ObjectLockArchive.Placement placement(long position, boolean holds) {
        return new ObjectLockArchive.Placement(new ObjectLockArchive.Segment("audit/runs/" + RUN_GAP
                + "/0000000000000000004.log", "v1", new ChainHead(4, "4".repeat(64)),
                new ChainHead(8, "8".repeat(64)), NOW), position, String.valueOf(position).repeat(64),
                String.valueOf(position - 1).repeat(64),
                position < 8 ? Optional.of(String.valueOf(position + 1).repeat(64)) : Optional.empty(), holds);
    }

    private static final String EVENT = "e0000000-0000-4000-8000-000000000272";
    private static final String RUN_DONE = "a0000000-0000-4000-8000-000000000001";
    private static final String RUN_GAP = "b0000000-0000-4000-8000-000000000002";
    private static final String RUN_BROKEN = "c0000000-0000-4000-8000-000000000003";

    /** A run's chain verified to record {@code head}, ended and complete or not. */
    private static ArchiveCheck.Verified verified(long head, boolean done) {
        return new ArchiveCheck.Verified(new ChainHead(head, "d".repeat(64)), "audit/runs/x/0000000000000000001.log",
                NOW, head, done ? head : 3, List.of(), 0, done, NOW.minusSeconds(60), done);
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

    /** The archive check: configured or not, its last run, and what a record's place is looked for. */
    private final class Archive extends AuditArchiveCheck {

        boolean configured;
        Optional<ArchiveCheck.Result> last = Optional.empty();
        Optional<ObjectLockArchive.Placement> placement = Optional.empty();
        boolean fails;
        final List<String> asked = new ArrayList<>();

        Archive() {
            super(auditSettings, Optional.empty(), Clock.fixed(NOW, ZoneOffset.UTC));
        }

        @Override
        boolean configured() {
            return configured;
        }

        @Override
        Optional<ArchiveCheck.Result> last() {
            return last;
        }

        @Override
        Optional<ObjectLockArchive.Placement> place(UUID run, long number, UUID eventId) {
            asked.add(run + " " + number + " " + eventId);
            if (fails) {
                throw new IllegalStateException("the archive's bucket cannot be read");
            }
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
