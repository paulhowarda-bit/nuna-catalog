package keel.catalog.store.sql;

import static keel.catalog.store.suite.Commits.advance;
import static keel.catalog.store.suite.Commits.batch;
import static keel.catalog.store.suite.Commits.newKey;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import keel.catalog.store.CommitBatch;
import keel.catalog.store.StoreException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import javax.sql.DataSource;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;

/**
 * YugabyteDB is not a supported backend (decision-ledger.md D7, 2026-11-11). It speaks PostgreSQL's
 * protocol and reports a PostgreSQL version, so the store must tell it apart and refuse it before
 * any schema step or write, not run on it as PostgreSQL. The refusal turns on the text of
 * {@code version()}, so no YugabyteDB server is needed here.
 */
class UnsupportedBackendTest {

    private static final String YUGABYTEDB = "PostgreSQL 15.12-YB-2025.2.8.1-b8 on x86_64-pc-linux-gnu";
    private static final String REFUSAL = "YugabyteDB is not a supported backend";

    @Test
    void aYugabyteVersionIsRefusedNotTakenForPostgresql() {
        assertThatThrownBy(() -> Backend.of(YUGABYTEDB)).isInstanceOf(StoreException.class).hasMessageContaining(REFUSAL);
    }

    @Test
    void postgresqlAndCockroachVersionsAreToldApart() {
        assertThat(Backend.of("PostgreSQL 18.1 on x86_64-pc-linux-gnu")).isEqualTo(Backend.POSTGRESQL);
        assertThat(Backend.of("CockroachDB CCL v25.4.20 (x86_64-pc-linux-gnu)")).isEqualTo(Backend.COCKROACHDB);
    }

    @Test
    void theStoreRefusesYugabyteBeforeAnySchemaStepOrWrite() {
        List<String> statements = new ArrayList<>();
        SqlCatalogStore store = new SqlCatalogStore(answeringOnlyItsVersion(YUGABYTEDB, statements));
        CommitBatch commit = batch(advance(newKey(), Optional.empty(), "refused"));

        for (ThrowingCallable call : List.<ThrowingCallable>of(store::migrate, store::selfCheck,
                () -> store.commit(commit), () -> store.extendJournal(Instant.now()))) {
            assertThatThrownBy(call).isInstanceOf(StoreException.class).hasMessageContaining(REFUSAL);
        }
        assertThat(statements).as("all the database was asked").isNotEmpty().containsOnly("SELECT version()");
    }

    /**
     * A database that answers {@code SELECT version()} and nothing else: any other call on its
     * connections fails, so a store that went past the version check fails with another error.
     */
    private static DataSource answeringOnlyItsVersion(String version, List<String> statements) {
        ResultSet row = fake(ResultSet.class, Map.of("next", arguments -> true, "getString", arguments -> version,
                "close", arguments -> null));
        Statement statement = fake(Statement.class, Map.of("executeQuery", arguments -> {
            statements.add((String) arguments[0]);
            return row;
        }, "close", arguments -> null));
        Connection connection = fake(Connection.class, Map.of("createStatement", arguments -> statement,
                "close", arguments -> null));
        return fake(DataSource.class, Map.of("getConnection", arguments -> connection));
    }

    private static <T> T fake(Class<T> type, Map<String, Function<Object[], Object>> answers) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (proxy, method, args) -> {
            Function<Object[], Object> answer = answers.get(method.getName());
            if (answer == null) {
                throw new UnsupportedOperationException(type.getSimpleName() + "." + method.getName());
            }
            return answer.apply(args);
        }));
    }
}
