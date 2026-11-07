package fidice.catalog.store.suite;

import fidice.catalog.store.Pointer;
import fidice.catalog.store.PointerKey;
import java.time.Duration;
import java.util.Optional;

/**
 * Faults a backend injects for the shared suite. The link is the path between the stores and the
 * database (a TCP proxy); replication is the path from the primary to its replicas. Each method
 * returns once its fault is in effect.
 */
public interface Faults {

    /** Closes every connection on the link and refuses new ones. */
    void cutLink();

    /** Loses every byte sent towards the database; connections stay open. */
    void dropRequests();

    /** Loses every byte the database sends back; connections stay open. */
    void dropResponses();

    /** Removes every link fault. */
    void healLink();

    /** Replicas stop acknowledging, so a durable commit waits at the primary. */
    void stallReplication();

    /** Replicas acknowledge again. */
    void resumeReplication();

    /** Replicas still acknowledge commits at once, but apply them {@code lag} late. */
    void lagReplicaApply(Duration lag);

    /** Reads a pointer on a replica, where injected apply lag shows. */
    Optional<Pointer> readPointerOnReplica(PointerKey key);

    /** Kills the primary without warning. */
    void killPrimary();

    /** Promotes a replica; afterwards the link leads to it. */
    void promoteReplica();

    /** Returns to a healthy topology with no faults. The suite calls it after every test. */
    void restore();
}
