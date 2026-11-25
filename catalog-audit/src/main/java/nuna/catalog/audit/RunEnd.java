package nuna.catalog.audit;

import java.util.Objects;

/**
 * A catalog server's run ended in order (D23, amended 2026-11-25; backlog 262): the last record the run writes to the
 * log, once it serves no more requests and its outbox relay has stopped. Its {@link LogStamp} number is the run's
 * count of records. A run that stops without one stopped some other way, a crash, a kill or a lost node, and may have
 * lost its last lines on the way to the archive; the archive check names it.
 *
 * @param host the host the run was on, its pod name in Kubernetes
 */
public record RunEnd(String host) implements EventData {

    public RunEnd {
        Objects.requireNonNull(host, "host");
    }

    @Override
    public EventType type() {
        return EventType.RUN_END;
    }

    @Override
    public String subject() {
        return null;
    }
}
