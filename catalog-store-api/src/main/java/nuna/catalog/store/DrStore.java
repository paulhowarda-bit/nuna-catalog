package nuna.catalog.store;

import java.util.Optional;
import java.util.UUID;

/**
 * The DR replication watermark (D37, amended 2026-11-20): what the maintenance worker's verifier found in the DR
 * store, kept per table and view. Like the worker's other reports (D38), a recording carries no change id and writes
 * no audit record; it is safe to repeat.
 */
public interface DrStore {

    /**
     * Keeps {@code watermark} for an active table or view, in place of a lower one. The object is share-locked, so a
     * drop at the same moment waits; a purge's completion deletes it.
     *
     * @return {@code Applied}; {@code Unchanged} when the same version is kept; {@code Refused(WATERMARK_BEHIND)}
     *         when a higher one is kept, since a watermark never moves back; {@code Refused(WATERMARK_AHEAD)} when it
     *         is above the version of the object's main pointer; or {@code Missing(OBJECT)} when no active table or
     *         view has that id, or it has no main pointer
     */
    WriteResult recordDrWatermark(UUID objectId, DrWatermark watermark);

    /** The object's watermark, in any state; empty when there is no such object or none was recorded. */
    Optional<DrWatermark> readDrWatermark(UUID objectId);

    /**
     * The active tables and views whose watermark is below the version of their main pointer, or which have none, by
     * id: what the verifier has to check, and what the DR readout lists.
     */
    Page<DrState> listDrBehind(PageRequest page);
}
