package nuna.catalog.audit.archive;

import java.util.Objects;

/**
 * The end of a hash chain: the last record's sequence number and hash. A head kept outside the
 * archive is what exposes a deleted tail or a rewritten chain.
 *
 * @param sequence the last record's sequence number; 0 before the first record
 * @param hash     the last record's SHA-256, lower-case hex
 */
public record ChainHead(long sequence, String hash) {

    /** The head before the first record. */
    public static final ChainHead GENESIS = new ChainHead(0, "0".repeat(64));

    public ChainHead {
        Objects.requireNonNull(hash, "hash");
    }
}
