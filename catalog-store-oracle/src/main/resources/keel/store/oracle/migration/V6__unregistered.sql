-- v1.x (D13): an object can be unregistered, a final state. It keeps its row, id, pointers,
-- journal and grants, frees its name, and is never undropped or purged: the unique index on active
-- names leaves it out, and the purge scan reads only DROPPED. dropped_at holds when it was
-- unregistered; purge_after stays NULL. Registering its own metadata file again makes it ACTIVE.
ALTER SESSION SET DDL_LOCK_TIMEOUT = 60;

ALTER TABLE catalog_object MODIFY (state VARCHAR2(12));
ALTER TABLE catalog_object DROP CONSTRAINT catalog_object_state_ck;
ALTER TABLE catalog_object ADD CONSTRAINT catalog_object_state_ck
    CHECK (state IN ('ACTIVE', 'DROPPED', 'PURGED', 'UNREGISTERED'));
