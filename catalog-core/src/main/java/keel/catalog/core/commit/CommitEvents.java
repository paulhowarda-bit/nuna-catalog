package keel.catalog.core.commit;

import keel.catalog.core.principal.Caller;
import keel.catalog.store.CatalogObject;
import keel.catalog.store.OutboxEvent;
import keel.catalog.store.Pointer;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The change events one commit writes to the outbox, in the commit's own transaction (D23). The
 * event format belongs to catalog-audit, which implements this.
 */
@FunctionalInterface
public interface CommitEvents {

    List<OutboxEvent> of(Commit commit);

    /** What the change did to the object besides moving its pointer. */
    enum Kind {
        /** A commit, a create or a register. */
        COMMIT,
        /** A dropped object brought back by registering its own metadata file again (D13, D25). */
        UNDROP,
        /** The object was unregistered (D13); its pointer did not move, so {@code before} and {@code after} match. */
        UNREGISTER
    }

    /**
     * One commit as the change stream reports it.
     *
     * @param before                the pointer it replaced; empty when it created the object
     * @param regulatedSchemaChange it changed the schema or partition spec of a table in a regulated
     *                              namespace, which is journaled and alerted, not blocked (D11a)
     */
    record Commit(UUID commitId, Instant at, Caller caller, CatalogObject object, Optional<Pointer> before,
                  Pointer after, String requirementsJson, String updatesJson, boolean regulatedSchemaChange,
                  Kind kind) {

        public Commit(UUID commitId, Instant at, Caller caller, CatalogObject object, Optional<Pointer> before,
                      Pointer after, String requirementsJson, String updatesJson, boolean regulatedSchemaChange) {
            this(commitId, at, caller, object, before, after, requirementsJson, updatesJson, regulatedSchemaChange,
                    Kind.COMMIT);
        }
    }
}
