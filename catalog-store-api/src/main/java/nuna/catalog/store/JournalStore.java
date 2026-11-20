package nuna.catalog.store;

import java.time.Instant;
import java.util.Optional;

/**
 * The commit journal (D5, D33). Branch and tag records exist in the schema from v1; their
 * operations ship in v1.x (storage-design 9).
 */
public interface JournalStore {

    /** The earliest and latest instants {@link #readJournal} takes, which hold every entry. */
    Instant EARLIEST = Instant.EPOCH;
    Instant LATEST = Instant.parse("9999-01-01T00:00:00Z");

    /**
     * Journal entries of one pointer committed in [{@code from}, {@code to}), by version. A page's token names the last
     * version it holds, so {@link #afterVersion} can start a page after any version.
     */
    Page<JournalRecord> readJournal(PointerKey key, Instant from, Instant to, PageRequest page);

    /** The page of {@code size} entries after version {@code version}: the token every backend's pages carry. */
    static PageRequest afterVersion(long version, int size) {
        return new PageRequest(Optional.of(PageToken.encode(Long.toString(version))), size);
    }
}
