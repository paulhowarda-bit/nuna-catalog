package nuna.catalog.store.mongo;

import static nuna.catalog.store.mongo.Docs.ascending;
import static nuna.catalog.store.mongo.Docs.int64;
import static nuna.catalog.store.mongo.Docs.keyFilter;
import static nuna.catalog.store.mongo.Docs.text;
import static nuna.catalog.store.mongo.Docs.time;
import static nuna.catalog.store.mongo.Docs.uuid;
import static nuna.catalog.store.mongo.MongoCatalogStore.JOURNAL;
import static nuna.catalog.store.mongo.MongoCatalogStore.OBJECT;
import static nuna.catalog.store.mongo.MongoCatalogStore.POINTER;
import static nuna.catalog.store.mongo.MongoCatalogStore.PROJECTION_PIECE;

import com.mongodb.client.ClientSession;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.UpdateOptions;
import nuna.catalog.store.CatalogObject;
import nuna.catalog.store.CommitBatch;
import nuna.catalog.store.CommitResult;
import nuna.catalog.store.DrWatermark;
import nuna.catalog.store.Pointer;
import nuna.catalog.store.PointerHold;
import nuna.catalog.store.PointerKey;
import nuna.catalog.store.PointerLock;
import nuna.catalog.store.PointerSwap;
import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.bson.BsonBinary;
import org.bson.BsonBoolean;
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

    /** Counts the clocks of the batch's swapped and held pointers (storage-design 9 b). Before the stamp is fixed. */
    static void see(Tx tx, CommitBatch batch) {
        for (PointerLock lock : batch.locks()) {
            BsonDocument clock = tx.collection(POINTER).find(tx.session, keyFilter(lock.key()))
                    .projection(new BsonDocument("clock", new BsonInt32(1))).first();
            if (clock != null) {
                tx.see(clock);
            }
        }
    }

    /**
     * The first swap whose object is no longer active (dropped, unregistered or purged), or else marks
     * the object of every swap. Reads take no lock, so the mark, a write of the object's clock, is what
     * makes a concurrent drop or unregister of the object conflict with this commit. An object the store
     * has no document for is not checked. Call after {@link #see}, before the stamp is fixed.
     */
    static Optional<CommitResult.Inactive> markObjects(Tx tx, CommitBatch batch) {
        List<BsonDocument> objects = new ArrayList<>();
        for (PointerSwap swap : batch.swaps()) {
            Optional<BsonDocument> object = tx.find(OBJECT, Docs.byId(uuid(swap.key().objectId())));
            if (object.isEmpty()) {
                continue;
            }
            if (!object.get().getString("state").getValue().equals(CatalogObject.State.ACTIVE.name())) {
                return Optional.of(new CommitResult.Inactive(swap.key()));
            }
            if (objects.stream().noneMatch(seen -> seen.get("_id").equals(object.get().get("_id")))) {
                objects.add(tx.see(object.get()));
            }
        }
        objects.forEach(object -> tx.mark(OBJECT, object));
        return Optional.empty();
    }

    /**
     * Compares and writes every swapped pointer, and compares and marks every held one, in key order.
     * When nothing matched, the transaction's snapshot shows what is there instead.
     */
    static Optional<CommitResult.Conflict> swap(Tx tx, CommitBatch batch, int pieceBytes) {
        for (PointerLock lock : batch.locks()) {
            boolean matched = switch (lock) {
                case PointerSwap swap -> swapOne(tx, batch, swap, pieceBytes);
                case PointerHold hold when hold.expected().isEmpty() -> holdAbsent(tx, hold.key());
                case PointerHold hold -> holdOne(tx, hold);
            };
            if (!matched) {
                return Optional.of(new CommitResult.Conflict(lock.key(), read(tx.database, tx.session, lock.key())));
            }
        }
        return Optional.empty();
    }

    /**
     * The write is conditional on the expected pointer, so it is the compare and the write at once; a
     * create inserts only if no pointer exists.
     */
    private static boolean swapOne(Tx tx, CommitBatch batch, PointerSwap swap, int pieceBytes) {
        MongoCollection<BsonDocument> pointers = tx.collection(POINTER);
        byte[][] pieces = pieces(swap.next().projection(), pieceBytes);
        BsonDocument next = fields(swap.next(), pieces, batch.commitId(), batch.committedAt()).append("clock", tx.clock());
        boolean written;
        if (swap.expected().isEmpty()) {
            written = pointers.updateOne(tx.session, keyFilter(swap.key()), new BsonDocument("$setOnInsert", next),
                    new UpdateOptions().upsert(true)).getUpsertedId() != null;
        } else {
            written = pointers.updateOne(tx.session, expectedFilter(swap.key(), swap.expected().get()),
                    new BsonDocument("$set", next)).getMatchedCount() == 1;
            if (written) {
                tx.collection(PROJECTION_PIECE).deleteMany(tx.session, keyFilter(swap.key()));
            }
        }
        if (written) {
            writePieces(tx, swap, pieces);
        }
        return written;
    }

    /**
     * Marks the held pointer if it is the expected one. Reads take no lock here, so the mark, a real
     * write of the clock alone, is what conflicts with a concurrent swap of it (section 9, Writes).
     */
    private static boolean holdOne(Tx tx, PointerHold hold) {
        BsonDocument held = tx.collection(POINTER).find(tx.session, expectedFilter(hold.key(), hold.expected().get()))
                .projection(new BsonDocument("clock", new BsonInt32(1))).first();
        if (held == null) {
            return false;
        }
        tx.mark(POINTER, held);
        return true;
    }

    /**
     * Takes the key of a pointer the batch holds absent, as a create takes it, and says whether it is absent (backlog
     * 278). A read takes no lock and a plain write matches nothing, so the hold upserts a placeholder and deletes it at
     * once: the upsert meets an open create of the key, or one committed after this snapshot, with
     * {@code WriteConflict}, and the transaction runs again and finds the pointer; once this transaction has written
     * the key, a create that comes later conflicts with it the same way and runs again after it, finding the key free.
     * No document is left, and no reader sees the placeholder.
     */
    private static boolean holdAbsent(Tx tx, PointerKey key) {
        MongoCollection<BsonDocument> pointers = tx.collection(POINTER);
        boolean absent = pointers.updateOne(tx.session, keyFilter(key),
                new BsonDocument("$setOnInsert", new BsonDocument("placeholder", BsonBoolean.TRUE)),
                new UpdateOptions().upsert(true)).getUpsertedId() != null;
        if (absent) {
            pointers.deleteOne(tx.session, keyFilter(key));
        }
        return absent;
    }

    private static BsonDocument expectedFilter(PointerKey key, Pointer expected) {
        return keyFilter(key)
                .append("version", int64(expected.version()))
                .append("metadataLocation", new BsonString(expected.metadataLocation()));
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
        for (PointerSwap swap : batch.swaps()) {
            if (swap.drWatermark()) {
                MongoDr.raise(tx, swap.key().objectId(), new DrWatermark(swap.next().version(),
                        swap.next().metadataLocation(), batch.committedAt()));
            }
        }
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
