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
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * The archive's commit records as the DR reconcile reads them (D37, backlog 10): every chain is read whole and
 * checked against its hash chain before any record counts, and an archive that cannot be read, or does not verify,
 * fails the read rather than proving less. Over an archive in memory ({@link MemoryS3}), so it needs no store.
 */
class ArchivedCommitsTest {

    private static final String ROOT = "s3://lake/warehouse/";
    private static final Securable ORDERS = new Securable(Securable.Kind.TABLE, UUID.randomUUID(), "Trading",
            List.of("eq", "orders"));
    private static final Securable TRADES = new Securable(Securable.Kind.TABLE, UUID.randomUUID(), "Trading",
            List.of("eq", "trades"));

    private final MemoryS3 s3 = new MemoryS3();
    private final ObjectLockArchive archive = new ObjectLockArchive(s3.client(), "audit-archive", "audit",
            ObjectLockArchive.MINIMUM_RETENTION, Clock.systemUTC());
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
            archive.write(chain, start, records.toByteArray(), head, head.sequence());
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

    private byte[] get(String key) {
        return s3.client().getObjectAsBytes(b -> b.bucket("audit-archive").key(key).versionId("v1")).asByteArray();
    }
}
