package nuna.catalog.load;

import static org.assertj.core.api.Assertions.assertThat;

import nuna.catalog.server.storage.StorageSettings;
import java.time.Duration;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/**
 * One client's conditional loads of one table, judged as D14 allows them since item 15 (amended 2026-11-19): a
 * table load's credential epoch is the issuance window the load falls in, {@code floor(now / window)} on the
 * server's clock counted from the Unix epoch, so an ETag holds only until its window ends. The client's first
 * conditional load after a window boundary may then be a full load with a new ETag, which it keeps from then on, as
 * a client does. Every other conditional load must be a 304: a full load inside the window its ETag came from, a
 * second one for one boundary, one that keeps the ETag it was sent or one without an ETag fails the test (item 76).
 * A 304 is never questioned.
 *
 * <p>Times are the test's wall clock: the servers run on the same machine, so it stands for theirs. A load sent before
 * a boundary and answered after it may have been answered on either side, so it counts as crossing that boundary.
 */
final class ConditionalLoads {

    /**
     * The window of the load stack's servers: a quarter of {@code nuna.storage.credential-lifetime}, as
     * {@code StsCredentialVending} takes it. The stack sets no lifetime ({@link Stack#server}), so its servers bind
     * {@link StorageSettings}' default, and so does this.
     */
    static final Duration WINDOW = window(Map.of());

    private final Duration window;
    private String etag;
    /** The earliest window the ETag held can be from. */
    private long earliest;
    private int boundaryLoads;

    /** Holds the ETag of {@code taken}, a full load. */
    ConditionalLoads(Duration window, Timed taken) {
        assertThat(taken.answer().status()).as("the load that gives the ETag").isEqualTo(200);
        assertThat(taken.answer().etag()).as("the ETag of the load that gives it").isNotNull();
        this.window = window;
        this.etag = taken.answer().etag();
        this.earliest = index(window, taken.sent());
    }

    /** The issuance window of a server started with {@code settings}, bound as the server binds them. */
    static Duration window(Map<String, String> settings) {
        return new Binder(new MapConfigurationPropertySource(settings))
                .bindOrCreate("nuna.storage", StorageSettings.class).credentialLifetime().dividedBy(4);
    }

    /** The window boundaries between two wall-clock times, in milliseconds. */
    static long boundaries(Duration window, long from, long to) {
        return index(window, to) - index(window, from);
    }

    private static long index(Duration window, long millis) {
        return Math.floorDiv(millis, window.toMillis());
    }

    /** The ETag to send with the next conditional load. */
    String etag() {
        return etag;
    }

    /** The full loads accepted, one per window boundary at most. */
    int boundaryLoads() {
        return boundaryLoads;
    }

    /**
     * Judges a conditional load sent with {@link #etag()}.
     *
     * @return true for a 304; false for a full load across a window boundary, whose ETag is held from then on
     * @throws AssertionError for any other answer
     */
    boolean judge(Timed load) {
        Catalog.Answer answer = load.answer();
        if (answer.status() == 304) {
            return true;
        }
        assertThat(answer.status()).as("a conditional load answered neither 304 nor 200: %s", answer.body())
                .isEqualTo(200);
        assertThat(index(window, load.answered()))
                .as("a full load answered inside the issuance window its ETag can be from (windows of %s, counted"
                        + " from the Unix epoch; sent at %d ms, answered at %d ms): only a boundary changes the epoch",
                        window, load.sent(), load.answered())
                .isGreaterThan(earliest);
        assertThat(answer.etag()).as("the ETag of a full load across a window boundary").isNotNull()
                .isNotEqualTo(etag);
        etag = answer.etag();
        // The new ETag is from a later window than the one it replaces, and from no earlier one than the load's.
        earliest = Math.max(index(window, load.sent()), earliest + 1);
        boundaryLoads++;
        return false;
    }

    /** A load as the client saw it: its answer, and the wall clock just before it was sent and once it was answered. */
    record Timed(Catalog.Answer answer, long sent, long answered) {

        static Timed of(Supplier<Catalog.Answer> load) {
            long sent = System.currentTimeMillis();
            Catalog.Answer answer = load.get();
            return new Timed(answer, sent, System.currentTimeMillis());
        }
    }
}
