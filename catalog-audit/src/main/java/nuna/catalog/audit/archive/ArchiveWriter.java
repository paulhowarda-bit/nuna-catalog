package nuna.catalog.audit.archive;

import static java.nio.charset.StandardCharsets.US_ASCII;

import nuna.catalog.audit.CatalogEvent;
import nuna.catalog.audit.EventCodec;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.List;
import java.util.Objects;

/**
 * Appends events to one archive segment as a hash chain (D23; format in {@link Chain}). A new
 * segment continues from the previous segment's head, so the chain runs across segments.
 *
 * <p>After a failed write the segment's tail is unknown, so the writer refuses further appends:
 * verify the segment and resume from its last good head. Not safe for concurrent use.
 */
public final class ArchiveWriter {

    private final OutputStream segment;
    private ChainHead head;
    private boolean failed;

    /** @param previous the head the first record links to: {@link ChainHead#GENESIS}, or the previous segment's */
    public ArchiveWriter(OutputStream segment, ChainHead previous) {
        this.segment = Objects.requireNonNull(segment, "segment");
        this.head = Objects.requireNonNull(previous, "previous");
    }

    /** Writes the events in order and flushes; returns the new head. */
    public ChainHead append(List<CatalogEvent> events) throws IOException {
        return appendLogged(events.stream().map(EventCodec::encode).toList());
    }

    /**
     * Writes the records in order, each the JSON of one CloudEvent as the log holds it, kept byte for byte, and
     * flushes; returns the new head.
     *
     * @throws IllegalArgumentException if a record is empty or breaks a line, which would break the chain's format;
     *                                  nothing is written then
     */
    public ChainHead appendLogged(List<byte[]> logged) throws IOException {
        for (byte[] record : logged) {
            if (!fitsOnALine(record)) {
                throw new IllegalArgumentException("an archived record is one line of JSON, not empty and with no"
                        + " line break");
            }
        }
        if (failed) {
            throw new IllegalStateException("an earlier write to this segment failed; verify it and resume from its head");
        }
        ByteArrayOutputStream records = new ByteArrayOutputStream();
        byte[] hash = Chain.HEX.parseHex(head.hash());
        long sequence = head.sequence();
        for (byte[] record : logged) {
            sequence++;
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            body.writeBytes((sequence + " ").getBytes(US_ASCII));
            body.writeBytes(record);
            hash = Chain.link(hash, body.toByteArray());
            records.writeBytes(Chain.HEX.formatHex(hash).getBytes(US_ASCII));
            records.write(' ');
            body.writeTo(records);
            records.write('\n');
        }
        try {
            records.writeTo(segment);
            segment.flush();
        } catch (IOException e) {
            failed = true;
            throw e;
        }
        head = new ChainHead(sequence, Chain.HEX.formatHex(hash));
        return head;
    }

    public ChainHead head() {
        return head;
    }

    /** Whether {@code record} can be archived as it is: not empty, and no line feed or carriage return in it. */
    public static boolean fitsOnALine(byte[] record) {
        if (record.length == 0) {
            return false;
        }
        for (byte b : record) {
            if (b == '\n' || b == '\r') {
                return false;
            }
        }
        return true;
    }
}
