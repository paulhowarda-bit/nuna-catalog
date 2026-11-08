package fidice.catalog.store.oracle;

import static fidice.catalog.store.oracle.Jdbc.update;

import fidice.catalog.store.CommitBatch;
import fidice.catalog.store.CommitResult;
import fidice.catalog.store.Pointer;
import fidice.catalog.store.PointerKey;
import fidice.catalog.store.PointerSwap;
import java.io.ByteArrayOutputStream;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Pointer rows and the commit algorithm (docs/storage-design.md section 4). A projection longer
 * than {@link #PIECE_BYTES} is stored in pieces: the first in the pointer row, the rest in
 * projection_piece under the pointer's version (D8.5).
 */
final class Pointers {

    /** The size budget of one stored projection piece. */
    static final int PIECE_BYTES = 1 << 20;

    private Pointers() {
    }

    /** The pointer and its projection, read by one statement so the pieces match the version. */
    static Optional<Pointer> read(Connection connection, PointerKey key) throws SQLException {
        String sql = "SELECT p.metadata_location, p.version, p.sha256, p.projection, pp.bytes"
                + " FROM pointer p LEFT JOIN projection_piece pp"
                + " ON pp.object_id = p.object_id AND pp.branch = p.branch AND pp.version = p.version"
                + " WHERE p.object_id = ? AND p.branch = ? ORDER BY pp.piece";
        try (PreparedStatement select = connection.prepareStatement(sql)) {
            Jdbc.bind(select, key.objectId(), key.branch());
            try (ResultSet row = select.executeQuery()) {
                if (!row.next()) {
                    return Optional.empty();
                }
                String location = Jdbc.text(row, 1);
                long version = row.getLong(2);
                String sha256 = Jdbc.text(row, 3);
                ByteArrayOutputStream projection = new ByteArrayOutputStream();
                projection.writeBytes(bytes(row.getBytes(4)));
                do {
                    byte[] piece = row.getBytes(5);
                    if (piece != null) {
                        projection.writeBytes(piece);
                    }
                } while (row.next());
                return Optional.of(new Pointer(location, version, sha256, projection.toByteArray()));
            }
        }
    }

    /** Locks every pointer in key order, compares, and writes the batch's swaps, journal and records. */
    static CommitResult apply(Connection connection, CommitBatch batch) throws SQLException {
        Optional<CommitResult.Conflict> conflict = lockAndCompare(connection, batch);
        if (conflict.isPresent()) {
            return conflict.get();
        }
        write(connection, batch);
        ChangeRecords.write(connection, batch.commitId(), batch.committedAt(), batch.outbox(), batch.idempotency());
        return new CommitResult.Committed(batch.commitId());
    }

    /**
     * A create inserts its row here: the primary key is its lock, and a concurrent creator waits on
     * it; a failed insert rolls back only itself. An update locks the row alone, then reads the
     * whole pointer only to report a conflict.
     */
    private static Optional<CommitResult.Conflict> lockAndCompare(Connection connection, CommitBatch batch)
            throws SQLException {
        for (PointerSwap swap : batch.swaps()) {
            if (swap.expected().isEmpty()) {
                try {
                    insert(connection, swap, batch);
                    continue;
                } catch (SQLIntegrityConstraintViolationException exists) {
                    if (!Jdbc.violates(exists, "POINTER_PK")) {
                        throw exists;
                    }
                }
            } else {
                Pointer expected = swap.expected().get();
                Optional<Locked> locked = Jdbc.first(connection, "SELECT version, metadata_location FROM pointer"
                                + " WHERE object_id = ? AND branch = ? FOR UPDATE",
                        row -> new Locked(row.getLong(1), Jdbc.text(row, 2)), swap.key().objectId(), swap.key().branch());
                if (locked.isPresent() && locked.get().version() == expected.version()
                        && locked.get().location().equals(expected.metadataLocation())) {
                    continue;
                }
            }
            return Optional.of(new CommitResult.Conflict(swap.key(), read(connection, swap.key())));
        }
        return Optional.empty();
    }

    private record Locked(long version, String location) {
    }

    private static void insert(Connection connection, PointerSwap swap, CommitBatch batch) throws SQLException {
        byte[][] pieces = pieces(swap.next().projection());
        List<Object> parameters = new ArrayList<>(List.of(swap.key().objectId(), swap.key().branch(),
                swap.next().metadataLocation(), swap.next().version(), swap.next().sha256()));
        String projection = blob(pieces[0], parameters);
        parameters.addAll(List.of(batch.commitId(), batch.committedAt()));
        update(connection, "INSERT INTO pointer (object_id, branch, metadata_location, version, sha256, projection,"
                + " last_commit_id, updated_at) VALUES (?, ?, ?, ?, ?, " + projection + ", ?, ?)", parameters.toArray());
        writePieces(connection, swap, pieces);
    }

    private static void write(Connection connection, CommitBatch batch) throws SQLException {
        for (PointerSwap swap : batch.swaps()) {
            if (swap.expected().isPresent()) {
                byte[][] pieces = pieces(swap.next().projection());
                List<Object> parameters = new ArrayList<>(List.of(swap.next().metadataLocation(), swap.next().version(),
                        swap.next().sha256()));
                String projection = blob(pieces[0], parameters);
                parameters.addAll(List.of(batch.commitId(), batch.committedAt(), swap.key().objectId(), swap.key().branch()));
                update(connection, "UPDATE pointer SET metadata_location = ?, version = ?, sha256 = ?, projection = "
                        + projection + ", last_commit_id = ?, updated_at = ? WHERE object_id = ? AND branch = ?",
                        parameters.toArray());
                update(connection, "DELETE FROM projection_piece WHERE object_id = ? AND branch = ?",
                        swap.key().objectId(), swap.key().branch());
                writePieces(connection, swap, pieces);
            }
        }
        List<Object[]> journal = new ArrayList<>();
        for (PointerSwap swap : batch.swaps()) {
            journal.add(new Object[] {batch.commitId(), swap.key().objectId(), swap.key().branch(), batch.committedAt(),
                    swap.journal().principal(), swap.journal().endUser(), swap.journal().engine(),
                    swap.journal().requirementsJson(), swap.journal().updatesJson(),
                    swap.expected().map(Pointer::metadataLocation).orElse(null), swap.next().metadataLocation(),
                    swap.expected().map(Pointer::version).orElse(null), swap.next().version()});
        }
        Jdbc.batch(connection, "INSERT INTO journal (commit_id, object_id, branch, committed_at, principal, end_user,"
                + " engine, requirements, updates, old_location, new_location, old_version, new_version)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", journal);
    }

    private static void writePieces(Connection connection, PointerSwap swap, byte[][] pieces) throws SQLException {
        List<Object[]> rows = new ArrayList<>();
        for (int piece = 1; piece < pieces.length; piece++) {
            rows.add(new Object[] {swap.key().objectId(), swap.key().branch(), swap.next().version(), piece, pieces[piece]});
        }
        Jdbc.batch(connection, "INSERT INTO projection_piece (object_id, branch, version, piece, bytes)"
                + " VALUES (?, ?, ?, ?, ?)", rows);
    }

    /**
     * The SQL for a BLOB value: Oracle stores a zero-length RAW as NULL, so an empty projection is
     * EMPTY_BLOB(), and anything else is a parameter.
     */
    private static String blob(byte[] value, List<Object> parameters) {
        if (value.length == 0) {
            return "EMPTY_BLOB()";
        }
        parameters.add(value);
        return "?";
    }

    private static byte[] bytes(byte[] stored) {
        return stored == null ? new byte[0] : stored;
    }

    /** At least one piece, each at most the budget. */
    static byte[][] pieces(byte[] projection) {
        int count = Math.max(1, (projection.length + PIECE_BYTES - 1) / PIECE_BYTES);
        byte[][] pieces = new byte[count][];
        for (int i = 0; i < count; i++) {
            pieces[i] = Arrays.copyOfRange(projection, i * PIECE_BYTES, Math.min(projection.length, (i + 1) * PIECE_BYTES));
        }
        return pieces;
    }
}
