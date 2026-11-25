package nuna.maintenance;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.Schema;
import org.apache.iceberg.TableMetadata;
import org.apache.iceberg.TableMetadataParser;
import org.apache.iceberg.io.FileInfo;
import org.apache.iceberg.types.Types;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * An orphan scan that fails is tried again at the next scan, as every failed job is (README, "What a scan does"), not
 * left as if it had run until the orphan interval's nights have passed. Found by the chart's first install (backlog
 * 5): the worker's first scans could not reach the store, and its table's orphan scan then waited for the next night.
 *
 * <p>Needs only Java: the catalog is a stand-in on the JDK's HTTP server that lists one empty table with orphan removal
 * on, and the worker's storage fails its first listing.
 */
class FailedOrphanScanTest {

    private static final String LOCATION = "s3://warehouse/tables/t";

    private final AtomicInteger listings = new AtomicInteger();
    private HttpServer catalog;

    @AfterEach
    void stop() {
        if (catalog != null) {
            catalog.stop(0);
        }
    }

    @Test
    void aFailedOrphanScanIsTriedAgainAtTheNextScan() throws Exception {
        URI uri = catalog(TableMetadata.newTableMetadata(new Schema(Types.NestedField.required(1, "id",
                Types.LongType.get())), PartitionSpec.unpartitioned(), LOCATION, Map.of()));
        FakeClock clock = new FakeClock();
        LocalTime turn = LocalTime.ofInstant(clock.instant().minus(Duration.ofHours(12)), ZoneOffset.UTC)
                .truncatedTo(ChronoUnit.SECONDS);
        TokenSource tokens = new TokenSource() {
            @Override
            public String token() {
                return "token";
            }

            @Override
            public Map<String, String> clientProperties() {
                return Map.of();
            }
        };
        try (Worker worker = new Worker(new Management(uri, tokens, HttpClient.newHttpClient(), 100),
                new Catalogs(uri, tokens, Map.of(), TraceHeaders.NONE), new FailingOnce(), Compactor.NONE, clock,
                new CleanupWindow(turn, turn, ZoneOffset.UTC), Duration.ofDays(1), Duration.ofHours(2), 2,
                new SimpleMeterRegistry(), ObservationRegistry.NOOP)) {
            ScanReport failed = worker.runOnce();
            assertThat(failed.failures).as("the store failed the listing").hasSize(1);
            assertThat(failed.failuresByKind).containsEntry(ScanReport.Failure.ORPHAN_SCAN, 1);
            assertThat(failed.orphanScans).isZero();

            clock.advance(Duration.ofMinutes(10));
            ScanReport again = worker.runOnce();
            assertThat(again.failures).isEmpty();
            assertThat(again.orphanScans).as("tried again at the next scan, the same night").isEqualTo(1);

            clock.advance(Duration.ofMinutes(10));
            assertThat(worker.runOnce().orphanScans).as("once it ran, not again that night").isZero();
            assertThat(listings).hasValue(2);
        }
    }

    /**
     * The catalog as the worker sees it: the work list naming one table with orphan removal on, the table itself
     * through Iceberg's REST routes, and every report of the worker's own work taken.
     */
    private URI catalog(TableMetadata metadata) throws IOException {
        String tables = "{\"tables\":[{\"catalog\":\"lakehouse\",\"id\":\"" + metadata.uuid() + "\",\"namespace\":"
                + "[\"ns\"],\"name\":\"t\",\"location\":\"" + LOCATION + "\",\"policy\":{\"orphan-removal.enabled\":"
                + "\"true\",\"orphan-removal.older-than\":\"P14D\"}}]}";
        String table = "{\"metadata-location\":\"" + LOCATION + "/metadata/00000.metadata.json\",\"metadata\":"
                + TableMetadataParser.toJson(metadata) + ",\"config\":{}}";
        catalog = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        catalog.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String query = String.valueOf(exchange.getRequestURI().getQuery());
            if (path.equals("/management/v1/maintenance/work")) {
                answer(exchange, 200, query.contains("section=tables") ? tables
                        : query.contains("section=purges") ? "{\"purges\":[]}" : "{\"deletions\":[]}");
            } else if (path.equals(TestReach.ROUTE)) {
                answer(exchange, 200, TestReach.ONE_PROFILE);
            } else if (path.equals("/v1/config")) {
                answer(exchange, 200, "{\"defaults\":{},\"overrides\":{}}");
            } else if (path.equals("/v1/namespaces/ns/tables/t")) {
                answer(exchange, 200, table);
            } else if (exchange.getRequestMethod().equals("PUT") && path.startsWith("/management/v1/maintenance/")) {
                answer(exchange, 204, null);
            } else {
                answer(exchange, 404, "{\"error\":{\"message\":\"no route " + path + "\",\"type\":\"NotFound\","
                        + "\"code\":404}}");
            }
        });
        catalog.start();
        return URI.create("http://localhost:" + catalog.getAddress().getPort() + "/");
    }

    private static void answer(HttpExchange exchange, int status, String body) throws IOException {
        exchange.getRequestBody().readAllBytes();
        if (body == null) {
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
            return;
        }
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    /** The worker's own storage, whose first listing fails as a store out of reach does; the table has no files. */
    private final class FailingOnce implements Storage {

        @Override
        public Iterable<FileInfo> list(String prefix) {
            if (listings.incrementAndGet() == 1) {
                throw new IllegalStateException("the store cannot be reached");
            }
            return List.of();
        }

        @Override
        public void delete(Collection<String> locations) {
            throw new AssertionError("an empty table has nothing to delete: " + locations);
        }

        @Override
        public Prefixes reach(List<Management.StorageProfile> profiles) {
            return TestReach.everyProfile(profiles);
        }

        @Override
        public void close() {
        }
    }
}
