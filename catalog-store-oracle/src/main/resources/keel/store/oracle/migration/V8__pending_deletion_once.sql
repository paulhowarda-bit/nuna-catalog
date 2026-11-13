-- v1.x (D30): a file is pending deletion once per object. The maintenance worker records what
-- snapshot expiry and every orphan scan find; a later scan finds the same unreferenced files again
-- until they are deleted, and records them again. Recording now skips a location already pending
-- for the object, which keeps its earlier not_before.
ALTER SESSION SET DDL_LOCK_TIMEOUT = 60;

DROP INDEX pending_deletion_object;
CREATE UNIQUE INDEX pending_deletion_object ON pending_deletion (object_id, location);
