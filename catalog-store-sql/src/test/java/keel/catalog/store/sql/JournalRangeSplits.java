package keel.catalog.store.sql;

import static org.assertj.core.api.Assertions.assertThat;

import keel.catalog.store.CommitBatch;
import keel.catalog.store.CommitLookup;
import keel.catalog.store.CommitResult;
import keel.catalog.store.JournalEntry;
import keel.catalog.store.JournalRecord;
import keel.catalog.store.PageRequest;
import keel.catalog.store.Pointer;
import keel.catalog.store.PointerKey;
import keel.catalog.store.PointerSwap;
import keel.catalog.store.suite.Changes;
import keel.catalog.store.suite.Commits;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import javax.sql.DataSource;

/**
 * The journal's roll-over on CockroachDB, which needs no partitions: a range splits by itself
 * when it outgrows its zone's range_max_bytes, so the journal grows over any span of commit times
 * with nothing created ahead. Here the journal's zone allows 64 KiB per range, three years of
 * commits overflow it many times, and every commit stays readable and findable.
 */
final class JournalRangeSplits {

    private static final int MONTHS = 36;
    private static final int SWAPS_PER_COMMIT = 40;
    private static final String UPDATES = "[{\"action\":\"set-properties\",\"padding\":\"" + "x".repeat(1_000) + "\"}]";

    private JournalRangeSplits() {
    }

    /** {@code database} is fresh and empty, reached directly. */
    static void run(DataSource database) throws InterruptedException {
        SqlCatalogStore store = new SqlCatalogStore(database);
        store.migrate();
        assertThat(store.extendJournal(Instant.now().plus(Duration.ofDays(3_650)))).as("nothing to create ahead").isZero();
        Sql.execute(database, "ALTER TABLE journal CONFIGURE ZONE USING range_min_bytes = 0, range_max_bytes = 65536");
        int rangesBefore = ranges(database);

        PointerKey followed = Commits.newKey();
        Optional<Pointer> current = Optional.empty();
        List<CommitBatch> batches = new ArrayList<>();
        Instant first = Changes.now().atOffset(ZoneOffset.UTC).minusMonths(MONTHS / 2).toInstant();
        for (int month = 0; month < MONTHS; month++) {
            Instant at = first.atOffset(ZoneOffset.UTC).plusMonths(month).toInstant();
            List<PointerKey> keys = new ArrayList<>(Stream.generate(Commits::newKey).limit(SWAPS_PER_COMMIT - 1).toList());
            keys.add(followed);
            List<PointerSwap> swaps = new ArrayList<>();
            for (PointerKey key : keys.stream().sorted().toList()) {
                Optional<Pointer> expected = key.equals(followed) ? current : Optional.empty();
                Pointer next = Commits.pointer("split-" + month + "-" + key.objectId(), expected.map(Pointer::version)
                        .orElse(0L) + 1);
                swaps.add(new PointerSwap(key, expected, next, new JournalEntry("svc-suite", "end-user", "suite-engine",
                        "[]", UPDATES)));
            }
            CommitBatch batch = new CommitBatch(UUID.randomUUID(), at, swaps, List.of(), Optional.empty());
            assertThat(store.commit(batch)).isEqualTo(new CommitResult.Committed(batch.commitId()));
            current = Optional.of(swaps.stream().filter(swap -> swap.key().equals(followed)).findFirst().orElseThrow().next());
            batches.add(batch);
        }

        Sql.await(Duration.ofSeconds(180), () -> ranges(database) >= rangesBefore + 8,
                "the journal to split into at least 8 more ranges");
        List<JournalRecord> journal = store.readJournal(followed, Instant.EPOCH,
                first.atOffset(ZoneOffset.UTC).plusMonths(MONTHS).toInstant(), PageRequest.first(MONTHS + 1)).items();
        assertThat(journal).extracting(JournalRecord::committedAt)
                .containsExactlyElementsOf(batches.stream().map(CommitBatch::committedAt).toList());
        assertThat(journal).allSatisfy(entry -> assertThat(entry.entry().updatesJson()).as("kept as sent").isEqualTo(UPDATES));
        assertThat(store.findCommit(batches.getFirst().commitId(), followed)).isEqualTo(CommitLookup.COMMITTED);
        assertThat(store.findCommit(batches.getLast().commitId(), followed)).isEqualTo(CommitLookup.COMMITTED);
    }

    private static int ranges(DataSource database) {
        return Integer.parseInt(Sql.value(database, "SELECT count(*) FROM [SHOW RANGES FROM TABLE journal]"));
    }
}
