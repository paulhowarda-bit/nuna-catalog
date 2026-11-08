package fidice.catalog.store.sql;

import static org.assertj.core.api.Assertions.assertThat;

import fidice.catalog.store.CommitBatch;
import fidice.catalog.store.CommitLookup;
import fidice.catalog.store.CommitResult;
import fidice.catalog.store.JournalEntry;
import fidice.catalog.store.JournalRecord;
import fidice.catalog.store.PageRequest;
import fidice.catalog.store.Pointer;
import fidice.catalog.store.PointerKey;
import fidice.catalog.store.PointerSwap;
import fidice.catalog.store.suite.Changes;
import fidice.catalog.store.suite.Commits;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import java.util.stream.Stream;
import javax.sql.DataSource;

/**
 * The journal's roll-over on YugabyteDB, which keeps it unpartitioned: a tablet splits by itself
 * when it outgrows the split threshold, so the journal grows over any span of commit times with
 * nothing created ahead. Here the node splits tablets above 64 KiB (YugabyteCluster), three years
 * of commits overflow that many times, and every commit stays readable and findable.
 */
final class JournalTabletSplits {

    private static final int MONTHS = 36;
    private static final int SWAPS_PER_COMMIT = 40;
    private static final int PADDING_BYTES = 2_048;
    private static final int SPLITS = 3;

    private JournalTabletSplits() {
    }

    /** {@code database} is fresh and empty, on a node whose tablets split above 64 KiB. */
    static void run(DataSource database) throws InterruptedException {
        SqlCatalogStore store = new SqlCatalogStore(database);
        store.migrate();
        assertThat(store.extendJournal(Instant.now().plus(Duration.ofDays(3_650)))).as("nothing to create ahead").isZero();
        assertThat(Sql.value(database, "SELECT count(*) FROM pg_inherits WHERE inhparent = 'journal'::regclass"))
                .as("the journal is not partitioned").isEqualTo("0");
        int tabletsBefore = tablets(database);

        // Random padding: the journal's text must not compress below the threshold.
        Random random = new Random(7);
        PointerKey followed = Commits.newKey();
        Optional<Pointer> current = Optional.empty();
        List<CommitBatch> batches = new ArrayList<>();
        List<String> followedUpdates = new ArrayList<>();
        Instant first = Changes.now().atOffset(ZoneOffset.UTC).minusMonths(MONTHS / 2).toInstant();
        for (int month = 0; month < MONTHS; month++) {
            Instant at = first.atOffset(ZoneOffset.UTC).plusMonths(month).toInstant();
            List<PointerKey> keys = new ArrayList<>(Stream.generate(Commits::newKey).limit(SWAPS_PER_COMMIT - 1).toList());
            keys.add(followed);
            List<PointerSwap> swaps = new ArrayList<>();
            for (PointerKey key : keys.stream().sorted().toList()) {
                Optional<Pointer> expected = key.equals(followed) ? current : Optional.empty();
                Pointer next = Commits.pointer("tablet-" + month + "-" + key.objectId(), expected.map(Pointer::version)
                        .orElse(0L) + 1);
                byte[] padding = new byte[PADDING_BYTES / 2];
                random.nextBytes(padding);
                String updates = "[{\"action\":\"set-properties\",\"padding\":\"" + HexFormat.of().formatHex(padding) + "\"}]";
                if (key.equals(followed)) {
                    followedUpdates.add(updates);
                }
                swaps.add(new PointerSwap(key, expected, next, new JournalEntry("svc-suite", "end-user", "suite-engine",
                        "[]", updates)));
            }
            CommitBatch batch = new CommitBatch(UUID.randomUUID(), at, swaps, List.of(), Optional.empty());
            assertThat(store.commit(batch)).isEqualTo(new CommitResult.Committed(batch.commitId()));
            current = Optional.of(swaps.stream().filter(swap -> swap.key().equals(followed)).findFirst().orElseThrow().next());
            batches.add(batch);
        }

        Sql.await(Duration.ofSeconds(240), () -> tablets(database) >= tabletsBefore + SPLITS,
                "the journal to split into at least " + SPLITS + " more tablets");
        List<JournalRecord> journal = store.readJournal(followed, Instant.EPOCH,
                first.atOffset(ZoneOffset.UTC).plusMonths(MONTHS).toInstant(), PageRequest.first(MONTHS + 1)).items();
        assertThat(journal).extracting(JournalRecord::committedAt)
                .containsExactlyElementsOf(batches.stream().map(CommitBatch::committedAt).toList());
        assertThat(journal).extracting(entry -> entry.entry().updatesJson()).as("kept as sent")
                .containsExactlyElementsOf(followedUpdates);
        assertThat(store.findCommit(batches.getFirst().commitId(), followed)).isEqualTo(CommitLookup.COMMITTED);
        assertThat(store.findCommit(batches.getLast().commitId(), followed)).isEqualTo(CommitLookup.COMMITTED);
    }

    private static int tablets(DataSource database) {
        return Integer.parseInt(Sql.value(database, "SELECT count(*) FROM yb_tablet_metadata"
                + " WHERE db_name = current_database() AND relname = 'journal'"));
    }
}
