package keel.maintenance;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.apache.iceberg.DataFile;
import org.apache.iceberg.DeleteFile;
import org.apache.iceberg.FileContent;
import org.apache.iceberg.FileFormat;
import org.apache.iceberg.ManifestFile;
import org.apache.iceberg.ManifestFiles;
import org.apache.iceberg.ManifestReader;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.Snapshot;
import org.apache.iceberg.Table;
import org.apache.iceberg.actions.BinPackRewriteFilePlanner;
import org.apache.iceberg.actions.RewriteDataFiles;
import org.apache.iceberg.actions.SizeBasedFileRewritePlanner;
import org.apache.iceberg.io.FileIO;

/**
 * Whether a table needs compaction tonight, from its current snapshot's manifests (D30, amended 2026-11-15), and how
 * much. A table needs it for any of three reasons:
 *
 * <ul>
 *   <li><b>small files</b>: a partition holds at least {@value #MIN_INPUT_FILES} data files under 75% of the target
 *       size, rewrite_data_files' own bound for a small file. Spark groups a table's files by partition and rewrites
 *       a group of at least that many, so this is what it would rewrite;</li>
 *   <li><b>deletes</b>: its delete files (position deletes, deletion vectors and equality deletes) are more than
 *       {@value #DELETE_FILE_RATIO} of its data files in number;</li>
 *   <li><b>equality deletes</b>: it has any equality-delete file, which every reader must join against.</li>
 * </ul>
 *
 * <p>The thresholds are the policy's, resolved like {@code compaction.target-file-size-bytes}; absent, they are
 * rewrite_data_files' own defaults. How far past them a table is, its score, orders the night's queue: tables with
 * equality deletes first, then the highest score.
 *
 * @param dataFiles           live data files
 * @param smallFiles          small data files in partitions with enough of them to rewrite
 * @param deleteFiles         live delete files of every kind
 * @param equalityDeleteFiles live equality-delete files
 * @param minInputFiles       the small-file threshold
 * @param deleteFileRatio     the delete-file threshold, as a share of data files
 * @param targetFileSize      the target file size, in bytes
 * @param deletionVectors     live deletion vectors (v3), which are among the delete files; the catalog shows them
 *                            apart from position-delete files (D38)
 */
record CompactionNeed(long dataFiles, long smallFiles, long deleteFiles, long equalityDeleteFiles, long minInputFiles,
                      double deleteFileRatio, long targetFileSize, long deletionVectors) {

    /** A need with no deletion vectors among its delete files. */
    CompactionNeed(long dataFiles, long smallFiles, long deleteFiles, long equalityDeleteFiles, long minInputFiles,
                   double deleteFileRatio, long targetFileSize) {
        this(dataFiles, smallFiles, deleteFiles, equalityDeleteFiles, minInputFiles, deleteFileRatio, targetFileSize, 0);
    }

    static final String TARGET_FILE_SIZE = "compaction.target-file-size-bytes";
    static final String MIN_INPUT_FILES = "compaction.min-input-files";
    static final String DELETE_FILE_RATIO = "compaction.delete-file-ratio";

    /** A reason a table needs compaction, the label {@code reason} of its meter: bounded. */
    enum Reason {
        SMALL_FILES("small-files"),
        DELETES("deletes"),
        EQUALITY_DELETES("equality-deletes");

        final String label;

        Reason(String label) {
            this.label = label;
        }
    }

    /** Equality deletes first, then the furthest past its thresholds. */
    static final Comparator<CompactionNeed> NEEDIEST_FIRST = Comparator
            .comparing((CompactionNeed need) -> need.reasons().contains(Reason.EQUALITY_DELETES)).reversed()
            .thenComparing(Comparator.comparingDouble(CompactionNeed::score).reversed());

    /** Reads the current snapshot's manifests: every live data file's size and partition, every live delete file. */
    static CompactionNeed assess(Table table, Map<String, String> policy) {
        long target = Long.parseLong(policy.getOrDefault(TARGET_FILE_SIZE,
                Long.toString(org.apache.iceberg.TableProperties.WRITE_TARGET_FILE_SIZE_BYTES_DEFAULT)));
        long minInput = Long.parseLong(policy.getOrDefault(MIN_INPUT_FILES,
                Integer.toString(SizeBasedFileRewritePlanner.MIN_INPUT_FILES_DEFAULT)));
        double ratio = Double.parseDouble(policy.getOrDefault(DELETE_FILE_RATIO,
                Double.toString(BinPackRewriteFilePlanner.DELETE_RATIO_THRESHOLD_DEFAULT)));
        Snapshot current = table.currentSnapshot();
        if (current == null) {
            return new CompactionNeed(0, 0, 0, 0, minInput, ratio, target);
        }
        long small = (long) (target * SizeBasedFileRewritePlanner.MIN_FILE_SIZE_DEFAULT_RATIO);
        Map<Integer, PartitionSpec> specs = table.specs();
        FileIO io = table.io();
        long dataFiles = 0;
        Map<String, Long> smallByPartition = new HashMap<>();
        for (ManifestFile manifest : current.dataManifests(io)) {
            try (ManifestReader<DataFile> files = ManifestFiles.read(manifest, io, specs)) {
                for (DataFile file : files) {
                    dataFiles++;
                    if (file.fileSizeInBytes() < small) {
                        smallByPartition.merge(file.specId() + "/" + specs.get(file.specId())
                                .partitionToPath(file.partition()), 1L, Long::sum);
                    }
                }
            } catch (IOException e) {
                throw new UncheckedIOException("cannot read manifest " + manifest.path(), e);
            }
        }
        long deleteFiles = 0;
        long equality = 0;
        long vectors = 0;
        for (ManifestFile manifest : current.deleteManifests(io)) {
            try (ManifestReader<DeleteFile> files = ManifestFiles.readDeleteManifest(manifest, io, specs)) {
                for (DeleteFile file : files) {
                    deleteFiles++;
                    if (file.content() == FileContent.EQUALITY_DELETES) {
                        equality++;
                    } else if (file.format() == FileFormat.PUFFIN) {
                        vectors++;
                    }
                }
            } catch (IOException e) {
                throw new UncheckedIOException("cannot read manifest " + manifest.path(), e);
            }
        }
        long smallFiles = smallByPartition.values().stream().filter(count -> count >= minInput)
                .mapToLong(Long::longValue).sum();
        return new CompactionNeed(dataFiles, smallFiles, deleteFiles, equality, minInput, ratio, target, vectors);
    }

    /** Why the table needs compaction; empty when it does not. */
    Set<Reason> reasons() {
        Set<Reason> reasons = EnumSet.noneOf(Reason.class);
        if (smallFiles > 0) {
            reasons.add(Reason.SMALL_FILES);
        }
        if (dataFiles > 0 && deleteShare() > deleteFileRatio) {
            reasons.add(Reason.DELETES);
        }
        if (equalityDeleteFiles > 0) {
            reasons.add(Reason.EQUALITY_DELETES);
        }
        return reasons;
    }

    boolean needed() {
        return !reasons().isEmpty();
    }

    /**
     * How far past its thresholds the table is: the larger of its small files over the small-file threshold and its
     * delete-file share over the delete-file threshold. 1 is at a threshold, 2 twice past it. A table with equality
     * deletes needs compaction whatever its score.
     */
    double score() {
        return Math.max((double) smallFiles / minInputFiles, deleteShare() / deleteFileRatio);
    }

    /** Live position-delete files that are not deletion vectors. */
    long positionDeleteFiles() {
        return deleteFiles - equalityDeleteFiles - deletionVectors;
    }

    private double deleteShare() {
        return dataFiles == 0 ? 0 : (double) deleteFiles / dataFiles;
    }

    /**
     * rewrite_data_files' options for this need: the target size and small-file threshold it was judged by, partial
     * progress, so file groups it finishes stay committed when the window's end stops it, and, for deletes, every
     * data file with a delete rewritten and the delete files left dangling removed.
     */
    Map<String, String> options() {
        Map<String, String> options = new LinkedHashMap<>();
        options.put(RewriteDataFiles.TARGET_FILE_SIZE_BYTES, Long.toString(targetFileSize));
        options.put(SizeBasedFileRewritePlanner.MIN_INPUT_FILES, Long.toString(minInputFiles));
        options.put(RewriteDataFiles.PARTIAL_PROGRESS_ENABLED, "true");
        Set<Reason> reasons = reasons();
        if (reasons.contains(Reason.DELETES) || reasons.contains(Reason.EQUALITY_DELETES)) {
            options.put(BinPackRewriteFilePlanner.DELETE_FILE_THRESHOLD, "1");
            options.put(RewriteDataFiles.REMOVE_DANGLING_DELETES, "true");
        }
        return options;
    }

    @Override
    public String toString() {
        return "score " + String.format(java.util.Locale.ROOT, "%.2f", score()) + " " + reasons() + ": " + dataFiles
                + " data files, " + smallFiles + " small, " + deleteFiles + " delete files, " + equalityDeleteFiles
                + " of them equality";
    }
}
