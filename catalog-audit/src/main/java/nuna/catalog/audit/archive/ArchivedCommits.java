package nuna.catalog.audit.archive;

import nuna.catalog.audit.CatalogEvent;
import nuna.catalog.audit.Commit;
import nuna.catalog.core.commit.Reconcile;
import nuna.catalog.store.PointerKey;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * The pointer swaps the archive records, as the DR reconcile's proof that a tip was committed (D37, backlog 10). A
 * swap's commit record goes to the outbox in the swap's own transaction (D23), so the archive holds one only for a
 * swap that committed on the primary. A record counts only once the chain holding it verifies.
 */
public final class ArchivedCommits {

    /**
     * @param commits    each table's or view's swaps of its main pointer, by object id, in archive order
     * @param chains     how many chains were read
     * @param records    how many commit records they hold, on any branch
     */
    public record Read(Map<UUID, List<Reconcile.ArchivedCommit>> commits, int chains, int records) {
    }

    private ArchivedCommits() {
    }

    /**
     * Every chain of {@code archive}, each read in one pass, segment by segment, and checked from genesis to its last
     * segment's recorded end ({@link ObjectLockArchive#verify}) before any of its records counts. Of each chain only
     * its commit records are kept, so the read holds the swaps, not the chain (backlog 86).
     *
     * @throws IllegalStateException if a chain does not verify
     */
    public static Read read(ObjectLockArchive archive) {
        Map<UUID, List<Reconcile.ArchivedCommit>> commits = new HashMap<>();
        int chains = 0;
        int records = 0;
        for (String chain : archive.chains()) {
            ChainCommits held = new ChainCommits();
            archive.forEachEvent(chain, Optional.empty(), held);
            chains++;
            records += held.records;
            held.swaps.forEach((id, swaps) -> commits.computeIfAbsent(id, k -> new ArrayList<>()).addAll(swaps));
        }
        return new Read(commits, chains, records);
    }

    /**
     * One chain's commit records, held aside until the chain verifies: its swaps of a main pointer, by object id, and
     * how many there are on any branch. A creation replaced nothing, so it is no swap.
     */
    private static final class ChainCommits implements Consumer<CatalogEvent> {

        private final Map<UUID, List<Reconcile.ArchivedCommit>> swaps = new HashMap<>();
        private int records;

        @Override
        public void accept(CatalogEvent event) {
            if (event.data() instanceof Commit commit) {
                records++;
                if (commit.before() != null && PointerKey.MAIN.equals(commit.branch())) {
                    swaps.computeIfAbsent(commit.object().id(), id -> new ArrayList<>()).add(new Reconcile.ArchivedCommit(
                            commit.before().metadataLocation(), commit.after().metadataLocation(),
                            commit.after().version(), commit.after().sha256()));
                }
            }
        }
    }
}
