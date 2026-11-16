package keel.maintenance;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.Map;
import org.apache.iceberg.io.FileInfo;

/**
 * A worker in its own process, for {@link KillMidPurgeTest} to kill: one scan, deleting at most one batch of files a
 * second and printing {@code DELETED <n>} after each, so the test can kill it part-way through a purge.
 *
 * <p>Arguments: those of {@link ChildJvm#workerArguments}.
 */
public final class PurgeProcess {

    private PurgeProcess() {
    }

    public static void main(String[] args) {
        ChildJvm.quietLogging();
        URI catalog = URI.create(args[0]);
        TokenSource tokens = new TokenSource.FileToken(Path.of(args[1]));
        Storage storage = ChildJvm.storage(args, 2);
        Storage slow = new Storage() {
            @Override
            public Iterable<FileInfo> list(String prefix) {
                return storage.list(prefix);
            }

            @Override
            public void delete(Collection<String> locations) {
                storage.delete(locations);
                System.out.println("DELETED " + locations.size());
                System.out.flush();
                try {
                    Thread.sleep(Duration.ofSeconds(1));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }

            @Override
            public void close() {
                storage.close();
            }
        };
        try (Worker worker = new Worker(new Management(catalog, tokens, HttpClient.newHttpClient(), 100),
                new Catalogs(catalog, tokens, Map.of(), TraceHeaders.NONE), slow, Compactor.NONE, Clock.systemUTC(),
                new CleanupWindow(LocalTime.MIDNIGHT, LocalTime.MIDNIGHT, ZoneOffset.UTC), Duration.ofDays(1), 1, new SimpleMeterRegistry(),
                ObservationRegistry.NOOP)) {
            System.out.println("SCANNED " + worker.runOnce());
        }
    }
}
