package keel.catalog.store;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * What the catalog keeps of a table's health for the console (D38): the maintenance worker's latest assessment of it
 * and how its last compaction went, each as the worker last reported it. This is the catalog's own worker's
 * maintenance state, not the engines' metrics reports, which never reach the database (D13).
 *
 * @param table          the table
 * @param assessment     its latest assessment, if the worker has made one
 * @param lastCompaction its last compaction, if the worker has started one
 */
public record TableHealth(CatalogObject table, Optional<TableAssessment> assessment,
                          Optional<CompactionRun> lastCompaction) {

    public TableHealth {
        Objects.requireNonNull(table, "table");
        Objects.requireNonNull(assessment, "assessment");
        Objects.requireNonNull(lastCompaction, "lastCompaction");
    }

    /**
     * A table's place among the neediest: tables with equality deletes first ({@code urgency} 0, else 1), then the
     * highest score, then by id. A listing resumes strictly after a rank, so a route that stops part-way through a
     * page can resume after the last table it kept.
     */
    public record Rank(int urgency, double score, UUID objectId) {

        public Rank {
            Objects.requireNonNull(objectId, "objectId");
        }

        /** The rank of a table so assessed. */
        public static Rank of(UUID objectId, TableAssessment assessment) {
            return new Rank(urgency(assessment), assessment.score(), objectId);
        }

        /** 0 when the table has equality deletes, which every reader joins against, else 1. */
        public static int urgency(TableAssessment assessment) {
            return assessment.equalityDeleteFiles() > 0 ? 0 : 1;
        }

        public String token() {
            return PageToken.encode(Integer.toString(urgency), Double.toString(score), objectId.toString());
        }

        /** The rank a page token names; a token the store did not issue is an IllegalArgumentException. */
        public static Rank parse(List<String> parts) {
            return new Rank(Integer.parseInt(parts.get(0)), Double.parseDouble(parts.get(1)), UUID.fromString(parts.get(2)));
        }
    }

    /** This table's place among the neediest; only for a table assessed. */
    public Rank rank() {
        return Rank.of(table.id(), assessment.orElseThrow());
    }
}
