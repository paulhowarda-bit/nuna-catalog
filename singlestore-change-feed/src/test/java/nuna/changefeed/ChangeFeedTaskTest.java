package nuna.changefeed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.apache.kafka.connect.errors.RetriableException;
import org.apache.kafka.connect.source.SourceRecord;
import org.junit.jupiter.api.Test;

class ChangeFeedTaskTest {

    @Test
    void theStoredOffsetIsReadAsWhateverNumberTheConverterGives() {
        assertThat(ChangeFeedTask.receiptedThrough(Map.of("snapshot_id", 5009893770895635200L))).isEqualTo(5009893770895635200L);
        assertThat(ChangeFeedTask.receiptedThrough(Map.of("snapshot_id", 42))).isEqualTo(42L);
        assertThat(ChangeFeedTask.receiptedThrough(Map.of("snapshot_id", "5009893770895635200"))).isEqualTo(5009893770895635200L);
        assertThat(ChangeFeedTask.receiptedThrough(Map.of())).isNull();
        assertThat(ChangeFeedTask.receiptedThrough(null)).isNull();
        assertThat(ChangeFeedTask.receiptedThrough(Map.of("calendar", 7, "accounts", "8"), "accounts")).isEqualTo(8L);
        assertThat(ChangeFeedTask.receiptedThrough(Map.of("calendar", 7), "accounts")).isNull();
    }

    @Test
    void aPipelineOfOneTableKeepsItsOwnConfiguration() {
        Map<String, String> props = Map.of("singlestore.table", "orders", "iceberg.table", "orders_copy");
        assertThat(ChangeFeedTask.perTable(props)).containsExactly(props);
    }

    @Test
    void eachListedTableGetsAFeedIntoAnIcebergTableOfItsName() {
        Map<String, String> props = Map.of("singlestore.tables", "calendar, securities  accounts", "singlestore.database", "brokerage",
                "iceberg.namespace", "brokerage.singlestore");
        List<Map<String, String>> configs = ChangeFeedTask.perTable(props);
        assertThat(configs).extracting(c -> c.get("singlestore.table")).containsExactly("calendar", "securities", "accounts");
        assertThat(configs).extracting(c -> c.get("iceberg.table")).containsExactly("calendar", "securities", "accounts");
        assertThat(configs).allSatisfy(c -> {
            assertThat(c).doesNotContainKey("singlestore.tables");
            assertThat(c).containsEntry("singlestore.database", "brokerage").containsEntry("iceberg.namespace", "brokerage.singlestore");
        });
        assertThatThrownBy(() -> ChangeFeedTask.perTable(Map.of("singlestore.tables", " , "))).hasMessageContaining("lists no table");
    }

    @Test
    void aPipelineOfOneTableStoresTheSnapshotIdAsBefore() {
        ChangeFeedTask task = new ChangeFeedTask();
        Script orders = new Script().then(receipt(11, "orders"), receipt(12, "orders"));
        task.started(Map.of("source", "ops.orders"), List.of(new ChangeFeedTask.Feed("orders", "snapshot_id", orders)), Map.of());

        assertThat(task.poll()).extracting(SourceRecord::sourceOffset)
                .containsExactly(Map.of("snapshot_id", 11L), Map.of("snapshot_id", 12L));
    }

    @Test
    void severalTablesStoreEachOnesLastReceiptInOneOffset() {
        ChangeFeedTask task = new ChangeFeedTask();
        Script calendar = new Script().then(receipt(10, "calendar")).then().then(receipt(13, "calendar"));
        Script accounts = new Script().then(receipt(20, "accounts"), receipt(21, "accounts")).then();
        task.started(Map.of("source", "brokerage.calendar,accounts"), List.of(
                new ChangeFeedTask.Feed("calendar", "calendar", calendar),
                new ChangeFeedTask.Feed("accounts", "accounts", accounts)), Map.of("accounts", 19L));

        List<SourceRecord> first = task.poll();
        assertThat(first).extracting(SourceRecord::sourceOffset).containsExactly(
                Map.of("calendar", 10L, "accounts", 19L),
                Map.of("calendar", 10L, "accounts", 20L),
                Map.of("calendar", 10L, "accounts", 21L));
        assertThat(first).allSatisfy(r -> assertThat(r.sourcePartition()).isEqualTo(Map.of("source", "brokerage.calendar,accounts")));
        assertThat(task.poll()).isEmpty();
        assertThat(task.poll()).extracting(SourceRecord::sourceOffset).containsExactly(Map.of("calendar", 13L, "accounts", 21L));
    }

    @Test
    void oneFeedFailingNeitherLosesTheOthersReceiptsNorGoesUnreported() {
        ChangeFeedTask task = new ChangeFeedTask();
        Script calendar = new Script().fail("lost the catalog").then(receipt(14, "calendar"));
        Script accounts = new Script().then(receipt(22, "accounts")).then(receipt(23, "accounts"));
        task.started(Map.of("source", "brokerage.calendar,accounts"), List.of(
                new ChangeFeedTask.Feed("calendar", "calendar", calendar),
                new ChangeFeedTask.Feed("accounts", "accounts", accounts)), Map.of());

        assertThat(task.poll()).extracting(SourceRecord::sourceOffset).containsExactly(Map.of("accounts", 22L));
        assertThatThrownBy(task::poll).isInstanceOf(RetriableException.class).hasMessageContaining("change feed of calendar")
                .hasMessageContaining("lost the catalog");
        assertThat(task.poll()).extracting(SourceRecord::sourceOffset)
                .containsExactly(Map.of("accounts", 22L, "calendar", 14L), Map.of("accounts", 23L, "calendar", 14L));
    }

    @Test
    void aFailureWithNothingElseToReturnIsThrownAtOnce() {
        ChangeFeedTask task = new ChangeFeedTask();
        Script orders = new Script().fail("connection reset").then(receipt(30, "orders"));
        task.started(Map.of("source", "ops.orders"), List.of(new ChangeFeedTask.Feed("orders", "snapshot_id", orders)), Map.of());

        assertThatThrownBy(task::poll).isInstanceOf(RetriableException.class).hasMessageContaining("connection reset");
        assertThat(task.poll()).extracting(SourceRecord::sourceOffset).containsExactly(Map.of("snapshot_id", 30L));
    }

    private static Map<String, Object> receipt(long snapshot, String table) {
        Map<String, Object> r = new HashMap<>();
        r.put("snapshot_id", snapshot);
        r.put("source_table", "brokerage." + table);
        r.put("iceberg_table", "brokerage.singlestore." + table);
        r.put("kind", "changes");
        r.put("rows_written", 1L);
        r.put("keys_deleted", 0L);
        r.put("offsets", "[]");
        r.put("committed_at", 0L);
        return r;
    }

    /** A feed answering each poll from a script, then with nothing. */
    private static final class Script implements Function<Long, List<Map<String, Object>>> {
        private final Deque<Object> answers = new ArrayDeque<>();

        @SafeVarargs
        final Script then(Map<String, Object>... receipts) {
            answers.add(List.of(receipts));
            return this;
        }

        Script fail(String message) {
            answers.add(new IllegalStateException(message));
            return this;
        }

        @Override
        @SuppressWarnings("unchecked")
        public List<Map<String, Object>> apply(Long waitMs) {
            Object next = answers.poll();
            if (next instanceof RuntimeException e) {
                throw e;
            }
            return next == null ? List.of() : (List<Map<String, Object>>) next;
        }
    }
}
