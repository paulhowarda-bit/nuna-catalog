package nuna.catalog.audit.archive;

import static nuna.catalog.audit.archive.ArchiveCheckTest.ended;
import static nuna.catalog.audit.archive.ArchiveCheckTest.logged;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import nuna.catalog.audit.EventCodec;
import nuna.catalog.audit.SampleEvents;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.exception.SdkClientException;

/**
 * The archive writer of the log path (backlog 262): what it takes, when it writes a segment, what it does when the
 * store fails or answers late, and how its checkpoints follow one another, across a restart too.
 */
class ArchiveSinkTest {

    private static final UUID RUN = UUID.fromString("c0000000-0000-4000-8000-000000000003");
    private static final Duration AGE = Duration.ofMinutes(1);
    private static final Duration INTERVAL = Duration.ofMinutes(5);

    private final ArchiveCheckTest.MovingClock clock = new ArchiveCheckTest.MovingClock(
            Instant.parse("2026-11-25T10:00:00Z"));
    private final MemoryS3 s3 = new MemoryS3(clock);
    private final ObjectLockArchive archive = new ObjectLockArchive(s3.client(), "audit-archive", "audit",
            ObjectLockArchive.MINIMUM_RETENTION, clock);

    private ArchiveSink sink(int segmentEvents, int maxWaiting) {
        return new ArchiveSink(archive, segmentEvents, AGE, INTERVAL, maxWaiting, clock);
    }

    private static List<byte[]> records(long from, long to) {
        return LongStream.rangeClosed(from, to).mapToObj(n -> logged(RUN, n)).toList();
    }

    private Checkpoint checkpoint(ObjectLockArchive.Stored stored) {
        return Checkpoint.parse(archive.read(stored.key(), stored.versionId()));
    }

    @Test
    void aSegmentIsWrittenWhenFullWhenOldEnoughOrWhenItsRunEnds() {
        ArchiveSink sink = sink(3, 100);
        sink.offer(records(1, 4));
        assertThat(sink.tick().segments()).as("the full one; the fourth record is young").isEqualTo(1);
        assertThat(sink.waiting()).isEqualTo(1);

        clock.advance(AGE);
        assertThat(sink.tick().segments()).as("the fourth, old enough now").isEqualTo(1);

        sink.offer(List.of(logged(RUN, 5), ended(RUN, 6)));
        assertThat(sink.tick().segments()).as("closed by the run's end, however young").isEqualTo(1);
        assertThat(archive.segments(RUN.toString())).extracting(s -> s.end().sequence()).containsExactly(3L, 4L, 6L);
        assertThat(sink.waiting()).isZero();
    }

    @Test
    void whatIsNotOneWholeRecordIsRefusedAndTheRestTaken() {
        ArchiveSink sink = sink(3, 100);
        byte[] stamped = logged(RUN, 1);
        String json = new String(stamped, StandardCharsets.UTF_8);
        byte[] halfStamped = json.replaceFirst(",\"" + EventCodec.SEQUENCE + "\":\"1\"", "")
                .getBytes(StandardCharsets.UTF_8);
        byte[] twoLines = (json + "\n" + json).getBytes(StandardCharsets.UTF_8);

        ArchiveSink.Taken taken = sink.offer(List.of("not json".getBytes(StandardCharsets.UTF_8), halfStamped,
                twoLines, stamped, stamped));

        assertThat(taken).isEqualTo(new ArchiveSink.Taken(1, 1, 3));
    }

    @Test
    void recordsWithNoStampGoToAChainOfTheWritersOwn() {
        ArchiveSink sink = sink(3, 100);
        sink.offer(List.of(EventCodec.encode(SampleEvents.event(SampleEvents.oneOfEachType().getFirst(), 1))));
        sink.finish();

        assertThat(archive.chains()).containsExactly("unstamped-" + sink.writer());
    }

    @Test
    void pastTheMostWaitingAWholeRequestIsRefusedAndNothingOfItTaken() {
        ArchiveSink sink = sink(3, 4);
        sink.offer(records(1, 2));

        assertThatThrownBy(() -> sink.offer(records(3, 5))).isInstanceOf(ArchiveSink.Busy.class);
        assertThat(sink.waiting()).isEqualTo(2);
        sink.offer(records(3, 4));
        assertThat(sink.waiting()).isEqualTo(4);
    }

    @Test
    void aStoreThatCannotBeReachedLosesNothingAndTheSegmentIsWrittenOnceItCanBe() {
        ArchiveSink sink = sink(3, 100);
        sink.offer(records(1, 3));
        s3.failWith(SdkClientException.create("unreachable"));

        ArchiveSink.Round failed = sink.tick();

        assertThat(failed.segments()).isZero();
        assertThat(failed.problems()).isNotEmpty();
        assertThat(sink.waiting()).isEqualTo(3);
        s3.failWith(null);
        ArchiveSink.Round later = sink.tick();
        assertThat(later.segments()).isEqualTo(1);
        assertThat(later.problems()).isEmpty();
        assertThat(checkpoint(later.checkpoint().orElseThrow()).chains()).singleElement()
                .satisfies(a -> assertThat(a.head().sequence()).isEqualTo(3));
    }

    @Test
    void aSegmentWhoseAnswerWasLostIsFoundWrittenAndNotWrittenAgain() {
        ArchiveSink sink = sink(3, 100);
        sink.offer(records(1, 3));
        s3.loseTheNextPutsAnswer();

        assertThat(sink.tick().segments()).isZero();
        ArchiveSink.Round again = sink.tick();

        assertThat(again.segments()).isEqualTo(1);
        assertThat(again.problems()).isEmpty();
        assertThat(archive.segments(RUN.toString())).hasSize(1);
        assertThat(archive.strays(RUN.toString())).isEmpty();
    }

    @Test
    void aSecondWriterOfTheSameChainIsReportedNotOverwritten() {
        ArchiveSink first = sink(3, 100);
        ArchiveSink second = sink(3, 100);
        first.offer(records(1, 3));
        second.offer(List.of(logged(RUN, 1), logged(RUN, 2), ended(RUN, 3)));
        first.tick();

        ArchiveSink.Round round = second.tick();

        assertThat(round.segments()).isZero();
        assertThat(round.problems()).singleElement().asString().contains("second archive writer");
        assertThat(archive.segments(RUN.toString())).hasSize(1);
    }

    @Test
    void aRestartedWriterContinuesTheChainAndItsFirstCheckpointLinksToTheLastOneInTheBucket() {
        ArchiveSink before = sink(3, 100);
        before.offer(records(1, 3));
        ObjectLockArchive.Stored last = before.finish().checkpoint().orElseThrow();

        ArchiveSink after = sink(3, 100);
        after.offer(records(4, 6));
        ObjectLockArchive.Stored next = after.finish().checkpoint().orElseThrow();

        assertThat(archive.segments(RUN.toString())).extracting(s -> s.start().sequence()).containsExactly(0L, 3L);
        Checkpoint linked = checkpoint(next);
        assertThat(linked.previous()).hasValueSatisfying(l -> {
            assertThat(l.key()).isEqualTo(last.key());
            assertThat(l.sha256()).isEqualTo(Checkpoint.sha256(archive.read(last.key(), last.versionId())));
        });
        assertThat(linked.at()).as("in the same millisecond, keyed after it all the same")
                .isAfter(checkpoint(last).at());
        assertThat(next.key()).isGreaterThan(last.key());
    }

    @Test
    void anIdleWriterStillWritesACheckpointEachIntervalAndOnlyThen() {
        ArchiveSink sink = sink(3, 100);
        assertThat(sink.tick().checkpoint()).as("its first, at once").isPresent();
        clock.advance(INTERVAL.minusSeconds(1));
        assertThat(sink.tick().checkpoint()).isEmpty();
        clock.advance(Duration.ofSeconds(1));

        ObjectLockArchive.Stored idle = sink.tick().checkpoint().orElseThrow();

        assertThat(checkpoint(idle).chains()).isEmpty();
        assertThat(archive.checkpointsAfter(null)).hasSize(2);
    }

    @Test
    void aRunsRecordsComeInAnyOrderAcrossRequestsAndEachIsKeptOnce() {
        ArchiveSink sink = sink(10, 100);
        List<byte[]> shuffled = new ArrayList<>(List.of(logged(RUN, 3), logged(RUN, 1)));
        sink.offer(shuffled);
        ArchiveSink.Taken taken = sink.offer(List.of(logged(RUN, 2), logged(RUN, 1), logged(RUN, 3), logged(RUN, 4)));

        assertThat(taken).isEqualTo(new ArchiveSink.Taken(2, 2, 0));
        assertThat(sink.waiting()).isEqualTo(4);
    }
}
