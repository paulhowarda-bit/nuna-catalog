package nuna.catalog.server.governance;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Proxy;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import nuna.catalog.audit.CatalogEvent;
import nuna.catalog.audit.Commit;
import nuna.catalog.audit.Denial;
import nuna.catalog.audit.EventCodec;
import nuna.catalog.audit.RefChange;
import nuna.catalog.core.CatalogError;
import nuna.catalog.core.Names;
import nuna.catalog.core.commit.Alerts;
import nuna.catalog.core.commit.CommitEngine;
import nuna.catalog.core.commit.CommitEvents;
import nuna.catalog.core.commit.CommitReply;
import nuna.catalog.core.commit.CommitRule;
import nuna.catalog.core.commit.CommitSettings;
import nuna.catalog.core.commit.MetadataCache;
import nuna.catalog.core.commit.ObjectName;
import nuna.catalog.core.commit.Rules;
import nuna.catalog.core.commit.TableFiles;
import nuna.catalog.core.principal.Caller;
import nuna.catalog.core.principal.Principal;
import nuna.catalog.iceberg.Metadata;
import nuna.catalog.iceberg.Outcome.NewMetadata;
import nuna.catalog.security.Authorizer;
import nuna.catalog.security.Privilege;
import nuna.catalog.security.Separation;
import nuna.catalog.server.rest.AccessDenied;
import nuna.catalog.server.rest.AuditEvents;
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
import nuna.catalog.store.Change;
import nuna.catalog.store.CommitBatch;
import nuna.catalog.store.CommitLookup;
import nuna.catalog.store.CommitResult;
import nuna.catalog.store.GovernanceAttribute;
import nuna.catalog.store.Grant;
import nuna.catalog.store.GrantRecord;
import nuna.catalog.store.IdempotencyClaim;
import nuna.catalog.store.IdempotencyResult;
import nuna.catalog.store.JournalRecord;
import nuna.catalog.store.KeyState;
import nuna.catalog.store.Name;
import nuna.catalog.store.NamespaceRecord;
import nuna.catalog.store.OutboxEvent;
import nuna.catalog.store.Page;
import nuna.catalog.store.PageRequest;
import nuna.catalog.store.PageToken;
import nuna.catalog.store.Pointer;
import nuna.catalog.store.PointerHold;
import nuna.catalog.store.PointerKey;
import nuna.catalog.store.PointerSwap;
import nuna.catalog.store.Ref;
import nuna.catalog.store.RefEntry;
import nuna.catalog.store.RefLock;
import nuna.catalog.store.RefPointers;
import nuna.catalog.store.Securable;
import nuna.catalog.store.WriteResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import tools.jackson.databind.JsonNode;

/**
 * The merge route (D33, backlog 107): a merge needs {@code create} on the catalog and, on each table and view it moves,
 * {@code write} for data alone or {@code modify} for a definition change, asked in one batch (security finding 3); it
 * answers main's new versions and records a {@code ref} event (merged) and a {@code Commit} event on main per table; a
 * conflict answers 409 listing each and moves nothing; main's rules refuse it as a denial; an Idempotency-Key replays
 * the answer; and the dry run, open to any holding in the catalog, names only what the caller can describe and counts
 * the rest (security finding 2). The routes, guard, authorizer, idempotency, commit engine and its rules are the real
 * ones, over a store double that keeps refs, pointers and keys and judges a merge's batch as the stores do (RefSuite
 * checks them), with metadata files in memory, so this needs only Java.
 */
class RefMergeRoutesTest {

    /** The wall clock's, as Iceberg stamps the metadata it writes with it. */
    private static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.MILLIS);
    private static final Duration DAY = Duration.ofDays(1);
    private static final RefSettings SETTINGS = new RefSettings(50, 1000, Duration.ofDays(30), Duration.ofDays(180),
            Duration.ofDays(7));
    private static final String SCHEMA = """
            {"type":"struct","schema-id":0,"fields":[{"id":1,"name":"id","required":true,"type":"long"}]}""";
    private static final CommitEvents NO_EVENTS = commit -> List.of();

    /** Create and modify on the catalog. */
    private static final Caller BEA = person("bea", "builders");
    /** Create and write on the catalog: a merge of data alone. */
    private static final Caller WREN = person("wren", "writers");
    /** Describe on the namespace hr only: something in the catalog, nothing on sales. */
    private static final Caller HAL = person("hal", "hr-readers");
    /** Nothing in the catalog. */
    private static final Caller NED = person("ned", "newcomers");
    /** Who commits, on main and on the branches. */
    private static final Caller BOB = person("bob", "builders");
    /** Select on the catalog: reads a table's metadata log. */
    private static final Caller REX = person("rex", "readers");

    private final CatalogRecord catalog = new CatalogRecord(UUID.randomUUID(), "gate", "gate");
    private final Map<UUID, NamespaceRecord> namespaces = new LinkedHashMap<>();
    private final Map<UUID, CatalogObject> objects = new LinkedHashMap<>();
    private final List<GrantRecord> grants = new ArrayList<>();
    private final List<GovernanceAttribute> attributes = new ArrayList<>();
    private final Map<UUID, Ref> refs = new LinkedHashMap<>();
    private final Map<UUID, List<RefEntry>> cuts = new HashMap<>();
    private final Map<PointerKey, Pointer> pointers = new HashMap<>();
    private final List<JournalRecord> journal = new ArrayList<>();
    private final List<UUID> commits = new ArrayList<>();
    private final Map<String, Key> keys = new HashMap<>();
    private final Map<String, byte[]> files = new HashMap<>();
    private final List<CatalogEvent> outbox = new ArrayList<>();
    private final List<CatalogEvent> audit = new ArrayList<>();

    private final MutableClock clock = new MutableClock(NOW);
    private final CatalogStore store = store();
    private final TableFiles tableFiles = new MemoryFiles();
    private final List<CommitRule> rules = Rules.standard(tableFiles, Duration.ofSeconds(60));
    private final NamespaceRecord hr = namespace("hr");
    private final NamespaceRecord sales = namespace("sales");
    /** One snapshot on main, version 2. */
    private final CatalogObject staff = table(hr, "Staff");
    /** One snapshot on main, version 2. */
    private final CatalogObject orders = table(sales, "orders");

    private final RestSettings rest = new RestSettings(100, 1000, 1_048_576, 1000, 268_435_456, Duration.ofDays(30),
            Duration.ofMinutes(5), "the identity provider", URI.create("urn:nuna:test"), 100, null);
    private final MockHttpServletRequest http = new MockHttpServletRequest();
    private final RefRoutes routes = routes(SETTINGS);
    private Caller caller = BEA;

    RefMergeRoutesTest() {
        http.setAttribute(RequestIds.class.getName(), "request-1");
        Securable onCatalog = new Securable(Securable.Kind.CATALOG, catalog.id());
        grant(onCatalog, "builders", Privilege.CREATE);
        grant(onCatalog, "builders", Privilege.MODIFY);
        grant(onCatalog, "writers", Privilege.CREATE);
        grant(onCatalog, "writers", Privilege.WRITE);
        grant(new Securable(Securable.Kind.NAMESPACE, hr.id()), "hr-readers", Privilege.DESCRIBE);
        grant(onCatalog, "readers", Privilege.SELECT);
    }

    // ---- merging ----

    @Test
    @DisplayName("a merge moves what only the branch changed, answers main's new versions, records a ref event and a"
            + " Commit event on main per table, and ends the branch; its key replays the answer")
    void aMergeMovesWhatOnlyTheBranchChanged() {
        Ref dev = branch("dev", staff, orders);
        onBranch(dev, orders, append(2, 1L, 2, 2));

        ResponseEntity<String> first = routes.merge("gate", "dev", false, "merge-1", http);

        JsonNode answer = json(first);
        assertThat(answer.get("ref").get("name").asString()).isEqualTo("dev");
        assertThat(answer.get("tables")).singleElement().satisfies(table -> {
            assertThat(table.get("namespace").valueStream().map(JsonNode::asString).toList()).containsExactly("sales");
            assertThat(table.get("name").asString()).isEqualTo("orders");
            assertThat(table.get("kind").asString()).isEqualTo("table");
            assertThat(table.get("cut-version").asLong()).isEqualTo(2);
            assertThat(table.get("branch-version").asLong()).as("the branch's own first").isEqualTo(1);
            assertThat(table.get("version").asLong()).isEqualTo(3);
            assertThat(table.get("rebased").asBoolean()).isFalse();
            assertThat(table.has("definition-change")).as("data alone").isFalse();
            assertThat(table.get("metadata-location").asString())
                    .isEqualTo(pointers.get(orders.mainKey()).metadataLocation());
        });
        assertThat(pointers.get(orders.mainKey()).version()).isEqualTo(3);
        assertThat(pointers.get(staff.mainKey()).version()).as("unchanged on the branch").isEqualTo(2);
        assertThat(refs).as("the merge ends the branch").doesNotContainKey(dev.id());

        assertThat(events(Commit.class)).singleElement().satisfies(commit -> {
            assertThat(commit.branch()).isEqualTo(PointerKey.MAIN);
            assertThat(commit.object().id()).isEqualTo(orders.id());
            assertThat(commit.object().path()).containsExactly("sales", "orders");
            assertThat(commit.before().version()).isEqualTo(2);
            assertThat(commit.after().version()).isEqualTo(3);
            assertThat(commit.updates()).contains("\"action\":\"merge-branch\"", "\"branch\":\"dev\"");
            assertThat(commit.actor().principal()).isEqualTo("bea");
        });
        assertThat(events(RefChange.class)).singleElement().satisfies(merged -> {
            assertThat(merged.action()).isEqualTo(RefChange.Action.MERGED);
            assertThat(merged.ref()).isEqualTo(dev.id());
            assertThat(merged.tables()).extracting(nuna.catalog.audit.Securable::path)
                    .containsExactly(List.of("sales", "orders"));
            assertThat(merged.actor().principal()).isEqualTo("bea");
        });
        assertThat(journal.getLast().entry().updatesJson()).as("main's journal reads as a merge")
                .contains("\"action\":\"merge-branch\"", "\"branch-id\":\"" + dev.id() + "\"");

        ResponseEntity<String> again = routes.merge("gate", "dev", false, "merge-1", http);
        assertThat(again.getStatusCode().value()).isEqualTo(200);
        assertThat(again.getBody()).as("the key replays the answer").isEqualTo(first.getBody());
        assertThat(answer(() -> routes.merge("gate", "dev", false, null, http)))
                .isEqualTo(new Answer(404, "NoSuchRefException", "Ref does not exist: dev"));
        assertThat(denials()).isEmpty();
    }

    @Test
    @DisplayName("each table needs the right its change needs on main, asked in one batch: write for data, modify for a"
            + " definition change; a refusal is a denial naming the table and nothing moves")
    void theRightFollowsTheChange() {
        Ref dev = branch("dev", staff, orders);
        onBranch(dev, orders, append(2, 1L, 2, 2));
        onBranch(dev, staff, update(addColumn()));

        caller = WREN;
        JsonNode dry = json(routes.merge("gate", "dev", true, null, http));
        assertThat(dry.get("may-merge").asBoolean()).isFalse();
        assertThat(table(dry, "Staff").get("right").get("needs").asString()).isEqualTo("modify");
        assertThat(table(dry, "Staff").get("right").get("allowed").asBoolean()).isFalse();
        assertThat(table(dry, "Staff").get("definition-change").asString()).startsWith("current schema 0 became 1");
        assertThat(table(dry, "orders").get("right").get("needs").asString()).isEqualTo("write");
        assertThat(table(dry, "orders").get("right").get("allowed").asBoolean()).isTrue();

        Answer refused = answer(() -> routes.merge("gate", "dev", false, null, http));

        assertThat(refused.status()).isEqualTo(403);
        assertThat(denials()).singleElement().satisfies(denial -> {
            assertThat(denial.operation()).isEqualTo("mergeRef");
            assertThat(denial.privilege()).isEqualTo("modify");
            assertThat(denial.securable().path()).containsExactly("hr", "Staff");
            assertThat(denial.actor().principal()).isEqualTo("wren");
        });
        assertThat(pointers.get(orders.mainKey()).version()).as("nothing moved").isEqualTo(2);
        assertThat(refs).containsKey(dev.id());

        caller = HAL;
        assertThat(answer(() -> routes.merge("gate", "dev", false, null, http)).status()).as("no create")
                .isEqualTo(403);
        caller = NED;
        assertThat(answer(() -> routes.merge("gate", "dev", false, null, http))).as("nothing in the catalog")
                .isEqualTo(new Answer(404, "NoSuchWarehouseException", "The given warehouse does not exist: gate"));

        caller = BEA;
        JsonNode merged = json(routes.merge("gate", "dev", false, null, http));
        assertThat(merged.get("tables")).hasSize(2);
        assertThat(pointers.get(staff.mainKey()).version()).isEqualTo(3);
        assertThat(pointers.get(orders.mainKey()).version()).isEqualTo(3);
    }

    @Test
    @DisplayName("a table changed on both sides or dropped on main answers 409 RefMergeConflictException listing each"
            + " conflict with its versions, and nothing moves")
    void conflictsAnswer409AndMoveNothing() {
        Ref dev = branch("dev", staff, orders);
        onBranch(dev, orders, append(2, 1L, 2, 2));
        onBranch(dev, staff, append(2, 1L, 2, 2));
        onMain(orders, append(3, 1L, 2, 1));
        objects.put(staff.id(), new CatalogObject(staff.catalogId(), staff.id(), staff.namespaceId(), staff.name(),
                staff.kind(), CatalogObject.State.DROPPED, Optional.of(NOW), Optional.of(NOW.plus(DAY)),
                staff.locationRoot(), Optional.empty()));

        ResponseEntity<String> response = routes.merge("gate", "dev", false, null, http);

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        JsonNode body = Management.JSON.readTree(response.getBody());
        assertThat(body.get("error").get("type").asString()).isEqualTo("RefMergeConflictException");
        assertThat(body.get("error").get("message").asString()).isEqualTo("Branch dev conflicts with main in 2 of the"
                + " 2 tables and views it changed, so nothing moved: hr.Staff (cut version 2, main dropped, branch"
                + " version 1): main has dropped at " + NOW + " it; sales.orders (cut version 2, main version 3, branch"
                + " version 1): main committed snapshot 3 (append)");
        assertThat(body.get("conflicts")).hasSize(2);
        assertThat(body.get("conflicts").get(0).get("dropped-at").asString()).isEqualTo(NOW.toString());
        assertThat(body.get("conflicts").get(1).get("main-version").asLong()).isEqualTo(3);
        assertThat(pointers.get(orders.mainKey()).version()).as("nothing moved").isEqualTo(3);
        assertThat(refs).containsKey(dev.id());
        assertThat(events(RefChange.class)).isEmpty();
    }

    @Test
    @DisplayName("main's rules judge the merge: a DR freeze set after the branch's commit refuses it, audited as a"
            + " denial, and the dry run shows the verdict")
    void mainsRulesRefuseAMergeAsADenial() {
        Ref dev = branch("dev", orders);
        onBranch(dev, orders, append(2, 1L, 2, 2));
        attributes.add(new GovernanceAttribute(new Securable(Securable.Kind.NAMESPACE, sales.id()),
                GovernanceAttribute.Kind.DR_FREEZE, "drill", "test", "ops", NOW));

        JsonNode dry = json(routes.merge("gate", "dev", true, null, http));
        assertThat(dry.get("answer").get("code").asInt()).isEqualTo(403);
        JsonNode verdict = table(dry, "orders").get("verdict");
        assertThat(verdict.get("allowed").asBoolean()).isFalse();
        assertThat(verdict.get("reason").asString()).isEqualTo("dr_freeze");
        assertThat(denials()).as("a dry run refuses nothing").isEmpty();

        Answer refused = answer(() -> routes.merge("gate", "dev", false, null, http));

        assertThat(refused.status()).isEqualTo(403);
        assertThat(refused.message()).contains("frozen for DR reconcile: drill");
        assertThat(denials()).singleElement().satisfies(denial -> {
            assertThat(denial.operation()).isEqualTo("mergeRef");
            assertThat(denial.reason()).isEqualTo("dr_freeze: " + refused.message());
            assertThat(denial.securable().path()).containsExactly("sales", "orders");
        });
        assertThat(pointers.get(orders.mainKey()).version()).isEqualTo(2);
        assertThat(refs).containsKey(dev.id());
    }

    // ---- the dry run ----

    @Test
    @DisplayName("the dry run writes nothing and names only what the caller can describe: the rest of the conflicts"
            + " and moves are counted, and who committed on main is shown only to one holding create")
    void theDryRunNamesOnlyWhatTheCallerCanDescribe() {
        Ref dev = branch("dev", staff, orders);
        onBranch(dev, staff, append(2, 1L, 2, 2));
        onBranch(dev, orders, append(2, 1L, 2, 2));
        onMain(orders, append(3, 1L, 2, 1));
        int written = files.size();

        caller = HAL;
        JsonNode hal = json(routes.merge("gate", "dev", true, null, http));

        assertThat(hal.get("dry-run").asBoolean()).isTrue();
        assertThat(hal.get("changed").asInt()).isEqualTo(2);
        assertThat(hal.get("hidden").asInt()).isEqualTo(1);
        assertThat(hal.get("may-merge").asBoolean()).isFalse();
        assertThat(hal.get("ref").has("created-by")).isFalse();
        assertThat(hal.get("tables")).singleElement().satisfies(table -> {
            assertThat(table.get("name").asString()).isEqualTo("Staff");
            assertThat(table.get("moves").asBoolean()).isTrue();
            assertThat(table.get("version").asLong()).isEqualTo(3);
            assertThat(table.get("main-commits").get("count").asLong()).isZero();
            assertThat(table.get("main-commits").has("by")).isFalse();
        });
        assertThat(hal.get("answer").get("code").asInt()).isEqualTo(409);
        assertThat(hal.get("answer").get("type").asString()).isEqualTo("RefMergeConflictException");
        assertThat(hal.get("answer").get("message").asString()).isEqualTo("Branch dev conflicts with main in 1 of the"
                + " 2 tables and views it changed, so nothing moved: 1 the caller cannot describe");
        assertThat(hal.toString()).doesNotContain("orders", "sales");

        caller = BEA;
        JsonNode bea = json(routes.merge("gate", "dev", true, null, http));
        assertThat(bea.get("hidden").asInt()).isZero();
        assertThat(bea.get("answer").get("message").asString()).endsWith("sales.orders (cut version 2, main version"
                + " 3, branch version 1): main committed snapshot 3 (append)");
        JsonNode orders = table(bea, "orders");
        assertThat(orders.get("moves").asBoolean()).isFalse();
        assertThat(orders.get("main-version").asLong()).isEqualTo(3);
        assertThat(orders.get("main-commits").get("count").asLong()).isEqualTo(1);
        assertThat(orders.get("main-commits").get("by").valueStream().map(JsonNode::asString).toList())
                .containsExactly("bob");
        assertThat(bea.get("ref").get("created-by").asString()).isEqualTo("bea");

        assertThat(files.size()).as("a dry run writes no file").isEqualTo(written);
        assertThat(refs).containsKey(dev.id());
        assertThat(outbox).isEmpty();
        assertThat(denials()).isEmpty();
    }

    @Test
    @DisplayName("the dry run of a merge that would land answers 200 with main's new versions and what each needs")
    void theDryRunOfAMergeThatWouldLand() {
        Ref dev = branch("dev", staff, orders);
        onBranch(dev, orders, append(2, 1L, 2, 2));

        JsonNode dry = json(routes.merge("gate", "dev", true, null, http));

        assertThat(dry.get("answer").get("code").asInt()).isEqualTo(200);
        assertThat(dry.get("may-merge").asBoolean()).isTrue();
        assertThat(dry.get("tables")).singleElement().satisfies(table -> {
            assertThat(table.get("version").asLong()).isEqualTo(3);
            assertThat(table.get("verdict").get("allowed").asBoolean()).isTrue();
            assertThat(table.get("right").get("needs").asString()).isEqualTo("write");
        });
        assertThat(pointers.get(orders.mainKey()).version()).isEqualTo(2);
    }

    @Test
    @DisplayName("a tag never merges; a branch past nuna.refs.max-merge-tables answers 409 RefLimitException")
    void aTagAndTheCap() {
        Ref dev = branch("dev", staff, orders);
        onBranch(dev, orders, append(2, 1L, 2, 2));
        onBranch(dev, staff, append(2, 1L, 2, 2));
        Ref tag = new Ref(catalog.id(), UUID.randomUUID(), name("v1"), Ref.Kind.TAG, NOW, "bea", Optional.empty(),
                Ref.MAIN, Optional.empty());
        refs.put(tag.id(), tag);

        assertThat(answer(() -> routes.merge("gate", "v1", false, null, http)))
                .isEqualTo(new Answer(400, "BadRequestException", "Ref v1 is a tag; only a branch merges"));
        RefRoutes capped = routes(new RefSettings(50, 1000, Duration.ofDays(30), Duration.ofDays(180),
                Duration.ofDays(7), 1));
        Answer over = answer(() -> capped.merge("gate", "dev", false, null, http));
        assertThat(over).isEqualTo(new Answer(409, "RefLimitException", "Branch dev changed 2 tables and views, and"
                + " one merge moves at most 1 (nuna.refs.max-merge-tables)"));
        assertThat(json(capped.merge("gate", "dev", true, null, http)).get("answer").get("type").asString())
                .isEqualTo("RefLimitException");
        assertThat(refs).containsKey(dev.id());
    }

    // ---- a branch's own versions, as the console reads them (backlog 109) ----

    @Test
    @DisplayName("commits through the engine on a branch number its own versions from 1, whatever the cut's: the ref's"
            + " read counts them, and the metadata log through it lists them, each an update on the branch's line,"
            + " then main's at and below the cut, never main's since")
    void aBranchsOwnVersionsNumberFromOne() {
        onMain(orders, append(3, 1L, 2, 3));
        Ref dev = branch("dev", staff, orders);
        onMain(orders, append(4, 3L, 3, 2));
        onBranch(dev, orders, append(5, 3L, 3, 1));
        onBranch(dev, orders, append(6, 5L, 4, 1));

        Pointer own = pointers.get(dev.key(orders.id()));
        assertThat(own.version()).as("the engine's numbering, not a fixture's").isEqualTo(2);
        assertThat(pointers.get(orders.mainKey()).version()).isEqualTo(4);
        JsonNode changed = json(routes.read("gate", "dev", http)).get("changed");
        assertThat(changed).singleElement().satisfies(table -> {
            assertThat(table.get("cut-version").asLong()).isEqualTo(3);
            assertThat(table.get("version").asLong()).as("two commits on the branch").isEqualTo(2);
        });

        caller = REX;
        JsonNode log = Management.JSON.readTree(lifecycle().metadataLog("gate", orders.id().toString(), null, null,
                "dev", http).getBody());
        List<JsonNode> entries = log.get("journal").valueStream().toList();
        assertThat(entries).extracting(entry -> entry.get("branch").asString() + " " + entry.get("new-version")
                .asLong()).as("main's 4, made since the cut, is not the branch's; main's 1 was never journaled here")
                .containsExactly("dev 2", "dev 1", "main 3", "main 2");
        assertThat(entries.getFirst().get("new-location").asString()).isEqualTo(own.metadataLocation());
        assertThat(entries.subList(0, 2)).allSatisfy(entry -> {
            assertThat(entry.get("new-location").asString()).contains(TableFiles.BRANCH_MARK + dev.id());
            assertThat(entry.get("updates").isArray()).as("a commit on the branch is an update, not a create")
                    .isTrue();
            assertThat(entry.get("updates").get(0).get("action").asString()).isEqualTo("add-snapshot");
        });
        assertThat(entries.get(1).has("old-location")).as("the branch's first commit, from the cut's file").isFalse();
        assertThat(log.get("ref").get("cut-version").asLong()).isEqualTo(3);
        assertThat(log.get("ref").has("log-stops-at-cut")).isFalse();
    }

    // ---- the estate ----

    /** The metadata log's route over the same store. */
    private LifecycleRoutes lifecycle() {
        Separation separation = new Separation(Set.of("grant-admins"), Set.of("platform-ops"));
        Authorizer authorizer = new Authorizer(store, store, store, separation, false, Set.of());
        Denials denials = DenialsForTests.denials(audit::add, rest, clock);
        Holdings holdings = new Holdings(authorizer, store, rest, separation, denials);
        Management management = new Management(store, request -> caller, rest, holdings);
        SecuritySettings security = new SecuritySettings(List.of(new SecuritySettings.Issuer("https://idp.test",
                URI.create("https://idp.test/keys"), Set.of("nuna"), Set.of(), "sub", "groups", "azp")),
                Duration.ofSeconds(60), Set.of("grant-admins"), Set.of("platform-ops"), Set.of("nuna-maintenance"),
                false, Set.of());
        ManagementGuard guard = new ManagementGuard(authorizer, separation, security, denials, holdings);
        return new LifecycleRoutes(store, management, guard, null, null, null, null, clock);
    }

    private RefRoutes routes(RefSettings settings) {
        AuditPublisher publisher = audit::add;
        Separation separation = new Separation(Set.of("grant-admins"), Set.of("platform-ops"));
        Authorizer authorizer = new Authorizer(store, store, store, separation, false, Set.of());
        Denials denials = DenialsForTests.denials(publisher, rest, clock);
        Holdings holdings = new Holdings(authorizer, store, rest, separation, denials);
        Management management = new Management(store, request -> caller, rest, holdings);
        SecuritySettings security = new SecuritySettings(List.of(new SecuritySettings.Issuer("https://idp.test",
                URI.create("https://idp.test/keys"), Set.of("nuna"), Set.of(), "sub", "groups", "azp")),
                Duration.ofSeconds(60), Set.of("grant-admins"), Set.of("platform-ops"), Set.of("nuna-maintenance"),
                false, Set.of());
        ManagementGuard guard = new ManagementGuard(authorizer, separation, security, denials, holdings);
        CatalogNames names = new CatalogNames(null, null) {
            @Override
            String name(UUID id) {
                return "gate";
            }
        };
        return new RefRoutes(store, management, guard, new ManagementIdempotency(store, clock), authorizer, settings,
                names, event -> {
                    throw new AssertionError("a merge sends no notice");
                }, rest, (merging, events) -> engine(events).merging(), new AuditEvents(rest), clock);
    }

    private CommitEngine engine(CommitEvents events) {
        return new CommitEngine(store, tableFiles, new MetadataCache(1 << 22), rules, events, new Alerts() {
            @Override
            public void metadataIntegrity(UUID objectId, String location, String expectedSha256,
                                          String actualSha256) {
                throw new AssertionError("no file is tampered with here");
            }

            @Override
            public void regulatedSchemaChange(UUID objectId, UUID commitId) {
            }
        }, CommitSettings.DEFAULTS, clock, clock::advance);
    }

    /** A commit on main, by Bob. */
    private void onMain(CatalogObject object, String request) {
        success(engine(NO_EVENTS).commitTable(name(object), request, BOB, Optional.empty()));
    }

    /** A commit on the branch, by Bob. */
    private void onBranch(Ref ref, CatalogObject object, String request) {
        Map<ObjectName, RefEntry> ofCut = new HashMap<>();
        cuts.get(ref.id()).stream().filter(entry -> entry.kind() != RefEntry.Kind.NAMESPACE)
                .forEach(entry -> ofCut.put(new ObjectName(catalog.id(), entry.parentId(), entry.name()), entry));
        success(engine(NO_EVENTS).on(new CommitEngine.Branch(ref, 100, ofCut)).commitTable(name(object), request, BOB,
                Optional.empty()));
    }

    /** A branch made now, whose cut takes every namespace and main's pointer of each object, expiring in 30 days. */
    private Ref branch(String display, CatalogObject... cut) {
        Ref ref = new Ref(catalog.id(), UUID.randomUUID(), name(display), Ref.Kind.BRANCH, NOW, "bea",
                Optional.empty(), Ref.MAIN, Optional.of(NOW.plus(DAY.multipliedBy(30))));
        refs.put(ref.id(), ref);
        List<RefEntry> entries = new ArrayList<>();
        namespaces.values().forEach(ns -> entries.add(new RefEntry(ref.id(), ns.id(), RefEntry.Kind.NAMESPACE,
                ns.parentId(), ns.name(), Optional.empty())));
        for (CatalogObject object : cut) {
            Pointer at = pointers.get(object.mainKey());
            entries.add(new RefEntry(ref.id(), object.id(), RefEntry.of(object.kind()), object.namespaceId(),
                    object.name(), Optional.of(new RefEntry.At(at.version(), at.metadataLocation(), at.sha256()))));
        }
        cuts.put(ref.id(), entries);
        return ref;
    }

    private NamespaceRecord namespace(String display) {
        NamespaceRecord record = new NamespaceRecord(catalog.id(), UUID.randomUUID(), catalog.id(), name(display),
                Map.of(), 1);
        namespaces.put(record.id(), record);
        return record;
    }

    /** A format-version 2 table with one snapshot, main's, two days old: version 2. */
    private CatalogObject table(NamespaceRecord namespace, String display) {
        UUID id = UUID.randomUUID();
        CatalogObject table = CatalogObject.active(catalog.id(), id, namespace.id(), name(display),
                CatalogObject.Kind.TABLE, tableFiles.newLocation(id));
        String json = ((NewMetadata) Metadata.createTable("{\"name\":\"" + display + "\",\"location\":\"s3://elsewhere/"
                + display + "\",\"schema\":" + SCHEMA + ",\"properties\":{\"format-version\":\"2\"}}", id.toString(),
                table.locationRoot())).json();
        TableFiles.Written written = tableFiles.write(table.locationRoot(), 1, json);
        objects.put(id, table);
        pointers.put(table.mainKey(), new Pointer(written.location(), 1, written.sha256(), new byte[0]));
        onMain(table, append(1, null, 1, 48));
        return table;
    }

    private void grant(Securable on, String group, Privilege privilege) {
        grants.add(new GrantRecord(new Grant(catalog.id(), on, group, privilege.wireName()), "test", Instant.EPOCH));
    }

    /** An append of snapshot {@code id}, made main, stamped {@code hoursAgo} hours before now. */
    private static String append(long id, Long parent, long sequence, int hoursAgo) {
        return update("{\"action\":\"add-snapshot\",\"snapshot\":{\"snapshot-id\":" + id
                + (parent == null ? "" : ",\"parent-snapshot-id\":" + parent) + ",\"sequence-number\":" + sequence
                + ",\"timestamp-ms\":" + NOW.minus(Duration.ofHours(hoursAgo)).toEpochMilli()
                + ",\"manifest-list\":\"s3://lake/warehouse/manifests/snap-" + id + ".avro\""
                + ",\"summary\":{\"operation\":\"append\"},\"schema-id\":0}}",
                "{\"action\":\"set-snapshot-ref\",\"ref-name\":\"main\",\"type\":\"branch\",\"snapshot-id\":" + id + "}");
    }

    /** Adds an optional column and makes the new schema current. */
    private static String[] addColumn() {
        return new String[] {"{\"action\":\"add-schema\",\"schema\":{\"type\":\"struct\",\"schema-id\":1,\"fields\":["
                + "{\"id\":1,\"name\":\"id\",\"required\":true,\"type\":\"long\"},"
                + "{\"id\":2,\"name\":\"note\",\"required\":false,\"type\":\"string\"}]}}",
                "{\"action\":\"set-current-schema\",\"schema-id\":-1}"};
    }

    private static String update(String... updates) {
        return "{\"requirements\":[],\"updates\":[" + String.join(",", updates) + "]}";
    }

    private static void success(CommitReply reply) {
        assertThat(reply).isInstanceOf(CommitReply.Success.class);
    }

    private ObjectName name(CatalogObject object) {
        return new ObjectName(catalog.id(), object.namespaceId(), object.name());
    }

    private static Name name(String display) {
        return new Name(Names.normalize(display), display);
    }

    private static JsonNode table(JsonNode body, String name) {
        return body.get("tables").valueStream().filter(table -> table.get("name").asString().equals(name)).findFirst()
                .orElseThrow();
    }

    private static JsonNode json(ResponseEntity<String> response) {
        assertThat(response.getStatusCode().value()).as(response.getBody()).isEqualTo(200);
        return Management.JSON.readTree(response.getBody());
    }

    /** What a call answered: a response, a refusal thrown, or an error the handler answers. */
    private record Answer(int status, String type, String message) {
    }

    private static Answer answer(Callable<ResponseEntity<String>> call) {
        try {
            ResponseEntity<String> response = call.call();
            JsonNode error = Management.JSON.readTree(response.getBody()).get("error");
            return new Answer(response.getStatusCode().value(), error == null ? null : error.get("type").asString(),
                    error == null ? null : error.get("message").asString());
        } catch (CatalogError e) {
            return new Answer(e.status(), e.type(), e.getMessage());
        } catch (AccessDenied e) {
            return new Answer(403, "ForbiddenException", e.getMessage());
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private <T> List<T> events(Class<T> type) {
        return outbox.stream().map(CatalogEvent::data).filter(type::isInstance).map(type::cast).toList();
    }

    private List<Denial> denials() {
        return audit.stream().map(CatalogEvent::data).filter(Denial.class::isInstance).map(Denial.class::cast)
                .toList();
    }

    private static Caller person(String id, String... groups) {
        return new Caller(new Principal(id, Set.of(groups)), "console", false, Optional.empty());
    }

    // ---- the store: refs, pointers and keys as RefSuite, CommitSuite and IdempotencySuite have them ----

    private record Key(String principal, String hash, UUID changeId, Instant leaseUntil, Instant expiresAt,
                       IdempotencyResult result) {
    }

    private void write(List<OutboxEvent> events, Optional<IdempotencyResult> idempotency, UUID changeId) {
        idempotency.ifPresent(result -> {
            Key key = keys.get(result.key());
            assertThat(key).as("a change completes only its own claim").isNotNull();
            assertThat(key.changeId()).isEqualTo(changeId);
            keys.put(result.key(), new Key(key.principal(), key.hash(), key.changeId(), key.leaseUntil(),
                    key.expiresAt(), result));
        });
        for (OutboxEvent event : events) {
            outbox.add(EventCodec.decode(event.payloadJson().getBytes(StandardCharsets.UTF_8)));
        }
    }

    /**
     * A batch is judged as the stores judge it: the branch it holds or ends must not have ended; a merge's must hold
     * every own pointer of the branch; every object it swaps must be active; every pointer it swaps or holds must be as
     * it expects. A merge's batch then ends the branch.
     */
    private CommitResult commit(CommitBatch batch) {
        PointerKey anyKey = batch.swaps().getFirst().key();
        if (batch.ref().isPresent()) {
            RefLock lock = batch.ref().get();
            Ref ref = refs.get(lock.refId());
            if (ref == null || ref.expiredAt(batch.committedAt())) {
                return new CommitResult.Inactive(anyKey, CommitResult.Subject.REF);
            }
            if (lock instanceof RefLock.End) {
                List<PointerKey> held = batch.holds().stream().map(PointerHold::key).toList();
                Optional<PointerKey> unheld = pointers.keySet().stream().filter(lock::owns)
                        .filter(key -> !held.contains(key)).findFirst();
                if (unheld.isPresent()) {
                    return new CommitResult.Conflict(unheld.get(), Optional.of(pointers.get(unheld.get())));
                }
            }
        }
        for (PointerSwap swap : batch.swaps()) {
            CatalogObject object = objects.get(swap.key().objectId());
            if (object == null || object.state() != CatalogObject.State.ACTIVE) {
                return new CommitResult.Inactive(swap.key(), CommitResult.Subject.OBJECT);
            }
            Optional<Pointer> actual = Optional.ofNullable(pointers.get(swap.key()));
            if (!same(actual, swap.expected())) {
                return new CommitResult.Conflict(swap.key(), actual);
            }
        }
        for (PointerHold hold : batch.holds()) {
            Optional<Pointer> actual = Optional.ofNullable(pointers.get(hold.key()));
            if (!same(actual, Optional.of(hold.expected()))) {
                return new CommitResult.Conflict(hold.key(), actual);
            }
        }
        for (PointerSwap swap : batch.swaps()) {
            Optional<Pointer> before = Optional.ofNullable(pointers.get(swap.key()));
            pointers.put(swap.key(), swap.next());
            journal.add(new JournalRecord(batch.commitId(), swap.key(), batch.committedAt(), swap.journal(),
                    before.map(Pointer::metadataLocation), swap.next().metadataLocation(), swap.next().version()));
        }
        if (batch.ref().orElse(null) instanceof RefLock.End end) {
            refs.remove(end.refId());
            cuts.remove(end.refId());
            pointers.keySet().removeIf(key -> key.branch().equals(end.refId().toString()));
        }
        commits.add(batch.commitId());
        write(batch.outbox(), batch.idempotency(), batch.commitId());
        return new CommitResult.Committed(batch.commitId());
    }

    private static boolean same(Optional<Pointer> actual, Optional<Pointer> expected) {
        return actual.map(Pointer::metadataLocation).equals(expected.map(Pointer::metadataLocation))
                && actual.map(Pointer::version).equals(expected.map(Pointer::version));
    }

    private KeyState claimKey(IdempotencyClaim claim) {
        Key key = keys.get(claim.key());
        if (key != null && key.expiresAt().isAfter(claim.now())) {
            if (!key.principal().equals(claim.principal()) || !key.hash().equals(claim.requestHash())) {
                return new KeyState.Mismatch();
            }
            if (key.result() != null) {
                return new KeyState.Done(key.result());
            }
            if (key.leaseUntil().isAfter(claim.now())) {
                return new KeyState.InFlight(key.changeId(), key.leaseUntil());
            }
        }
        keys.put(claim.key(), new Key(claim.principal(), claim.requestHash(), claim.changeId(), claim.leaseUntil(),
                claim.expiresAt(), null));
        return new KeyState.Claimed();
    }

    private WriteResult completeKey(IdempotencyResult result, UUID changeId) {
        Key key = keys.get(result.key());
        if (key == null || !key.changeId().equals(changeId) || key.result() != null) {
            return new WriteResult.Refused(WriteResult.Reason.NOT_HOLDER);
        }
        keys.put(result.key(), new Key(key.principal(), key.hash(), changeId, key.leaseUntil(), key.expiresAt(),
                result));
        return WriteResult.APPLIED;
    }

    private WriteResult releaseKey(String name, UUID changeId) {
        Key key = keys.get(name);
        if (key == null || !key.changeId().equals(changeId) || key.result() != null) {
            return new WriteResult.Refused(WriteResult.Reason.NOT_HOLDER);
        }
        keys.remove(name);
        return WriteResult.APPLIED;
    }

    private Page<RefEntry> entries(UUID refId, UUID parentId, RefEntry.Kind kind) {
        return new Page<>(cuts.getOrDefault(refId, List.of()).stream()
                .filter(entry -> entry.parentId().equals(parentId) && entry.kind() == kind).toList(), Optional.empty());
    }

    @SuppressWarnings("unchecked")
    private CatalogStore store() {
        return (CatalogStore) Proxy.newProxyInstance(CatalogStore.class.getClassLoader(),
                new Class<?>[] {CatalogStore.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "findCatalog" -> Optional.of(catalog).filter(c -> c.normalizedName().equals(args[0]));
                    case "readNamespace" -> Optional.ofNullable(namespaces.get((UUID) args[0]));
                    case "readObject" -> Optional.ofNullable(objects.get((UUID) args[0]));
                    case "findObject" -> objects.values().stream().filter(o -> o.state() == CatalogObject.State.ACTIVE
                            && o.namespaceId().equals(args[0]) && o.name().normalized().equals(args[1])).findFirst();
                    case "grantsFor" -> grants.stream()
                            .filter(g -> ((Set<Securable>) args[0]).contains(g.grant().securable())
                                    && ((Set<String>) args[1]).contains(g.grant().group()))
                            .toList();
                    case "listGrantsOfGroups" -> new Page<>(grants.stream()
                            .filter(g -> g.grant().catalogId().equals(args[0])
                                    && ((Set<String>) args[1]).contains(g.grant().group()))
                            .toList(), Optional.empty());
                    case "attributesOf" -> attributes.stream()
                            .filter(a -> ((Set<Securable>) args[0]).contains(a.target())).toList();
                    case "holdsOn" -> List.of();
                    case "readRef" -> Optional.ofNullable(refs.get((UUID) args[0]));
                    case "findRef" -> refs.values().stream().filter(ref -> ref.catalogId().equals(args[0])
                            && ref.name().normalized().equals(args[1])).findFirst();
                    case "deleteRef" -> {
                        Change change = (Change) args[1];
                        if (refs.remove((UUID) args[0]) == null) {
                            yield new WriteResult.Missing(WriteResult.Subject.REF);
                        }
                        cuts.remove((UUID) args[0]);
                        write(change.outbox(), change.idempotency(), change.id());
                        yield WriteResult.APPLIED;
                    }
                    case "countOwnPointers" -> pointers.keySet().stream()
                            .filter(key -> key.branch().equals(args[0].toString())).count();
                    case "listRefNamespaces" -> entries((UUID) args[0], (UUID) args[1], RefEntry.Kind.NAMESPACE);
                    case "listRefPointers" -> refs.values().stream().flatMap(ref -> cuts.getOrDefault(ref.id(),
                            List.of()).stream().filter(entry -> entry.id().equals(args[0])).map(entry ->
                            new RefPointers(ref, entry.pointer().orElseThrow(), Optional.ofNullable(pointers.get(
                                    ref.key(entry.id()))).map(own -> new RefEntry.At(own.version(),
                                    own.metadataLocation(), own.sha256()))))).toList();
                    case "listRefObjects" -> entries((UUID) args[0], (UUID) args[1],
                            RefEntry.of((CatalogObject.Kind) args[2]));
                    case "readPointer" -> Optional.ofNullable(pointers.get((PointerKey) args[0]));
                    case "readJournal" -> {
                        long after = Long.parseLong(PageToken.decode(((PageRequest) args[3]).token().orElseThrow(), 1)
                                .getFirst());
                        yield new Page<>(journal.stream().filter(r -> r.key().equals(args[0])
                                && r.newVersion() > after).limit(((PageRequest) args[3]).size()).toList(),
                                Optional.empty());
                    }
                    case "commit" -> commit((CommitBatch) args[0]);
                    case "findCommit" -> commits.contains((UUID) args[0]) ? CommitLookup.COMMITTED
                            : CommitLookup.NOT_COMMITTED;
                    case "claimKey" -> claimKey((IdempotencyClaim) args[0]);
                    case "completeKey" -> completeKey((IdempotencyResult) args[0], (UUID) args[1]);
                    case "releaseKey" -> releaseKey((String) args[0], (UUID) args[1]);
                    case "toString" -> "the test's store";
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    /** Metadata files in memory, laid out as catalog-objectstore lays them out in a bucket. */
    private final class MemoryFiles implements TableFiles {

        @Override
        public String newLocation(UUID objectId) {
            return "s3://lake/warehouse/" + objectId;
        }

        @Override
        public Written write(String locationRoot, long version, String json, Optional<UUID> branch) {
            String location = locationRoot + "/metadata/" + TableFiles.fileName(version, branch);
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            files.put(location, bytes);
            return new Written(location, sha256(bytes));
        }

        @Override
        public Read read(String location, String expectedSha256) {
            byte[] bytes = files.get(location);
            String actual = sha256(bytes);
            return new Read(new String(bytes, StandardCharsets.UTF_8), actual, actual.equals(expectedSha256));
        }

        @Override
        public boolean isWithin(String location, String root) {
            return location.startsWith(root + "/");
        }

        @Override
        public List<String> metadataFiles(String locationRoot) {
            return files.keySet().stream().filter(location -> location.startsWith(locationRoot + "/metadata/"))
                    .toList();
        }

        @Override
        public List<String> tableLocations() {
            return List.of();
        }

        @Override
        public boolean exists(String location) {
            return files.containsKey(location);
        }

        @Override
        public byte[] bytes(String location) {
            return files.get(location);
        }

        private static String sha256(byte[] bytes) {
            try {
                return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    /** A clock the test moves, as the commit engine's back-off does. */
    private static final class MutableClock extends Clock {

        private volatile Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}
