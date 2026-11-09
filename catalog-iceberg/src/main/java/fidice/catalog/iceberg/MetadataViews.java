package fidice.catalog.iceberg;

import fidice.catalog.iceberg.RestResponse.StorageCredential;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.iceberg.TableMetadata;
import org.apache.iceberg.TableMetadataParser;
import org.apache.iceberg.rest.credentials.Credential;
import org.apache.iceberg.rest.responses.ImmutableLoadViewResponse;
import org.apache.iceberg.rest.responses.LoadTableResponse;
import org.apache.iceberg.rest.responses.LoadTableResponseParser;
import org.apache.iceberg.rest.responses.LoadViewResponseParser;
import org.apache.iceberg.view.ViewMetadata;
import org.apache.iceberg.view.ViewMetadataParser;

/**
 * Table and view load responses, from parsed metadata cached by file location (D14). A location names
 * one immutable file, so an entry is never stale; entries are evicted least recently used first, to
 * keep the JSON they came from under a character budget. The {@code snapshots=refs} form of a table is
 * derived once per file and cached beside it.
 */
public final class MetadataViews {

    /** What the load route reports and alerts on, without the caller parsing anything. */
    public record TableStats(int snapshots, long metadataChars) {
    }

    private static final class Entry {
        final Object parsed;
        final long chars;
        TableMetadata refsOnly;

        Entry(Object parsed, long chars) {
            this.parsed = parsed;
            this.chars = chars;
        }
    }

    private final long maxChars;
    private final LinkedHashMap<String, Entry> entries = new LinkedHashMap<>(16, 0.75f, true);
    private long chars;

    public MetadataViews(long maxChars) {
        this.maxChars = maxChars;
    }

    /**
     * {@code GET .../tables/{table}}. With {@code refsOnly}, only snapshots a branch or tag references are
     * returned, as {@code snapshots=refs} asks; statistics files of the others go with them, so the
     * response never names a snapshot it leaves out (a statistics file on an unreferenced snapshot is
     * what makes the reference fixture answer 400 here).
     */
    public String loadTable(String location, String json, boolean refsOnly, Map<String, String> config,
                            List<StorageCredential> credentials) {
        TableMetadata metadata = table(location, json, refsOnly);
        return LoadTableResponseParser.toJson(LoadTableResponse.builder()
                .withTableMetadata(metadata)
                .addAllConfig(new HashMap<>(config))
                .addAllCredentials(credentials(credentials))
                .build());
    }

    /** The response to a staged create: the new table's metadata, with no metadata file yet. */
    public static String stagedTable(String metadataJson, Map<String, String> config,
                                     List<StorageCredential> credentials) {
        return LoadTableResponseParser.toJson(LoadTableResponse.builder()
                .withTableMetadata(TableMetadataParser.fromJson(metadataJson))
                .addAllConfig(new HashMap<>(config))
                .addAllCredentials(credentials(credentials))
                .build());
    }

    /** {@code GET .../views/{view}}, and the create and replace responses. */
    public String loadView(String location, String json, Map<String, String> config) {
        ViewMetadata metadata = (ViewMetadata) parsed(location, json, false);
        return LoadViewResponseParser.toJson(ImmutableLoadViewResponse.builder()
                .metadataLocation(location)
                .metadata(metadata)
                .config(new HashMap<>(config))
                .build());
    }

    public TableStats tableStats(String location, String json) {
        TableMetadata metadata = table(location, json, false);
        return new TableStats(metadata.snapshots().size(), json.length());
    }

    private TableMetadata table(String location, String json, boolean refsOnly) {
        Entry entry = entry(location, json, true);
        if (!refsOnly) {
            return (TableMetadata) entry.parsed;
        }
        synchronized (entry) {
            if (entry.refsOnly == null) {
                TableMetadata full = (TableMetadata) entry.parsed;
                // Suppressing drops the statistics of the snapshots it removes, which the builder records as
                // changes; they describe no file change, so they are discarded to keep the file's location.
                entry.refsOnly = TableMetadata.buildFrom(full)
                        .withMetadataLocation(full.metadataFileLocation())
                        .suppressHistoricalSnapshots()
                        .discardChanges()
                        .build();
            }
            return entry.refsOnly;
        }
    }

    private Object parsed(String location, String json, boolean table) {
        return entry(location, json, table).parsed;
    }

    private Entry entry(String location, String json, boolean table) {
        synchronized (this) {
            Entry cached = entries.get(location);
            if (cached != null) {
                return cached;
            }
        }
        Object parsed = table ? TableMetadataParser.fromJson(location, json) : ViewMetadataParser.fromJson(location, json);
        Entry entry = new Entry(parsed, json.length());
        synchronized (this) {
            Entry raced = entries.get(location);
            if (raced != null) {
                return raced;
            }
            if (entry.chars <= maxChars) {
                entries.put(location, entry);
                chars += entry.chars;
                Iterator<Map.Entry<String, Entry>> oldest = entries.entrySet().iterator();
                while (chars > maxChars) {
                    chars -= oldest.next().getValue().chars;
                    oldest.remove();
                }
            }
            return entry;
        }
    }

    synchronized int size() {
        return entries.size();
    }

    static List<Credential> credentials(List<StorageCredential> credentials) {
        return credentials.stream().<Credential>map(RestResponse::credential).toList();
    }
}
