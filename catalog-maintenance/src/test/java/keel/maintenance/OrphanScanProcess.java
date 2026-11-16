package keel.maintenance;

import java.lang.management.ManagementFactory;
import java.net.URI;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.apache.iceberg.Table;

/**
 * One orphan scan in its own process, for {@link LargeOrphanScanTest} to measure: the heap the scan holds once it has
 * read the table's metadata and listed its location, which is when its reachable set is whole, printed as
 * {@code HEAP <bytes>} over what the process held before the scan; then {@code ORPHAN <location>} for each orphan,
 * {@code ORPHANS <n>} and {@code MILLIS <n>}.
 *
 * <p>Arguments: those of {@link ChildJvm#workerArguments}, then the catalog, the namespace and the table's name.
 */
public final class OrphanScanProcess {

    private OrphanScanProcess() {
    }

    public static void main(String[] args) {
        ChildJvm.quietLogging();
        URI uri = URI.create(args[0]);
        TokenSource tokens = new TokenSource.FileToken(Path.of(args[1]));
        try (Storage storage = ChildJvm.storage(args, 2); Catalogs catalogs = new Catalogs(uri, tokens, Map.of())) {
            Table table = catalogs.load(args[6], List.of(args[7]), args[8]);
            long before = heap();
            long start = System.nanoTime();
            long[] held = new long[1];
            List<String> orphans = OrphanScan.find(table, storage, Instant.now(), () -> true,
                    () -> held[0] = heap() - before);
            System.out.println("HEAP " + held[0]);
            orphans.forEach(orphan -> System.out.println("ORPHAN " + orphan));
            System.out.println("ORPHANS " + orphans.size());
            System.out.println("MILLIS " + (System.nanoTime() - start) / 1_000_000);
        }
    }

    /** The heap in use after a full collection. */
    private static long heap() {
        System.gc();
        System.gc();
        return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
    }
}
