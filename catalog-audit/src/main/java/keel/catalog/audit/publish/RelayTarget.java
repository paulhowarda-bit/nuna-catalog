package keel.catalog.audit.publish;

import keel.catalog.audit.CatalogEvent;
import java.util.List;

/**
 * Where the outbox relay hands each batch it claims (D23). The relay marks a batch relayed only
 * after {@link #deliver} returns; if it throws, the relay offers the same batch again later, so
 * delivery is at least once and consumers drop repeats by event id.
 */
@FunctionalInterface
public interface RelayTarget {

    /**
     * Delivers the events in list order. Returns once every event is acknowledged. On the first
     * failure it throws, and no later event in the batch has been sent.
     *
     * @throws AuditUnavailableException if an event was not acknowledged
     */
    void deliver(List<CatalogEvent> orderedBatch);
}
