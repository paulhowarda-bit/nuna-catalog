package keel.catalog.core.commit;

import keel.catalog.store.IdempotencyResult;
import java.util.UUID;

/**
 * How an idempotency key's answer is stored (D10). A success keeps only where its metadata file is
 * and the file's hash: the replay reads the file again and checks it, so a key never holds a copy
 * of the metadata. A transaction's success is a 204 with no body. A definite 4xx keeps its error type
 * and message.
 */
final class Replies {

    static final int APPLIED = 204;

    private Replies() {
    }

    sealed interface Stored {
    }

    record Success(UUID objectId, String location, String sha256) implements Stored {
    }

    record Applied() implements Stored {
    }

    record Failure(int status, String type, String message) implements Stored {
    }

    static String success(UUID objectId, String location, String sha256) {
        return objectId + "\n" + location + "\n" + sha256;
    }

    static String failure(String type, String message) {
        return type + "\n" + message;
    }

    /** Locations and hashes hold no line breaks; a message may, so it is the last field. */
    static Stored decode(IdempotencyResult result) {
        if (result.status() == 200) {
            String[] parts = result.body().split("\n", 3);
            return new Success(UUID.fromString(parts[0]), parts[1], parts[2]);
        }
        if (result.status() == APPLIED) {
            return new Applied();
        }
        String[] parts = result.body().split("\n", 2);
        return new Failure(result.status(), parts[0], parts.length > 1 ? parts[1] : "");
    }
}
