-- v1.x (D30): the maintenance worker. A purge is claimed first: the object becomes PURGING, and
-- purge_claimant names the maintenance principal that holds the claim until it completes the purge
-- (PURGED). The purge scan reads dropped and claimed objects through catalog_object_purge, which
-- leads with the state. pending_deletion holds the files the worker deletes once the database's PITR
-- window has passed (not_before), so a database restored to any point it still holds never names a
-- deleted file; a completed purge deletes an object's rows.
ALTER SESSION SET DDL_LOCK_TIMEOUT = 60;

ALTER TABLE catalog_object DROP CONSTRAINT catalog_object_state_ck;
ALTER TABLE catalog_object ADD CONSTRAINT catalog_object_state_ck
    CHECK (state IN ('ACTIVE', 'DROPPED', 'PURGING', 'PURGED', 'UNREGISTERED'));
ALTER TABLE catalog_object ADD (purge_claimant VARCHAR2(1000 CHAR));

CREATE TABLE pending_deletion (
    id          RAW(16)                  CONSTRAINT pending_deletion_pk PRIMARY KEY,
    object_id   RAW(16)                  NOT NULL,
    catalog_id  RAW(16)                  NOT NULL,
    location    VARCHAR2(4000)           NOT NULL,
    not_before  TIMESTAMP WITH TIME ZONE NOT NULL,
    recorded_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX pending_deletion_due ON pending_deletion (not_before, id);
CREATE INDEX pending_deletion_object ON pending_deletion (object_id);
