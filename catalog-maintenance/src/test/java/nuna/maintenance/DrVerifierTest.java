package nuna.maintenance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * The DR verifier's passes (D37, amended 2026-11-20), without a catalog or a DR store: a pass reads every page of the
 * work list, reports only a version above the watermark kept, keeps going past an object whose check fails, retries a
 * failed whole check no sooner than its interval, and sets its meters; a pass that cannot read the work list fails and
 * leaves the gauges as they were; docs/observability/worker-metrics.md lists every DR meter. {@link DrVerifyTest}
 * checks real tables.
 */
class DrVerifierTest {

    private static final Instant T1 = Instant.parse("2026-11-20T12:00:00Z");
    private static final Duration RETRY = Duration.ofMinutes(10);

    private final MeterRegistry meters = new SimpleMeterRegistry();
    private final SetClock clock = new SetClock();
    /** The work list's pages, in order. */
    private final List<List<DrVerify.Item>> pages = new ArrayList<>();
    /** What each object's check finds. */
    private final Map<UUID, DrVerify.Result> results = new HashMap<>();
    private final List<UUID> checked = new ArrayList<>();
    private final Map<UUID, Long> reported = new HashMap<>();
    /** Objects whose report the catalog refuses. */
    private final Set<UUID> refused = new HashSet<>();
    private RuntimeException workFails;

    private final DrVerifier verifier = new DrVerifier(new DrVerifier.Catalog() {
        @Override
        public Management.Page<DrVerify.Item> work(Optional<String> token) {
            if (workFails != null) {
                throw workFails;
            }
            int page = token.map(Integer::parseInt).orElse(0);
            return new Management.Page<>(pages.get(page), page + 1 < pages.size()
                    ? Optional.of(Integer.toString(page + 1)) : Optional.empty());
        }

        @Override
        public boolean report(UUID id, DrVerify.Version version) {
            if (refused.contains(id)) {
                return false;
            }
            reported.put(id, version.version());
            return true;
        }
    }, item -> {
        checked.add(item.id());
        DrVerify.Result result = results.get(item.id());
        if (result == null) {
            throw new IllegalStateException("the DR store did not answer");
        }
        return result;
    }, clock, RETRY, meters, () -> { });

    @Test
    void everyMeterReadsZeroBeforeTheFirstPass() {
        assertThat(gauge(DrVerifier.LAST_SUCCESS)).isZero();
        assertThat(gauge(DrVerifier.BEHIND)).isZero();
        assertThat(gauge(DrVerifier.LAG)).isZero();
        assertThat(counter(DrVerifier.VERIFIED)).isZero();
        assertThat(counter(DrVerifier.FAILURES)).isZero();
        assertThat(passes("success")).isZero();
        assertThat(passes("failure")).isZero();
    }

    @Test
    void aPassReportsOnlyAVersionAboveTheWatermarkKept() {
        DrVerify.Item caughtUp = table(4, 5, 5);
        DrVerify.Item moved = table(2, 5, 3, 4, 5);
        DrVerify.Item same = table(3, 5, 4, 5);
        DrVerify.Item gone = table(1, 3, 2, 3);
        pages.add(List.of(caughtUp, moved));
        pages.add(List.of(same, gone));
        results.put(caughtUp.id(), safe(caughtUp, 5));
        results.put(moved.id(), safe(moved, 4));
        results.put(same.id(), new DrVerify.Result(same.watermark(), Optional.of("s3://lake/t/data/b.parquet")));
        results.put(gone.id(), safe(gone, 3));
        refused.add(gone.id());

        DrVerifier.Pass pass = verifier.runOnce();

        assertThat(checked).as("every page read").containsExactly(caughtUp.id(), moved.id(), same.id(), gone.id());
        assertThat(reported).containsOnly(Map.entry(caughtUp.id(), 5L), Map.entry(moved.id(), 4L));
        assertThat(pass.objects()).isEqualTo(4);
        assertThat(pass.reported()).isEqualTo(2);
        assertThat(pass.verified()).isEqualTo(3);
        assertThat(counter(DrVerifier.VERIFIED)).isEqualTo(3);
        assertThat(pass.behind()).as("moved, same and the refused one").isEqualTo(3);
    }

    @Test
    void aPassSetsTheTablesBehindAndTheLargestLag() {
        clock.now = T1;
        DrVerify.Item moved = table(2, 5, 3, 4, 5);
        DrVerify.Item whole = new DrVerify.Item(UUID.randomUUID(), "table", "s3://lake/w", version(7), Optional.empty(),
                List.of(), Optional.of(T1.minusSeconds(1200)));
        DrVerify.Item done = table(1, 2, 2);
        pages.add(List.of(moved, whole, done));
        results.put(moved.id(), safe(moved, 4));
        results.put(whole.id(), new DrVerify.Result(Optional.empty(), Optional.of("s3://lake/w/data/a.parquet")));
        results.put(done.id(), safe(done, 2));

        DrVerifier.Pass pass = verifier.runOnce();

        assertThat(pass.behind()).isEqualTo(2);
        // The moved table lags from its version 5's commit, 5 minutes before; the whole one from 20 minutes before.
        assertThat(pass.maxLag()).isEqualTo(Duration.ofMinutes(20));
        assertThat(gauge(DrVerifier.BEHIND)).isEqualTo(2);
        assertThat(gauge(DrVerifier.LAG)).isEqualTo(1200);
        assertThat(gauge(DrVerifier.LAST_SUCCESS)).isEqualTo(T1.getEpochSecond());
        assertThat(passes("success")).isEqualTo(1);

        results.put(whole.id(), safe(whole, 7));
        results.put(moved.id(), safe(moved, 5));
        clock.now = T1.plus(RETRY);
        verifier.runOnce();

        assertThat(gauge(DrVerifier.BEHIND)).isZero();
        assertThat(gauge(DrVerifier.LAG)).isZero();
        assertThat(gauge(DrVerifier.LAST_SUCCESS)).isEqualTo(T1.plus(RETRY).getEpochSecond());
    }

    @Test
    void anObjectWhoseCheckFailsDoesNotStopThePass() {
        DrVerify.Item failing = table(1, 3, 2, 3);
        DrVerify.Item next = table(1, 2, 2);
        pages.add(List.of(failing, next));
        results.put(next.id(), safe(next, 2));

        DrVerifier.Pass pass = verifier.runOnce();

        assertThat(pass.failed()).isEqualTo(1);
        assertThat(counter(DrVerifier.FAILURES)).isEqualTo(1);
        assertThat(reported).containsOnly(Map.entry(next.id(), 2L));
        assertThat(pass.behind()).isEqualTo(1);
        assertThat(passes("success")).isEqualTo(1);
    }

    @Test
    void aFailedWholeCheckIsRetriedNoSoonerThanItsInterval() {
        clock.now = T1;
        DrVerify.Item whole = new DrVerify.Item(UUID.randomUUID(), "table", "s3://lake/w", version(7), Optional.empty(),
                List.of(), Optional.of(T1.minusSeconds(60)));
        pages.add(List.of(whole));
        results.put(whole.id(), new DrVerify.Result(Optional.empty(), Optional.of("s3://lake/w/data/a.parquet")));

        verifier.runOnce();
        clock.now = T1.plus(RETRY).minusSeconds(1);
        DrVerifier.Pass deferred = verifier.runOnce();

        assertThat(checked).containsExactly(whole.id());
        assertThat(deferred.deferred()).isEqualTo(1);
        assertThat(deferred.behind()).as("still behind while deferred").isEqualTo(1);

        clock.now = T1.plus(RETRY);
        verifier.runOnce();

        assertThat(checked).containsExactly(whole.id(), whole.id());
    }

    @Test
    void aPassThatCannotReadTheWorkListFailsAndLeavesTheGauges() {
        clock.now = T1;
        DrVerify.Item moved = table(2, 5, 3, 4, 5);
        pages.add(List.of(moved));
        results.put(moved.id(), safe(moved, 4));
        verifier.runOnce();
        workFails = new IllegalStateException("the catalog is down");
        clock.now = T1.plusSeconds(60);

        assertThatThrownBy(verifier::runOnce).isSameAs(workFails);

        assertThat(passes("failure")).isEqualTo(1);
        assertThat(gauge(DrVerifier.LAST_SUCCESS)).isEqualTo(T1.getEpochSecond());
        assertThat(gauge(DrVerifier.BEHIND)).isEqualTo(1);
    }

    @Test
    void theDocsListEveryDrMeter() throws IOException {
        String docs = Files.readString(Path.of(System.getProperty("repo.root"), "docs", "observability",
                "worker-metrics.md"));

        Set<String> names = meters.getMeters().stream().map(meter -> meter.getId().getName())
                .filter(name -> name.startsWith(DrVerifier.PREFIX)).collect(Collectors.toSet());
        assertThat(names).containsExactlyInAnyOrder(DrVerifier.PASS, DrVerifier.LAST_SUCCESS, DrVerifier.BEHIND,
                DrVerifier.LAG, DrVerifier.VERIFIED, DrVerifier.FAILURES);
        assertThat(names).allSatisfy(name -> assertThat(docs).as(name).contains("`" + name + "`"));
    }

    // ---- objects ----

    /** A table at {@code pointer} with its watermark at {@code watermark}, its later versions committed a minute apart. */
    private DrVerify.Item table(long watermark, long pointer, long... above) {
        List<DrVerify.Version> versions = new ArrayList<>();
        for (long v : above) {
            versions.add(version(v));
        }
        return new DrVerify.Item(UUID.randomUUID(), "table", "s3://lake/t", version(pointer),
                Optional.of(version(watermark)), versions,
                versions.isEmpty() ? Optional.empty() : versions.getFirst().committedAt());
    }

    /** Version {@code v}, committed {@code 10 - v} minutes before {@link #T1}. */
    private static DrVerify.Version version(long v) {
        return new DrVerify.Version(v, "s3://lake/t/metadata/0000" + v + ".metadata.json",
                Optional.of(T1.minus(Duration.ofMinutes(10 - v))));
    }

    private static DrVerify.Result safe(DrVerify.Item item, long version) {
        DrVerify.Version safe = item.versions().stream().filter(v -> v.version() == version).findFirst()
                .orElse(item.pointer().version() == version ? item.pointer() : version(version));
        return new DrVerify.Result(Optional.of(safe), Optional.empty());
    }

    private double gauge(String name) {
        return meters.get(name).gauge().value();
    }

    private double counter(String name) {
        return meters.get(name).counter().count();
    }

    private long passes(String outcome) {
        return meters.get(DrVerifier.PASS).tag(WorkerMetrics.OUTCOME, outcome).timer().count();
    }

    private static final class SetClock extends Clock {

        volatile Instant now = T1;

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}
