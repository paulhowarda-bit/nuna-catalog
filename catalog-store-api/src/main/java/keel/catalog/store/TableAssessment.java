package keel.catalog.store;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The maintenance worker's assessment of one table's need for compaction, made in its nightly cleanup window from the
 * table's current manifests (D30, amended 2026-11-15), as it reports it to the catalog (D38).
 *
 * @param assessedAt          when the worker read the manifests, by its clock
 * @param dataFiles           live data files
 * @param smallFiles          small data files in partitions holding enough of them to rewrite
 * @param positionDeleteFiles live position-delete files, deletion vectors not counted
 * @param deletionVectors     live deletion vectors (v3)
 * @param equalityDeleteFiles live equality-delete files
 * @param reasons             why the table needs compaction ({@code small-files}, {@code deletes},
 *                            {@code equality-deletes}); empty when it needs none
 * @param score               how far past its thresholds it is: 1 at a threshold, 2 twice past it; kept to four
 *                            decimals, so every store holds it exactly
 */
public record TableAssessment(Instant assessedAt, long dataFiles, long smallFiles, long positionDeleteFiles,
                              long deletionVectors, long equalityDeleteFiles, Set<String> reasons, double score) {

    /** A reason or a failure kind: a bounded label, as the worker's meters name them. */
    public static final Pattern LABEL = Pattern.compile("[a-z][a-z0-9-]{0,63}");
    /** The largest score kept: a billion times past a threshold. */
    public static final double MAX_SCORE = 1e9;

    public TableAssessment {
        Objects.requireNonNull(assessedAt, "assessedAt");
        reasons = Set.copyOf(reasons);
        if (dataFiles < 0 || smallFiles < 0 || positionDeleteFiles < 0 || deletionVectors < 0
                || equalityDeleteFiles < 0 || smallFiles > dataFiles) {
            throw new IllegalArgumentException("file counts are not negative, and small files are data files");
        }
        if (!(score >= 0 && score <= MAX_SCORE)) {
            throw new IllegalArgumentException("a score is a number from 0 to " + MAX_SCORE + ": " + score);
        }
        score = BigDecimal.valueOf(score).setScale(4, RoundingMode.HALF_UP).doubleValue();
        if (reasons.size() > 16 || !reasons.stream().allMatch(reason -> LABEL.matcher(reason).matches())) {
            throw new IllegalArgumentException("reasons are at most 16 labels such as small-files: " + reasons);
        }
    }

    /** The verdict: whether the table needs compaction. */
    public boolean needsCompaction() {
        return !reasons.isEmpty();
    }

    /** Delete files of every kind. */
    public long deleteFiles() {
        return positionDeleteFiles + deletionVectors + equalityDeleteFiles;
    }
}
