package nuna.catalog.store.sql;

import static nuna.catalog.store.suite.Commits.advance;
import static nuna.catalog.store.suite.Commits.batch;
import static nuna.catalog.store.suite.Commits.newKey;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import nuna.catalog.store.CatalogObject;
import nuna.catalog.store.CommitBatch;
import nuna.catalog.store.CommitHook;
import nuna.catalog.store.StoreException;
import nuna.catalog.store.suite.Changes;
import java.lang.reflect.Array;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import javax.sql.DataSource;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * PostgreSQL's journal horizon is kept by the store itself (backlog 9): once a day at most, before a
 * write that journals, it extends the monthly partitions twelve months ahead of its clock, so a server
 * that runs on without a migration never runs out. These tests need only Java: a clock the test moves,
 * and a database faked at the JDBC interface. The partitions themselves are checked on PostgreSQL in
 * {@code JournalHorizonKept} (heavy).
 */
class JournalHorizonTest {

    private static final Instant START = Instant.parse("2026-11-19T10:15:30Z");

    @Nested
    class WhenAndHowFar {

        private final MovableClock clock = new MovableClock(START);
        private final JournalHorizon horizon = new JournalHorizon(clock);

        @Test
        void theTargetIsTwelveMonthsOnInUtc() {
            assertThat(horizon.target()).isEqualTo(Instant.parse("2027-11-19T10:15:30Z"));
            MovableClock leapDay = new MovableClock(Instant.parse("2028-02-29T23:30:00Z"));
            assertThat(new JournalHorizon(leapDay).target()).as("the last day of the month, a year on")
                    .isEqualTo(Instant.parse("2029-02-28T23:30:00Z"));
        }

        @Test
        void theFirstExtensionIsDueADayAfterTheStoreIsMade() {
            assertThat(horizon.claim()).as("the server's migration has just extended it").isEmpty();
            clock.advance(JournalHorizon.EVERY.minusMillis(1));
            assertThat(horizon.claim()).isEmpty();

            clock.advance(Duration.ofMillis(1));

            assertThat(horizon.claim()).contains(Instant.parse("2027-11-20T10:15:30Z"));
            assertThat(horizon.claim()).as("once a day").isEmpty();
        }

        @Test
        void eachExtensionIsDueADayAfterTheLast() {
            clock.advance(Duration.ofDays(5));
            assertThat(horizon.claim()).contains(Instant.parse("2027-11-24T10:15:30Z"));

            clock.advance(JournalHorizon.EVERY.minusMillis(1));
            assertThat(horizon.claim()).as("missed days are not made up").isEmpty();
            clock.advance(Duration.ofMillis(1));
            assertThat(horizon.claim()).contains(Instant.parse("2027-11-25T10:15:30Z"));
        }

        @Test
        void oneOfManyWritersExtendsAndTheOthersGoOn() throws Exception {
            clock.advance(JournalHorizon.EVERY);
            int writers = 16;
            CountDownLatch start = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(writers);
            try {
                List<Future<Optional<Instant>>> claims = new ArrayList<>();
                for (int i = 0; i < writers; i++) {
                    claims.add(pool.submit(() -> {
                        start.await();
                        return horizon.claim();
                    }));
                }
                start.countDown();
                int extending = 0;
                for (Future<Optional<Instant>> claim : claims) {
                    extending += claim.get().isPresent() ? 1 : 0;
                }
                assertThat(extending).isEqualTo(1);
            } finally {
                pool.shutdownNow();
            }
        }
    }

    @Nested
    class TheStore {

        private final MovableClock clock = new MovableClock(START);
        private final FakeDatabase database = new FakeDatabase(POSTGRESQL);
        private final SqlCatalogStore store = new SqlCatalogStore(database.dataSource(), CommitHook.NONE, clock);

        @Test
        void aWriteADayLaterExtendsThePartitionsBeforeItRuns() {
            commit();
            assertThat(database.extensions()).as("not before a day has passed").isEmpty();

            clock.advance(JournalHorizon.EVERY);
            commit();

            assertThat(database.extensions()).containsExactly(Instant.parse("2027-11-20T10:15:30Z"));
            List<String> asked = database.statements();
            int extension = asked.indexOf(EXTEND);
            assertThat(asked.get(extension - 1)).as("waiting at most a second for each lock")
                    .isEqualTo(LOCK_WAIT + " [1000ms]");
            assertThat(asked.subList(extension + 1, asked.size())).as("then the commit's own statements")
                    .anySatisfy(sql -> assertThat(sql).contains("pointer"));

            commit();
            assertThat(database.extensions()).as("once a day").hasSize(1);
        }

        @Test
        void creatingAndRevivingAnObjectKeepItToo() {
            clock.advance(JournalHorizon.EVERY);
            CatalogObject table = CatalogObject.active(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                    Changes.name("t"), CatalogObject.Kind.TABLE, "s3://bucket/t");
            assertThatThrownBy(() -> store.createObject(Changes.creation(table))).isInstanceOf(StoreException.class);
            assertThat(database.extensions()).hasSize(1);

            clock.advance(JournalHorizon.EVERY);
            CommitBatch revival = batch(advance(table.mainKey(), Optional.empty(), "revived"));
            assertThatThrownBy(() -> store.reviveObject(table.id(), CatalogObject.State.DROPPED, table.namespaceId(),
                    table.name(), revival)).isInstanceOf(StoreException.class);
            assertThat(database.extensions()).hasSize(2);
        }

        @Test
        void aFailedExtensionIsTriedAgainTheNextDayAndTheWriteGoesAhead() {
            database.failExtensions();
            clock.advance(JournalHorizon.EVERY);

            assertThatThrownBy(() -> store.commit(batch(advance(newKey(), Optional.empty(), "after a failed extension"))))
                    .as("the commit's own failure, not the extension's").isInstanceOf(StoreException.class)
                    .hasMessageStartingWith("commit ");
            assertThat(database.extensions()).hasSize(1);
            commit();
            assertThat(database.extensions()).as("not again the same day").hasSize(1);

            clock.advance(JournalHorizon.EVERY);
            commit();
            assertThat(database.extensions()).hasSize(2);
        }

        @Test
        void cockroachdbHasNoPartitionsToExtend() {
            FakeDatabase cockroach = new FakeDatabase(COCKROACHDB);
            SqlCatalogStore onCockroach = new SqlCatalogStore(cockroach.dataSource(), CommitHook.NONE, clock);
            clock.advance(JournalHorizon.EVERY);

            assertThatThrownBy(() -> onCockroach.commit(batch(advance(newKey(), Optional.empty(), "c"))))
                    .isInstanceOf(StoreException.class);

            assertThat(cockroach.statements()).doesNotContain(EXTEND).noneMatch(sql -> sql.startsWith(LOCK_WAIT));
        }

        /** A commit, which the fake database fails at its first statement: only what came before it matters here. */
        private void commit() {
            assertThatThrownBy(() -> store.commit(batch(advance(newKey(), Optional.empty(), "c"))))
                    .isInstanceOf(StoreException.class).hasMessageStartingWith("commit ");
        }
    }

    private static final String POSTGRESQL = "PostgreSQL 18.1 on x86_64-pc-linux-gnu";
    private static final String COCKROACHDB = "CockroachDB CCL v25.4.20 (x86_64-pc-linux-gnu)";
    private static final String EXTEND = "SELECT journal_extend(?)";
    private static final String LOCK_WAIT = "SELECT set_config('lock_timeout', ?, true)";

    /**
     * A database faked at the JDBC interface. It reports its version, takes the session settings,
     * and answers the journal's extension (with 0 partitions created, or a lock timeout); any other
     * statement fails as a database error, so a write stops at its first statement.
     */
    private static final class FakeDatabase {

        private final String version;
        private final List<String> statements = new CopyOnWriteArrayList<>();
        private final List<Instant> extensions = new CopyOnWriteArrayList<>();
        private volatile boolean failExtensions;

        FakeDatabase(String version) {
            this.version = version;
        }

        void failExtensions() {
            failExtensions = true;
        }

        /** Every statement asked, in order; a lock wait with the value it set. */
        List<String> statements() {
            return List.copyOf(statements);
        }

        /** The {@code through} of each extension asked, failed ones included. */
        List<Instant> extensions() {
            return List.copyOf(extensions);
        }

        DataSource dataSource() {
            return fake(DataSource.class, (method, args) -> method.equals("getConnection") ? connection() : null);
        }

        private Connection connection() {
            return fake(Connection.class, (method, args) -> switch (method) {
                case "createStatement" -> statement();
                case "prepareStatement" -> prepared((String) args[0]);
                default -> null;
            });
        }

        private Statement statement() {
            return fake(Statement.class, (method, args) -> {
                if (method.equals("executeQuery") || method.equals("execute")) {
                    String sql = (String) args[0];
                    statements.add(sql);
                    if (sql.equals("SELECT version()")) {
                        return row(version);
                    }
                    if (sql.equals("SET TIME ZONE 'UTC'")) {
                        return false;
                    }
                    throw new SQLException("not faked: " + sql, "XX000");
                }
                return null;
            });
        }

        private PreparedStatement prepared(String sql) {
            List<Object> parameters = new ArrayList<>();
            return fake(PreparedStatement.class, (method, args) -> {
                switch (method) {
                    case "setObject", "setString" -> parameters.add(args[1]);
                    case "executeQuery", "executeUpdate" -> {
                        if (sql.equals(LOCK_WAIT)) {
                            statements.add(sql + " " + parameters);
                            return row(parameters.getFirst());
                        }
                        statements.add(sql);
                        if (sql.equals(EXTEND)) {
                            extensions.add(((OffsetDateTime) parameters.getFirst()).toInstant());
                            if (failExtensions) {
                                throw new SQLException("canceling statement due to lock timeout", "55P03");
                            }
                            return row(0);
                        }
                        throw new SQLException("not faked: " + sql, "XX000");
                    }
                    default -> {
                    }
                }
                return null;
            });
        }

        /** One row of one column. */
        private static ResultSet row(Object value) {
            boolean[] read = {false};
            return fake(ResultSet.class, (method, args) -> switch (method) {
                case "next" -> {
                    boolean first = !read[0];
                    read[0] = true;
                    yield first;
                }
                case "getString" -> String.valueOf(value);
                case "getInt" -> ((Number) value).intValue();
                default -> null;
            });
        }
    }

    @FunctionalInterface
    private interface Answer {
        Object answer(String method, Object[] args) throws SQLException;
    }

    /** A proxy whose methods answer by name; a method left unanswered returns its type's default. */
    private static <T> T fake(Class<T> type, Answer answer) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (proxy, method, args) -> {
            Object result = answer.answer(method.getName(), args);
            Class<?> returns = method.getReturnType();
            if (result != null || !returns.isPrimitive() || returns == void.class) {
                return result;
            }
            return Array.get(Array.newInstance(returns, 1), 0);
        }));
    }
}
