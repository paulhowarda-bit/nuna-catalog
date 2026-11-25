package nuna.catalog.audit.archive;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import nuna.catalog.audit.EventCodec;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;

/**
 * A logged record's place in its run's chain (backlog 272), on the archive writer's own output: found by its number
 * and event id, with its hash, its neighbours' and whether its link holds, near where its number puts it or, asked to,
 * anywhere in the chain.
 */
class PlacementTest {

    private static final UUID RUN = UUID.fromString("a0000000-0000-4000-8000-000000000272");
    private static final String CHAIN = RUN.toString();

    private final ArchiveCheckTest.MovingClock clock = new ArchiveCheckTest.MovingClock(
            Instant.parse("2026-11-25T10:00:00Z"));
    private final MemoryS3 s3 = new MemoryS3(clock);
    private final ObjectLockArchive archive = new ObjectLockArchive(s3.client(), "audit-archive", "audit",
            ObjectLockArchive.MINIMUM_RETENTION, clock);
    private final ArchiveSink sink = new ArchiveSink(archive, 3, Duration.ofMinutes(1), Duration.ofMinutes(5), 1000,
            clock);

    /** Archives the run's records with these numbers, in this order, three to a segment. */
    private void archived(long... numbers) {
        List<byte[]> bodies = new ArrayList<>();
        for (long n : numbers) {
            bodies.add(ArchiveCheckTest.logged(RUN, n));
        }
        sink.offer(bodies);
        sink.tick();
        clock.advance(Duration.ofMinutes(2));
        sink.tick();
    }

    private static String id(long n) {
        return EventCodec.decode(ArchiveCheckTest.logged(RUN, n)).id().toString();
    }

    /** Every record's stored hash, in chain order. */
    private List<String> hashes() {
        List<String> hashes = new ArrayList<>();
        for (ObjectLockArchive.Segment segment : archive.segments(CHAIN)) {
            new String(archive.read(segment.key(), segment.versionId()), UTF_8).lines()
                    .forEach(line -> hashes.add(line.substring(0, Chain.HASH_HEX_LENGTH)));
        }
        return hashes;
    }

    @Test
    void findsEachRecordWithItsHashItsNeighboursAndALinkThatHolds() {
        archived(LongStream.rangeClosed(1, 10).toArray());
        List<String> hashes = hashes();
        assertThat(archive.segments(CHAIN)).hasSize(4);

        for (long n = 1; n <= 10; n++) {
            ObjectLockArchive.Placement place = archive.place(CHAIN, n, id(n), 1).orElseThrow();
            int at = (int) n - 1;
            assertThat(place.position()).as("record %d", n).isEqualTo(n);
            assertThat(place.hash()).isEqualTo(hashes.get(at));
            assertThat(place.previous()).as("record %d, across segments too", n)
                    .isEqualTo(at == 0 ? ChainHead.GENESIS.hash() : hashes.get(at - 1));
            assertThat(place.next()).isEqualTo(n == 10 ? Optional.empty() : Optional.of(hashes.get(at + 1)));
            assertThat(place.holds()).isTrue();
            assertThat(place.segment().start().sequence()).isLessThan(n);
            assertThat(place.segment().end().sequence()).isGreaterThanOrEqualTo(n);
        }
    }

    @Test
    void aRecordThatArrivedLateIsFoundInTheSegmentAfterItsNumbersPlace() {
        archived(1, 2, 3, 4, 5, 7, 8, 9, 6);

        assertThat(archive.place(CHAIN, 6, id(6), 1)).get().extracting(ObjectLockArchive.Placement::position)
                .isEqualTo(9L);
        assertThat(archive.place(CHAIN, 6, id(6), 0)).as("only where its number puts it").isEmpty();
    }

    @Test
    void aRecordFarFromItsNumbersPlaceIsFoundOnlyWhenTheWholeChainIsSearched() {
        archived(4, 5, 6, 7, 8, 9, 10, 11, 12, 1, 2, 3);

        assertThat(archive.place(CHAIN, 2, id(2), 1)).isEmpty();
        assertThat(archive.place(CHAIN, 2, id(2), Integer.MAX_VALUE)).get()
                .extracting(ObjectLockArchive.Placement::position).isEqualTo(11L);
    }

    @Test
    void aNumberNotArchivedOrAnotherRecordsIdIsNotFound() {
        archived(1, 2, 3, 5);

        assertThat(archive.place(CHAIN, 4, id(4), Integer.MAX_VALUE)).isEmpty();
        assertThat(archive.place(CHAIN, 2, id(3), Integer.MAX_VALUE)).as("number 2 holds another record").isEmpty();
        assertThat(archive.place(UUID.randomUUID().toString(), 1, id(1), 1)).as("a run with no chain").isEmpty();
    }

    @Test
    void aChangedRecordsLinkDoesNotHold() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ChainHead end = new ArchiveWriter(out, ChainHead.GENESIS).appendLogged(List.of(ArchiveCheckTest.logged(RUN, 1),
                ArchiveCheckTest.logged(RUN, 2), ArchiveCheckTest.logged(RUN, 3)));
        List<String> lines = new ArrayList<>(new String(out.toByteArray(), UTF_8).lines().toList());
        String second = lines.get(1);
        lines.set(1, second.replaceFirst("\"time\":\"20", "\"time\":\"19"));
        assertThat(lines.get(1)).isNotEqualTo(second);
        archive.write(CHAIN, ChainHead.GENESIS, (String.join("\n", lines) + "\n").getBytes(UTF_8), end);

        assertThat(archive.place(CHAIN, 2, id(2), 1)).get().extracting(ObjectLockArchive.Placement::holds)
                .isEqualTo(false);
        assertThat(archive.place(CHAIN, 1, id(1), 1)).get().extracting(ObjectLockArchive.Placement::holds)
                .isEqualTo(true);
        assertThat(archive.place(CHAIN, 3, id(3), 1)).get().extracting(ObjectLockArchive.Placement::holds)
                .as("it links to the hash stored before it").isEqualTo(true);
    }
}
