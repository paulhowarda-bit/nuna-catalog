-- v1.x (D38): what the maintenance worker reports of its own work, kept for the console's health
-- dashboard. table_health holds each table's latest assessment (its file counts, the verdict and its
-- reasons, its score) and how its last compaction went; a purge's completion deletes its row.
-- maintenance_scan holds the worker's recent scans and its last successful one. Both tables are new
-- and no server of the previous release reads or writes them, so those servers keep serving while
-- the first server of this release creates them: no table they use is altered or locked.
-- needed is 1 for a table that needs compaction, 0 otherwise. A score has at most four decimals,
-- so it is exact as a NUMBER and as a double alike.
ALTER SESSION SET DDL_LOCK_TIMEOUT = 60;

CREATE TABLE table_health (
    object_id             RAW(16)                  CONSTRAINT table_health_pk PRIMARY KEY,
    assessed_at           TIMESTAMP WITH TIME ZONE,
    data_files            NUMBER(19),
    small_files           NUMBER(19),
    position_delete_files NUMBER(19),
    deletion_vectors      NUMBER(19),
    equality_delete_files NUMBER(19),
    reasons               VARCHAR2(4000),
    score                 NUMBER,
    needed                NUMBER(1)                DEFAULT 0 NOT NULL,
    urgency               NUMBER(1)                DEFAULT 1 NOT NULL,
    compaction_started_at TIMESTAMP WITH TIME ZONE,
    compaction_ended_at   TIMESTAMP WITH TIME ZONE,
    compaction_outcome    VARCHAR2(20)
);
-- The neediest first: equality deletes (urgency 0), then the highest score.
CREATE INDEX table_health_neediest ON table_health (needed, urgency, score DESC, object_id);

CREATE TABLE maintenance_scan (
    id         RAW(16)                  CONSTRAINT maintenance_scan_pk PRIMARY KEY,
    worker     VARCHAR2(1000 CHAR)      NOT NULL,
    started_at TIMESTAMP WITH TIME ZONE NOT NULL,
    ended_at   TIMESTAMP WITH TIME ZONE,
    outcome    VARCHAR2(20),
    failures   VARCHAR2(4000)           NOT NULL,
    error      VARCHAR2(1000 CHAR)
);
CREATE INDEX maintenance_scan_started ON maintenance_scan (started_at, id);
