package nuna.catalog.server.governance;

import java.util.Set;
import tools.jackson.databind.JsonNode;

/**
 * Whether a journaled commit changed the rows a table holds (backlog 330), for Contents' Last data change, which the
 * worker's own commits would otherwise fill: on a table it keeps, most commits are its snapshot expiries.
 *
 * <p>A data change is a commit whose updates add a snapshot that appends, overwrites or deletes rows (Iceberg's
 * {@code append}, {@code overwrite} and {@code delete} operations, or a snapshot that names none, which nothing proves
 * left the rows alone); one that merges a catalog branch into the line; one that moves {@code main} to a snapshot
 * without adding one (a rollback, or a cherry-pick that fast-forwards); and a registration, or a revive, of a metadata
 * file, which makes the table read that file's rows. Not a data change: a {@code replace} snapshot, which compaction
 * and manifest rewrites add, holding the same rows in other files; a commit that adds no snapshot (an expiry, a schema,
 * spec, sort order or property change, a ref other than main moved); and a create, which holds no rows yet.
 */
final class DataChanges {

    /** The snapshot operations that change the rows; {@code replace} keeps them. */
    private static final Set<String> CHANGING = Set.of("append", "overwrite", "delete");

    private DataChanges() {
    }

    /** Whether the commit whose updates (the journal's {@code updatesJson}, parsed) these are changed the rows. */
    static boolean changesData(JsonNode updates) {
        if (updates.isObject()) {
            // A create, a registration or a revive keeps its request; only the last two name a metadata file.
            return updates.hasNonNull("metadata-location");
        }
        boolean added = false;
        boolean movesMain = false;
        for (JsonNode update : updates) {
            switch (update.path("action").asString("")) {
                case "add-snapshot" -> {
                    added = true;
                    JsonNode operation = update.path("snapshot").path("summary").path("operation");
                    if (operation.isMissingNode() || operation.isNull() || CHANGING.contains(operation.asString())) {
                        return true;
                    }
                }
                case "merge-branch" -> {
                    return true;
                }
                case "set-snapshot-ref" -> movesMain |= "main".equals(update.path("ref-name").asString(""));
                default -> {
                }
            }
        }
        return movesMain && !added;
    }
}
