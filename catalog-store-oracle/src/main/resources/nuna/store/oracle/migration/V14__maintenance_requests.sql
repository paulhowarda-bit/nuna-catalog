-- v1.x (D30, amended 2026-11-20; backlog 114): a request to run one of a table's heavy maintenance jobs now, a
-- compaction or an orphan scan, outside the nightly cleanup window too. One row per table and job: it waits
-- (started_at NULL) until the maintenance worker reports the job started, and a request made after that replaces
-- it. requested_by is the principal who asked, requested_at the catalog's time. A purge's completion deletes the
-- table's rows. The table is new and no server of the previous release reads or writes it, so those servers keep
-- serving while the first server of this release creates it: no table they use is altered or locked.
ALTER SESSION SET DDL_LOCK_TIMEOUT = 60;

CREATE TABLE maintenance_request (
    object_id    RAW(16)                  NOT NULL,
    job          VARCHAR2(20)             NOT NULL,
    requested_by VARCHAR2(1000 CHAR)      NOT NULL,
    requested_at TIMESTAMP WITH TIME ZONE NOT NULL,
    started_at   TIMESTAMP WITH TIME ZONE,
    CONSTRAINT maintenance_request_pk PRIMARY KEY (object_id, job)
);
-- The work list's requested section: the waiting requests, by the time asked. Oracle has no partial index; the
-- rows are one per table and job at most.
CREATE INDEX maintenance_request_waiting ON maintenance_request (requested_at, object_id, job);
