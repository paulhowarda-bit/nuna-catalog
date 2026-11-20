package nuna.catalog.store.oracle;

import static nuna.catalog.store.suite.Commits.advance;
import static nuna.catalog.store.suite.Commits.batch;
import static nuna.catalog.store.suite.Commits.before;
import static nuna.catalog.store.suite.Commits.fullBatch;
import static nuna.catalog.store.suite.Commits.newKey;
import static nuna.catalog.store.suite.Commits.next;
import static nuna.catalog.store.suite.Commits.pointer;
import static nuna.catalog.store.suite.Commits.swap;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import nuna.catalog.store.CatalogObject;
import nuna.catalog.store.CatalogRecord;
import nuna.catalog.store.CatalogSettings;
import nuna.catalog.store.CatalogStore;
import nuna.catalog.store.Change;
import nuna.catalog.store.CommitBatch;
import nuna.catalog.store.CommitHook;
import nuna.catalog.store.CommitLookup;
import nuna.catalog.store.CommitOutcomeUnknownException;
import nuna.catalog.store.CommitResult;
import nuna.catalog.store.CommitStore;
import nuna.catalog.store.GovernanceAttribute;
import nuna.catalog.store.Grant;
import nuna.catalog.store.GrantRecord;
import nuna.catalog.store.IdempotencyClaim;
import nuna.catalog.store.IdempotencyResult;
import nuna.catalog.store.JournalEntry;
import nuna.catalog.store.JournalRecord;
import nuna.catalog.store.KeyState;
import nuna.catalog.store.LegalHold;
import nuna.catalog.store.NamespaceRecord;
import nuna.catalog.store.ObjectCreation;
import nuna.catalog.store.OutboxEvent;
import nuna.catalog.store.PageRequest;
import nuna.catalog.store.Pointer;
import nuna.catalog.store.PointerKey;
import nuna.catalog.store.PointerSwap;
import nuna.catalog.store.SafetyFinding;
import nuna.catalog.store.Securable;
import nuna.catalog.store.StorageProfile;
import nuna.catalog.store.TrustedEngine;
import nuna.catalog.store.suite.CatalogObjectSuite;
import nuna.catalog.store.suite.CatalogStoreUnderTest;
import nuna.catalog.store.suite.Changes;
import nuna.catalog.store.suite.CommitSuite;
import nuna.catalog.store.suite.GovernanceSuite;
import nuna.catalog.store.suite.HealthSuite;
import nuna.catalog.store.suite.GrantSuite;
import nuna.catalog.store.suite.GroupSuite;
import nuna.catalog.store.suite.IdempotencySuite;
import nuna.catalog.store.suite.JournalSuite;
import nuna.catalog.store.suite.MutationSuite;
import nuna.catalog.store.suite.NamespaceSuite;
import nuna.catalog.store.suite.OutboxSuite;
import nuna.catalog.store.suite.SizeSuite;
import nuna.catalog.store.suite.StoreUnderTest;
import nuna.catalog.store.suite.UpgradeSuite;
import nuna.catalog.store.suite.WrittenRows;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The shared suite against Oracle AI Database Free with Transaction Guard: the commit suite, every
 * group suite as a nested class on the same backend, and Oracle-only hazards.
 */
@Tag("heavy")
class OracleStoreSuiteTest extends CommitSuite {

    /** Shorter than the link's read timeout: the client still gets its answer. */
    private static final Duration COMMIT_DELAY = Duration.ofSeconds(3);
    /** Longer than the link's read timeout: the client gives up while the COMMIT is still on its way. */
    private static final Duration LATE_COMMIT_DELAY = Duration.ofSeconds(OracleFree.READ_TIMEOUT_SECONDS + 3);

    private OracleFree oracle;

    @Override
    protected StoreUnderTest storeUnderTest() {
        oracle = new OracleFree();
        return oracle;
    }

    @Nested
    class Namespaces extends NamespaceSuite {
        @Override
        protected CatalogStoreUnderTest underTest() {
            return oracle;
        }
    }

    @Nested
    class CatalogObjects extends CatalogObjectSuite {
        @Override
        protected CatalogStoreUnderTest underTest() {
            return oracle;
        }
    }

    @Nested
    class Grants extends GrantSuite {
        @Override
        protected CatalogStoreUnderTest underTest() {
            return oracle;
        }
    }

    @Nested
    class Idempotency extends IdempotencySuite {
        @Override
        protected CatalogStoreUnderTest underTest() {
            return oracle;
        }
    }

    @Nested
    class Outbox extends OutboxSuite {
        @Override
        protected CatalogStoreUnderTest underTest() {
            return oracle;
        }
    }

    @Nested
    class Governance extends GovernanceSuite {
        @Override
        protected CatalogStoreUnderTest underTest() {
            return oracle;
        }
    }

    @Nested
    class Health extends HealthSuite {
        @Override
        protected CatalogStoreUnderTest underTest() {
            return oracle;
        }
    }

    @Nested
    class Journal extends JournalSuite {
        @Override
        protected CatalogStoreUnderTest underTest() {
            return oracle;
        }
    }

    @Nested
    class Size extends SizeSuite {
        @Override
        protected CatalogStoreUnderTest underTest() {
            return oracle;
        }
    }

    @Nested
    class Upgrade extends UpgradeSuite {
        @Override
        protected CatalogStoreUnderTest underTest() {
            return oracle;
        }
    }

    @Nested
    class Mutations extends MutationSuite {
        @Override
        protected CatalogStoreUnderTest underTest() {
            return oracle;
        }
    }

    /** Oracle-only hazards of the whole contract. */
    @Nested
    class OracleOnly extends GroupSuite {

        @Override
        protected CatalogStoreUnderTest underTest() {
            return oracle;
        }

        @Test
        @DisplayName("Oracle: Transaction Guard answers NOT_COMMITTED for an object create whose COMMIT is still on its"
                + " way, and that COMMIT then cannot land")
        void transactionGuardBlocksACreateStillOnItsWay() {
            NamespaceRecord namespace = newNamespace(newCatalog(), "ns");
            ObjectCreation creation = Changes.creation(CatalogObject.active(namespace.catalogId(), UUID.randomUUID(),
                    namespace.id(), Changes.name("too-late"), CatalogObject.Kind.TABLE, "s3://suite-warehouse/too-late"));
            long[] sentAt = new long[1];

            // The link holds the COMMIT longer than the client waits for its answer.
            CatalogStore delaying = oracle.store(before(creation.firstCommit(), () -> {
                oracle.delayRequests(LATE_COMMIT_DELAY);
                sentAt[0] = System.nanoTime();
            }));
            assertThatThrownBy(() -> delaying.createObject(creation)).isInstanceOf(CommitOutcomeUnknownException.class);
            CatalogStore sameProcess = oracle.storeAroundTheLink();
            assertThat(sameProcess.findCommit(creation.firstCommit().commitId(), creation.object().mainKey()))
                    .isEqualTo(CommitLookup.NOT_COMMITTED);

            Sql.pause(Duration.ofNanos(sentAt[0] + LATE_COMMIT_DELAY.plusSeconds(3).toNanos() - System.nanoTime()));
            assertThat(sameProcess.readObject(creation.object().id())).as("the held COMMIT reached the database and"
                    + " was refused").isEmpty();
            assertThat(sameProcess.readPointer(creation.object().mainKey())).isEmpty();
        }

        @Test
        @DisplayName("Oracle: empty strings, and strings that start with NUL, round-trip though Oracle stores '' as NULL")
        void emptyStringsRoundTrip() {
            UUID catalog = newCatalog();
            Map<String, String> properties = Map.of("", "", "empty", "", "\0key", "\0value", "plain", "x");
            NamespaceRecord namespace = new NamespaceRecord(catalog, UUID.randomUUID(), catalog, Changes.name("\0empties"),
                    properties, 1);
            assertApplied(store.createNamespace(namespace, Changes.change()));
            assertThat(store.readNamespace(namespace.id())).contains(namespace);
            assertThat(store.findNamespace(catalog, List.of("\0empties"))).contains(namespace);

            StorageProfile profile = new StorageProfile(UUID.randomUUID(), "", StorageProfile.Kind.S3, Optional.of(""), "",
                    List.of("", "\0s3://x/", "s3://y/"));
            assertApplied(store.putStorageProfile(profile, Changes.change()));
            assertThat(store.readStorageProfile(profile.id())).contains(profile);

            UUID other = UUID.randomUUID();
            CatalogSettings settings = new CatalogSettings(profile.id(), "", "");
            assertApplied(store.createCatalog(new CatalogRecord(other, "e-" + other, ""), settings, Changes.change()));
            assertThat(store.catalogSettings(other)).contains(settings);
            assertThat(store.findCatalog("e-" + other)).contains(new CatalogRecord(other, "e-" + other, ""));

            Securable target = new Securable(Securable.Kind.CATALOG, catalog);
            Grant grant = new Grant(catalog, target, "", "");
            Change put = Changes.change();
            assertApplied(store.putGrant(grant, "", put));
            assertThat(store.grantsFor(Set.of(target), Set.of(""))).containsExactly(new GrantRecord(grant, "", put.at()));

            GovernanceAttribute attribute = new GovernanceAttribute(target, GovernanceAttribute.Kind.OWNER, "", "", "",
                    Changes.now());
            assertApplied(store.putAttribute(attribute, Changes.change()));
            assertThat(store.attributesOf(Set.of(target))).containsExactly(attribute);

            LegalHold hold = new LegalHold(UUID.randomUUID(), new Securable(Securable.Kind.NAMESPACE, namespace.id()), "",
                    "", Changes.now());
            assertApplied(store.placeHold(hold, Changes.change()));
            assertThat(store.holdsOn(Set.of(hold.target()))).containsExactly(hold);

            TrustedEngine engine = new TrustedEngine("", "\0", "", Set.of());
            assertApplied(store.putTrustedEngine(engine, Changes.change()));
            assertThat(store.findTrustedEngine("", "\0")).contains(engine);

            PointerKey key = newKey();
            JournalEntry entry = new JournalEntry("", "", "", "[]", "[]");
            Instant at = Changes.now();
            String idempotencyKey = UUID.randomUUID().toString();
            IdempotencyResult result = new IdempotencyResult(idempotencyKey, "", "", 204, "", at.plusSeconds(3600));
            CommitBatch batch = new CommitBatch(UUID.randomUUID(), at, List.of(new PointerSwap(key, Optional.empty(),
                    new Pointer("", 1, "", new byte[0]), entry)), List.of(new OutboxEvent("", "{}")), Optional.of(result));
            assertThat(store.commit(batch)).isEqualTo(new CommitResult.Committed(batch.commitId()));
            assertThat(store.readPointer(key)).contains(new Pointer("", 1, "", new byte[0]));
            assertThat(store.readJournal(key, at, at.plusMillis(1), PageRequest.first(1)).items())
                    .extracting(JournalRecord::entry).containsExactly(entry);
            assertThat(store.readKey(idempotencyKey, Changes.now())).isEqualTo(new KeyState.Done(result));
            // The relay reads the type as every read does, through the codec. The outbox suite's relay
            // runs on its own clock, so this test reads the row instead of taking the relay lease.
            assertThat(Jdbc.decode(Sql.value(oracle.direct(), "SELECT type FROM outbox WHERE commit_id = ?",
                    batch.commitId()))).isEmpty();
        }

        @Test
        @DisplayName("Oracle: a done key the previous release wrote with an empty body (stored as NULL) reads back empty")
        void previousReleaseEmptyBodyReadsBackEmpty() {
            String key = UUID.randomUUID().toString();
            Instant expires = Changes.now().plusSeconds(3600);
            Sql.execute(oracle.direct(), "INSERT INTO idempotency (key, principal, request_hash, status, body, expires_at)"
                    + " VALUES (?, 'svc', 'hash', 204, NULL, ?)", key, java.time.OffsetDateTime.ofInstant(expires,
                    java.time.ZoneOffset.UTC));

            assertThat(store.readKey(key, Changes.now()))
                    .isEqualTo(new KeyState.Done(new IdempotencyResult(key, "svc", "hash", 204, "", expires)));
        }

        @Test
        @DisplayName("Oracle: a session sorting linguistically (NLS_SORT GERMAN) skips names when paging; self-check"
                + " reports it")
        void linguisticSortSkipsNamesWhenPaging() {
            UUID catalog = newCatalog();
            newNamespace(catalog, "b");
            newNamespace(catalog, "\u00e4");
            CatalogStore german = oracle.storeWithSession("ALTER SESSION SET NLS_SORT = GERMAN");

            List<String> binary = allPages(1, page -> store.listNamespaces(catalog, page)).stream()
                    .map(namespace -> namespace.name().normalized()).toList();
            List<String> linguistic = allPages(1, page -> german.listNamespaces(catalog, page)).stream()
                    .map(namespace -> namespace.name().normalized()).toList();

            assertThat(binary).as("byte order: b is 0x62, \u00e4 is 0xC3 0xA4").containsExactly("b", "\u00e4");
            assertThat(linguistic).as("ORDER BY sorts \u00e4 first, then name_norm > '\u00e4' compares bytes")
                    .containsExactly("\u00e4");
            assertThat(german.selfCheck()).extracting(SafetyFinding::code).contains("ora.linguistic-sort");
            assertThat(store.selfCheck()).extracting(SafetyFinding::code).doesNotContain("ora.linguistic-sort");
        }

        @Test
        @DisplayName("Oracle: the journal is interval-partitioned by month; a commit in a new month adds a partition")
        void journalAddsAPartitionForANewMonth() {
            assertThat(Sql.value(oracle.direct(), "SELECT interval FROM user_part_tables WHERE table_name = 'JOURNAL'"))
                    .isEqualTo("NUMTOYMINTERVAL(1, 'MONTH')");
            PointerKey key = newKey();
            Instant far = Changes.now().plus(Duration.ofDays(5 * 366));
            YearMonth farMonth = YearMonth.from(far.atZone(ZoneOffset.UTC));
            assertThat(partitionMonths(oracle.direct())).doesNotContain(farMonth);
            CommitBatch batch = new CommitBatch(UUID.randomUUID(), far, List.of(advance(key, Optional.empty(), "far")),
                    List.of(), Optional.empty());

            // Around the link's 5 s read timeout, creating a partition inside a commit can time out on a
            // loaded machine (OracleFree.precreateJournalMonths), so this commit takes the admin pool.
            CatalogStore direct = new OracleCatalogStore(oracle.direct());
            assertThat(direct.commit(batch)).isEqualTo(new CommitResult.Committed(batch.commitId()));

            assertThat(partitionMonths(oracle.direct())).contains(farMonth);
            assertThat(direct.readJournal(key, far, far.plusMillis(1), PageRequest.first(1)).items())
                    .extracting(JournalRecord::commitId).containsExactly(batch.commitId());
        }

        @Test
        @DisplayName("Oracle: a daily scheduler job creates the journal partitions of this month and the next two")
        void schedulerJobCreatesJournalPartitionsAhead() {
            DataSource fresh = oracle.freshSchema();
            String job = "SELECT %s FROM user_scheduler_jobs WHERE job_name = 'JOURNAL_PARTITIONS_AHEAD'";

            assertThat(Sql.value(fresh, job.formatted("enabled || ' ' || repeat_interval"))).isEqualTo("TRUE FREQ=DAILY");
            Sql.await(Duration.ofSeconds(120), () -> Integer.parseInt(Sql.value(fresh, job.formatted("run_count"))) > 0,
                    "the job's first run");

            assertThat(Sql.value(fresh, job.formatted("failure_count"))).isEqualTo("0");
            YearMonth month = YearMonth.now(ZoneOffset.UTC);
            assertThat(partitionMonths(fresh)).contains(month, month.plusMonths(1), month.plusMonths(2))
                    .doesNotContain(month.plusMonths(3));
        }

        @Test
        @DisplayName("Oracle: self-check reports a journal-partition job that is disabled or missing, and not the job"
                + " as V5 makes it")
        void selfCheckReportsAJournalJobThatCannotRun() {
            DataSource fresh = oracle.freshSchemaAfterTheJobsFirstRun();
            CatalogStore schema = new OracleCatalogStore(fresh);
            assertThat(journalJobFinding(schema)).as("the job as V5 makes it").isEmpty();

            Sql.execute(fresh, "BEGIN DBMS_SCHEDULER.DISABLE('" + JournalJob.NAME + "', force => TRUE); END;");
            assertThat(journalJobFinding(schema)).hasValueSatisfying(detail ->
                    assertThat(detail).contains(JournalJob.NAME + " is disabled"));

            Sql.execute(fresh, "BEGIN DBMS_SCHEDULER.ENABLE('" + JournalJob.NAME + "'); END;");
            assertThat(journalJobFinding(schema)).as("enabled again").isEmpty();

            Sql.execute(fresh, "BEGIN DBMS_SCHEDULER.DROP_JOB('" + JournalJob.NAME + "', force => TRUE); END;");
            assertThat(journalJobFinding(schema)).hasValueSatisfying(detail ->
                    assertThat(detail).contains(JournalJob.NAME + " is missing"));
        }

        @Test
        @DisplayName("Oracle: self-check reports a journal-partition job whose last two runs failed, not one failed run,"
                + " and no longer once a run succeeds")
        void selfCheckReportsAJournalJobThatKeepsFailing() {
            DataSource fresh = oracle.freshSchemaAfterTheJobsFirstRun();
            OracleFree.awaitJobRunsLogged(fresh, 1);
            CatalogStore schema = new OracleCatalogStore(fresh);
            // Each run now fails, as a revoked privilege or a full tablespace would make it fail.
            Sql.execute(fresh, "CREATE OR REPLACE PROCEDURE create_journal_partitions AS BEGIN"
                    + " RAISE_APPLICATION_ERROR(-20072, 'staged failure'); END;");

            runJournalJob(fresh, 2);
            assertThat(journalJobFinding(schema)).as("one failed run after a success").isEmpty();

            runJournalJob(fresh, 3);
            assertThat(journalJobFinding(schema)).hasValueSatisfying(detail ->
                    assertThat(detail).contains(JournalJob.NAME + "'s last 2 runs failed, the newest with ORA-20072"));

            Sql.execute(fresh, "CREATE OR REPLACE PROCEDURE create_journal_partitions AS BEGIN NULL; END;");
            runJournalJob(fresh, 4);
            assertThat(journalJobFinding(schema)).as("a run succeeded after the failures").isEmpty();
        }

        /** Runs the job now as the Scheduler runs it, logged and counted, and waits until it has logged {@code runs}. */
        private static void runJournalJob(DataSource schema, int runs) {
            Sql.execute(schema, "BEGIN DBMS_SCHEDULER.RUN_JOB('" + JournalJob.NAME + "', use_current_session => FALSE);"
                    + " END;");
            OracleFree.awaitJobRunsLogged(schema, runs);
        }

        private static Optional<String> journalJobFinding(CatalogStore schema) {
            return schema.selfCheck().stream().filter(finding -> finding.code().equals(JournalJob.FINDING))
                    .map(SafetyFinding::detail).findFirst();
        }

        /** The month of each journal partition, from its upper bound, the first instant of the month after it. */
        private static List<YearMonth> partitionMonths(DataSource schema) {
            return Sql.column(schema, "SELECT high_value FROM user_tab_partitions WHERE table_name = 'JOURNAL'").stream()
                    .map(bound -> YearMonth.parse(bound.replaceAll("(?s).*'\\s*(\\d{4}-\\d{2})-01 .*", "$1")).minusMonths(1))
                    .toList();
        }
    }

    @Test
    @DisplayName("Oracle: the catalog service has COMMIT_OUTCOME, so the only finding on Free is the missing Data Guard")
    void transactionGuardIsNotReportedWhereItIsOn() {
        assertThat(store().selfCheck()).extracting(SafetyFinding::code).containsExactly("ora.no-data-guard");
    }

    @Test
    @DisplayName("Oracle: Transaction Guard answers NOT_COMMITTED for a COMMIT still on its way, and that COMMIT"
            + " then cannot land")
    void transactionGuardBlocksACommitStillOnItsWay() {
        PointerKey key = newKey();
        Pointer base = commitNew(key, "base");
        CommitBatch batch = batch(swap(key, Optional.of(base), pointer("too-late", 2)));
        long[] sentAt = new long[1];

        // The link holds the COMMIT longer than the client waits for its answer.
        assertOutcomeUnknown(oracle.store(before(batch, () -> {
            oracle.delayRequests(LATE_COMMIT_DELAY);
            sentAt[0] = System.nanoTime();
        })), batch);
        CommitStore sameProcess = oracle.storeAroundTheLink();
        assertThat(sameProcess.findCommit(batch.commitId(), key)).isEqualTo(CommitLookup.NOT_COMMITTED);

        Sql.pause(Duration.ofNanos(sentAt[0] + LATE_COMMIT_DELAY.plusSeconds(3).toNanos() - System.nanoTime()));
        assertThat(sameProcess.readPointer(key)).as("the held COMMIT reached the database and was refused")
                .contains(base);
        assertThat(oracle.writtenRows(batch)).isEqualTo(WrittenRows.NONE);
    }

    @Test
    @DisplayName("Oracle: without the commit's transaction id, findCommit waits for an update whose COMMIT is on its way")
    void anotherProcessWaitsForAnUpdateOnItsWay() throws Exception {
        PointerKey key = newKey();
        assertAnotherProcessWaitsForCommitOnItsWay(key, Optional.of(commitNew(key, "base")));
    }

    @Test
    @DisplayName("Oracle: without the commit's transaction id, findCommit waits for a create whose COMMIT is on its way")
    void anotherProcessWaitsForACreateOnItsWay() throws Exception {
        assertAnotherProcessWaitsForCommitOnItsWay(newKey(), Optional.empty());
    }

    private void assertAnotherProcessWaitsForCommitOnItsWay(PointerKey key, Optional<Pointer> base) throws Exception {
        CommitBatch batch = batch(advance(key, base, "on-its-way"));
        CountDownLatch held = new CountDownLatch(1);
        CommitStore delaying = oracle.store(before(batch, () -> {
            oracle.delayRequests(COMMIT_DELAY);
            held.countDown();
        }));
        CompletableFuture<CommitResult> commit = CompletableFuture.supplyAsync(() -> delaying.commit(batch));
        assertThat(held.await(30, SECONDS)).isTrue();

        CompletableFuture<CommitLookup> lookup = CompletableFuture.supplyAsync(
                () -> oracle.storeOfAnotherProcess().findCommit(batch.commitId(), key));
        Thread.sleep(1_000);
        assertThat(lookup).as("findCommit must wait while the COMMIT is on its way").isNotDone();

        assertThat(commit.get(60, SECONDS)).isEqualTo(new CommitResult.Committed(batch.commitId()));
        assertThat(lookup.get(60, SECONDS)).isEqualTo(CommitLookup.COMMITTED);
    }

    @Test
    @DisplayName("Oracle: a commit shows before its redo is written, and findCommit without its transaction id"
            + " answers COMMITTED only once the redo is written")
    void anotherProcessAnswersCommittedOnlyOnceTheRedoIsWritten() throws Exception {
        PointerKey key = newKey();
        Pointer base = commitNew(key, "base");
        CommitBatch batch = batch(swap(key, Optional.of(base), pointer("redo-unwritten", 2)));
        oracle.stallReplication();

        assertOutcomeUnknown(store(), batch);
        CommitStore anotherProcess = oracle.storeOfAnotherProcess();
        // The hazard is real: other sessions see the commit though its redo is not written.
        assertThat(anotherProcess.readPointer(key)).contains(next(batch));

        CompletableFuture<CommitLookup> lookup =
                CompletableFuture.supplyAsync(() -> anotherProcess.findCommit(batch.commitId(), key));
        Thread.sleep(1_500);
        assertThat(lookup).as("COMMITTED must wait until the redo is written").isNotDone();
        oracle.resumeReplication();

        assertThat(lookup.get(60, SECONDS)).isEqualTo(CommitLookup.COMMITTED);
    }

    @Test
    @DisplayName("Oracle: a key's answer whose COMMIT is still waiting for its redo is replayed only once the redo is"
            + " written")
    void idempotentReplayWaitsForTheRedo() throws Exception {
        CatalogStore store = oracle.store(CommitHook.NONE);
        String key = UUID.randomUUID().toString();
        Instant now = Changes.now();
        CommitBatch batch = new CommitBatch(UUID.randomUUID(), now, List.of(advance(newKey(), Optional.empty(), "keyed")),
                List.of(), Optional.of(Changes.result(key, 200, now)));
        oracle.stallReplication();

        assertOutcomeUnknown(store, batch);

        CompletableFuture<KeyState> claim = CompletableFuture.supplyAsync(() -> store.claimKey(new IdempotencyClaim(key,
                "svc-suite", "hash-of-" + key, UUID.randomUUID(), now, now.plusSeconds(30), now.plusSeconds(3600))));
        CompletableFuture<KeyState> read = CompletableFuture.supplyAsync(() -> store.readKey(key, Changes.now()));
        CompletableFuture<KeyState> await = CompletableFuture.supplyAsync(() -> store.awaitKey(key, Duration.ofSeconds(30)));
        Thread.sleep(1_500);
        assertThat(claim).as("Done must wait until the answer's redo is written").isNotDone();
        assertThat(read).as("Done must wait until the answer's redo is written").isNotDone();
        assertThat(await).as("Done must wait until the answer's redo is written").isNotDone();
        oracle.resumeReplication();

        KeyState done = new KeyState.Done(batch.idempotency().orElseThrow());
        assertThat(claim.get(60, SECONDS)).isEqualTo(done);
        assertThat(read.get(60, SECONDS)).isEqualTo(done);
        assertThat(await.get(60, SECONDS)).isEqualTo(done);
    }

    @Test
    @DisplayName("Oracle: an empty idempotency body (a 204 answer) commits and reads back empty, though Oracle stores ''"
            + " as NULL")
    void emptyIdempotencyBodyCommits() {
        PointerKey key = newKey();
        CommitBatch full = fullBatch(advance(key, Optional.empty(), "no-content"));
        IdempotencyResult result = full.idempotency().orElseThrow();
        CommitBatch batch = new CommitBatch(full.commitId(), full.committedAt(), full.swaps(), full.outbox(),
                Optional.of(new IdempotencyResult(result.key(), result.principal(), result.requestHash(), 204, "",
                        Changes.now().plusSeconds(3600))));

        assertThat(store().commit(batch)).isEqualTo(new CommitResult.Committed(batch.commitId()));
        assertThat(oracle.writtenRows(batch)).isEqualTo(WrittenRows.allOf(batch));
        assertThat(oracle.store(CommitHook.NONE).readKey(result.key(), Changes.now()))
                .isEqualTo(new KeyState.Done(batch.idempotency().orElseThrow()));
    }
}
