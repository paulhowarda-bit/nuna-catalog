package keel.catalog.audit.archive;

import static java.nio.charset.StandardCharsets.US_ASCII;

import keel.catalog.audit.CatalogEvent;
import keel.catalog.audit.EventCodec;
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
        if (failed) {
            throw new IllegalStateException("an earlier write to this segment failed; verify it and resume from its head");
        }
        ByteArrayOutputStream records = new ByteArrayOutputStream();
        byte[] hash = Chain.HEX.parseHex(head.hash());
        long sequence = head.sequence();
        for (CatalogEvent event : events) {
            sequence++;
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            body.writeBytes((sequence + " ").getBytes(US_ASCII));
            body.writeBytes(EventCodec.encode(event));
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
}
