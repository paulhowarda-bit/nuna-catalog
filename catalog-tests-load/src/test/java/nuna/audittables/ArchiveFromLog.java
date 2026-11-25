package nuna.audittables;

import nuna.catalog.audit.archive.ArchiveSink;
import nuna.catalog.audit.archive.ObjectLockArchive;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;

/**
 * The audit table writer's archive leg for a test stack (D23, amended 2026-11-25; backlog 262): the writer's own OTLP
 * logs receiver, which the stack's servers export their log {@code nuna.audit} to, handing every record to an {@link
 * ArchiveSink} that writes a segment within a second and a checkpoint every five. Its table writer never commits: the
 * stack has no audit table, and the records it holds are a test's few thousand.
 */
public final class ArchiveFromLog implements AutoCloseable {

    /** The token the servers send in the receiver's header. */
    public static final String TOKEN = "the-m8-stacks-audit-token-of-32-characters";

    private final ArchiveSink sink;
    private final OtlpLogsReceiver receiver;
    private final Thread ticking;
    private volatile boolean running = true;

    private ArchiveFromLog(ObjectLockArchive archive) {
        sink = new ArchiveSink(archive, 1_000, Duration.ofSeconds(1), Duration.ofSeconds(5), 1_000_000,
                Clock.systemUTC());
        LogWriter tables = new LogWriter(() -> {
            throw new IllegalStateException("the stack has no audit table");
        }, "nuna-audit-tables", Duration.ofDays(1), Integer.MAX_VALUE, Duration.ofMinutes(15), Clock.systemUTC());
        receiver = OtlpLogsReceiver.start(0, TOKEN, tables, Optional.of(sink::offer));
        ticking = Thread.ofPlatform().name("m8-audit-archive").daemon(true).start(this::tick);
    }

    /** Listens on a free port of this machine and archives to {@code archive}. */
    public static ArchiveFromLog start(ObjectLockArchive archive) {
        return new ArchiveFromLog(archive);
    }

    /**
     * The server properties that export its log to the receiver, as the charts set them, with a short batch delay so a
     * record reaches the archive within a second or so.
     */
    public String[] serverProperties() {
        return new String[] {
                "--management.opentelemetry.logging.export.otlp.endpoint=http://127.0.0.1:" + receiver.port()
                        + OtlpLogsReceiver.PATH,
                "--management.opentelemetry.logging.export.otlp.headers.X-Nuna-Audit-Token=" + TOKEN,
                "--management.opentelemetry.logging.export.schedule-delay=200ms"};
    }

    /** This writer's run, as its checkpoints name it. */
    public String writer() {
        return sink.writer();
    }

    private void tick() {
        while (running) {
            ArchiveSink.Round round = sink.tick();
            round.problems().forEach(p -> System.err.println("m8 audit archive: " + p));
            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    /** Stops taking records and writes what waits. */
    @Override
    public void close() {
        running = false;
        receiver.close();
        ticking.interrupt();
        try {
            ticking.join(Duration.ofSeconds(10));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        sink.finish();
    }
}
