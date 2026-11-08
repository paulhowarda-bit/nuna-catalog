package fidice.catalog.store.mongo;

import static fidice.catalog.store.mongo.Docs.ascending;
import static fidice.catalog.store.mongo.Docs.int64;
import static fidice.catalog.store.mongo.Docs.keyFilter;
import static fidice.catalog.store.mongo.Docs.text;
import static fidice.catalog.store.mongo.Docs.time;
import static fidice.catalog.store.mongo.Docs.uuid;
import static fidice.catalog.store.mongo.MongoCatalogStore.JOURNAL;
import static fidice.catalog.store.mongo.MongoCatalogStore.POINTER;
import static fidice.catalog.store.mongo.MongoCatalogStore.PROJECTION_PIECE;

import com.mongodb.client.ClientSession;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.UpdateOptions;
import fidice.catalog.store.CommitBatch;
import fidice.catalog.store.CommitResult;
import fidice.catalog.store.Pointer;
import fidice.catalog.store.PointerKey;
import fidice.catalog.store.PointerSwap;
import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.bson.BsonBinary;
import org.bson.BsonDocument;
import org.bson.BsonInt32;
import org.bson.BsonInt64;
import org.bson.BsonNull;
import org.bson.BsonString;
import org.bson.BsonValue;
import org.bson.types.ObjectId;

/**
 * Pointer documents, their projection pieces, and the commit core's writes (sections 4 and 7).
 *
 * <p>A projection larger than {@link #PIECE_BYTES} is split: the pointer document holds the first
 * piece and the count of pieces; the rest are {@code projection_piece} documents keyed by
 * pointer, version and piece number. The budget is half the 16 MiB document limit, which leaves
 * room for the pointer's other fields.
 */
final class Pointers {

    static final int PIECE_BYTES = 8 << 20;

    private Pointers() {
    }

    /**
     * The pointer at {@code key} with its projection whole. Every read runs in {@code session},
     * which is a transaction or a snapshot session, so the pieces are those of the version read.
     */
    static Optional<Pointer> read(MongoDatabase database, ClientSession session, PointerKey key) {
        BsonDocument doc = database.getCollection(POINTER, BsonDocument.class).find(session, keyFilter(key)).first();
        if (doc == null) {
            return Optional.empty();
        }
        byte[] first = doc.getBinary("projection").getData();
        // A node of the previous release writes no piece count, and leaves this release's count in
        // place when it writes over a projection in pieces. The pieces are read by version, so a
        // stale count finds none and costs one read.
        if (doc.getInt32("pieces", new BsonInt32(1)).getValue() == 1) {
            return Optional.of(pointer(doc, first));
        }
        ByteArrayOutputStream projection = new ByteArrayOutputStream();
        projection.writeBytes(first);
        database.getCollection(PROJECTION_PIECE, BsonDocument.class)
                .find(session, keyFilter(key).append("version", doc.get("version"))).sort(ascending("piece"))
                .forEach(piece -> projection.writeBytes(piece.getBinary("bytes").getData()));
        return Optional.of(pointer(doc, projection.toByteArray()));
    }

    private static Pointer pointer(BsonDocument doc, byte[] projection) {
        return new Pointer(doc.getString("metadataLocation").getValue(), doc.getInt64("version").getValue(),
                doc.getString("sha256").getValue(), projection);
    }

    /** Counts the clocks of the batch's pointers (storage-design 9 b). Before the stamp is fixed. */
    static void see(Tx tx, CommitBatch batch) {
        for (PointerSwap swap : batch.swaps()) {
            BsonDocument clock = tx.collection(POINTER).find(tx.session, keyFilter(swap.key()))
                    .projection(new BsonDocument("clock", new BsonInt32(1))).first();
            if (clock != null) {
                tx.see(clock);
            }
        }
    }

    /**
     * Compares and writes every pointer in key order. Each write is conditional on the expected
     * pointer, so it is the compare and the write at once; a create inserts only if no pointer
     * exists. When nothing matched, the transaction's snapshot shows what is there instead.
     */
    static Optional<CommitResult.Conflict> swap(Tx tx, CommitBatch batch, int pieceBytes) {
        MongoCollection<BsonDocument> pointers = tx.collection(POINTER);
        for (PointerSwap swap : batch.swaps()) {
            byte[][] pieces = pieces(swap.next().projection(), pieceBytes);
            BsonDocument next = fields(swap.next(), pieces, batch.commitId(), batch.committedAt()).append("clock", tx.clock());
            boolean written;
            if (swap.expected().isEmpty()) {
                written = pointers.updateOne(tx.session, keyFilter(swap.key()), new BsonDocument("$setOnInsert", next),
                        new UpdateOptions().upsert(true)).getUpsertedId() != null;
            } else {
                Pointer expected = swap.expected().get();
                BsonDocument filter = keyFilter(swap.key())
                        .append("version", int64(expected.version()))
                        .append("metadataLocation", new BsonString(expected.metadataLocation()));
                written = pointers.updateOne(tx.session, filter, new BsonDocument("$set", next)).getMatchedCount() == 1;
                if (written) {
                    tx.collection(PROJECTION_PIECE).deleteMany(tx.session, keyFilter(swap.key()));
                }
            }
            if (!written) {
                return Optional.of(new CommitResult.Conflict(swap.key(), read(tx.database, tx.session, swap.key())));
            }
            writePieces(tx, swap, pieces);
        }
        return Optional.empty();
    }

    /** The journal entries of the batch, and its outbox events and idempotency result. */
    static void records(Tx tx, CommitBatch batch, Supplier<ObjectId> entryIds) {
        BsonBinary commitId = uuid(batch.commitId());
        List<BsonDocument> journal = new ArrayList<>();
        for (PointerSwap swap : batch.swaps()) {
            journal.add(new BsonDocument("commitId", commitId)
                    .append("objectId", uuid(swap.key().objectId()))
                    .append("branch", new BsonString(swap.key().branch()))
                    .append("committedAt", time(batch.committedAt()))
                    .append("principal", new BsonString(swap.journal().principal()))
                    .append("endUser", text(swap.journal().endUser()))
                    .append("engine", text(swap.journal().engine()))
                    .append("requirements", new BsonString(swap.journal().requirementsJson()))
                    .append("updates", new BsonString(swap.journal().updatesJson()))
                    .append("oldLocation", text(swap.expected().map(Pointer::metadataLocation).orElse(null)))
                    .append("newLocation", new BsonString(swap.next().metadataLocation()))
                    .append("oldVersion", swap.expected().<BsonValue>map(p -> new BsonInt64(p.version())).orElse(BsonNull.VALUE))
                    .append("newVersion", new BsonInt64(swap.next().version())));
        }
        tx.collection(JOURNAL).insertMany(tx.session, journal);
        ChangeRecords.write(tx, entryIds, batch.commitId(), batch.committedAt(), batch.outbox(), batch.idempotency());
    }

    private static void writePieces(Tx tx, PointerSwap swap, byte[][] pieces) {
        if (pieces.length == 1) {
            return;
        }
        List<BsonDocument> rest = new ArrayList<>();
        for (int piece = 1; piece < pieces.length; piece++) {
            rest.add(keyFilter(swap.key())
                    .append("version", int64(swap.next().version()))
                    .append("piece", new BsonInt32(piece))
                    .append("bytes", new BsonBinary(pieces[piece])));
        }
        tx.collection(PROJECTION_PIECE).insertMany(tx.session, rest);
    }

    private static BsonDocument fields(Pointer pointer, byte[][] pieces, UUID commitId, Instant committedAt) {
        BsonDocument fields = new BsonDocument("metadataLocation", new BsonString(pointer.metadataLocation()))
                .append("version", int64(pointer.version()))
                .append("sha256", new BsonString(pointer.sha256()))
                .append("projection", new BsonBinary(pieces[0]))
                .append("lastCommitId", uuid(commitId))
                .append("updatedAt", time(committedAt))
                .append("pieces", new BsonInt32(pieces.length));
        return fields;
    }

    static byte[][] pieces(byte[] projection, int pieceBytes) {
        int count = Math.max(1, (projection.length + pieceBytes - 1) / pieceBytes);
        byte[][] pieces = new byte[count][];
        for (int i = 0; i < count; i++) {
            pieces[i] = Arrays.copyOfRange(projection, i * pieceBytes, Math.min(projection.length, (i + 1) * pieceBytes));
        }
        return pieces;
    }
}
