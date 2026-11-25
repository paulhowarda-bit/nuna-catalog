package nuna.catalog.audit.archive;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import nuna.catalog.audit.CatalogEvent;
import nuna.catalog.audit.Commit;
import nuna.catalog.audit.GrantChange;
import nuna.catalog.audit.PointerState;
import nuna.catalog.audit.SampleEvents;
import nuna.catalog.audit.Securable;
import nuna.catalog.core.commit.Reconcile;
import nuna.catalog.audit.archive.Verification.Broken;
import nuna.catalog.audit.archive.Verification.Defect;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.s3.model.ObjectLockMode;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * The archive's commit records as the DR reconcile reads them (D37, backlog 10): every chain is read in one pass,
 * segment by segment, a buffer of it at a time (backlog 86), and checked against its hash chain before any record
 * counts, and an archive that cannot be read, or does not verify, fails the read rather than proving less. Over an
 * archive in memory ({@link MemoryS3}), so it needs no store.
 */
class ArchivedCommitsTest {

    private static final String ROOT = "s3://lake/warehouse/";
    private static final Securable ORDERS = new Securable(Securable.Kind.TABLE, UUID.randomUUID(), "Trading",
            List.of("eq", "orders"));
    private static final Securable TRADES = new Securable(Securable.Kind.TABLE, UUID.randomUUID(), "Trading",
            List.of("eq", "trades"));

    /** The long chain: 40 segments of 25 records, each record longer than the small reader's buffer. */
    private static final String LONG = "nuna.catalog.audit-7";
    private static final int RECORDS = 1000;
    private static final int PER_SEGMENT = 25;
    /** What the small reader holds of a chain at once, besides the record it is checking. */
    private static final int SMALL = 256;

    private final MemoryS3 s3 = new MemoryS3();
    private final ObjectLockArchive archive = new ObjectLockArchive(s3.client(), "audit-archive", "audit",
            ObjectLockArchive.MINIMUM_RETENTION, Clock.systemUTC());
    private final ObjectLockArchive small = new ObjectLockArchive(s3.client(), "audit-archive", "audit",
            ObjectLockArchive.MINIMUM_RETENTION, Clock.systemUTC(), ObjectLockMode.COMPLIANCE, SMALL);
    private int n;

    private static PointerState state(Securable table, int version) {
        String hex = Integer.toHexString(version);
        return new PointerState(ROOT + table.id() + "/metadata/0000" + version + "-x.metadata.json", version,
                "0".repeat(64 - hex.length()) + hex);
    }

    private CatalogEvent commit(Securable table, String branch, Integer from, int to) {
        return SampleEvents.event(new Commit(UUID.randomUUID(), table, branch, from == null ? null : state(table, from),
                state(table, to), "[]", SampleEvents.ACTOR), ++n);
    }

    private CatalogEvent other() {
        return SampleEvents.event(new GrantChange(GrantChange.Action.GRANTED, ORDERS, "readers", "select",
                SampleEvents.ACTOR), ++n);
    }

    /** Writes {@code events} as segments of {@code chain}, at most {@code perSegment} records each. */
    private void archive(String chain, int perSegment, CatalogEvent... events) throws IOException {
        ChainHead head = ChainHead.GENESIS;
        for (int from = 0; from < events.length; from += perSegment) {
            ByteArrayOutputStream records = new ByteArrayOutputStream();
            ChainHead start = head;
            head = new ArchiveWriter(records, start).append(List.of(events).subList(from,
                    Math.min(events.length, from + perSegment)));
            archive.write(chain, start, records.toByteArray(), head);
        }
    }

    /**
     * {@link #RECORDS} records for {@link #LONG}, four at a time: a swap of ORDERS' main pointer, another record, a swap
     * of TRADES on another branch and a swap of its main pointer.
     */
    private CatalogEvent[] longChain() {
        CatalogEvent[] events = new CatalogEvent[RECORDS];
        for (int i = 0; i < RECORDS; i++) {
            int version = i / 4 + 1;
            events[i] = switch (i % 4) {
                case 0 -> commit(ORDERS, "main", version, version + 1);
                case 1 -> other();
                case 2 -> commit(TRADES, "audit-branch", version, version + 1);
                default -> commit(TRADES, "main", version, version + 1);
            };
        }
        return events;
    }

    /** Every stored segment, in the order written: the chain as the read before backlog 86 held it, whole. */
    private byte[] stored() {
        ByteArrayOutputStream all = new ByteArrayOutputStream();
        s3.keys().forEach(key -> all.writeBytes(s3.bytes(key)));
        return all.toByteArray();
    }

    /** Changes the {@code index}th record of {@code key}: the hash stored with it no longer covers it. */
    private void forge(String key, int index) {
        List<String> lines = new ArrayList<>(List.of(new String(s3.bytes(key), UTF_8).split("\n")));
        String forged = lines.get(index).replace("alice@example.com", "mallory@example.com");
        assertThat(forged).isNotEqualTo(lines.get(index));
        lines.set(index, forged);
        s3.overwrite(key, (String.join("\n", lines) + "\n").getBytes(UTF_8));
    }

    /**
     * The read fails, with the default buffer and with the small one, naming the defect and line that the check of the
     * whole chain names: the check the read made before backlog 86, of every segment fetched and joined.
     */
    private void failsAsTheWholeChainsCheckDoes(Defect defect, long line) throws IOException {
        Verification whole = ArchiveVerifier.verify(new ByteArrayInputStream(stored()), ChainHead.GENESIS,
                archive.last(LONG).orElseThrow().end());
        assertThat(whole).isInstanceOfSatisfying(Broken.class, broken -> {
            assertThat(broken.defect()).isEqualTo(defect);
            assertThat(broken.line()).isEqualTo(line);
        });
        for (ObjectLockArchive reader : List.of(archive, small)) {
            assertThatThrownBy(() -> ArchivedCommits.read(reader)).isInstanceOf(IllegalStateException.class)
                    .hasMessage("archive chain " + LONG + " is broken: " + whole);
        }
    }

    private static Reconcile.ArchivedCommit swap(Securable table, int from, int to) {
        return new Reconcile.ArchivedCommit(state(table, from).metadataLocation(), state(table, to).metadataLocation(),
                to, state(table, to).sha256());
    }

    @Test
    @DisplayName("every chain's swaps of a main pointer, by table, in archive order; creations, other branches and"
            + " other records prove nothing")
    void readsEachTablesSwapsFromEveryChain() throws IOException {
        archive("nuna.catalog.audit-0", 2, commit(ORDERS, "main", null, 1), other(), commit(ORDERS, "main", 1, 2),
                commit(ORDERS, "audit-branch", 2, 3), commit(ORDERS, "main", 2, 3));
        archive("nuna.catalog.audit-1", 10, commit(TRADES, "main", 4, 5), other());

        ArchivedCommits.Read read = ArchivedCommits.read(archive);

        assertThat(read.chains()).isEqualTo(2);
        assertThat(read.records()).isEqualTo(5);
        assertThat(read.commits()).containsOnlyKeys(ORDERS.id(), TRADES.id());
        assertThat(read.commits().get(ORDERS.id())).containsExactly(swap(ORDERS, 1, 2), swap(ORDERS, 2, 3));
        assertThat(read.commits().get(TRADES.id())).containsExactly(swap(TRADES, 4, 5));
    }

    @Test
    @DisplayName("an empty archive is read as one that proves nothing")
    void anEmptyArchiveProvesNothing() {
        ArchivedCommits.Read read = ArchivedCommits.read(archive);

        assertThat(read.chains()).isZero();
        assertThat(read.commits()).isEmpty();
    }

    @Test
    @DisplayName("a chain whose stored bytes no longer match its hashes fails the read: none of its records counts")
    void aChainThatDoesNotVerifyFailsTheRead() throws IOException {
        archive("nuna.catalog.audit-0", 2, commit(ORDERS, "main", 1, 2), commit(ORDERS, "main", 2, 3));
        String segment = s3.keys().getFirst();
        // The segment's first record now says the table moved to another file: the hash stored with it no longer covers it.
        List<String> lines = new ArrayList<>(List.of(new String(get(segment), UTF_8).split("\n")));
        lines.set(0, lines.getFirst().replace(state(ORDERS, 2).metadataLocation(), ROOT + "forged.metadata.json"));
        s3.overwrite(segment, (String.join("\n", lines) + "\n").getBytes(UTF_8));

        assertThatThrownBy(() -> ArchivedCommits.read(archive)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("nuna.catalog.audit-0").hasMessageContaining("broken")
                .hasMessageContaining("HASH_MISMATCH");
    }

    @Test
    @DisplayName("a chain whose last segment was emptied fails the read: it ends short of the head its segments record")
    void aChainCutShortFailsTheRead() throws IOException {
        archive("nuna.catalog.audit-0", 1, commit(ORDERS, "main", 1, 2), commit(ORDERS, "main", 2, 3));
        s3.overwrite(s3.keys().getLast(), new byte[0]);

        assertThatThrownBy(() -> ArchivedCommits.read(archive)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("HEAD_MISMATCH");
    }

    @Test
    @DisplayName("a store that cannot be read fails the read with the store's failure")
    void aStoreThatCannotBeReadFailsTheRead() throws IOException {
        archive("nuna.catalog.audit-0", 2, commit(ORDERS, "main", 1, 2));
        s3.failWith(S3Exception.builder().statusCode(503).message("Service Unavailable").build());

        assertThatThrownBy(() -> ArchivedCommits.read(archive)).isInstanceOf(S3Exception.class)
                .hasMessageContaining("Service Unavailable");
    }

    @Test
    @DisplayName("a chain far larger than the reader holds at once is read in one pass, segment by segment: never more"
            + " than one buffer past the record being checked, each segment fetched once, one at a time")
    void readsAChainLargerThanTheReaderHoldsAtOnce() throws IOException {
        CatalogEvent[] events = longChain();
        archive(LONG, PER_SEGMENT, events);
        byte[] stored = stored();
        List<Long> ends = new ArrayList<>();
        for (int i = 0; i < stored.length; i++) {
            if (stored[i] == '\n') {
                ends.add(i + 1L);
            }
        }
        assertThat(s3.keys()).hasSize(RECORDS / PER_SEGMENT);
        assertThat(stored.length).isGreaterThan(1000 * SMALL);
        assertThat(stored.length / RECORDS).isGreaterThan(SMALL);
        long before = s3.fetched();

        List<CatalogEvent> handed = new ArrayList<>();
        small.forEachEvent(LONG, Optional.empty(), event -> {
            assertThat(s3.fetched() - before).as("bytes fetched when record %d is handed over", handed.size() + 1)
                    .isLessThanOrEqualTo(ends.get(handed.size()) + SMALL);
            handed.add(event);
        });

        assertThat(handed).containsExactly(events);
        assertThat(s3.fetched() - before).isEqualTo(stored.length);
        assertThat(s3.mostOpen()).isEqualTo(1);

        ArchivedCommits.Read read = ArchivedCommits.read(small);

        assertThat(read.chains()).isEqualTo(1);
        assertThat(read.records()).isEqualTo(RECORDS / 4 * 3);
        assertThat(read.commits()).containsOnlyKeys(ORDERS.id(), TRADES.id());
        assertThat(read.commits().get(ORDERS.id())).containsExactlyElementsOf(
                IntStream.rangeClosed(1, RECORDS / 4).mapToObj(v -> swap(ORDERS, v, v + 1)).toList());
        assertThat(read.commits().get(TRADES.id())).containsExactlyElementsOf(
                IntStream.rangeClosed(1, RECORDS / 4).mapToObj(v -> swap(TRADES, v, v + 1)).toList());
        assertThat(s3.fetched() - before).isEqualTo(2L * stored.length);
        assertThat(s3.mostOpen()).isEqualTo(1);
    }

    @Test
    @DisplayName("a record changed in a segment in the middle of a long chain fails the read as the whole chain's"
            + " check did: none of the chain's records counts")
    void aTamperedSegmentInTheMiddleFailsTheRead() throws IOException {
        archive(LONG, PER_SEGMENT, longChain());
        forge(s3.keys().get(20), 3);

        failsAsTheWholeChainsCheckDoes(Defect.HASH_MISMATCH, 20 * PER_SEGMENT + 4);
    }

    @Test
    @DisplayName("a segment in the middle of a long chain emptied fails the read as the whole chain's check did, at"
            + " the next segment's first record")
    void anEmptiedSegmentInTheMiddleFailsTheRead() throws IOException {
        archive(LONG, PER_SEGMENT, longChain());
        s3.overwrite(s3.keys().get(20), new byte[0]);

        failsAsTheWholeChainsCheckDoes(Defect.SEQUENCE_BREAK, 20 * PER_SEGMENT + 1);
    }

    @Test
    @DisplayName("a segment the store cannot serve after a break fails the read as unreadable, as it did when every"
            + " segment was fetched before any was checked")
    void aSegmentThatCannotBeFetchedAfterABreakFailsTheReadAsUnreadable() throws IOException {
        archive(LONG, PER_SEGMENT, longChain());
        forge(s3.keys().get(20), 3);
        s3.failGetOf(s3.keys().get(30), S3Exception.builder().statusCode(503).message("Service Unavailable").build());

        assertThatThrownBy(() -> ArchivedCommits.read(small)).isInstanceOf(S3Exception.class)
                .hasMessageContaining("Service Unavailable");
    }

    private byte[] get(String key) {
        return s3.client().getObjectAsBytes(b -> b.bucket("audit-archive").key(key).versionId("v1")).asByteArray();
    }
}
