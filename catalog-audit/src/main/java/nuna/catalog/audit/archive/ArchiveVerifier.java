package nuna.catalog.audit.archive;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.nio.charset.StandardCharsets.UTF_8;

import nuna.catalog.audit.archive.Verification.Broken;
import nuna.catalog.audit.archive.Verification.Defect;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.function.Consumer;

/**
 * Checks an archive's hash chain (format in {@link Chain}). Hashes cover the stored bytes, so the
 * check needs no knowledge of the events. A chain rewritten from some record onwards is
 * internally consistent; only the head kept outside the archive exposes it.
 */
public final class ArchiveVerifier {

    private ArchiveVerifier() {
    }

    /**
     * @param records      the segments to check, concatenated in order
     * @param start        the head the first record links to
     * @param expectedHead the head the last record must produce
     */
    public static Verification verify(InputStream records, ChainHead start, ChainHead expectedHead)
            throws IOException {
        return verify(new BufferedInputStream(records), start, expectedHead, body -> { });
    }

    /**
     * As {@link #verify(InputStream, ChainHead, ChainHead)}, reading through the caller's buffer, and handing
     * {@code verified} each record's body ({@code <sequence> SP <event JSON>}) once its hash checks, before the next
     * record is read. A chain broken further on has handed over every record before the break, so the caller acts
     * on none of them unless this returns {@link Verification.Intact}.
     */
    static Verification verify(BufferedInputStream in, ChainHead start, ChainHead expectedHead,
                               Consumer<byte[]> verified) throws IOException {
        byte[] previous = Chain.HEX.parseHex(start.hash());
        long sequence = start.sequence();
        long line = 0;
        byte[] record;
        while ((record = nextLine(in)) != null) {
            line++;
            if (record[record.length - 1] != '\n') {
                return new Broken(line, Defect.MALFORMED_RECORD, "the record has no closing line feed");
            }
            if (record.length < Chain.HASH_HEX_LENGTH + 2 || record[Chain.HASH_HEX_LENGTH] != ' ') {
                return new Broken(line, Defect.MALFORMED_RECORD, "the record has no hash field");
            }
            String stored = new String(record, 0, Chain.HASH_HEX_LENGTH, US_ASCII);
            byte[] body = Arrays.copyOfRange(record, Chain.HASH_HEX_LENGTH + 1, record.length - 1);
            byte[] expectedPrefix = ((sequence + 1) + " ").getBytes(US_ASCII);
            if (!Arrays.equals(body, 0, Math.min(expectedPrefix.length, body.length), expectedPrefix, 0,
                    expectedPrefix.length)) {
                return new Broken(line, Defect.SEQUENCE_BREAK,
                        "expected record " + (sequence + 1) + ", found " + sequenceField(body));
            }
            byte[] computed = Chain.link(previous, body);
            if (!Chain.HEX.formatHex(computed).equals(stored)) {
                return new Broken(line, Defect.HASH_MISMATCH, "record " + (sequence + 1) + " stores hash " + stored
                        + " but its content and predecessor hash to " + Chain.HEX.formatHex(computed));
            }
            previous = computed;
            sequence++;
            verified.accept(body);
        }
        ChainHead end = new ChainHead(sequence, Chain.HEX.formatHex(previous));
        if (!end.equals(expectedHead)) {
            return new Broken(line, Defect.HEAD_MISMATCH, "the chain ends at " + end + ", expected " + expectedHead);
        }
        return new Verification.Intact(sequence - start.sequence(), end);
    }

    /** The next line including its line feed, the unterminated rest of the stream, or null at its end. */
    private static byte[] nextLine(InputStream in) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != -1) {
            line.write(b);
            if (b == '\n') {
                break;
            }
        }
        return line.size() == 0 ? null : line.toByteArray();
    }

    private static String sequenceField(byte[] body) {
        int end = 0;
        while (end < body.length && body[end] != ' ' && end < 20) {
            end++;
        }
        return "'" + new String(body, 0, end, UTF_8) + "'";
    }
}
