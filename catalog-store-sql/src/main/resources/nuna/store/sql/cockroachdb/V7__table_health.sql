-- v1.x (D38): what the maintenance worker reports of its own work, kept for the console's health
-- dashboard. table_health holds each table's latest assessment (its file counts, the verdict and its
-- reasons, its score) and how its last compaction went; a purge's completion deletes its row.
-- maintenance_scan holds the worker's recent scans and its last successful one. Both tables are new
-- and no server of the previous release reads or writes them, so those servers keep serving while
-- the first server of this release creates them: no table they use is altered or locked.
CREATE TABLE table_health (
    object_id             UUID             PRIMARY KEY,
    assessed_at           TIMESTAMPTZ,
    data_files            BIGINT,
    small_files           BIGINT,
    position_delete_files BIGINT,
    deletion_vectors      BIGINT,
    equality_delete_files BIGINT,
    reasons               TEXT,
    score                 DOUBLE PRECISION,
    needed                BOOLEAN          NOT NULL DEFAULT FALSE,
    urgency               SMALLINT         NOT NULL DEFAULT 1,
    compaction_started_at TIMESTAMPTZ,
    compaction_ended_at   TIMESTAMPTZ,
    compaction_outcome    TEXT
);
-- The neediest first: equality deletes (urgency 0), then the highest score.
CREATE INDEX table_health_neediest ON table_health (urgency, score DESC, object_id) WHERE needed;

CREATE TABLE maintenance_scan (
    id         UUID        PRIMARY KEY,
    worker     TEXT        NOT NULL,
    started_at TIMESTAMPTZ NOT NULL,
    ended_at   TIMESTAMPTZ,
    outcome    TEXT,
    failures   TEXT        NOT NULL,
    error      TEXT
);
CREATE INDEX maintenance_scan_started ON maintenance_scan (started_at, id);
