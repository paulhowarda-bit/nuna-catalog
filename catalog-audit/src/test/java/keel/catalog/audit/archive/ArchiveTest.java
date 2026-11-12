package keel.catalog.audit.archive;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import keel.catalog.audit.CatalogEvent;
import keel.catalog.audit.EventCodec;
import keel.catalog.audit.SampleEvents;
import keel.catalog.audit.archive.Verification.Broken;
import keel.catalog.audit.archive.Verification.Defect;
import keel.catalog.audit.archive.Verification.Intact;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ArchiveTest {

    private static final int RECORDS = 24;

    private final List<CatalogEvent> events = IntStream.range(0, RECORDS)
            .mapToObj(i -> SampleEvents.event(SampleEvents.oneOfEachType().get(i % 8), i + 1)).toList();

    private byte[] archive;
    private ChainHead head;

    @BeforeEach
    void writeArchive() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ArchiveWriter writer = new ArchiveWriter(out, ChainHead.GENESIS);
        writer.append(events.subList(0, 10));
        head = writer.append(events.subList(10, RECORDS));
        archive = out.toByteArray();
    }

    @Test
    void writtenArchiveVerifies() throws IOException {
        assertThat(verify(archive, head)).isEqualTo(new Intact(RECORDS, head));
        assertThat(head.sequence()).isEqualTo(RECORDS);
    }

    @Test
    void eachRecordIsOneLineHoldingTheEncodedEvent() {
        List<String> lines = lines(archive);
        assertThat(lines).hasSize(RECORDS);
        for (int i = 0; i < RECORDS; i++) {
            String[] fields = lines.get(i).split(" ", 3);
            assertThat(fields[0]).matches("[0-9a-f]{64}");
            assertThat(fields[1]).isEqualTo(String.valueOf(i + 1));
            assertThat(EventCodec.decode(fields[2].getBytes(UTF_8))).isEqualTo(events.get(i));
        }
    }

    @Test
    void emptyArchiveVerifiesAgainstItsStart() throws IOException {
        assertThat(verify(new byte[0], ChainHead.GENESIS)).isEqualTo(new Intact(0, ChainHead.GENESIS));
    }

    @Test
    void chainRunsAcrossSegments() throws IOException {
        ByteArrayOutputStream first = new ByteArrayOutputStream();
        ChainHead firstHead = new ArchiveWriter(first, ChainHead.GENESIS).append(events.subList(0, 10));
        ByteArrayOutputStream second = new ByteArrayOutputStream();
        ChainHead secondHead = new ArchiveWriter(second, firstHead).append(events.subList(10, RECORDS));

        assertThat(secondHead).isEqualTo(head);
        assertThat(concat(first.toByteArray(), second.toByteArray())).isEqualTo(archive);
        assertThat(ArchiveVerifier.verify(new ByteArrayInputStream(second.toByteArray()), firstHead, secondHead))
                .isEqualTo(new Intact(RECORDS - 10, secondHead));
        assertThat(ArchiveVerifier.verify(new ByteArrayInputStream(second.toByteArray()), ChainHead.GENESIS,
                secondHead)).isEqualTo(new Broken(1, Defect.SEQUENCE_BREAK, "expected record 1, found '11'"));
    }

    @Test
    void changedEventIsDetected() throws IOException {
        List<String> lines = lines(archive);
        lines.set(6, change(lines.get(6), "10:15:30.123Z", "10:15:31.123Z"));
        assertBroken(join(lines), 7, Defect.HASH_MISMATCH);
    }

    @Test
    void changedHashIsDetected() throws IOException {
        List<String> lines = lines(archive);
        String line = lines.get(6);
        lines.set(6, (line.charAt(0) == '0' ? "1" : "0") + line.substring(1));
        assertBroken(join(lines), 7, Defect.HASH_MISMATCH);
    }

    @Test
    void changedSequenceFieldIsDetected() throws IOException {
        List<String> lines = lines(archive);
        lines.set(6, change(lines.get(6), " 7 {", " 07 {"));
        assertBroken(join(lines), 7, Defect.SEQUENCE_BREAK);
    }

    @Test
    void reorderedRecordsAreDetected() throws IOException {
        List<String> lines = lines(archive);
        Collections.swap(lines, 4, 5);
        assertBroken(join(lines), 5, Defect.SEQUENCE_BREAK);
    }

    @Test
    void deletedRecordIsDetected() throws IOException {
        List<String> lines = lines(archive);
        lines.remove(4);
        assertBroken(join(lines), 5, Defect.SEQUENCE_BREAK);
    }

    @Test
    void deletedFirstRecordIsDetected() throws IOException {
        List<String> lines = lines(archive);
        lines.remove(0);
        assertBroken(join(lines), 1, Defect.SEQUENCE_BREAK);
    }

    @Test
    void insertedRecordIsDetected() throws IOException {
        // A forger who knows the format links a record to its predecessor; the next record breaks.
        List<String> lines = lines(archive);
        String forged = forge(lines.get(3), 5, events.get(0));
        lines.add(4, forged);
        assertBroken(join(lines), 6, Defect.SEQUENCE_BREAK);
    }

    @Test
    void insertedRecordWithRewrittenChainIsDetectedByTheHead() throws IOException {
        List<CatalogEvent> forged = new ArrayList<>(events);
        forged.add(4, events.get(0));
        assertBroken(rewrite(forged), RECORDS + 1, Defect.HEAD_MISMATCH);
    }

    @Test
    void deletedRecordWithRewrittenChainIsDetectedByTheHead() throws IOException {
        List<CatalogEvent> forged = new ArrayList<>(events);
        forged.remove(4);
        assertBroken(rewrite(forged), RECORDS - 1, Defect.HEAD_MISMATCH);
    }

    @Test
    void changedRecordWithRewrittenChainIsDetectedByTheHead() throws IOException {
        List<CatalogEvent> forged = new ArrayList<>(events);
        forged.set(4, events.get(5));
        assertBroken(rewrite(forged), RECORDS, Defect.HEAD_MISMATCH);
    }

    @Test
    void deletedTailIsDetectedByTheHead() throws IOException {
        List<String> lines = lines(archive);
        lines.remove(RECORDS - 1);
        assertBroken(join(lines), RECORDS - 1, Defect.HEAD_MISMATCH);
    }

    @Test
    void wrongHeadIsDetected() throws IOException {
        ChainHead tampered = new ChainHead(head.sequence(), "f".repeat(64));
        assertThat(verify(archive, tampered)).isInstanceOf(Broken.class)
                .extracting(v -> ((Broken) v).defect()).isEqualTo(Defect.HEAD_MISMATCH);
        ChainHead longer = new ChainHead(head.sequence() + 1, head.hash());
        assertThat(verify(archive, longer)).extracting(v -> ((Broken) v).defect()).isEqualTo(Defect.HEAD_MISMATCH);
    }

    @Test
    void unterminatedLastRecordIsDetected() throws IOException {
        byte[] truncated = Arrays.copyOf(archive, archive.length - 1);
        assertBroken(truncated, RECORDS, Defect.MALFORMED_RECORD);
    }

    @Test
    void lineWithoutAHashIsDetected() throws IOException {
        List<String> lines = lines(archive);
        lines.add(3, "not a record");
        assertBroken(join(lines), 4, Defect.MALFORMED_RECORD);
    }

    @Test
    void headAndWriterRequireTheirParts() {
        assertThatThrownBy(() -> new ChainHead(1, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ArchiveWriter(null, ChainHead.GENESIS)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ArchiveWriter(new ByteArrayOutputStream(), null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void writerRefusesToAppendAfterAFailedWrite() throws IOException {
        FailingStream segment = new FailingStream();
        ArchiveWriter writer = new ArchiveWriter(segment, ChainHead.GENESIS);
        writer.append(events.subList(0, 2));
        segment.failing = true;
        assertThatThrownBy(() -> writer.append(events.subList(2, 3))).isInstanceOf(IOException.class);
        segment.failing = false;
        assertThatThrownBy(() -> writer.append(events.subList(3, 4))).isInstanceOf(IllegalStateException.class);
        assertThat(writer.head().sequence()).isEqualTo(2);
    }

    private void assertBroken(byte[] tampered, long line, Defect defect) throws IOException {
        Verification result = verify(tampered, head);
        assertThat(result).isInstanceOf(Broken.class);
        assertThat(((Broken) result).defect()).as(((Broken) result).detail()).isEqualTo(defect);
        assertThat(((Broken) result).line()).isEqualTo(line);
    }

    private static Verification verify(byte[] records, ChainHead expectedHead) throws IOException {
        return ArchiveVerifier.verify(new ByteArrayInputStream(records), ChainHead.GENESIS, expectedHead);
    }

    private static byte[] rewrite(List<CatalogEvent> forged) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new ArchiveWriter(out, ChainHead.GENESIS).append(forged);
        return out.toByteArray();
    }

    /** A record linked correctly to {@code previousLine}, as a forger who knows the format would make it. */
    private static String forge(String previousLine, long sequence, CatalogEvent event) {
        byte[] body = (sequence + " " + new String(EventCodec.encode(event), UTF_8)).getBytes(UTF_8);
        byte[] previousHash = Chain.HEX.parseHex(previousLine.substring(0, 64));
        return Chain.HEX.formatHex(Chain.link(previousHash, body)) + " " + new String(body, UTF_8);
    }

    private static String change(String line, String from, String to) {
        assertThat(line).contains(from);
        return line.replaceFirst(Pattern.quote(from), to);
    }

    private static List<String> lines(byte[] archive) {
        String text = new String(archive, UTF_8);
        assertThat(text).endsWith("\n");
        return new ArrayList<>(List.of(text.substring(0, text.length() - 1).split("\n", -1)));
    }

    private static byte[] join(List<String> lines) {
        return (String.join("\n", lines) + "\n").getBytes(UTF_8);
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] both = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, both, a.length, b.length);
        return both;
    }

    private static final class FailingStream extends OutputStream {
        boolean failing;
        final ByteArrayOutputStream written = new ByteArrayOutputStream();

        @Override
        public void write(int b) throws IOException {
            if (failing) {
                throw new IOException("disk full");
            }
            written.write(b);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            if (failing) {
                written.write(b, off, len / 2);
                throw new IOException("disk full");
            }
            written.write(b, off, len);
        }
    }
}
