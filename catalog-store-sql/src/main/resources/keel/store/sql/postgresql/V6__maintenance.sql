-- v1.x (D30): the maintenance worker. A purge is claimed first: the object becomes PURGING, and
-- purge_claimant names the maintenance principal that holds the claim until it completes the purge
-- (PURGED). The purge scan reads dropped and claimed objects. pending_deletion holds the files the
-- worker deletes once the database's PITR window has passed (not_before), so a database restored to
-- any point it still holds never names a deleted file; a completed purge deletes an object's rows.
ALTER TABLE catalog_object DROP CONSTRAINT catalog_object_state_check;
ALTER TABLE catalog_object ADD CONSTRAINT catalog_object_state_check
    CHECK (state IN ('ACTIVE', 'DROPPED', 'PURGING', 'PURGED', 'UNREGISTERED'));
ALTER TABLE catalog_object ADD COLUMN purge_claimant TEXT;
CREATE INDEX catalog_object_purge_queue ON catalog_object (purge_after, id) WHERE state IN ('DROPPED', 'PURGING');
DROP INDEX catalog_object_purge;

CREATE TABLE pending_deletion (
    id          UUID        PRIMARY KEY,
    object_id   UUID        NOT NULL,
    catalog_id  UUID        NOT NULL,
    location    TEXT        NOT NULL,
    not_before  TIMESTAMPTZ NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX pending_deletion_due ON pending_deletion (not_before, id);
CREATE INDEX pending_deletion_object ON pending_deletion (object_id);
