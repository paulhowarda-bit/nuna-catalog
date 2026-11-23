package nuna.maintenance;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.iceberg.TableMetadataParser;
import org.apache.iceberg.io.FileIO;

/**
 * What the catalog's refs reach of a table's files (D33, P11, item 26's choice 14; item 108). A branch or tag keeps
 * every file its metadata reaches for the table, a tag's cut, a branch's cut and its own head, as the orphan scan
 * reaches main's: so a file that expiry, an orphan scan or a manifest rewrite leaves unreferenced on main is recorded
 * for deletion only when no ref reaches it either, and a due file recorded before a ref was made is checked against
 * that ref before it goes.
 *
 * <p>A file is kept for the first kind of ref that reaches it, branches before tags: each kind is read in a pass that
 * skips the snapshots and manifests the passes before it read.
 */
final class RefReach {

    /** Files split by what reaches them: those no ref reaches, and how many each kind of ref keeps. */
    record Split(List<String> unreached, Map<Management.RefKind, Integer> kept) {
    }

    private RefReach() {
    }

    /**
     * Splits {@code files} by whether any of {@code refs} reaches them, reading each ref's metadata with {@code io}
     * (the table's, which reads its location). {@code onMain} are snapshots, by id, that reach none of
     * {@code files}, main's own after the job that left them unreferenced: they are not read again. Nothing is read
     * when there are no files or no refs.
     */
    static Split split(Collection<String> files, List<Management.RefWork> refs, FileIO io, Set<Long> onMain) {
        Map<Management.RefKind, Integer> kept = new EnumMap<>(Management.RefKind.class);
        if (files.isEmpty() || refs.isEmpty()) {
            return new Split(List.copyOf(files), kept);
        }
        Map<Management.RefKind, OrphanScan.Reach> reaches = new EnumMap<>(Management.RefKind.class);
        OrphanScan.Reach reach = OrphanScan.Reach.skipping(onMain);
        for (Management.RefKind kind : Management.RefKind.values()) {
            for (Management.RefWork ref : refs) {
                if (ref.kind() == kind) {
                    for (String location : ref.metadata()) {
                        reach.collect(TableMetadataParser.read(io, location), io);
                    }
                }
            }
            reaches.put(kind, reach);
            reach = reach.next();
        }
        List<String> unreached = new ArrayList<>();
        for (String file : files) {
            String normalized = Storage.normalize(file);
            Management.RefKind keeper = null;
            for (Map.Entry<Management.RefKind, OrphanScan.Reach> kind : reaches.entrySet()) {
                if (kind.getValue().files.contains(normalized)) {
                    keeper = kind.getKey();
                    break;
                }
            }
            if (keeper == null) {
                unreached.add(file);
            } else {
                kept.merge(keeper, 1, Integer::sum);
            }
        }
        return new Split(List.copyOf(unreached), kept);
    }
}
