package keel.catalog.audit.archive;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * The archive's record format, one record per line:
 *
 * <pre>{@code <hash> SP <sequence> SP <event JSON> LF}</pre>
 *
 * where {@code hash} is the lower-case hex SHA-256 of the previous record's hash (32 bytes)
 * followed by the record's body, the bytes {@code <sequence> SP <event JSON>}. Sequence numbers
 * start at 1 and grow by 1, and the first record links to {@link ChainHead#GENESIS}.
 */
final class Chain {

    static final int HASH_HEX_LENGTH = 64;
    static final HexFormat HEX = HexFormat.of();

    private Chain() {
    }

    static byte[] link(byte[] previousHash, byte[] body) {
        MessageDigest digest = sha256();
        digest.update(previousHash);
        digest.update(body);
        return digest.digest();
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            // Every Java platform must provide SHA-256 (MessageDigest's contract).
            throw new IllegalStateException(e);
        }
    }
}
