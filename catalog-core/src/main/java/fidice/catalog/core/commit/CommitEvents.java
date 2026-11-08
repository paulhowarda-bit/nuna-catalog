package fidice.catalog.core.commit;

import fidice.catalog.core.principal.Caller;
import fidice.catalog.store.CatalogObject;
import fidice.catalog.store.OutboxEvent;
import fidice.catalog.store.Pointer;
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

    /**
     * One commit as the change stream reports it.
     *
     * @param before                the pointer it replaced; empty when it created the object
     * @param regulatedSchemaChange it changed the schema or partition spec of a table in a regulated
     *                              namespace, which is journaled and alerted, not blocked (D11a)
     */
    record Commit(UUID commitId, Instant at, Caller caller, CatalogObject object, Optional<Pointer> before,
                  Pointer after, String requirementsJson, String updatesJson, boolean regulatedSchemaChange) {
    }
}
