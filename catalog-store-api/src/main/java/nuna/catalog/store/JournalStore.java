package nuna.catalog.store;

import java.time.Instant;

/**
 * The commit journal (D5, D33). Branch and tag records exist in the schema from v1; their
 * operations ship in v1.x (storage-design 9).
 */
public interface JournalStore {

    /** Journal entries of one pointer committed in [{@code from}, {@code to}), by version. */
    Page<JournalRecord> readJournal(PointerKey key, Instant from, Instant to, PageRequest page);
}
