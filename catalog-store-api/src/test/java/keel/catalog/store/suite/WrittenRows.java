package keel.catalog.store.suite;

import keel.catalog.store.Change;
import keel.catalog.store.CommitBatch;

/**
 * The rows one commit or change left in the database: pointers it last set, and its journal,
 * outbox and idempotency rows. A change sets no pointer and writes no journal.
 */
public record WrittenRows(int pointers, int journal, int outbox, int idempotency) {

    public static final WrittenRows NONE = new WrittenRows(0, 0, 0, 0);

    /** Every row {@code batch} writes when it commits. */
    public static WrittenRows allOf(CommitBatch batch) {
        return new WrittenRows(batch.swaps().size(), batch.swaps().size(), batch.outbox().size(),
                batch.idempotency().isPresent() ? 1 : 0);
    }

    /** Every row {@code change} writes when it applies: its outbox events and idempotency result. */
    public static WrittenRows allOf(Change change) {
        return new WrittenRows(0, 0, change.outbox().size(), change.idempotency().isPresent() ? 1 : 0);
    }
}
