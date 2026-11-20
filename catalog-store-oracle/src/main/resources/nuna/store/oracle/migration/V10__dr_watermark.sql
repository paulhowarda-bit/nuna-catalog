-- v1.x (D37, amended 2026-11-20): each table's and view's DR-safe watermark, the newest version of its
-- main pointer whose every file the maintenance worker's verifier found in the DR store. It is kept in
-- the catalog database so it reaches the DR region with the database; a purge's completion deletes its
-- row. The table is new and no server of the previous release reads or writes it, so those servers keep
-- serving while the first server of this release creates it: no table they use is altered or locked.
ALTER SESSION SET DDL_LOCK_TIMEOUT = 60;

CREATE TABLE dr_watermark (
    object_id         RAW(16)                  CONSTRAINT dr_watermark_pk PRIMARY KEY,
    version           NUMBER(19)               NOT NULL CONSTRAINT dr_watermark_version_ck CHECK (version >= 1),
    metadata_location VARCHAR2(4000)           NOT NULL,
    recorded_at       TIMESTAMP WITH TIME ZONE NOT NULL
);
