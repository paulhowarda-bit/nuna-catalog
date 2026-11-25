package nuna.catalog.audit.archive;

import static org.assertj.core.api.Assertions.assertThat;

import nuna.catalog.audit.CatalogEvent;
import nuna.catalog.audit.EventCodec;
import nuna.catalog.audit.LogStamp;
import nuna.catalog.audit.RunEnd;
import nuna.catalog.audit.SampleEvents;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.sync.RequestBody;

/**
 * The archive check of the log path (D23, amended 2026-11-25; backlog 262), on the archive writer's own output: each
 * run's chain verified up to the newest head a checkpoint gave it, its numbers followed for gaps, repeats and its end,
 * and the checkpoints followed for their links and their lateness.
 */
class ArchiveCheckTest {

    private static final UUID RUN_A = UUID.fromString("a0000000-0000-4000-8000-000000000001");
    private static final UUID RUN_B = UUID.fromString("b0000000-0000-4000-8000-000000000002");
    private static final Duration INTERVAL = Duration.ofMinutes(5);

    private final MovingClock clock = new MovingClock(Instant.parse("2026-11-25T10:00:00Z"));
    private final MemoryS3 s3 = new MemoryS3(clock);
    private final ObjectLockArchive archive = new ObjectLockArchive(s3.client(), "audit-archive", "audit",
            ObjectLockArchive.MINIMUM_RETENTION, clock);
    private final ArchiveSink sink = new ArchiveSink(archive, 3, Duration.ofMinutes(1), INTERVAL, 1000, clock);
    private final ArchiveCheck check = new ArchiveCheck(archive, clock);

    /** Record {@code n} of {@code run}, as the run logged it. */
    static byte[] logged(UUID run, long n) {
        CatalogEvent event = SampleEvents.event(SampleEvents.oneOfEachType().get((int) (n % 8)), (int) n);
        return EventCodec.encode(event, new LogStamp(run, n));
    }

    /** The run's run-end record, its {@code n}th. */
    static byte[] ended(UUID run, long n) {
        return EventCodec.encode(SampleEvents.event(new RunEnd("nuna-catalog-0"), 9000 + (int) n),
                new LogStamp(run, n));
    }

    private void offer(UUID run, long... numbers) {
        List<byte[]> bodies = new ArrayList<>();
        for (long n : numbers) {
            bodies.add(logged(run, n));
        }
        sink.offer(bodies);
    }

    private static long[] range(long from, long to) {
        return LongStream.rangeClosed(from, to).toArray();
    }

    private ArchiveCheck.ChainResult chain(ArchiveCheck.Result result, UUID run) {
        return result.chains().stream().filter(c -> c.chain().equals(run.toString())).findFirst().orElseThrow();
    }

    @Test
    void verifiesEachRunToTheNewestHeadACheckpointGaveItAndNoFurther() {
        offer(RUN_A, range(1, 6));
        offer(RUN_B, range(1, 2));
        sink.tick();
        clock.advance(Duration.ofMinutes(2));
        assertThat(sink.tick().segments()).as("run B's two records, old enough now").isEqualTo(1);
        // Written as a segment, but no checkpoint gives the chain its head yet.
        ChainHead six = chainHead(RUN_A);
        archive.write(RUN_A.toString(), six, segment(six, logged(RUN_A, 7)), next(six, logged(RUN_A, 7)));

        ArchiveCheck.Result result = check.run();

        assertThat(result.intact()).isTrue();
        assertThat(result.complete()).isTrue();
        assertThat(result.checkpoints().late()).isFalse();
        ArchiveCheck.ChainResult a = chain(result, RUN_A);
        assertThat(a.verified().head().sequence()).as("to the checkpoint's head, not the segment after it")
                .isEqualTo(6);
        assertThat(a.records()).isEqualTo(6);
        assertThat(a.verified().highest()).isEqualTo(6);
        assertThat(a.verified().through()).isEqualTo(6);
        assertThat(chain(result, RUN_B).verified().highest()).isEqualTo(2);
    }

    @Test
    void aLaterRunReadsOnlyWhatWasWrittenSinceAndAFreshCheckReadsItsStateFromTheBucket() {
        offer(RUN_A, range(1, 30));
        sink.tick();
        check.run();
        long fetched = s3.fetched();

        offer(RUN_A, range(31, 33));
        sink.tick();
        ArchiveCheck.Result later = new ArchiveCheck(archive, clock).run();

        assertThat(chain(later, RUN_A).records()).isEqualTo(3);
        assertThat(chain(later, RUN_A).verified().through()).isEqualTo(33);
        assertThat(s3.fetched() - fetched).as("the new segment, the new checkpoint and the state: a fraction")
                .isLessThan(fetched / 3);
    }

    @Test
    void numbersMissingFromARunAreReportedOnceTheyHaveBeenMissingForTwoIntervalsAndALateOneFillsItsGap() {
        offer(RUN_A, 1, 2, 5, 6);
        sink.tick();
        ArchiveCheck.Result first = check.run();
        assertThat(chain(first, RUN_A).missing()).as("not yet: a late record may still land").isEmpty();
        assertThat(chain(first, RUN_A).verified().missing()).hasSize(1);
        assertThat(chain(first, RUN_A).verified().through()).isEqualTo(2);

        clock.advance(INTERVAL.multipliedBy(2).plusSeconds(1));
        offer(RUN_A, 3);
        sink.tick();
        ArchiveCheck.Result later = check.run();

        assertThat(chain(later, RUN_A).missing()).containsExactly(new ArchiveCheck.Range(4, 4));
        assertThat(later.complete()).isFalse();
        assertThat(later.intact()).as("nothing archived was changed").isTrue();
        assertThat(chain(later, RUN_A).verified().through()).isEqualTo(3);
    }

    @Test
    void aRecordSentAgainIsARepeatNotAGap() {
        offer(RUN_A, range(1, 4));
        sink.finish();
        // A writer that restarted does not know what its predecessor took.
        ArchiveSink restarted = new ArchiveSink(archive, 3, Duration.ofMinutes(1), INTERVAL, 1000, clock);
        restarted.offer(List.of(logged(RUN_A, 3), logged(RUN_A, 4), logged(RUN_A, 5)));
        restarted.finish();

        ArchiveCheck.Result result = check.run();

        ArchiveCheck.ChainResult a = chain(result, RUN_A);
        assertThat(a.verified().repeats()).isEqualTo(2);
        assertThat(a.missing()).isEmpty();
        assertThat(a.verified().through()).isEqualTo(5);
        assertThat(result.intact()).isTrue();
    }

    @Test
    void aRunThatEndsWithEveryNumberIsDoneAndOneThatStopsWithoutItsEndIsUnendedAfterADay() {
        offer(RUN_A, range(1, 4));
        sink.offer(List.of(ended(RUN_A, 5)));
        offer(RUN_B, range(1, 2));
        clock.advance(Duration.ofMinutes(2));
        sink.tick();
        ArchiveCheck.Result first = check.run();
        assertThat(chain(first, RUN_A).verified().ended()).isTrue();
        assertThat(chain(first, RUN_A).verified().done()).isTrue();
        assertThat(chain(first, RUN_B).unended()).isFalse();

        clock.advance(ArchiveCheck.UNENDED_AFTER.plusMinutes(1));
        sink.tick();
        ArchiveCheck.Result dayLater = check.run();

        assertThat(chain(dayLater, RUN_B).unended()).isTrue();
        assertThat(chain(dayLater, RUN_A).unended()).isFalse();
        assertThat(dayLater.complete()).isFalse();
        assertThat(dayLater.intact()).isTrue();
    }

    @Test
    void aChangedRecordIsFoundAndTheVerifiedHeadStaysWhereItWas() {
        offer(RUN_A, range(1, 3));
        sink.tick();
        check.run();
        offer(RUN_A, range(4, 6));
        sink.tick();
        String key = archive.segments(RUN_A.toString()).getLast().key();
        byte[] bytes = s3.bytes(key);
        bytes[bytes.length - 10] ^= 1;
        s3.overwrite(key, bytes);

        ArchiveCheck.Result result = check.run();

        assertThat(result.intact()).isFalse();
        assertThat(chain(result, RUN_A).problems()).singleElement().asString().contains("after record 3");
        assertThat(chain(result, RUN_A).verified().head().sequence()).isEqualTo(3);
        assertThat(chain(check.run(), RUN_A).problems()).as("found again on every run").hasSize(1);
    }

    @Test
    void aDeleteMarkerAndASecondVersionOnASegmentKeyAreReported() {
        offer(RUN_A, range(1, 3));
        sink.tick();
        String key = archive.segments(RUN_A.toString()).getFirst().key();
        s3.client().deleteObject(b -> b.bucket("audit-archive").key(key));
        s3.client().putObject(b -> b.bucket("audit-archive").key(key).ifNoneMatch("*"),
                RequestBody.fromString("forged\n"));

        ArchiveCheck.Result result = check.run();

        assertThat(result.intact()).isFalse();
        assertThat(chain(result, RUN_A).problems()).anyMatch(p -> p.startsWith("a delete marker on " + key))
                .anyMatch(p -> p.startsWith("a second version of " + key));
        assertThat(chain(result, RUN_A).verified().head().sequence()).as("read by the first version all the same")
                .isEqualTo(3);
    }

    @Test
    void aWriterThatStopsShowsAsALateCheckpoint() {
        assertThat(check.run().checkpoints().late()).as("no checkpoint at all").isTrue();
        sink.tick();
        assertThat(check.run().checkpoints().late()).isFalse();

        clock.advance(INTERVAL.multipliedBy(2).plusSeconds(1));
        ArchiveCheck.Result result = check.run();

        assertThat(result.checkpoints().late()).isTrue();
        assertThat(result.intact()).isFalse();
        sink.tick();
        assertThat(check.run().checkpoints().late()).as("an idle writer still checkpoints").isFalse();
    }

    @Test
    void aCheckpointThatDoesNotFollowTheOneBeforeOrGivesAChainAShorterHeadIsReported() {
        offer(RUN_A, range(1, 6));
        sink.tick();
        ArchiveCheck.Result before = check.run();
        assertThat(before.intact()).isTrue();
        clock.advance(Duration.ofSeconds(1));
        ChainHead three = archive.segments(RUN_A.toString()).getFirst().end();
        archive.writeCheckpoint(clock.instant(), new Checkpoint("forger", clock.instant(), INTERVAL,
                ObjectLockArchive.MINIMUM_RETENTION, Optional.of(new Checkpoint.Link("audit/checkpoints/x", "00")),
                List.of(new Checkpoint.Anchor(RUN_A.toString(), three, "k"))).toJson());

        ArchiveCheck.Result result = check.run();

        assertThat(result.checkpoints().problems()).anyMatch(p -> p.contains("as the one before it"))
                .anyMatch(p -> p.contains("short of"));
        assertThat(result.intact()).isFalse();
    }

    @Test
    void aSegmentNoCheckpointCoversIsReportedOnceItIsOlderThanTwoIntervals() {
        archive.write(RUN_A.toString(), ChainHead.GENESIS, segment(ChainHead.GENESIS, logged(RUN_A, 1)),
                next(ChainHead.GENESIS, logged(RUN_A, 1)));
        sink.tick();
        assertThat(chain(check.run(), RUN_A).problems()).isEmpty();

        clock.advance(INTERVAL.multipliedBy(2).plusSeconds(1));
        sink.tick();

        assertThat(chain(check.run(), RUN_A).problems()).singleElement().asString()
                .contains("no checkpoint covers it");
    }

    @Test
    void aRecordOfAnotherRunInARunsChainIsReported() {
        ChainHead end = next(ChainHead.GENESIS, logged(RUN_B, 1));
        ObjectLockArchive.Segment forged = archive.write(RUN_A.toString(), ChainHead.GENESIS,
                segment(ChainHead.GENESIS, logged(RUN_B, 1)), end);
        archive.writeCheckpoint(clock.instant(), new Checkpoint("w", clock.instant(), INTERVAL,
                ObjectLockArchive.MINIMUM_RETENTION, Optional.empty(),
                List.of(new Checkpoint.Anchor(RUN_A.toString(), end, forged.key()))).toJson());

        assertThat(chain(check.run(), RUN_A).problems()).singleElement().asString()
                .contains("belongs to run " + RUN_B);
    }

    @Test
    void recordsWithNoStampGoToTheWritersOwnChainAndVerify() {
        sink.offer(List.of(EventCodec.encode(SampleEvents.events().getFirst()),
                EventCodec.encode(SampleEvents.events().get(1))));
        sink.finish();

        ArchiveCheck.Result result = check.run();

        ArchiveCheck.ChainResult unstamped = result.chains().getFirst();
        assertThat(unstamped.chain()).isEqualTo("unstamped-" + sink.writer());
        assertThat(unstamped.run()).isFalse();
        assertThat(unstamped.verified().head().sequence()).isEqualTo(2);
        assertThat(result.intact()).isTrue();
        assertThat(result.complete()).isTrue();
    }

    /** The head of the run's chain as the archive holds it. */
    private ChainHead chainHead(UUID run) {
        return archive.last(run.toString()).orElseThrow().end();
    }

    private static byte[] segment(ChainHead from, byte[] record) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            new ArchiveWriter(out, from).appendLogged(List.of(record));
        } catch (IOException e) {
            throw new AssertionError(e);
        }
        return out.toByteArray();
    }

    private static ChainHead next(ChainHead from, byte[] record) {
        try {
            return new ArchiveWriter(new ByteArrayOutputStream(), from).appendLogged(List.of(record));
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    /** A clock the test moves. */
    static final class MovingClock extends Clock {

        private Instant now;

        MovingClock(Instant now) {
            this.now = now;
        }

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
