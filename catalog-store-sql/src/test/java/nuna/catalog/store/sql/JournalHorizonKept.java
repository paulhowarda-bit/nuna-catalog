package nuna.catalog.store.sql;

import static nuna.catalog.store.sql.JournalRollOver.monthsFrom;
import static nuna.catalog.store.sql.JournalRollOver.partitionName;
import static nuna.catalog.store.suite.Commits.advance;
import static nuna.catalog.store.suite.Commits.newKey;
import static org.assertj.core.api.Assertions.assertThat;

import nuna.catalog.store.CommitBatch;
import nuna.catalog.store.CommitHook;
import nuna.catalog.store.CommitResult;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;

/**
 * PostgreSQL keeps its journal's horizon without a migration (backlog 9). Two servers migrate once
 * and run on for fourteen months, each committing once a month: before its commit, each extends
 * the monthly partitions twelve months ahead of its clock, both at once, so every commit lands in
 * its month's partition and none in the default one, past the twelve months the migration made.
 * Then an extension that cannot lock the default partition within a second, behind an open reader,
 * gives up, and its commit goes ahead; the next day's commit extends.
 */
final class JournalHorizonKept {

    private static final int MONTHS = 14;

    private final DataSource database;
    private final MovableClock clock = new MovableClock(Instant.now());

    /** {@code database} is fresh and empty. */
    JournalHorizonKept(DataSource database) {
        this.database = database;
    }

    void run() throws Exception {
        SqlCatalogStore first = new SqlCatalogStore(database, CommitHook.NONE, clock);
        SqlCatalogStore second = new SqlCatalogStore(database, CommitHook.NONE, clock);
        first.migrate();
        Instant cutover = Instant.parse(Sql.value(database,
                "SELECT to_char(cutover AT TIME ZONE 'UTC', 'YYYY-MM-DD\"T\"HH24:MI:SS\"Z\"') FROM journal_layout"));
        assertThat(monthlyPartitions()).as("the migration reaches twelve months ahead")
                .containsExactlyElementsOf(monthsFrom(cutover, twelveMonthsOn()));

        for (int month = 1; month <= MONTHS; month++) {
            clock.advance(Duration.ofDays(31));
            CompletableFuture<CommitBatch> one = CompletableFuture.supplyAsync(() -> commit(first));
            CompletableFuture<CommitBatch> other = CompletableFuture.supplyAsync(() -> commit(second));
            for (CommitBatch batch : List.of(one.get(60, TimeUnit.SECONDS), other.get(60, TimeUnit.SECONDS))) {
                assertThat(partitionOf(batch)).as("month %d's commit lands in its month", month)
                        .isEqualTo(partitionName(clock.instant()));
            }
            assertThat(monthlyPartitions()).as("month %d: the horizon is twelve months ahead", month)
                    .contains(partitionName(twelveMonthsOn()));
        }
        assertThat(monthlyPartitions()).as("each month once, with no gap")
                .containsExactlyElementsOf(monthsFrom(cutover, twelveMonthsOn()));
        assertThat(Sql.value(database, "SELECT count(*) FROM journal_default")).isEqualTo("0");

        try (Connection reader = database.getConnection()) {
            reader.setAutoCommit(false);
            readDefaultPartition(reader);
            clock.advance(Duration.ofDays(31));
            String wanted = partitionName(twelveMonthsOn());

            CommitBatch behindReader = CompletableFuture.supplyAsync(() -> commit(first)).get(15, TimeUnit.SECONDS);

            assertThat(partitionOf(behindReader)).isEqualTo(partitionName(clock.instant()));
            assertThat(monthlyPartitions()).as("the extension gave up on its locks").doesNotContain(wanted);
            reader.rollback();
            clock.advance(JournalHorizon.EVERY);
            commit(first);
            assertThat(monthlyPartitions()).as("the next day's commit extends").contains(wanted);
        }
    }

    private CommitBatch commit(SqlCatalogStore store) {
        CommitBatch batch = new CommitBatch(UUID.randomUUID(), clock.instant(),
                List.of(advance(newKey(), Optional.empty(), "kept")), List.of(), Optional.empty());
        assertThat(store.commit(batch)).isEqualTo(new CommitResult.Committed(batch.commitId()));
        return batch;
    }

    /** Holds a lock on the default partition, as a long reader of the journal does, until {@code reader} ends its transaction. */
    private static void readDefaultPartition(Connection reader) throws SQLException {
        try (Statement statement = reader.createStatement()) {
            statement.executeQuery("SELECT count(*) FROM journal_default").close();
        }
    }

    private Instant twelveMonthsOn() {
        return clock.instant().atOffset(ZoneOffset.UTC).plusMonths(JournalHorizon.MONTHS).toInstant();
    }

    private String partitionOf(CommitBatch batch) {
        return Sql.value(database, "SELECT tableoid::regclass::text FROM journal WHERE commit_id = ?", batch.commitId());
    }

    private List<String> monthlyPartitions() {
        return Sql.column(database, "SELECT c.relname FROM pg_inherits i JOIN pg_class c ON c.oid = i.inhrelid"
                + " WHERE i.inhparent = 'journal'::regclass AND c.relname LIKE 'journal\\_p%' ORDER BY c.relname");
    }
}
