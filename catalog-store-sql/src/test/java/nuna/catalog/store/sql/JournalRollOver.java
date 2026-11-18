package nuna.catalog.store.sql;

import static nuna.catalog.store.suite.Commits.advance;
import static nuna.catalog.store.suite.Commits.newKey;
import static nuna.catalog.store.suite.Commits.next;
import static org.assertj.core.api.Assertions.assertThat;

import nuna.catalog.store.CommitBatch;
import nuna.catalog.store.CommitLookup;
import nuna.catalog.store.CommitResult;
import nuna.catalog.store.CommitStore;
import nuna.catalog.store.JournalRecord;
import nuna.catalog.store.PageRequest;
import nuna.catalog.store.Pointer;
import nuna.catalog.store.PointerKey;
import nuna.catalog.store.suite.Changes;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;

/**
 * The journal's roll-over on PostgreSQL, which partitions it by month: migrating a
 * previous-release database keeps its journal as the partition below the cut-over, monthly
 * partitions follow through the horizon, a commit beyond the horizon waits in the default
 * partition, and extending the horizon moves it into its month. Reads span every partition.
 */
final class JournalRollOver {

    private final DataSource database;
    private final SqlCatalogStore store;
    private final PointerKey key = newKey();
    private final List<CommitBatch> commits = new ArrayList<>();
    private Optional<Pointer> current = Optional.empty();

    /** {@code database} is fresh and empty. */
    JournalRollOver(DataSource database, SqlCatalogStore store) {
        this.database = database;
        this.store = store;
    }

    void run() {
        Instant now = Changes.now();
        PreviousReleaseStore previous = new PreviousReleaseStore(database);
        previous.migrate();
        commit(previous, now);
        commit(previous, now);
        long previousMaxSeq = Long.parseLong(Sql.value(database, "SELECT max(seq) FROM journal"));

        store.migrate();

        Instant cutover = Instant.parse(Sql.value(database,
                "SELECT to_char(cutover AT TIME ZONE 'UTC', 'YYYY-MM-DD\"T\"HH24:MI:SS\"Z\"') FROM journal_layout"));
        assertThat(cutover).as("the cut-over is the first of the month after the migration")
                .isEqualTo(monthStart(now).atOffset(ZoneOffset.UTC).plusMonths(1).toInstant());
        assertThat(partitionOf(commits.get(0))).isEqualTo("journal_m1");
        assertThat(partitionOf(commits.get(1))).isEqualTo("journal_m1");
        assertThat(monthlyPartitions()).as("one partition a month from the cut-over through twelve months ahead")
                .containsExactlyElementsOf(monthsFrom(cutover, now.atOffset(ZoneOffset.UTC).plusMonths(12).toInstant()));

        CommitBatch thisMonth = commit(store, now);
        CommitBatch nextMonth = commit(store, cutover.plusSeconds(3600));
        Instant beyond = now.atOffset(ZoneOffset.UTC).plusMonths(20).toInstant();
        CommitBatch beyondHorizon = commit(store, beyond);
        assertThat(partitionOf(thisMonth)).isEqualTo("journal_m1");
        assertThat(partitionOf(nextMonth)).isEqualTo(partitionName(cutover));
        assertThat(partitionOf(beyondHorizon)).as("a commit beyond the horizon is kept, not refused")
                .isEqualTo("journal_default");
        assertThat(Long.parseLong(Sql.value(database, "SELECT seq FROM journal WHERE commit_id = ?", thisMonth.commitId())))
                .as("seq continues above the previous release's").isGreaterThan(previousMaxSeq);

        int created = store.extendJournal(beyond);

        assertThat(created).isEqualTo(8);
        assertThat(partitionOf(beyondHorizon)).as("extending the horizon moves the commit into its month")
                .isEqualTo(partitionName(beyond));
        assertThat(Sql.value(database, "SELECT count(*) FROM journal_default")).isEqualTo("0");
        assertThat(monthlyPartitions()).containsExactlyElementsOf(monthsFrom(cutover, beyond));
        assertThat(store.extendJournal(beyond)).as("extending again creates nothing").isZero();

        List<JournalRecord> journal = store.readJournal(key, Instant.EPOCH, beyond.plusSeconds(1),
                PageRequest.first(10)).items();
        assertThat(journal).extracting(JournalRecord::commitId)
                .containsExactlyElementsOf(commits.stream().map(CommitBatch::commitId).toList());
        assertThat(journal).extracting(JournalRecord::committedAt)
                .containsExactlyElementsOf(commits.stream().map(CommitBatch::committedAt).toList());
        assertThat(previous.findCommit(beyondHorizon.commitId(), key)).isEqualTo(CommitLookup.COMMITTED);
    }

    private CommitBatch commit(CommitStore node, Instant at) {
        CommitBatch batch = new CommitBatch(UUID.randomUUID(), at, List.of(advance(key, current,
                "roll-over-" + commits.size())), List.of(), Optional.empty());
        assertThat(node.commit(batch)).isEqualTo(new CommitResult.Committed(batch.commitId()));
        current = Optional.of(next(batch));
        commits.add(batch);
        return batch;
    }

    private String partitionOf(CommitBatch batch) {
        return Sql.value(database, "SELECT tableoid::regclass::text FROM journal WHERE commit_id = ?", batch.commitId());
    }

    private List<String> monthlyPartitions() {
        return Sql.column(database, "SELECT c.relname FROM pg_inherits i JOIN pg_class c ON c.oid = i.inhrelid"
                + " WHERE i.inhparent = 'journal'::regclass AND c.relname LIKE 'journal\\_p%' ORDER BY c.relname");
    }

    private static List<String> monthsFrom(Instant cutover, Instant through) {
        List<String> names = new ArrayList<>();
        for (OffsetDateTime month = cutover.atOffset(ZoneOffset.UTC); !month.toInstant().isAfter(through);
             month = month.plusMonths(1)) {
            names.add(partitionName(month.toInstant()));
        }
        return names;
    }

    private static String partitionName(Instant at) {
        OffsetDateTime utc = at.atOffset(ZoneOffset.UTC);
        return "journal_p%04d%02d".formatted(utc.getYear(), utc.getMonthValue());
    }

    private static Instant monthStart(Instant at) {
        return at.atOffset(ZoneOffset.UTC).withDayOfMonth(1).truncatedTo(ChronoUnit.DAYS).toInstant();
    }
}
