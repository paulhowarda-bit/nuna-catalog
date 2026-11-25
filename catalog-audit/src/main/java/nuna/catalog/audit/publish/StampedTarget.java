package nuna.catalog.audit.publish;

import nuna.catalog.audit.StampedEvent;
import java.util.List;

/**
 * Where the audit trail sends a relayed batch's Kafka copy: each record as the log holds it, stamped with its run and
 * number (item 276). The contract is {@link RelayTarget}'s, which the relay hands its batches to before they are
 * logged and stamped.
 */
@FunctionalInterface
public interface StampedTarget {

    /**
     * Delivers the records in list order. Returns once every record is acknowledged. On the first failure it throws,
     * and no later record in the batch has been sent.
     *
     * @throws AuditUnavailableException if a record was not acknowledged
     */
    void deliver(List<StampedEvent> orderedBatch);
}
