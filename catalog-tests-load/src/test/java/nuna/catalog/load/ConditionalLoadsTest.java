package nuna.catalog.load;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import nuna.catalog.load.ConditionalLoads.Timed;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * How the overnight load tests judge a client's conditional loads under the shared credential epoch (item 76): every
 * one a 304, except one full load with a new ETag per window boundary crossed. Java only, no server: the answers are
 * made up, at times chosen around a boundary.
 */
class ConditionalLoadsTest {

    private static final Duration WINDOW = Duration.ofMinutes(7).plusSeconds(30);
    /** Every whole hour is a boundary of 7.5-minute windows counted from the Unix epoch. */
    private static final long BOUNDARY = Instant.parse("2026-11-20T03:00:00Z").toEpochMilli();
    private static final URI SERVER = URI.create("http://localhost:20000");

    @Test
    void theWindowIsAQuarterOfTheServersCredentialLifetime() {
        assertThat(ConditionalLoads.WINDOW).as("the server's default lifetime of 30 minutes").isEqualTo(WINDOW);
        assertThat(ConditionalLoads.window(Map.of("nuna.storage.credential-lifetime", "15m")))
                .isEqualTo(Duration.ofSeconds(225));
        assertThat(ConditionalLoads.window(Map.of("nuna.storage.credential-lifetime", "1h")))
                .isEqualTo(Duration.ofMinutes(15));
    }

    @Test
    void boundariesAreCountedFromTheUnixEpoch() {
        assertThat(ConditionalLoads.boundaries(WINDOW, BOUNDARY - 1, BOUNDARY)).isEqualTo(1);
        assertThat(ConditionalLoads.boundaries(WINDOW, BOUNDARY, BOUNDARY + WINDOW.toMillis() - 1)).isZero();
        assertThat(ConditionalLoads.boundaries(WINDOW, BOUNDARY - 1, BOUNDARY + Duration.ofHours(2).toMillis()))
                .isEqualTo(17);
    }

    @Test
    void every304PassesEvenAcrossABoundary() {
        ConditionalLoads loads = new ConditionalLoads(WINDOW, full("a", BOUNDARY + 1_000));
        assertThat(loads.judge(answer(304, "a", BOUNDARY + 60_000))).isTrue();
        // A server whose clock lags, or credentials with an epoch of their own, keep the ETag past the boundary.
        assertThat(loads.judge(answer(304, "a", BOUNDARY + WINDOW.toMillis() + 5_000))).isTrue();
        assertThat(loads.etag()).isEqualTo("a");
        assertThat(loads.boundaryLoads()).isZero();
    }

    @Test
    void aFullLoadInsideTheETagsWindowFails() {
        ConditionalLoads loads = new ConditionalLoads(WINDOW, full("a", BOUNDARY + 1_000));
        assertThat(loads.judge(answer(304, "a", BOUNDARY + 2_000))).isTrue();
        assertThatThrownBy(() -> loads.judge(answer(200, "b", BOUNDARY + WINDOW.toMillis() - 1_000)))
                .isInstanceOf(AssertionError.class).hasMessageContaining("inside the issuance window");
    }

    @Test
    void aFullLoadAfterABoundaryPassesOnceAndItsETagIsHeld() {
        ConditionalLoads loads = new ConditionalLoads(WINDOW, full("a", BOUNDARY - 5_000));
        assertThat(loads.judge(answer(304, "a", BOUNDARY - 1_000))).isTrue();
        assertThat(loads.judge(answer(200, "b", BOUNDARY + 1_000))).as("a full load across the boundary").isFalse();
        assertThat(loads.etag()).isEqualTo("b");
        assertThat(loads.judge(answer(304, "b", BOUNDARY + 2_000))).isTrue();
        assertThat(loads.boundaryLoads()).isOne();
        assertThatThrownBy(() -> loads.judge(answer(200, "c", BOUNDARY + 3_000)))
                .as("a second full load for one boundary").isInstanceOf(AssertionError.class);
    }

    @Test
    void aLoadStraddlingABoundaryCountsAsCrossingIt() {
        ConditionalLoads early = new ConditionalLoads(WINDOW, full("a", BOUNDARY - 10_000));
        assertThatThrownBy(() -> early.judge(new Timed(new Catalog.Answer(200, "{}", "b", 49_000_000, SERVER),
                BOUNDARY - 50, BOUNDARY - 1))).as("answered before the boundary").isInstanceOf(AssertionError.class);

        ConditionalLoads loads = new ConditionalLoads(WINDOW, full("a", BOUNDARY - 10_000));
        assertThat(loads.judge(new Timed(new Catalog.Answer(200, "{}", "b", 50_000_000, SERVER), BOUNDARY - 20,
                BOUNDARY + 30))).as("sent before the boundary, answered after it").isFalse();
        assertThatThrownBy(() -> loads.judge(answer(200, "c", BOUNDARY + 1_000)))
                .as("the straddling load used the boundary up").isInstanceOf(AssertionError.class);

        // The ETag taken by a straddling load may be from either side, so the next boundary is the one after it.
        ConditionalLoads straddled = new ConditionalLoads(WINDOW, new Timed(new Catalog.Answer(200, "{}", "a",
                50_000_000, SERVER), BOUNDARY - 20, BOUNDARY + 30));
        assertThat(straddled.judge(answer(200, "b", BOUNDARY + 1_000))).as("taken before the boundary").isFalse();
        assertThatThrownBy(() -> straddled.judge(answer(200, "c", BOUNDARY + 2_000)))
                .isInstanceOf(AssertionError.class);
    }

    @Test
    void aFullLoadAcrossTwoBoundariesIsStillOneFullLoad() {
        ConditionalLoads loads = new ConditionalLoads(WINDOW, full("a", BOUNDARY - 1_000));
        long later = BOUNDARY + WINDOW.toMillis() + 1_000;
        assertThat(loads.judge(answer(200, "b", later))).isFalse();
        assertThatThrownBy(() -> loads.judge(answer(200, "c", later + 1_000))).isInstanceOf(AssertionError.class);
    }

    @Test
    void aFullLoadMustBringANewETag() {
        ConditionalLoads same = new ConditionalLoads(WINDOW, full("a", BOUNDARY - 1_000));
        assertThatThrownBy(() -> same.judge(answer(200, "a", BOUNDARY + 1_000))).isInstanceOf(AssertionError.class);
        ConditionalLoads none = new ConditionalLoads(WINDOW, full("a", BOUNDARY - 1_000));
        assertThatThrownBy(() -> none.judge(answer(200, null, BOUNDARY + 1_000))).as("near expiry: no ETag")
                .isInstanceOf(AssertionError.class);
    }

    @Test
    void anyOtherStatusFails() {
        ConditionalLoads loads = new ConditionalLoads(WINDOW, full("a", BOUNDARY - 1_000));
        assertThatThrownBy(() -> loads.judge(answer(503, null, BOUNDARY + 1_000))).isInstanceOf(AssertionError.class)
                .hasMessageContaining("neither 304 nor 200");
        assertThatThrownBy(() -> new ConditionalLoads(WINDOW, answer(304, "a", BOUNDARY)))
                .as("an ETag is taken from a full load").isInstanceOf(AssertionError.class);
    }

    /**
     * A client loading for two hours, each load taking up to 5 s and the next sent up to 20 s later, against a server
     * whose ETag is that of the window it is in at some instant while the load is in flight, so some loads straddle a
     * boundary on either side of it: never more than one full load per boundary, one for every boundary before the
     * last load was sent, and every other load a 304.
     */
    @Test
    void aClientOverManyWindowsLoadsInFullOncePerBoundary() {
        Random random = new Random(76);
        long start = BOUNDARY - 7_000;
        ConditionalLoads loads = new ConditionalLoads(WINDOW, served(null, start, random));
        int sent = 0;
        int notModified = 0;
        int straddling = 0;
        Timed last = null;
        for (long at = start + 10_000; at < start + Duration.ofHours(2).toMillis(); at = last.answered()
                + random.nextInt(20_000)) {
            last = served(loads.etag(), at, random);
            sent++;
            if (ConditionalLoads.boundaries(WINDOW, last.sent(), last.answered()) > 0) {
                straddling++;
            }
            if (loads.judge(last)) {
                notModified++;
            }
        }
        assertThat(straddling).as("loads in flight at a boundary").isPositive();
        assertThat(ConditionalLoads.boundaries(WINDOW, start, last.sent())).isEqualTo(16);
        assertThat(loads.boundaryLoads()).isBetween((int) ConditionalLoads.boundaries(WINDOW, start, last.sent()),
                (int) ConditionalLoads.boundaries(WINDOW, start, last.answered()));
        assertThat(notModified).isEqualTo(sent - loads.boundaryLoads());
    }

    /** A load sent at {@code sent}, answered within 5 s, the server's ETag taken at a moment in between. */
    private static Timed served(String ifNoneMatch, long sent, Random random) {
        long answered = sent + random.nextInt(5_000);
        long at = sent + random.nextLong(answered - sent + 1);
        String etag = "w" + Math.floorDiv(at, WINDOW.toMillis());
        int status = etag.equals(ifNoneMatch) ? 304 : 200;
        return new Timed(new Catalog.Answer(status, status == 200 ? "{}" : "", etag, (answered - sent) * 1_000_000,
                SERVER), sent, answered);
    }

    private static Timed full(String etag, long at) {
        return answer(200, etag, at);
    }

    private static Timed answer(int status, String etag, long at) {
        return new Timed(new Catalog.Answer(status, status == 200 ? "{}" : "", etag, 5_000_000, SERVER), at, at + 5);
    }
}
