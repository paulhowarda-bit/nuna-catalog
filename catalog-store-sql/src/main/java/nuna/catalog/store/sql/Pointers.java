package nuna.catalog.store.sql;

import static nuna.catalog.store.sql.Jdbc.update;
import static nuna.catalog.store.sql.Jdbc.utc;

import nuna.catalog.store.CommitBatch;
import nuna.catalog.store.CommitResult;
import nuna.catalog.store.Pointer;
import nuna.catalog.store.PointerHold;
import nuna.catalog.store.PointerKey;
import nuna.catalog.store.PointerLock;
import nuna.catalog.store.PointerSwap;
import java.io.ByteArrayOutputStream;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

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
            select.setObject(1, key.objectId());
            select.setString(2, key.branch());
            try (ResultSet row = select.executeQuery()) {
                if (!row.next()) {
                    return Optional.empty();
                }
                String location = row.getString(1);
                long version = row.getLong(2);
                String sha256 = row.getString(3);
                ByteArrayOutputStream projection = new ByteArrayOutputStream();
                projection.writeBytes(row.getBytes(4));
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
        Optional<CommitResult.Inactive> inactive = inactive(connection, batch);
        if (inactive.isPresent()) {
            return inactive.get();
        }
        record(connection, batch);
        return new CommitResult.Committed(batch.commitId());
    }

    /** Locks every pointer of the batch in key order and compares it, as {@link #apply} does first. */
    static Optional<CommitResult.Conflict> lock(Connection connection, CommitBatch batch) throws SQLException {
        return lockAndCompare(connection, batch);
    }

    /** Writes the batch's swaps, journal and records, once {@link #lock} has matched every pointer. */
    static void record(Connection connection, CommitBatch batch) throws SQLException {
        write(connection, batch);
        ChangeRecords.write(connection, batch.commitId(), batch.committedAt(), batch.outbox(), batch.idempotency());
    }

    /**
     * Locks every pointer of the object, by branch, as a commit locks a pointer before its object, and
     * gives the main pointer's version and location; empty when it has none.
     */
    static Optional<Locked> lockObject(Connection connection, UUID objectId) throws SQLException {
        List<Locked> main = Jdbc.query(connection, "SELECT branch, version, metadata_location FROM pointer"
                        + " WHERE object_id = ? ORDER BY branch FOR UPDATE",
                row -> row.getString(1).equals(PointerKey.MAIN) ? new Locked(row.getLong(2), row.getString(3)) : null, objectId);
        return main.stream().filter(java.util.Objects::nonNull).findFirst();
    }

    /**
     * Swapped and held pointers are locked together in key order. A create inserts its row here: the
     * unique key is its lock, and a concurrent creator waits on it. An update or a hold locks the row
     * alone, then reads the whole pointer only to report a conflict: a locking read that joins the
     * pieces could see pieces of an older snapshot.
     */
    private static Optional<CommitResult.Conflict> lockAndCompare(Connection connection, CommitBatch batch)
            throws SQLException {
        for (PointerLock lock : batch.locks()) {
            boolean matched = switch (lock) {
                case PointerSwap swap when swap.expected().isEmpty() -> insert(connection, swap, batch);
                case PointerSwap swap -> lockedAt(connection, swap.key(), swap.expected().get());
                case PointerHold hold -> lockedAt(connection, hold.key(), hold.expected());
            };
            if (!matched) {
                return Optional.of(new CommitResult.Conflict(lock.key(), read(connection, lock.key())));
            }
        }
        return Optional.empty();
    }

    /**
     * The first swap whose object is no longer active: dropped, unregistered or purged since the commit
     * read it. The object row is locked after the pointers, shared, so commits of one object do not wait
     * for each other. Unregister locks the pointers and then the object too; a drop locks the object
     * alone, so either it waits for this commit, or this commit waits for it and reads its new state. An
     * object the store has no row for is not checked.
     */
    private static Optional<CommitResult.Inactive> inactive(Connection connection, CommitBatch batch)
            throws SQLException {
        for (PointerSwap swap : batch.swaps()) {
            Optional<String> state = Jdbc.first(connection, "SELECT state FROM catalog_object WHERE id = ? FOR SHARE",
                    row -> row.getString(1), swap.key().objectId());
            if (state.isPresent() && !state.get().equals("ACTIVE")) {
                return Optional.of(new CommitResult.Inactive(swap.key()));
            }
        }
        return Optional.empty();
    }

    /** Locks the pointer row until the transaction ends, and says whether it is {@code expected}. */
    private static boolean lockedAt(Connection connection, PointerKey key, Pointer expected) throws SQLException {
        Optional<Locked> locked = Jdbc.first(connection, "SELECT version, metadata_location FROM pointer"
                        + " WHERE object_id = ? AND branch = ? FOR UPDATE",
                row -> new Locked(row.getLong(1), row.getString(2)), key.objectId(), key.branch());
        return locked.isPresent() && locked.get().version() == expected.version()
                && locked.get().location().equals(expected.metadataLocation());
    }

    /** A locked pointer's version and location. */
    record Locked(long version, String location) {

        boolean is(Pointer pointer) {
            return version == pointer.version() && location.equals(pointer.metadataLocation());
        }
    }

    private static boolean insert(Connection connection, PointerSwap swap, CommitBatch batch) throws SQLException {
        byte[][] pieces = pieces(swap.next().projection());
        int inserted = update(connection, "INSERT INTO pointer (object_id, branch, metadata_location, version, sha256,"
                        + " projection, last_commit_id, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)"
                        + " ON CONFLICT (object_id, branch) DO NOTHING",
                swap.key().objectId(), swap.key().branch(), swap.next().metadataLocation(), swap.next().version(),
                swap.next().sha256(), pieces[0], batch.commitId(), batch.committedAt());
        if (inserted == 1) {
            writePieces(connection, swap, pieces);
        }
        return inserted == 1;
    }

    private static void write(Connection connection, CommitBatch batch) throws SQLException {
        for (PointerSwap swap : batch.swaps()) {
            if (swap.expected().isPresent()) {
                byte[][] pieces = pieces(swap.next().projection());
                update(connection, "UPDATE pointer SET metadata_location = ?, version = ?, sha256 = ?, projection = ?,"
                                + " last_commit_id = ?, updated_at = ? WHERE object_id = ? AND branch = ?",
                        swap.next().metadataLocation(), swap.next().version(), swap.next().sha256(), pieces[0],
                        batch.commitId(), batch.committedAt(), swap.key().objectId(), swap.key().branch());
                update(connection, "DELETE FROM projection_piece WHERE object_id = ? AND branch = ?",
                        swap.key().objectId(), swap.key().branch());
                writePieces(connection, swap, pieces);
            }
        }
        String journalSql = "INSERT INTO journal (commit_id, object_id, branch, committed_at, principal, end_user,"
                + " engine, requirements, updates, old_location, new_location, old_version, new_version)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement insert = connection.prepareStatement(journalSql)) {
            for (PointerSwap swap : batch.swaps()) {
                insert.setObject(1, batch.commitId());
                insert.setObject(2, swap.key().objectId());
                insert.setString(3, swap.key().branch());
                insert.setObject(4, utc(batch.committedAt()));
                insert.setString(5, swap.journal().principal());
                insert.setString(6, swap.journal().endUser());
                insert.setString(7, swap.journal().engine());
                insert.setObject(8, swap.journal().requirementsJson(), Types.OTHER);
                insert.setObject(9, swap.journal().updatesJson(), Types.OTHER);
                insert.setString(10, swap.expected().map(Pointer::metadataLocation).orElse(null));
                insert.setString(11, swap.next().metadataLocation());
                insert.setObject(12, swap.expected().map(Pointer::version).orElse(null));
                insert.setLong(13, swap.next().version());
                insert.addBatch();
            }
            insert.executeBatch();
        }
    }

    private static void writePieces(Connection connection, PointerSwap swap, byte[][] pieces) throws SQLException {
        if (pieces.length == 1) {
            return;
        }
        String sql = "INSERT INTO projection_piece (object_id, branch, version, piece, bytes) VALUES (?, ?, ?, ?, ?)";
        try (PreparedStatement insert = connection.prepareStatement(sql)) {
            for (int piece = 1; piece < pieces.length; piece++) {
                insert.setObject(1, swap.key().objectId());
                insert.setString(2, swap.key().branch());
                insert.setLong(3, swap.next().version());
                insert.setInt(4, piece);
                insert.setBytes(5, pieces[piece]);
                insert.addBatch();
            }
            insert.executeBatch();
        }
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
