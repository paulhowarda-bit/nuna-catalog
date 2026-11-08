package fidice.catalog.audit.archive;

/** The outcome of {@link ArchiveVerifier#verify}. */
public sealed interface Verification {

    /** Every record links to the one before, and the chain ends at the expected head. */
    record Intact(long records, ChainHead head) implements Verification {
    }

    /**
     * The first defect found.
     *
     * @param line   the 1-based line where it was found; for a head mismatch, the last line read
     * @param defect what is wrong
     * @param detail what was expected and what was found
     */
    record Broken(long line, Defect defect, String detail) implements Verification {
    }

    enum Defect {
        /** A line is not a record: no hash field, no sequence field, or no closing line feed. */
        MALFORMED_RECORD,
        /** A record is not the next in sequence: one was deleted, inserted or moved. */
        SEQUENCE_BREAK,
        /** A record's hash does not cover its content and the record before it: it was changed. */
        HASH_MISMATCH,
        /** The chain is intact but ends somewhere other than the expected head. */
        HEAD_MISMATCH
    }
}
