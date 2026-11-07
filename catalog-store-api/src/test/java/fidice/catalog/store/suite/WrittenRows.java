package fidice.catalog.store.suite;

import fidice.catalog.store.CommitBatch;

/**
 * The rows one commit left in the database: pointers it last set, and its journal, outbox and
 * idempotency rows.
 */
public record WrittenRows(int pointers, int journal, int outbox, int idempotency) {

    public static final WrittenRows NONE = new WrittenRows(0, 0, 0, 0);

    /** Every row {@code batch} writes when it commits. */
    public static WrittenRows allOf(CommitBatch batch) {
        return new WrittenRows(batch.swaps().size(), batch.swaps().size(), batch.outbox().size(),
                batch.idempotency().isPresent() ? 1 : 0);
    }
}
