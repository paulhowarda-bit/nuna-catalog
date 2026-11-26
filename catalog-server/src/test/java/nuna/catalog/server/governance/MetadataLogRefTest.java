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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import nuna.catalog.audit.CatalogEvent;
import nuna.catalog.audit.ObjectRead;
import nuna.catalog.audit.PointerState;
import nuna.catalog.audit.publish.EventPublisher;
import nuna.catalog.core.CatalogError;
import nuna.catalog.core.commit.TableFiles;
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
 * its own come from its own journal, numbered from 1 as the store numbers them (item 105), and then main's, at and below
 * the cut, never main's commits since; through a tag, main's log as the cut left it; through a ref made from a branch,
 * that branch's own versions up to its cut, then main's; through a ref made from one that has ended, the log stops at
 * the cut unless main wrote it. Two lines hold the same version numbers, so each entry names its line and a page token
 * names the line it stopped in. The export through a ref (backlog 172) is the ref's file, checked against its hash, and
 * the same log, oldest first, audited through the ref. The routes, guard and authorizer are the real ones over a store
 * double and metadata files in memory, so this needs only Java; {@code RefMergeRoutesTest} reads the log of commits the
 * real commit engine made on a branch.
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
    /** Staff as main has it now: active, unless a test drops it. */
    private CatalogObject staffNow = staff;
    private final List<GrantRecord> grants = List.of(new GrantRecord(new Grant(catalog.id(),
            new Securable(Securable.Kind.NAMESPACE, hr.id()), "readers", Privilege.SELECT.wireName()), "test",
            Instant.EPOCH));
    /** Made when main was at version 3; it committed its own versions 1 to 3. Main went on to 5. */
    private final Ref dev = ref("dev", Ref.Kind.BRANCH);
    /** Made when main was at version 3. */
    private final Ref v1 = ref("v1", Ref.Kind.TAG);
    /** Its cut does not hold staff, as for a table made after it. */
    private final Ref later = ref("later", Ref.Kind.BRANCH);
    /** Made from dev when dev was at its own version 3, while main was at 5: the same number as dev's cut. */
    private final Ref v2 = ref("v2", Ref.Kind.TAG, dev);
    /** Made from dev when dev was at its own version 3; it committed its own versions 1 and 2. */
    private final Ref dev2 = ref("dev2", Ref.Kind.BRANCH, dev);
    /** Made from a branch that has since ended, at the branch's own version 2: main's 2 is another file. */
    private final Ref orphan = ref("orphan", Ref.Kind.TAG, ref("old", Ref.Kind.BRANCH));
    /** Made from a branch that has since ended, which had not changed staff: its cut is main's version 3. */
    private final Ref kept = ref("kept", Ref.Kind.TAG, ref("older", Ref.Kind.BRANCH));
    /** Made when main was at version 3; it has not changed staff. */
    private final Ref quiet = ref("quiet", Ref.Kind.BRANCH);
    /** Made from quiet, which had no pointer of its own for staff: its cut is quiet's, main's version 3. */
    private final Ref hush = ref("hush", Ref.Kind.TAG, quiet);
    private final Map<PointerKey, List<JournalRecord>> journals = new HashMap<>();
    private final List<CatalogEvent> audit = new ArrayList<>();
    private final Set<String> tampered = new HashSet<>();

    private final LifecycleRoutes lifecycle;
    private final MockHttpServletRequest http = new MockHttpServletRequest();

    MetadataLogRefTest() {
        http.setAttribute(RequestIds.class.getName(), "request-1");
        journals.put(staff.mainKey(), journal(staff.mainKey(), "main", 1, 5));
        journals.put(dev.key(staffId), journal(dev.key(staffId), "dev", 1, 3));
        journals.put(dev2.key(staffId), journal(dev2.key(staffId), "dev2", 1, 2));
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
                false, Set.of());
        ManagementGuard guard = new ManagementGuard(authorizer, separation, security, denials, holdings);
        AuditTrail trail = new AuditTrail(Optional.<EventPublisher>of(stamped -> audit.add(stamped.event())),
                URI.create("urn:nuna:test"), clock, Duration.ofMinutes(1));
        // No governance settings or catalog names: the log and the export need neither. Each metadata file holds its
        // own location, and is intact unless the test tampers with it.
        lifecycle = new LifecycleRoutes(store, management, guard, null, trail, null, clock,
                (catalogId, location, sha256) -> new TableFiles.Read("{\"file\":\"" + location + "\"}",
                        "sha-" + location, !tampered.contains(location) && sha256.equals("sha-" + location)));
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

        assertThat(log.get("pointer").get("version").asLong()).isEqualTo(3);
        assertThat(log.get("pointer").get("metadata-location").asString()).isEqualTo("dev-3");
        assertThat(log.get("ref").get("name").asString()).isEqualTo("dev");
        assertThat(log.get("ref").get("kind").asString()).isEqualTo("branch");
        assertThat(log.get("ref").get("cut-version").asLong()).isEqualTo(3);
        assertThat(locations(log)).as("main's 4 and 5, made since the cut, are not the branch's")
                .containsExactly("dev-3", "dev-2", "dev-1", "main-3", "main-2", "main-1");
        assertThat(versions(log)).containsExactly(3L, 2L, 1L, 3L, 2L, 1L);
        assertThat(lines(log)).containsExactly("dev", "dev", "dev", "main", "main", "main");
    }

    @Test
    void throughABranchThePagesCrossTheCutWithoutAGapOrARepeat() {
        for (int size = 1; size <= 7; size++) {
            List<String> read = new ArrayList<>();
            String token = null;
            int pages = 0;
            do {
                JsonNode log = log("dev", token, size);
                read.addAll(locations(log));
                token = log.has("next-page-token") ? log.get("next-page-token").asString() : null;
                pages++;
                // The log has six lines, so a page that repeats one ends the reading here instead of never.
                assertThat(read).as("pages of " + size + ", read so far").hasSizeLessThanOrEqualTo(6);
            } while (token != null);

            assertThat(read).as("pages of " + size + ": the branch's 3 to 1, then main's 3 to 1, each once")
                    .containsExactly("dev-3", "dev-2", "dev-1", "main-3", "main-2", "main-1");
            assertThat(pages).isEqualTo((6 + size - 1) / size);
        }
    }

    @Test
    void aPageTokenNamesItsLineAndMainsTokenIsNotOne() {
        JsonNode first = log("dev", null, 4);
        String token = first.get("next-page-token").asString();

        assertThat(PageToken.decode(token, 2).getFirst()).as("main's line, by its key").isEqualTo(PointerKey.MAIN);
        assertThat(locations(log("dev", token, 4))).containsExactly("main-2", "main-1");
        String mains = log(null, null, 2).get("next-page-token").asString();
        assertThatThrownBy(() -> log("dev", mains, 4)).as("a version alone cannot say which line")
                .isInstanceOfSatisfying(CatalogError.class, e -> assertThat(e.status()).isEqualTo(400));
        assertThat(locations(log("dev", PageToken.encode(UUID.randomUUID().toString(), "2"), 4)))
                .as("a line the log no longer reads, as when the branch it named has ended").isEmpty();
    }

    @Test
    void throughATagTheLogIsMainsAsTheCutLeftIt() {
        JsonNode log = log("v1", null, null);

        assertThat(log.get("pointer").get("version").asLong()).isEqualTo(3);
        assertThat(log.get("ref").get("kind").asString()).isEqualTo("tag");
        assertThat(locations(log)).containsExactly("main-3", "main-2", "main-1");
    }

    @Test
    void throughARefMadeFromABranchTheVersionsBelowItsCutAreTheBranchsThenMains() {
        JsonNode tag = log("v2", null, null);

        assertThat(tag.get("pointer").get("metadata-location").asString()).isEqualTo("dev-3");
        assertThat(tag.get("ref").get("cut-version").asLong()).isEqualTo(3);
        assertThat(locations(tag)).as("dev's 3 to 1, not main's of the same numbers, then main's at dev's cut")
                .containsExactly("dev-3", "dev-2", "dev-1", "main-3", "main-2", "main-1");
        assertThat(lines(tag)).containsExactly("dev", "dev", "dev", "main", "main", "main");
        assertThat(tag.get("ref").has("log-stops-at-cut")).isFalse();

        List<String> read = new ArrayList<>();
        String token = null;
        do {
            JsonNode log = log("dev2", token, 2);
            read.addAll(locations(log));
            token = log.has("next-page-token") ? log.get("next-page-token").asString() : null;
            assertThat(read).as("read so far").hasSizeLessThanOrEqualTo(8);
        } while (token != null);
        assertThat(read).as("its own, then dev's, then main's, across pages")
                .containsExactly("dev2-2", "dev2-1", "dev-3", "dev-2", "dev-1", "main-3", "main-2", "main-1");

        JsonNode hushed = log("hush", null, null);
        assertThat(locations(hushed)).as("quiet had none of its own, so its cut, main's, is the tag's")
                .containsExactly("main-3", "main-2", "main-1");
        assertThat(hushed.get("ref").has("log-stops-at-cut")).isFalse();
    }

    @Test
    void throughARefMadeFromOneThatHasEndedTheLogStopsAtTheCutUnlessMainWroteIt() {
        JsonNode orphaned = log("orphan", null, null);

        assertThat(orphaned.get("pointer").get("metadata-location").asString()).isEqualTo("old-2");
        assertThat(locations(orphaned)).as("main's 2 is not the file the cut took").isEmpty();
        assertThat(orphaned.get("ref").get("log-stops-at-cut").asBoolean()).isTrue();
        assertThat(orphaned.has("next-page-token")).isFalse();

        JsonNode kept = log("kept", null, null);
        assertThat(locations(kept)).containsExactly("main-3", "main-2", "main-1");
        assertThat(kept.get("ref").has("log-stops-at-cut")).isFalse();
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

    // ---- the export through a ref (backlog 172) ----

    @Test
    void anExportThroughABranchIsItsHeadAndTheLogAsTheBranchReadsItOldestFirstAuditedThroughIt() {
        JsonNode export = export("DEV");

        assertThat(export.get("pointer").get("metadata-location").asString()).isEqualTo("dev-3");
        assertThat(export.get("pointer").get("version").asLong()).isEqualTo(3);
        assertThat(export.get("metadata").get("file").asString()).as("the branch's file, not main's")
                .isEqualTo("dev-3");
        assertThat(export.get("ref").get("name").asString()).isEqualTo("dev");
        assertThat(export.get("ref").get("kind").asString()).isEqualTo("branch");
        assertThat(export.get("ref").get("cut-version").asLong()).isEqualTo(3);
        assertThat(export.get("ref").has("log-stops-at-cut")).isFalse();
        assertThat(locations(export)).as("main's 4 and 5, made since the cut, are not the branch's")
                .containsExactly("main-1", "main-2", "main-3", "dev-1", "dev-2", "dev-3");
        assertThat(lines(export)).containsExactly("main", "main", "main", "dev", "dev", "dev");
        assertThat(reads()).singleElement().satisfies(read -> {
            assertThat(read.branch()).isEqualTo("dev");
            assertThat(read.served()).isEqualTo(new PointerState("dev-3", 3, "sha-dev-3"));
            assertThat(read.object().id()).isEqualTo(staffId);
        });
    }

    @Test
    void anExportThroughATagOrARefMadeFromABranchIsWhatTheLogReadsThroughIt() {
        JsonNode tag = export("v1");
        assertThat(tag.get("pointer").get("metadata-location").asString()).isEqualTo("main-3");
        assertThat(tag.get("ref").get("kind").asString()).isEqualTo("tag");
        assertThat(locations(tag)).containsExactly("main-1", "main-2", "main-3");

        JsonNode branched = export("dev2");
        assertThat(branched.get("pointer").get("metadata-location").asString()).isEqualTo("dev2-2");
        assertThat(locations(branched)).as("main's at dev's cut, then dev's to dev2's cut, then dev2's own")
                .containsExactly("main-1", "main-2", "main-3", "dev-1", "dev-2", "dev-3", "dev2-1", "dev2-2");
        assertThat(lines(branched)).containsExactly("main", "main", "main", "dev", "dev", "dev", "dev2", "dev2");

        JsonNode orphaned = export("orphan");
        assertThat(orphaned.get("pointer").get("metadata-location").asString()).isEqualTo("old-2");
        assertThat(orphaned.get("ref").get("log-stops-at-cut").asBoolean()).isTrue();
        assertThat(locations(orphaned)).as("main's 2 is not the file the cut took").isEmpty();
        assertThat(reads()).extracting(ObjectRead::branch).containsExactly("v1", "dev2", "orphan");
    }

    @Test
    void aTableMainHasDroppedSinceIsExportedThroughTheBranchByItsId() {
        staffNow = new CatalogObject(catalog.id(), staffId, hr.id(), staff.name(), CatalogObject.Kind.TABLE,
                CatalogObject.State.DROPPED, Optional.of(NOW.minus(Duration.ofHours(1))),
                Optional.of(NOW.plus(Duration.ofDays(7))), staff.locationRoot(), Optional.empty());

        JsonNode export = export("dev");

        assertThat(export.get("object").get("state").asString()).isEqualTo("dropped");
        assertThat(export.get("pointer").get("metadata-location").asString()).isEqualTo("dev-3");
        assertThat(locations(export)).containsExactly("main-1", "main-2", "main-3", "dev-1", "dev-2", "dev-3");
    }

    @Test
    void anExportOnMainIsMainsWhateverTheRefsDid() {
        JsonNode export = export(null);

        assertThat(export.get("pointer").get("metadata-location").asString()).isEqualTo("main-5");
        assertThat(export.get("metadata").get("file").asString()).isEqualTo("main-5");
        assertThat(export.has("ref")).isFalse();
        assertThat(locations(export)).containsExactly("main-1", "main-2", "main-3", "main-4", "main-5");
        assertThat(reads()).extracting(ObjectRead::branch).containsExactly(PointerKey.MAIN);
    }

    @Test
    void aRefsFileThatDoesNotMatchItsHashIsNotExported() {
        tampered.add("dev-3");

        assertThatThrownBy(() -> export("dev")).isInstanceOfSatisfying(CatalogError.class, e -> {
            assertThat(e.status()).isEqualTo(500);
            assertThat(e.type()).isEqualTo("MetadataIntegrityException");
        });
        assertThat(reads()).isEmpty();
        assertThat(export(null).get("pointer").get("metadata-location").asString())
                .as("main's file is another, and intact").isEqualTo("main-5");
    }

    @Test
    void anExportThroughARefThatIsNotLiveOrDoesNotHoldTheTableAnswers404() {
        assertThatThrownBy(() -> export("nope")).isInstanceOfSatisfying(CatalogError.class, e -> {
            assertThat(e.status()).isEqualTo(404);
            assertThat(e.type()).isEqualTo("NoSuchRefException");
        });
        assertThatThrownBy(() -> export("later")).isInstanceOfSatisfying(CatalogError.class, e -> {
            assertThat(e.status()).isEqualTo(404);
            assertThat(e.type()).isEqualTo("NoSuchObjectException");
        });
        assertThat(reads()).isEmpty();
    }

    private JsonNode export(String ref) {
        return Management.JSON.readTree(lifecycle.export("gate", staffId.toString(), ref, http).getBody());
    }

    private List<ObjectRead> reads() {
        return audit.stream().map(CatalogEvent::data).filter(ObjectRead.class::isInstance).map(ObjectRead.class::cast)
                .toList();
    }

    private JsonNode log(String ref, String token, Integer size) {
        return Management.JSON.readTree(lifecycle.metadataLog("gate", staffId.toString(), token, size, ref, http)
                .getBody());
    }

    private static List<String> locations(JsonNode log) {
        return log.get("journal").valueStream().map(entry -> entry.get("new-location").asString()).toList();
    }

    private static List<Long> versions(JsonNode log) {
        return log.get("journal").valueStream().map(entry -> entry.get("new-version").asLong()).toList();
    }

    private static List<String> lines(JsonNode log) {
        return log.get("journal").valueStream().map(entry -> entry.get("branch").asString()).toList();
    }

    private Ref ref(String name, Ref.Kind kind) {
        return ref(name, kind, null);
    }

    /** A ref made from {@code from}, or from main when it is null. */
    private Ref ref(String name, Ref.Kind kind, Ref from) {
        return new Ref(catalog.id(), UUID.randomUUID(), new Name(name, name), kind, NOW.minus(Duration.ofDays(1)),
                "bea", Optional.ofNullable(from).map(Ref::id), from == null ? Ref.MAIN : from.name().display(),
                kind == Ref.Kind.BRANCH ? Optional.of(NOW.plus(Duration.ofDays(29))) : Optional.empty(),
                Optional.empty());
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
        RefEntry.At devs = new RefEntry.At(3, "dev-3", "sha-dev-3");
        return objectId.equals(staffId)
                ? List.of(new RefPointers(dev, cut, Optional.of(devs)), new RefPointers(v1, cut, Optional.empty()),
                        new RefPointers(v2, devs, Optional.empty()),
                        new RefPointers(dev2, devs, Optional.of(new RefEntry.At(2, "dev2-2", "sha-dev2-2"))),
                        new RefPointers(orphan, new RefEntry.At(2, "old-2", "sha-old-2"), Optional.empty()),
                        new RefPointers(kept, cut, Optional.empty()), new RefPointers(quiet, cut, Optional.empty()),
                        new RefPointers(hush, cut, Optional.empty()))
                : List.of();
    }

    /** The catalog gate, its namespace hr, the table staff, the refs dev, v1 and later, and the journals. */
    @SuppressWarnings("unchecked")
    private CatalogStore store() {
        return (CatalogStore) Proxy.newProxyInstance(CatalogStore.class.getClassLoader(),
                new Class<?>[] {CatalogStore.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "findCatalog" -> Optional.of(catalog).filter(c -> c.normalizedName().equals(args[0]));
                    case "readNamespace" -> Optional.of(hr).filter(ns -> ns.id().equals(args[0]));
                    case "readObject" -> Optional.of(staffNow).filter(o -> o.id().equals(args[0]));
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
                    case "findRef" -> List.of(dev, v1, later, v2, dev2, orphan, kept, quiet, hush).stream()
                            .filter(r -> r.catalogId().equals(args[0]) && r.name().normalized().equals(args[1]))
                            .findFirst();
                    case "listRefPointers" -> refPointers((UUID) args[0]);
                    case "readJournal" -> readJournal((PointerKey) args[0], (PageRequest) args[3]);
                    case "toString" -> "the test's store";
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }
}
