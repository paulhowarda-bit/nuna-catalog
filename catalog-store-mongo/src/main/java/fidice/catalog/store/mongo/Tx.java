package fidice.catalog.store.mongo;

import static fidice.catalog.store.mongo.Docs.byId;

import com.mongodb.client.ClientSession;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import java.util.Optional;
import org.bson.BsonDocument;
import org.bson.BsonInt64;
import org.bson.conversions.Bson;

/**
 * One attempt of a write transaction: its session, and the Lamport clock of the change
 * (storage-design 9 b). Every document the change writes or deletes is {@link #see seen} first;
 * the change's {@link #stamp} is one more than every clock seen, and each document it writes,
 * and each of its outbox entries, takes that stamp. So of two changes that wrote one document,
 * the later one stamps higher, and the relay claims its events after the earlier one's.
 *
 * <p>MongoDB has no shared locks. A write that depends on a record it does not change (a
 * namespace under its parent, a grant on its securable) {@link #mark marks} that record with the
 * stamp, a real write, so a concurrent drop of the record conflicts with it.
 */
final class Tx {

    final ClientSession session;
    final MongoDatabase database;
    private long seen;
    private long stamp;

    Tx(ClientSession session, MongoDatabase database) {
        this.session = session;
        this.database = database;
    }

    MongoCollection<BsonDocument> collection(String name) {
        return database.getCollection(name, BsonDocument.class);
    }

    /** The first document matching {@code filter}, as this transaction's snapshot shows it. */
    Optional<BsonDocument> find(String collection, Bson filter) {
        return Optional.ofNullable(collection(collection).find(session, filter).first());
    }

    boolean exists(String collection, Bson filter) {
        return collection(collection).find(session, filter).projection(new BsonDocument("_id", new BsonInt64(1)))
                .first() != null;
    }

    /** Counts the clock of a document this change writes, marks or deletes. Only before {@link #stamp}. */
    BsonDocument see(BsonDocument doc) {
        if (stamp != 0) {
            throw new IllegalStateException("a document was seen after the change's stamp was fixed");
        }
        seen = Math.max(seen, Docs.clock(doc));
        return doc;
    }

    /** The change's stamp: one more than every clock seen, fixed from the first call. */
    long stamp() {
        if (stamp == 0) {
            stamp = seen + 1;
        }
        return stamp;
    }

    BsonInt64 clock() {
        return new BsonInt64(stamp());
    }

    /**
     * Sets the stamp on a seen document that the change depends on but does not otherwise change.
     * The stamp is above the document's clock, so this always writes, and conflicts with any
     * concurrent write of the document.
     */
    void mark(String collection, BsonDocument doc) {
        if (Docs.clock(doc) >= stamp()) {
            throw new IllegalStateException("a marked document must be seen before the stamp is fixed");
        }
        collection(collection).updateOne(session, byId(doc.get("_id")),
                new BsonDocument("$set", new BsonDocument("clock", clock())));
    }
}
