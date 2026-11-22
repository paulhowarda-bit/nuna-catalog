package nuna.catalog.audit.archive;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import nuna.catalog.audit.CatalogEvent;
import nuna.catalog.audit.SampleEvents;
import nuna.catalog.audit.archive.ArchiveCheck.Anchor;
import nuna.catalog.audit.archive.ArchiveCheck.ChainResult;
import nuna.catalog.audit.archive.ArchiveCheck.Checked;
import nuna.catalog.audit.archive.ArchiveCheck.Result;
import nuna.catalog.audit.archive.ObjectLockArchive.Placement;
import nuna.catalog.audit.archive.ObjectLockArchive.Segment;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The archive check and a record's place in its chain (D23; backlog 125), on an in-memory bucket: each chain verified
 * to the archive consumer's anchor and no further, a later run reading only what was written since, a changed record
 * or a lost tail found and the verified head kept, and a record found by its offset with its neighbours.
 */
class ArchiveCheckTest {

    private static final String TOPIC = "nuna.catalog.audit";
    private static final String P0 = ArchiveConsumer.chain(TOPIC, 0);
    private static final String P1 = ArchiveConsumer.chain(TOPIC, 1);
    private static final Instant NOW = Instant.parse("2026-11-22T06:00:00Z");

    private final MemoryS3 s3 = new MemoryS3();
    private final ObjectLockArchive archive = new ObjectLockArchive(s3.client(), "audit-archive", "audit",
            ObjectLockArchive.MINIMUM_RETENTION, Clock.fixed(NOW, ZoneOffset.UTC));
    private final FakeHeads heads = new FakeHeads();
    private final ArchiveCheck check = new ArchiveCheck(archive, TOPIC, heads, Clock.fixed(NOW, ZoneOffset.UTC));
    private final Map<String, ChainHead> ends = new HashMap<>();
    private final Map<String, Long> nextOffsets = new HashMap<>();
    private final Map<String, List<CatalogEvent>> written = new HashMap<>();
    private int n;

    @Test
    void verifiesEachChainToItsAnchorAndNoFurther() throws IOException {
        Segment first = segment(P0, 5);
        Segment second = segment(P0, 7);
        segment(P0, 3); // written, not yet anchored
        Segment other = segment(P1, 4);
        heads.anchor(0, second);
        heads.anchor(1, other);

        Result result = check.run();

        assertThat(result.intact()).isTrue();
        assertThat(chain(result, 0).verified()).contains(new Checked(second.end(), second.nextOffset(), second.key(),
                NOW));
        assertThat(chain(result, 0).records()).isEqualTo(12);
        assertThat(chain(result, 1).verified().orElseThrow().head()).isEqualTo(other.end());
        assertThat(heads.checked).containsOnlyKeys(0, 1);
        assertThat(heads.checked.get(0).nextOffset()).isEqualTo(first.nextOffset() + 7);
    }

    @Test
    void aLaterRunReadsOnlyWhatWasWrittenSince() throws IOException {
        segment(P0, 40);
        Segment second = segment(P0, 40);
        heads.anchor(0, second);
        check.run();
        Segment third = segment(P0, 6);
        heads.anchor(0, third);
        long before = s3.fetched();

        Result result = check.run();

        assertThat(result.intact()).isTrue();
        assertThat(chain(result, 0).records()).isEqualTo(6);
        assertThat(s3.fetched() - before).as("the third segment's bytes only")
                .isEqualTo(s3.bytes(third.key()).length);
        assertThat(heads.checked.get(0).head()).isEqualTo(third.end());
    }

    @Test
    void aChangedRecordIsFoundAndTheVerifiedHeadStaysWhereItWas() throws IOException {
        Segment first = segment(P0, 5);
        heads.anchor(0, first);
        check.run();
        Segment second = segment(P0, 5);
        heads.anchor(0, second);
        String text = new String(s3.bytes(second.key()), UTF_8);
        s3.overwrite(second.key(), text.replaceFirst("2026", "2027").getBytes(UTF_8));

        Result result = check.run();
        Result again = check.run();

        assertThat(result.intact()).isFalse();
        assertThat(chain(result, 0).broken()).hasValueSatisfying(why -> assertThat(why).contains("HASH_MISMATCH"));
        assertThat(heads.checked.get(0).head()).as("kept at the last head verified").isEqualTo(first.end());
        assertThat(chain(again, 0).broken()).as("found again on every run").isPresent();
    }

    @Test
    void anAnchorPastTheArchivesLastSegmentIsALostTail() throws IOException {
        Segment first = segment(P0, 5);
        heads.anchors.put(0, new Anchor(first.nextOffset() + 3, new ChainHead(first.end().sequence() + 3,
                "1".repeat(64))));

        Result result = check.run();

        assertThat(chain(result, 0).broken()).hasValueSatisfying(why -> assertThat(why)
                .contains("no segment of the chain ends there"));
        assertThat(heads.checked).isEmpty();
    }

    @Test
    void aChainWithNothingAnchoredHasNothingVerified() throws IOException {
        segment(P0, 5);

        Result result = check.run();

        assertThat(result.intact()).isTrue();
        assertThat(chain(result, 0).verified()).isEmpty();
        assertThat(heads.checked).isEmpty();
    }

    @Test
    void whatWasVerifiedIsKeptAsACommittedOffsetsMetadata() {
        Checked checked = new Checked(new ChainHead(12, "ab".repeat(32)), 40, "audit/nuna.catalog.audit-0/x.log", NOW);

        assertThat(Checked.parse(40, checked.metadata())).contains(checked);
        assertThat(Checked.parse(0, new Checked(ChainHead.GENESIS, 0, null, NOW).metadata()))
                .contains(new Checked(ChainHead.GENESIS, 0, null, NOW));
        assertThat(Checked.parse(40, "12:" + "ab".repeat(32))).as("the archive consumer's own anchor").isEmpty();
        assertThat(Checked.parse(40, null)).isEmpty();
    }

    @Test
    void placesARecordByItsOffsetWithItsNeighbours() throws IOException {
        // The chain starts at offset 100: older records were gone from the topic when archiving began.
        nextOffsets.put(P0, 100L);
        segment(P0, 4);
        segment(P0, 3);
        List<String[]> lines = lines(P0);
        List<CatalogEvent> events = written.get(P0);

        Placement middle = archive.place(P0, 101, events.get(1).id().toString(), 10_000).orElseThrow();
        assertThat(middle.sequence()).isEqualTo(2);
        assertThat(middle.hash()).isEqualTo(lines.get(1)[0]);
        assertThat(middle.previous()).isEqualTo(lines.get(0)[0]);
        assertThat(middle.next()).contains(lines.get(2)[0]);
        assertThat(middle.holds()).isTrue();

        Placement lastOfFirst = archive.place(P0, 103, events.get(3).id().toString(), 10_000).orElseThrow();
        assertThat(lastOfFirst.next()).as("the next segment's first record").contains(lines.get(4)[0]);
        Placement firstOfSecond = archive.place(P0, 104, events.get(4).id().toString(), 2).orElseThrow();
        assertThat(firstOfSecond.previous()).as("the segment's start head").isEqualTo(lines.get(3)[0]);
        assertThat(firstOfSecond.segment().start().sequence()).isEqualTo(4);
        Placement newest = archive.place(P0, 106, events.get(6).id().toString(), 10_000).orElseThrow();
        assertThat(newest.next()).as("the chain's last record so far").isEmpty();

        assertThat(archive.place(P0, 107, "not-archived-yet", 10_000)).isEmpty();
        assertThat(archive.place(P0, 99, "before-the-archive", 10_000)).isEmpty();
        assertThat(archive.place(P1, 0, "no-chain", 10_000)).isEmpty();
    }

    @Test
    void aPlacedRecordWhoseHashNoLongerHoldsSaysSo() throws IOException {
        Segment first = segment(P0, 4);
        CatalogEvent third = written.get(P0).get(2);
        String text = new String(s3.bytes(first.key()), UTF_8);
        String[] records = text.split("\n");
        records[2] = records[2].replace("\"time\":\"2026", "\"time\":\"2027");
        s3.overwrite(first.key(), (String.join("\n", records) + "\n").getBytes(UTF_8));

        Placement placed = archive.place(P0, 2, third.id().toString(), 10_000).orElseThrow();

        assertThat(placed.holds()).isFalse();
        assertThat(archive.place(P0, 1, written.get(P0).get(1).id().toString(), 10_000).orElseThrow().holds())
                .isTrue();
    }

    // ---- helpers ----

    /** Writes a segment of {@code count} new records to the chain, continuing from its head and offset. */
    private Segment segment(String chain, int count) throws IOException {
        ChainHead start = ends.getOrDefault(chain, ChainHead.GENESIS);
        List<CatalogEvent> events = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            events.add(SampleEvents.event(SampleEvents.oneOfEachType().get(n % 8), ++n));
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ChainHead end = new ArchiveWriter(bytes, start).append(events);
        long next = nextOffsets.getOrDefault(chain, 0L) + count;
        Segment segment = archive.write(chain, start, bytes.toByteArray(), end, next);
        ends.put(chain, end);
        nextOffsets.put(chain, next);
        written.computeIfAbsent(chain, c -> new ArrayList<>()).addAll(events);
        return segment;
    }

    /** Every record of the chain as stored: its hash and its body. */
    private List<String[]> lines(String chain) {
        List<String[]> lines = new ArrayList<>();
        for (Segment segment : archive.segments(chain)) {
            for (String line : new String(s3.bytes(segment.key()), UTF_8).split("\n")) {
                lines.add(line.split(" ", 2));
            }
        }
        return lines;
    }

    private static ChainResult chain(Result result, int partition) {
        return result.chains().stream().filter(c -> c.partition() == partition).findFirst().orElseThrow();
    }

    /** The anchors and the verified heads, in memory: the server keeps them in Kafka. */
    private static final class FakeHeads implements ArchiveCheck.Heads {

        final Map<Integer, Anchor> anchors = new HashMap<>();
        final Map<Integer, Checked> checked = new HashMap<>();

        void anchor(int partition, Segment segment) {
            anchors.put(partition, new Anchor(segment.nextOffset(), segment.end()));
        }

        @Override
        public List<Integer> partitions() {
            return List.of(0, 1);
        }

        @Override
        public Map<Integer, Anchor> anchors() {
            return Map.copyOf(anchors);
        }

        @Override
        public Map<Integer, Checked> checked() {
            return Map.copyOf(checked);
        }

        @Override
        public void record(Map<Integer, Checked> verified) {
            checked.putAll(verified);
        }
    }
}
