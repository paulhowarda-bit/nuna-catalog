package nuna.catalog.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import nuna.catalog.store.WorkerStorage.Listed;
import nuna.catalog.store.WorkerStorage.Prefix;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * What the worker reports it reaches at a scan (backlog 250), as the store keeps it: what a report may hold, the text
 * the SQL and Oracle stores keep it as, and the prefix that covers a location, as the worker matches it.
 */
class WorkerStorageTest {

    private static final Prefix LAKE = prefix("s3://lake/tables", Listed.OK, null, 12, 0, 0, null);
    private static final Prefix LEDGER = prefix("s3://ledger-warehouse/tables", Listed.REFUSED, "AccessDenied", 0, 3,
            1, "AccessDenied");
    private static final Prefix GONE = prefix("s3://gone", Listed.FAILED, "SdkClientException", 0, 0, 0, null);

    @Test
    void theTextReadsBackAsWritten() {
        List<WorkerStorage> storage = List.of(
                new WorkerStorage("s3", Optional.of("https://silo.example:9000"), List.of(LAKE, LEDGER)),
                new WorkerStorage("s3", Optional.empty(), List.of(GONE)),
                new WorkerStorage("s3", Optional.of("https://empty.example"), List.of()));

        String text = WorkerStorage.write(storage);

        assertThat(WorkerStorage.read(text)).isEqualTo(storage);
        assertThat(WorkerStorage.read(text).getFirst().prefixes().get(1).listed()).as("a refused listing stays refused")
                .isEqualTo(Listed.REFUSED);
        assertThat(WorkerStorage.read(WorkerStorage.write(List.of()))).isEmpty();
        assertThatThrownBy(() -> WorkerStorage.read("prefix\ts3://lake/tables\tOK\t\t0\t0\t0\t"))
                .as("a prefix before any store").isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theGroupsTextReadsBackAsWritten() {
        TreeSet<String> groups = new TreeSet<>(Set.of("platform workers", "maintainers"));
        assertThat(MaintenanceScan.readGroups(MaintenanceScan.writeGroups(groups))).isEqualTo(groups);
        assertThat(MaintenanceScan.readGroups(MaintenanceScan.writeGroups(new TreeSet<>()))).isEmpty();
    }

    @Test
    void aReportNamesNoKeyAndHoldsCodesOnlyWhereSomethingWasNotDone() {
        assertThatThrownBy(() -> prefix("s3://lake/tables", Listed.OK, "AccessDenied", 0, 0, 0, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> prefix("s3://lake/tables", Listed.REFUSED, null, 0, 0, 0, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> prefix("s3://lake/tables", Listed.OK, null, 0, 1, 0, null))
                .as("a refused delete has its code").isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> prefix("s3://lake/tables", Listed.OK, null, 1, 0, 0, "AccessDenied"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> prefix("s3://lake/tables", Listed.REFUSED, "Access Denied for tables/a.parquet", 0, 0,
                0, null)).as("a code, never the store's message").isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> prefix("s3://lake/\ttables", Listed.OK, null, 0, 0, 0, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> prefix("s3://lake/tables", Listed.OK, null, -1, 0, 0, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WorkerStorage("S3", Optional.empty(), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WorkerStorage("s3", Optional.empty(), List.of(LAKE, LAKE)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> scan(Optional.of(new TreeSet<>()), List.of(
                new WorkerStorage("s3", Optional.empty(), List.of(LAKE)),
                new WorkerStorage("s3", Optional.empty(), List.of(LEDGER)))))
                .as("one identity a kind and endpoint").isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> scan(Optional.of(new TreeSet<>(Set.of("a\ngroup"))), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theLongestPrefixAboveALocationCoversIt() {
        Prefix tables = prefix("s3://lake/tables", Listed.OK, null, 0, 0, 0, null);
        Prefix hot = prefix("s3a://lake/tables/hot/", Listed.REFUSED, "AccessDenied", 0, 0, 0, null);
        Prefix bucket = prefix("s3://ledger", Listed.OK, null, 0, 0, 0, null);
        MaintenanceScan scan = scan(Optional.empty(), List.of(
                new WorkerStorage("s3", Optional.of("https://a.example"), List.of(tables, bucket)),
                new WorkerStorage("s3", Optional.of("https://b.example"), List.of(hot))));

        assertThat(scan.covering("s3://lake/tables/ns/t").map(c -> c.prefix())).contains(tables);
        assertThat(scan.covering("s3://lake/tables")).as("the prefix itself").map(c -> c.prefix()).contains(tables);
        assertThat(scan.covering("s3n://lake/tables/hot/t").map(c -> c.prefix())).as("the longer prefix")
                .contains(hot);
        assertThat(scan.covering("s3://lake/tables/hot/t").map(c -> c.store().endpoint()))
                .contains(Optional.of("https://b.example"));
        assertThat(scan.covering("s3://lake/tablesx/t")).as("whole segments only").isEmpty();
        assertThat(scan.covering("s3://ledger/any/where").map(c -> c.prefix())).contains(bucket);
        assertThat(scan(Optional.empty(), null).covering("s3://lake/tables/t")).as("no storage reported").isEmpty();
    }

    private static MaintenanceScan scan(Optional<java.util.SortedSet<String>> groups, List<WorkerStorage> storage) {
        return new MaintenanceScan(UUID.randomUUID(), "svc-maintenance", Instant.parse("2026-11-24T10:00:00Z"),
                Optional.empty(), Optional.empty(), Map.of(), Optional.empty(), groups, Optional.ofNullable(storage));
    }

    private static Prefix prefix(String location, Listed listed, String listCode, long deleted, long refused,
                                 long failed, String deleteCode) {
        return new Prefix(location, listed, Optional.ofNullable(listCode), deleted, refused, failed,
                Optional.ofNullable(deleteCode));
    }
}
