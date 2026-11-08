package fidice.catalog.core.commit;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** Metadata files in memory, laid out as catalog-objectstore lays them out in a bucket. */
final class MemoryFiles implements TableFiles {

    static final String ROOT = "s3://lake/warehouse";

    private final Map<String, byte[]> files = new ConcurrentHashMap<>();
    final AtomicInteger writes = new AtomicInteger();
    final AtomicInteger reads = new AtomicInteger();

    @Override
    public String newLocation(UUID objectId) {
        return ROOT + "/" + objectId;
    }

    @Override
    public Written write(String locationRoot, long version, String json) {
        writes.incrementAndGet();
        String location = locationRoot + "/metadata/"
                + String.format(Locale.ROOT, "%05d-%s.metadata.json", version, UUID.randomUUID());
        byte[] bytes = json.getBytes(UTF_8);
        files.put(location, bytes);
        return new Written(location, sha256(bytes));
    }

    @Override
    public Read read(String location, String expectedSha256) {
        reads.incrementAndGet();
        byte[] bytes = files.get(location);
        if (bytes == null) {
            throw new IllegalStateException("no metadata file at " + location);
        }
        String actual = sha256(bytes);
        return new Read(new String(bytes, UTF_8), actual, actual.equals(expectedSha256));
    }

    /** Whole segments, strictly beneath, and nothing a store could read another way. */
    @Override
    public boolean isWithin(String location, String root) {
        if (!location.startsWith("s3://") || !root.startsWith("s3://")) {
            return false;
        }
        List<String> path = List.of(location.substring(5).split("/", -1));
        List<String> prefix = List.of(root.substring(5).split("/", -1));
        if (path.stream().anyMatch(s -> s.isEmpty() || s.equals(".") || s.equals("..") || s.contains("%"))) {
            return false;
        }
        return path.size() > prefix.size() && path.subList(0, prefix.size()).equals(prefix);
    }

    /** Changes a file behind the catalog's back. */
    void tamper(String location, String json) {
        files.put(location, json.getBytes(UTF_8));
    }

    int count() {
        return files.size();
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }
}
