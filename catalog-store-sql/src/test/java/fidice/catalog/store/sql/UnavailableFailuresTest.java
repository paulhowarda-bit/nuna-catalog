package fidice.catalog.store.sql;

import static org.assertj.core.api.Assertions.assertThat;

import fidice.catalog.store.StoreException;
import fidice.catalog.store.StoreUnavailableException;
import java.io.EOFException;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Which failures before COMMIT mean the database could not be reached (M8): those become
 * {@link StoreUnavailableException}, which the REST layer answers 503 with Retry-After; the rest stay plain
 * store failures.
 */
class UnavailableFailuresTest {

    @ParameterizedTest
    @ValueSource(strings = {"08001", "08003", "08006", "57P01", "57P02", "57P03", "53300", "25006"})
    void anUnreachableOrFailingOverDatabaseIsUnavailable(String sqlState) {
        assertThat(Transactions.failed("x failed", new SQLException("lost", sqlState)))
                .isInstanceOf(StoreUnavailableException.class);
    }

    @Test
    void aPoolThatTimesOutWaitingForAConnectionIsUnavailable() {
        assertThat(Transactions.failed("x failed", new SQLTransientConnectionException("pool timed out")))
                .isInstanceOf(StoreUnavailableException.class);
    }

    @Test
    void aConnectionFailureFoundAmongTheCausesIsUnavailable() {
        SQLException io = new SQLException("An I/O error occurred while sending to the backend", "08006",
                new EOFException());
        assertThat(Transactions.failed("x failed", new SQLException("batch failed", null, io)))
                .isInstanceOf(StoreUnavailableException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"23505", "42P01", "22001", "40001"})
    void aDatabaseThatAnsweredIsNotUnavailable(String sqlState) {
        assertThat(Transactions.failed("x failed", new SQLException("refused", sqlState)))
                .isExactlyInstanceOf(StoreException.class);
    }
}
