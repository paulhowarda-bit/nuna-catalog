package fidice.catalog.core.commit;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Metadata JSON by file location (D14). A location names one immutable file, so entries are never
 * invalidated, only evicted, least recently used first, to keep the total under a character budget.
 * Only files whose hash was checked, or that the catalog wrote, go in.
 */
public final class MetadataCache {

    private final long maxChars;
    private final LinkedHashMap<String, String> entries = new LinkedHashMap<>(16, 0.75f, true);
    private long chars;

    public MetadataCache(long maxChars) {
        this.maxChars = maxChars;
    }

    public synchronized String get(String location) {
        return entries.get(location);
    }

    /** Adds the file unless it alone exceeds the budget, evicting the least recently used to fit. */
    public synchronized void put(String location, String json) {
        if (json.length() > maxChars) {
            return;
        }
        String previous = entries.put(location, json);
        chars += json.length() - (previous == null ? 0 : previous.length());
        Iterator<Map.Entry<String, String>> oldest = entries.entrySet().iterator();
        while (chars > maxChars) {
            chars -= oldest.next().getValue().length();
            oldest.remove();
        }
    }

    synchronized int size() {
        return entries.size();
    }
}
