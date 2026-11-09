package fidice.catalog.load;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;

/** Latency samples, in nanoseconds, and their percentiles in milliseconds. */
final class Latencies {

    private long[] samples = new long[1024];
    private int count;

    synchronized void record(long nanos) {
        if (count == samples.length) {
            samples = Arrays.copyOf(samples, count * 2);
        }
        samples[count++] = nanos;
    }

    synchronized int count() {
        return count;
    }

    /** The {@code p}th percentile (0 to 100) in milliseconds, nearest rank. */
    synchronized double percentile(double p) {
        if (count == 0) {
            return Double.NaN;
        }
        long[] sorted = Arrays.copyOf(samples, count);
        Arrays.sort(sorted);
        int rank = (int) Math.ceil(p / 100.0 * count);
        return sorted[Math.max(0, Math.min(count - 1, rank - 1))] / 1e6;
    }

    String summary() {
        return "n=%d p50=%.1fms p95=%.1fms p99=%.1fms max=%.1fms".formatted(count(), percentile(50), percentile(95),
                percentile(99), percentile(100));
    }

    /** Appends a line to {@code target/m8/results/<file>} and prints it. */
    static void report(String file, String line) {
        System.out.println("[m8] " + line);
        Path results = Path.of(System.getProperty("m8.work"), "results");
        try {
            Files.createDirectories(results);
            Files.writeString(results.resolve(file), line + System.lineSeparator(), StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
