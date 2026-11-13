-- v1.x (D13): an object can be unregistered, a final state. It keeps its row, id, pointers,
-- journal and grants, frees its name, and is never undropped or purged: the partial indexes on
-- active names and on dropped objects leave it out as they are. dropped_at holds when it was
-- unregistered; purge_after stays NULL. Registering its own metadata file again makes it ACTIVE.
ALTER TABLE catalog_object DROP CONSTRAINT catalog_object_state_check;
ALTER TABLE catalog_object ADD CONSTRAINT catalog_object_state_check
    CHECK (state IN ('ACTIVE', 'DROPPED', 'PURGED', 'UNREGISTERED'));
