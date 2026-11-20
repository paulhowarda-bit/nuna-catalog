-- v1.x (D37, amended 2026-11-20): each table's and view's DR-safe watermark, the newest version of its
-- main pointer whose every file the maintenance worker's verifier found in the DR store. It is kept in
-- the catalog database so it reaches the DR region with the database; a purge's completion deletes its
-- row. The table is new and no server of the previous release reads or writes it, so those servers keep
-- serving while the first server of this release creates it: no table they use is altered or locked.
CREATE TABLE dr_watermark (
    object_id         UUID        PRIMARY KEY,
    version           BIGINT      NOT NULL CHECK (version >= 1),
    metadata_location TEXT        NOT NULL,
    recorded_at       TIMESTAMPTZ NOT NULL
);
