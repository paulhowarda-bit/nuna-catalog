package nuna.catalog.store;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * One journaled swap of a pointer: the commit, its time, who made it and what they asked for, and
 * the location it moved from ({@code newVersion} - 1, absent on create) and to.
 */
public record JournalRecord(UUID commitId, PointerKey key, Instant committedAt, JournalEntry entry,
                            Optional<String> oldLocation, String newLocation, long newVersion) {
}
