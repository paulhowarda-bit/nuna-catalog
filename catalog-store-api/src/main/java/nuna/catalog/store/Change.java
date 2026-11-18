package nuna.catalog.store;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * What every non-commit mutation writes beside its own records, in the same transaction: its
 * outbox events (at least one, since every change is audited, D23) and the idempotency result to
 * replay (D10). Like a commit id, {@code id} is generated before the transaction begins; it names
 * the change in the outbox, in {@link CommitOutcomeUnknownException} and to the {@link CommitHook}.
 * {@code at} is the change's time, e.g. a drop's dropped-at.
 */
public record Change(UUID id, Instant at, List<OutboxEvent> outbox, Optional<IdempotencyResult> idempotency) {

    public Change {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(at, "at");
        Objects.requireNonNull(idempotency, "idempotency");
        outbox = List.copyOf(outbox);
        if (outbox.isEmpty()) {
            throw new IllegalArgumentException("a change writes at least one outbox event");
        }
    }
}
