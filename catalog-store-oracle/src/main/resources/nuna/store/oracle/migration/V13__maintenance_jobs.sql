-- v1.x (D45, backlog 126): each job of the maintenance worker's nights, kept for the console's overview and
-- maintenance pages, which lead with last night's totals and draw its compaction Jobs on their slots. night is the
-- date the cleanup window opened in the window's zone, and night_opens and night_closes the window itself, as the
-- worker saw it. slot is a compaction's slot, NULL for every other kind. The counts are what the job did: files_in
-- and files_out the files a compaction or manifest rewrite replaced and wrote, snapshots_expired, and files_deleted
-- and bytes_deleted for deletions and purges. A report replaces the row of its id unless the row ended later; the
-- server deletes the rows of nights more than 31 days old. No foreign key: a job outlives its table's purge.
-- The table is new and no server of the previous release reads or writes it, so those servers keep serving while
-- the first server of this release creates it: no table they use is altered or locked.
ALTER SESSION SET DDL_LOCK_TIMEOUT = 60;

CREATE TABLE maintenance_job (
    id                RAW(16)                  CONSTRAINT maintenance_job_pk PRIMARY KEY,
    worker            VARCHAR2(1000 CHAR)      NOT NULL,
    night             DATE                     NOT NULL,
    night_opens       TIMESTAMP WITH TIME ZONE NOT NULL,
    night_closes      TIMESTAMP WITH TIME ZONE NOT NULL,
    kind              VARCHAR2(20)             NOT NULL,
    object_id         RAW(16)                  NOT NULL,
    slot              NUMBER(10),
    started_at        TIMESTAMP WITH TIME ZONE NOT NULL,
    ended_at          TIMESTAMP WITH TIME ZONE,
    outcome           VARCHAR2(20)             NOT NULL,
    files_in          NUMBER(19)               NOT NULL,
    files_out         NUMBER(19)               NOT NULL,
    snapshots_expired NUMBER(19)               NOT NULL,
    files_deleted     NUMBER(19)               NOT NULL,
    bytes_deleted     NUMBER(19)               NOT NULL
);
-- A night's totals and its jobs of a kind, by start; and the deletion of old nights.
CREATE INDEX maintenance_job_night ON maintenance_job (night, kind, started_at, id);
