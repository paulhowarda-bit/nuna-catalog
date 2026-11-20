package nuna.catalog.store;

import java.time.Instant;
import java.util.Objects;

/**
 * A table's or view's DR-safe watermark (D37, amended 2026-11-20): a version of its main pointer whose every file,
 * data included, the maintenance worker's verifier found in the DR store, so the table reads whole there at that
 * version. The catalog keeps the newest it was told of; it never moves back, and never above the pointer. It lives in
 * the catalog database so it reaches the DR region with the database, and it is written after the version it names,
 * so a replica that keeps commit order never holds a watermark above its pointer.
 *
 * @param version          the pointer version
 * @param metadataLocation that version's metadata file
 * @param recordedAt       when the catalog recorded it
 */
public record DrWatermark(long version, String metadataLocation, Instant recordedAt) {

    public DrWatermark {
        Objects.requireNonNull(metadataLocation, "metadataLocation");
        Objects.requireNonNull(recordedAt, "recordedAt");
        if (version < 1) {
            throw new IllegalArgumentException("a watermark names a pointer version, from 1: " + version);
        }
    }
}
