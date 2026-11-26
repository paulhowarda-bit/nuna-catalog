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
import nuna.catalog.store.Ref;
import nuna.catalog.store.RefEntry;
import nuna.catalog.store.Securable;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import tools.jackson.databind.JsonNode;

/**
 * A namespace's tables with each one's format, snapshot count and last commit, for the console's Contents (D45,
 * backlog 137): read from the pointer's projection and the journal entry of its version, never from the object store;
 * a table the caller may not describe is left out; a caller who holds nothing on the namespace is answered as for one
 * that does not exist, and audited; paged, and bounded in rows and in the tables one page looks at. Beside it, each
 * child namespace's totals over the tables beneath it (backlog 147), with the same rules and bounds, and a table the
 * caller may not describe not counted. The route, guard and authorizer are the real ones over a store double, which
 * fails any call it does not expect, so this needs only Java.
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
    private final Map<UUID, Ref> refs = new LinkedHashMap<>();
    private final Map<UUID, List<RefEntry>> cuts = new LinkedHashMap<>();
    /** The object store's metadata files, by location, which only a read through a ref may open. */
    private final Map<String, String> files = new LinkedHashMap<>();
    private final List<String> filesRead = new ArrayList<>();

    private final NamespaceRecord desk = namespace(null, "desk");
    private final NamespaceRecord lobby = namespace(null, "lobby");
    /** Format 2, three snapshots, four commits: the last by an engine for an end user. */
    private final CatalogObject trades = table(desk, "trades");
    /** Format 3, no snapshot yet, created and not committed to since. */
    private final CatalogObject positions = table(desk, "positions");
    private final CatalogObject daily = object(desk, "daily", Kind.VIEW);
    private final CatalogObject visitors = table(lobby, "visitors");
    /** Below desk: an empty namespace, and eu, which holds fills and, a level further down, quotes. */
    private final NamespaceRecord archive = namespace(desk, "archive");
    private final NamespaceRecord eu = namespace(desk, "eu");
    private final NamespaceRecord xetra = namespace(eu, "xetra");
    /** Two snapshots, last committed an hour ago. */
    private final CatalogObject fills = table(eu, "fills");
    /** Five snapshots, last committed ten seconds ago: the newest commit anywhere. */
    private final CatalogObject quotes = table(xetra, "quotes");

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
        commits(fills, 2, 2, List.of(new JournalEntry("ingest", null, null, "[]", "[]")), Duration.ofHours(1));
        commits(quotes, 2, 5, List.of(
                new JournalEntry("etl", null, "spark", "[]", "[]"),
                new JournalEntry("spark", "bo", "spark", "[]", "[]")), Duration.ofSeconds(10));

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
        routes = new NamespaceTablesRoutes(store, management, guard, holdings, authorizer,
                (catalogId, location, sha256) -> readFile(location), clock);
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
        Throwable refused = catchThrowable(() -> routes.tables("gate", "desk", null, null, null, http));
        assertThat(refused).isInstanceOfSatisfying(CatalogError.class, error -> {
            assertThat(error.status()).isEqualTo(404);
            assertThat(error.type()).isEqualTo("NoSuchNamespaceException");
            assertThat(error.getMessage()).isEqualTo("Namespace does not exist: desk");
        });
        assertThat(denials()).extracting(Denial::operation).containsExactly("listNamespaceTables");
        assertThat(denials().getFirst().privilege()).isEqualTo("describe");
        assertThat(denials().getFirst().securable().path()).containsExactly("desk");

        caller = NED;
        assertThat(catchThrowable(() -> routes.tables("gate", "desk", null, null, null, http)))
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
        Throwable refused = catchThrowable(() -> routes.tables("gate", "desk", "nope", null, null, http));

        assertThat(refused).isInstanceOfSatisfying(CatalogError.class, error -> {
            assertThat(error.status()).isEqualTo(400);
            assertThat(error.type()).isEqualTo("BadRequestException");
        });
    }

    // ---- a child namespace's totals (backlog 147) ----

    /**
     * Rita may describe all of desk: its row counts every table beneath it, at every depth, and names the newest
     * commit by its path below desk; lobby, where she holds nothing, is no row.
     */
    @Test
    void eachChildNamespaceIsOneRowWithItsTotalsOverEveryTableBeneathIt() {
        JsonNode top = totals(null, null, null);

        assertThat(top.has("next-page-token")).isFalse();
        assertThat(rowNames(top)).containsExactly("desk");
        JsonNode deskRow = top.get("namespaces").get(0);
        assertThat(deskRow.get("tables").asInt()).as("trades, positions, fills and quotes; not the view").isEqualTo(4);
        assertThat(deskRow.get("snapshots").asLong()).isEqualTo(3 + 0 + 2 + 5);
        assertThat(deskRow.get("complete").asBoolean()).isTrue();
        JsonNode newest = deskRow.get("newest-commit");
        assertThat(newest.get("table").valueStream().map(JsonNode::asString)).containsExactly("eu", "xetra", "quotes");
        assertThat(newest.get("version").asLong()).isEqualTo(2);
        assertThat(newest.get("committed-at").asString()).isEqualTo(NOW.minusSeconds(10).toString());
        assertThat(newest.get("principal").asString()).isEqualTo("spark");
        assertThat(newest.get("end-user").asString()).isEqualTo("bo");

        JsonNode below = totals("desk", null, null);
        assertThat(rowNames(below)).containsExactly("archive", "eu");
        JsonNode empty = below.get("namespaces").get(0);
        assertThat(empty.get("tables").asInt()).isZero();
        assertThat(empty.get("snapshots").asLong()).isZero();
        assertThat(empty.get("complete").asBoolean()).isTrue();
        assertThat(empty.has("newest-commit")).isFalse();
        JsonNode euRow = below.get("namespaces").get(1);
        assertThat(euRow.get("tables").asInt()).isEqualTo(2);
        assertThat(euRow.get("snapshots").asLong()).isEqualTo(7);
        assertThat(euRow.get("newest-commit").get("table").valueStream().map(JsonNode::asString))
                .containsExactly("xetra", "quotes");
        assertThat(denials()).as("totals record nothing per table").isEmpty();

        caller = OTTO;
        JsonNode otto = totals(null, null, null);
        assertThat(rowNames(otto)).containsExactly("lobby");
        JsonNode lobby = otto.get("namespaces");
        assertThat(lobby.get(0).get("tables").asInt()).isEqualTo(1);
        assertThat(lobby.get(0).get("snapshots").asLong()).isEqualTo(1);
        assertThat(lobby.get(0).get("newest-commit").get("table").valueStream().map(JsonNode::asString))
                .containsExactly("visitors");
    }

    /**
     * Tom holds select on trades alone: desk's row counts trades and nothing else, though it holds three more tables
     * beneath it; the namespaces below desk, where he holds nothing, are no rows.
     */
    @Test
    void aTableTheCallerMayNotDescribeIsNotCounted() {
        caller = TOM;

        JsonNode top = totals(null, null, null);

        assertThat(rowNames(top)).containsExactly("desk");
        JsonNode deskRow = top.get("namespaces").get(0);
        assertThat(deskRow.get("tables").asInt()).isEqualTo(1);
        assertThat(deskRow.get("snapshots").asLong()).isEqualTo(3);
        assertThat(deskRow.get("newest-commit").get("table").valueStream().map(JsonNode::asString))
                .as("quotes committed later, but Tom may not describe it").containsExactly("trades");
        assertThat(deskRow.get("newest-commit").get("principal").asString()).isEqualTo("trino");
        assertThat(totals("desk", null, null).get("namespaces")).isEmpty();
        assertThat(denials()).as("a table not counted is not a refusal").isEmpty();
    }

    @Test
    void aCallerWhoHoldsNothingOnTheParentIsAnsweredAsForANamespaceThatDoesNotExist() {
        caller = OTTO;
        Throwable refused = catchThrowable(() -> routes.namespaces("gate", "desk", null, null, null, http));
        assertThat(refused).isInstanceOfSatisfying(CatalogError.class, error -> {
            assertThat(error.status()).isEqualTo(404);
            assertThat(error.type()).isEqualTo("NoSuchNamespaceException");
        });
        assertThat(denials()).extracting(Denial::operation).containsExactly("listNamespaceTotals");

        caller = NED;
        assertThat(catchThrowable(() -> routes.namespaces("gate", null, null, null, null, http)))
                .isInstanceOfSatisfying(CatalogError.class, error ->
                        assertThat(error.type()).isEqualTo("NoSuchWarehouseException"));
    }

    @Test
    void namespacePagesWalkEveryChildOnce() {
        for (int i = 0; i < 4; i++) {
            namespace(desk, "c" + i);
        }
        List<String> paged = new ArrayList<>();
        String token = null;
        do {
            JsonNode page = totals("desk", token, 2);
            assertThat(page.get("namespaces").size()).isLessThanOrEqualTo(2);
            paged.addAll(rowNames(page));
            token = page.has("next-page-token") ? page.get("next-page-token").asString() : null;
        } while (token != null);

        assertThat(paged).containsExactly("archive", "c0", "c1", "c2", "c3", "eu");
    }

    @Test
    void aNamespacePageHoldsAtMostTwoHundredRows() {
        for (int i = 0; i < NamespaceTablesRoutes.MAX_PAGE + 50; i++) {
            namespace(desk, "n" + i);
        }

        JsonNode page = totals("desk", null, 100_000);

        assertThat(page.get("namespaces")).hasSize(NamespaceTablesRoutes.MAX_PAGE);
        assertThat(page.has("next-page-token")).isTrue();
    }

    /**
     * A page looks at 1,000 tables and namespaces at most. big, beneath which there are more, waits for the next page
     * after archive; there it is the first row, counted over what it looked at and marked incomplete; eu follows.
     */
    @Test
    void aNamespacePageLooksAtAThousandTablesAndNamespacesAtMost() {
        NamespaceRecord big = namespace(desk, "big");
        for (int i = 0; i < NamespaceTablesRoutes.MAX_EXAMINED + 100; i++) {
            table(big, "b" + i);
        }

        JsonNode first = totals("desk", null, null);
        assertThat(rowNames(first)).containsExactly("archive");
        assertThat(first.has("next-page-token")).isTrue();

        JsonNode second = totals("desk", first.get("next-page-token").asString(), null);
        assertThat(rowNames(second)).containsExactly("big");
        JsonNode cut = second.get("namespaces").get(0);
        assertThat(cut.get("complete").asBoolean()).isFalse();
        assertThat(cut.get("tables").asInt()).isEqualTo(NamespaceTablesRoutes.MAX_EXAMINED - 1);
        assertThat(second.has("next-page-token")).isTrue();

        JsonNode third = totals("desk", second.get("next-page-token").asString(), null);
        assertThat(rowNames(third)).containsExactly("eu");
        assertThat(third.get("namespaces").get(0).get("complete").asBoolean()).isTrue();
        assertThat(third.has("next-page-token")).isFalse();
    }

    @Test
    void aNamespaceTokenTheStoreDidNotIssueIsABadRequest() {
        assertThat(catchThrowable(() -> routes.namespaces("gate", "desk", "nope", null, null, http)))
                .isInstanceOfSatisfying(CatalogError.class, error -> assertThat(error.status()).isEqualTo(400));
    }

    // ---- through a branch or tag (backlog 168) ----

    /**
     * A tag made when trades was at version 4: main has committed since, so through the tag trades reads the cut's
     * pointer, on main's line, with the format and snapshots of the cut's file, read from the object store, and the
     * journal's entry that wrote it. Positions main has not touched reads main's projection, and no file is read for
     * it. A table main made since is not on the tag; one main dropped since still is.
     */
    @Test
    void throughATagEachTableReadsAsTheCutHasIt() {
        Ref eod = refFromMain("eod", Ref.Kind.TAG);
        files.put(location(trades, 4), "{\"format-version\":2,\"snapshots\":[{},{},{}]}");
        commits(trades, 2, 7, List.of(
                new JournalEntry("etl", null, "spark", "[]", "[]"),
                new JournalEntry("etl", null, "spark", "[]", "[]"),
                new JournalEntry("etl", null, "spark", "[]", "[]"),
                new JournalEntry("trino", "ana", "trino", "[]", "[]"),
                new JournalEntry("late", null, "spark", "[]", "[]")));
        table(desk, "later");
        CatalogObject dropped = table(desk, "gone");
        cuts.get(eod.id()).add(new RefEntry(eod.id(), dropped.id(), RefEntry.Kind.TABLE, desk.id(), dropped.name(),
                Optional.of(new RefEntry.At(1, location(dropped, 1), "0".repeat(64)))));
        objects.put(dropped.id(), dropped(dropped));

        JsonNode body = tables("desk", null, null, "eod");

        assertThat(names(body)).containsExactly("gone", "positions", "trades");
        JsonNode row = body.get("tables").get(2);
        assertThat(row.get("format-version").asInt()).isEqualTo(2);
        assertThat(row.get("snapshots").asLong()).as("the cut's file's, not main's 7").isEqualTo(3);
        assertThat(row.get("ref").get("pointer").asString()).isEqualTo("cut");
        assertThat(row.get("ref").get("version").asLong()).isEqualTo(4);
        assertThat(row.get("ref").get("line").asString()).isEqualTo("main");
        JsonNode last = row.get("last-commit");
        assertThat(last.get("version").asLong()).isEqualTo(4);
        assertThat(last.get("line").asString()).isEqualTo("main");
        assertThat(last.get("principal").asString()).as("the commit that wrote the cut's file").isEqualTo("trino");
        assertThat(last.get("end-user").asString()).isEqualTo("ana");

        JsonNode untouched = body.get("tables").get(1);
        assertThat(untouched.get("format-version").asInt()).isEqualTo(3);
        assertThat(untouched.get("snapshots").asLong()).isZero();
        assertThat(untouched.get("ref").get("version").asLong()).isEqualTo(1);
        assertThat(filesRead).as("main's projection serves a table main has not moved, a dropped one too")
                .containsExactly(location(trades, 4));
        assertThat(denials()).as("a listing records nothing per table").isEmpty();
    }

    /** A branch's own pointer, numbered from 1 on its own line (item 105), with its projection and its own journal. */
    @Test
    void throughABranchATableItChangedReadsItsOwnPointer() {
        Ref dev = refFromMain("dev", Ref.Kind.BRANCH);
        commitOnBranch(dev, trades, 9, new JournalEntry("ana", null, "spark", "[]", "[]"));

        JsonNode row = tables("desk", null, null, "dev").get("tables").get(1);

        assertThat(row.get("name").asString()).isEqualTo("trades");
        assertThat(row.get("snapshots").asLong()).isEqualTo(9);
        assertThat(row.get("ref").get("pointer").asString()).isEqualTo("own");
        assertThat(row.get("ref").get("version").asLong()).isEqualTo(1);
        assertThat(row.get("ref").get("line").asString()).isEqualTo("dev");
        assertThat(row.get("last-commit").get("principal").asString()).isEqualTo("ana");
        assertThat(row.get("last-commit").get("line").asString()).isEqualTo("dev");
        assertThat(filesRead).isEmpty();
    }

    /**
     * A tag made from a branch took the branch's own pointer: through it trades reads that pointer, on the branch's
     * line, its entry from the branch's journal, which stays, keyed by the branch's id, once the branch has ended.
     */
    @Test
    void aRefMadeFromABranchReadsTheBranchsPointerOnTheBranchsLine() {
        Ref dev = refFromMain("dev", Ref.Kind.BRANCH);
        commitOnBranch(dev, trades, 9, new JournalEntry("ana", null, "spark", "[]", "[]"));
        refFrom("snap", Ref.Kind.TAG, dev);
        files.put(branchLocation(dev, trades), "{\"format-version\":2,\"snapshots\":[{},{}]}");
        refs.remove(dev.id());
        pointers.remove(dev.key(trades.id()));

        JsonNode body = tables("desk", null, null, "snap");

        JsonNode row = body.get("tables").get(1);
        assertThat(row.get("ref").get("pointer").asString()).isEqualTo("cut");
        assertThat(row.get("ref").get("version").asLong()).isEqualTo(1);
        assertThat(row.get("ref").get("line").asString()).isEqualTo("dev");
        assertThat(row.get("snapshots").asLong()).isEqualTo(2);
        assertThat(row.get("last-commit").get("principal").asString()).isEqualTo("ana");
        assertThat(body.get("tables").get(0).get("ref").get("line").asString())
                .as("positions: main's, which dev's cut took").isEqualTo("main");
    }

    /**
     * Rights through a ref are main's: Tom sees trades alone; Otto, who holds nothing on desk, is answered as for a
     * namespace that does not exist, and audited; Ned, nothing in the catalog. A ref no one has made, and a namespace
     * main made after the cut, are not found.
     */
    @Test
    void throughARefOnlyWhatTheCallerMayDescribeIsListed() {
        refFromMain("eod", Ref.Kind.TAG);
        NamespaceRecord later = namespace(null, "later");
        grant(new Securable(Securable.Kind.NAMESPACE, later.id()), "desk-readers", Privilege.DESCRIBE);

        caller = TOM;
        assertThat(names(tables("desk", null, null, "eod"))).containsExactly("trades");
        JsonNode top = totals(null, null, null, "eod");
        assertThat(rowNames(top)).containsExactly("desk");
        assertThat(top.get("namespaces").get(0).get("tables").asInt()).isEqualTo(1);
        assertThat(denials()).isEmpty();

        caller = OTTO;
        assertThat(catchThrowable(() -> routes.tables("gate", "desk", null, null, "eod", http)))
                .isInstanceOfSatisfying(CatalogError.class, error ->
                        assertThat(error.type()).isEqualTo("NoSuchNamespaceException"));
        assertThat(denials()).extracting(Denial::operation).containsExactly("listNamespaceTables");
        assertThat(denials().getFirst().securable().path()).containsExactly("desk");

        caller = NED;
        assertThat(catchThrowable(() -> routes.tables("gate", "desk", null, null, "eod", http)))
                .isInstanceOfSatisfying(CatalogError.class, error ->
                        assertThat(error.type()).isEqualTo("NoSuchWarehouseException"));

        caller = RITA;
        assertThat(catchThrowable(() -> routes.tables("gate", "desk", null, null, "nope", http)))
                .isInstanceOfSatisfying(CatalogError.class, error ->
                        assertThat(error.type()).isEqualTo("NoSuchRefException"));
        assertThat(catchThrowable(() -> routes.tables("gate", "later", null, null, "eod", http)))
                .isInstanceOfSatisfying(CatalogError.class, error ->
                        assertThat(error.type()).isEqualTo("NoSuchNamespaceException"));
        assertThat(rowNames(totals(null, null, null, "eod"))).as("later is main's, made after the cut")
                .containsExactly("desk");
    }

    /** Totals through a ref count each table as the ref reads it, and name the newest commit's line. */
    @Test
    void throughARefTotalsCountEachTableAsTheRefHasIt() {
        Ref dev = refFromMain("dev", Ref.Kind.BRANCH);
        commitOnBranch(dev, trades, 9, new JournalEntry("ana", null, "spark", "[]", "[]"));
        commits(quotes, 2, 50, List.of(new JournalEntry("etl", null, "spark", "[]", "[]"),
                new JournalEntry("spark", "bo", "spark", "[]", "[]"),
                new JournalEntry("late", null, "spark", "[]", "[]")), Duration.ofSeconds(1));
        files.put(location(quotes, 2), "{\"format-version\":2,\"snapshots\":[{},{},{},{},{}]}");

        JsonNode deskRow = totals(null, null, null, "dev").get("namespaces").get(0);

        assertThat(deskRow.get("tables").asInt()).isEqualTo(4);
        assertThat(deskRow.get("snapshots").asLong()).as("trades' own 9, positions 0, fills 2, quotes' cut 5")
                .isEqualTo(9 + 0 + 2 + 5);
        JsonNode newest = deskRow.get("newest-commit");
        assertThat(newest.get("table").valueStream().map(JsonNode::asString)).containsExactly("trades");
        assertThat(newest.get("line").asString()).isEqualTo("dev");
        assertThat(newest.get("version").asLong()).isEqualTo(1);
    }

    // ---- helpers ----

    private JsonNode tables(String namespace, String token, Integer size, String ref) {
        ResponseEntity<String> response = routes.tables("gate", namespace, token, size, ref, http);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        return Management.JSON.readTree(response.getBody());
    }

    private JsonNode totals(String namespace, String token, Integer size, String ref) {
        ResponseEntity<String> response = routes.namespaces("gate", namespace, token, size, ref, http);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        return Management.JSON.readTree(response.getBody());
    }

    /** A ref made from main now: every namespace, and every active table and view at its main pointer. */
    private Ref refFromMain(String name, Ref.Kind kind) {
        Ref ref = ref(name, kind, Optional.empty());
        List<RefEntry> cut = new ArrayList<>();
        namespaces.values().forEach(ns -> cut.add(new RefEntry(ref.id(), ns.id(), RefEntry.Kind.NAMESPACE,
                ns.parentId(), ns.name(), Optional.empty())));
        objects.values().stream().filter(o -> o.state() == CatalogObject.State.ACTIVE).forEach(o -> {
            Pointer at = pointers.getOrDefault(o.mainKey(), new Pointer(location(o, 1), 1, "0".repeat(64),
                    new byte[0]));
            cut.add(new RefEntry(ref.id(), o.id(), RefEntry.of(o.kind()), o.namespaceId(), o.name(),
                    Optional.of(new RefEntry.At(at.version(), at.metadataLocation(), at.sha256()))));
        });
        cuts.put(ref.id(), cut);
        return ref;
    }

    /** A ref made from another now: its cut, with the source's own pointer where it has one, as the stores make it. */
    private Ref refFrom(String name, Ref.Kind kind, Ref source) {
        Ref ref = ref(name, kind, Optional.of(source));
        cuts.put(ref.id(), new ArrayList<>(cuts.get(source.id()).stream().map(entry -> new RefEntry(ref.id(),
                entry.id(), entry.kind(), entry.parentId(), entry.name(), entry.pointer().map(kept -> Optional
                        .ofNullable(pointers.get(source.key(entry.id())))
                        .map(own -> new RefEntry.At(own.version(), own.metadataLocation(), own.sha256()))
                        .orElse(kept)))).toList()));
        return ref;
    }

    private Ref ref(String name, Ref.Kind kind, Optional<Ref> from) {
        Ref ref = new Ref(catalog.id(), UUID.randomUUID(), new Name(Names.normalize(name), name), kind, NOW, "bea",
                from.map(Ref::id), from.map(r -> r.name().display()).orElse(Ref.MAIN),
                kind == Ref.Kind.BRANCH ? Optional.of(NOW.plus(Duration.ofDays(30))) : Optional.empty());
        refs.put(ref.id(), ref);
        return ref;
    }

    /** One commit on the branch: its own pointer at version 1 (item 105), and its own journal's entry for it. */
    private void commitOnBranch(Ref branch, CatalogObject table, int snapshots, JournalEntry entry) {
        PointerKey key = branch.key(table.id());
        journal.put(key, List.of(new JournalRecord(UUID.randomUUID(), key, NOW.minusSeconds(5), entry,
                Optional.of(location(table, 4)), branchLocation(branch, table), 1)));
        String projection = "{\"kind\":\"table\",\"uuid\":\"" + table.id() + "\",\"format-version\":2"
                + ",\"current-schema-id\":0,\"default-spec-id\":0,\"snapshots\":" + snapshots + "}";
        pointers.put(key, new Pointer(branchLocation(branch, table), 1, "0".repeat(64),
                projection.getBytes(StandardCharsets.UTF_8)));
    }

    private static String branchLocation(Ref branch, CatalogObject table) {
        return table.locationRoot() + "/metadata/00001-x.branch-" + branch.id() + ".metadata.json";
    }

    private static CatalogObject dropped(CatalogObject table) {
        return new CatalogObject(table.catalogId(), table.id(), table.namespaceId(), table.name(), table.kind(),
                CatalogObject.State.DROPPED, Optional.of(NOW), Optional.of(NOW.plus(Duration.ofDays(30))),
                table.locationRoot(), Optional.empty());
    }

    private Optional<String> readFile(String location) {
        filesRead.add(location);
        return Optional.ofNullable(files.get(location));
    }

    private List<RefEntry> entries(UUID refId, UUID parentId, RefEntry.Kind kind) {
        return cuts.getOrDefault(refId, List.of()).stream()
                .filter(entry -> entry.parentId().equals(parentId) && entry.kind() == kind)
                .sorted(Comparator.comparing((RefEntry entry) -> entry.name().normalized())
                        .thenComparing(RefEntry::id))
                .toList();
    }

    private Optional<RefEntry> findRefNamespace(UUID refId, List<String> path) {
        UUID parent = catalog.id();
        RefEntry found = null;
        for (String level : path) {
            UUID above = parent;
            found = entries(refId, above, RefEntry.Kind.NAMESPACE).stream()
                    .filter(entry -> entry.name().normalized().equals(level)).findFirst().orElse(null);
            if (found == null) {
                return Optional.empty();
            }
            parent = found.id();
        }
        return Optional.ofNullable(found);
    }

    private JsonNode tables(String namespace, String token, Integer size) {
        ResponseEntity<String> response = routes.tables("gate", namespace, token, size, null, http);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        return Management.JSON.readTree(response.getBody());
    }

    /** The child namespaces of {@code namespace}, or of the catalog when it is null, with their totals. */
    private JsonNode totals(String namespace, String token, Integer size) {
        ResponseEntity<String> response = routes.namespaces("gate", namespace, token, size, null, http);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        return Management.JSON.readTree(response.getBody());
    }

    private static List<String> rowNames(JsonNode body) {
        return body.get("namespaces").valueStream().map(row -> row.get("name").asString()).toList();
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
        commits(table, format, snapshots, entries, Duration.ofMinutes(1));
    }

    /** {@link #commits}, the last of them {@code lastAgo} before now. */
    private void commits(CatalogObject table, int format, int snapshots, List<JournalEntry> entries,
                         Duration lastAgo) {
        PointerKey key = table.mainKey();
        List<JournalRecord> records = new ArrayList<>();
        for (int i = 0; i < entries.size(); i++) {
            long version = i + 1;
            records.add(new JournalRecord(UUID.randomUUID(), key,
                    NOW.minus(lastAgo).minusSeconds(60L * (entries.size() - 1 - i)),
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
                    case "listNamespaces" -> page(namespaces.values().stream()
                            .filter(ns -> ns.parentId().equals(args[0]))
                            .sorted(Comparator.comparing((NamespaceRecord ns) -> ns.name().normalized())
                                    .thenComparing(NamespaceRecord::id))
                            .toList(), (PageRequest) args[1]);
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
                    case "findRef" -> refs.values().stream().filter(ref -> ref.catalogId().equals(args[0])
                            && ref.name().normalized().equals(args[1])).findFirst();
                    case "readRef" -> Optional.ofNullable(refs.get((UUID) args[0]));
                    case "findRefNamespace" -> findRefNamespace((UUID) args[0], (List<String>) args[1]);
                    case "listRefNamespaces" -> page(entries((UUID) args[0], (UUID) args[1], RefEntry.Kind.NAMESPACE),
                            (PageRequest) args[2]);
                    case "listRefObjects" -> page(entries((UUID) args[0], (UUID) args[1], RefEntry.of((Kind) args[2])),
                            (PageRequest) args[3]);
                    case "toString" -> "the test's store";
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }
}
