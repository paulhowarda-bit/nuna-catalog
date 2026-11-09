package fidice.catalog.server.rest;

import fidice.catalog.core.CatalogError;
import fidice.catalog.core.commit.CommitSettings;
import fidice.catalog.core.principal.Caller;
import fidice.catalog.core.principal.Principal;
import fidice.catalog.iceberg.RestRequest;
import fidice.catalog.iceberg.Wire;
import fidice.catalog.store.Change;
import fidice.catalog.store.CatalogStore;
import fidice.catalog.store.CommitOutcomeUnknownException;
import fidice.catalog.store.IdempotencyClaim;
import fidice.catalog.store.IdempotencyResult;
import fidice.catalog.store.KeyState;
import fidice.catalog.store.OutboxEvent;
import fidice.catalog.store.StoreException;
import fidice.catalog.store.StoreUnavailableException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

/**
 * Idempotency keys for the mutations that are not commits: namespace create, drop and property
 * updates, drops and renames (D10). The commit engine keys its own routes the same way. The answer is
 * written in the change's own transaction when the change applies; a definite 4xx, or a success that
 * wrote nothing, is recorded after; a 5xx releases the key so a retry runs again.
 */
@Component
class Idempotency {

    private static final Logger LOG = LoggerFactory.getLogger(Idempotency.class);
    private static final int CLAIM_ROUNDS = 3;
    private static final int MAX_KEY_LENGTH = 128;

    private final CatalogStore store;
    private final Clock clock;
    private final CommitSettings settings = CommitSettings.DEFAULTS;

    Idempotency(CatalogStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    /** What a route answers; {@code written} says the answer went into the store with the change. */
    record Reply(int status, String body, boolean written) {

        static Reply of(int status, String body) {
            return new Reply(status, body, false);
        }

        static Reply error(int status, String type, String message) {
            return of(status, Wire.error(status, type, message));
        }

        ResponseEntity<String> response() {
            return status == 204 ? ResponseEntity.noContent().build() : Http.json(status, body);
        }
    }

    private record Key(String key, String principal, String requestHash, Instant expiresAt) {
    }

    /** One request's change: the id it writes with, which is also its claim's, and the answer to record. */
    static final class Ticket {
        private final UUID changeId;
        private final Optional<Key> key;

        private Ticket(UUID changeId, Optional<Key> key) {
            this.changeId = changeId;
            this.key = key;
        }

        /** The change that answers {@code status} with {@code body} (empty for 204). */
        Change change(Instant at, List<OutboxEvent> outbox, int status, String body) {
            return new Change(changeId, at, outbox, key.map(k -> new IdempotencyResult(k.key(), k.principal(),
                    k.requestHash(), status, body, k.expiresAt())));
        }
    }

    /**
     * Runs {@code work} under the key, if the client sent one.
     *
     * @param operation names the route; with {@code scope} (the path it acts on) and {@code body} it binds the key
     *                  to one request
     */
    ResponseEntity<String> run(Optional<String> key, Caller caller, String operation, String scope, String body,
                               Function<Ticket, Reply> work) {
        UUID changeId = UUID.randomUUID();
        if (key.isEmpty()) {
            return perform(work, new Ticket(changeId, Optional.empty())).response();
        }
        if (key.get().isBlank() || key.get().length() > MAX_KEY_LENGTH) {
            throw CatalogError.badRequest("Invalid Idempotency-Key: must be 1 to " + MAX_KEY_LENGTH + " characters");
        }
        Key fixed = new Key(key.get(), caller.principal().id(), requestHash(operation, scope, caller, body),
                clock.instant().plus(settings.keyLifetime()));
        Ticket ticket = new Ticket(changeId, Optional.of(fixed));
        RuntimeException lastClaimFailure = null;
        for (int round = 0; round < CLAIM_ROUNDS; round++) {
            KeyState state;
            try {
                Instant now = clock.instant();
                state = store.claimKey(new IdempotencyClaim(fixed.key(), fixed.principal(), fixed.requestHash(),
                        changeId, now, now.plus(settings.keyLease()), fixed.expiresAt()));
            } catch (CommitOutcomeUnknownException | StoreException e) {
                LOG.debug("claim of an idempotency key failed; claiming again", e);
                lastClaimFailure = e;
                continue;
            }
            switch (state) {
                case KeyState.Claimed claimed -> {
                    return finish(work, ticket, fixed).response();
                }
                case KeyState.InFlight inFlight when inFlight.changeId().equals(changeId) -> {
                    return finish(work, ticket, fixed).response();
                }
                case KeyState.Done done -> {
                    return replay(done.result());
                }
                case KeyState.Mismatch mismatch -> throw CatalogError.badRequest(
                        "Idempotency key " + fixed.key() + " was used for a different request");
                case KeyState.InFlight inFlight -> {
                    KeyState settled = store.awaitKey(fixed.key(), settings.keyLease());
                    if (settled instanceof KeyState.Done done) {
                        return replay(done.result());
                    }
                }
                case KeyState.Absent absent -> {
                }
            }
        }
        if (lastClaimFailure instanceof StoreUnavailableException) {
            // Nothing ran under the key; the request sent again claims it as this one would have.
            return Http.unavailable("Could not claim idempotency key " + fixed.key() + ": catalog store unavailable");
        }
        return Http.error(500, Http.SERVICE_FAILURE, "Could not claim idempotency key " + fixed.key());
    }

    private Reply finish(Function<Ticket, Reply> work, Ticket ticket, Key key) {
        Reply reply;
        try {
            reply = perform(work, ticket);
        } catch (RuntimeException e) {
            release(key, ticket.changeId);
            throw e;
        }
        if (reply.written()) {
            return reply;
        }
        if (reply.status() < 500) {
            try {
                store.completeKey(new IdempotencyResult(key.key(), key.principal(), key.requestHash(), reply.status(),
                        reply.body(), key.expiresAt()), ticket.changeId);
            } catch (RuntimeException e) {
                LOG.warn("recording the answer for an idempotency key failed", e);
            }
        } else {
            release(key, ticket.changeId);
        }
        return reply;
    }

    /** Definite failures the work throws become replies, so they are recorded like any other answer. */
    private static Reply perform(Function<Ticket, Reply> work, Ticket ticket) {
        try {
            return work.apply(ticket);
        } catch (CatalogError e) {
            return Reply.error(e.status(), e.type(), e.getMessage());
        } catch (RestRequest.InvalidRequest e) {
            return Reply.error(e.status(), e.type(), e.getMessage());
        } catch (StoreUnavailableException e) {
            // Nothing was applied: the key is released and the error handler answers 503 with Retry-After.
            throw e;
        } catch (StoreException e) {
            LOG.warn("catalog store failed", e);
            return Reply.error(500, Http.SERVICE_FAILURE, "Catalog store failed: " + e.getMessage());
        }
    }

    private void release(Key key, UUID changeId) {
        try {
            store.releaseKey(key.key(), changeId);
        } catch (RuntimeException e) {
            LOG.warn("releasing an idempotency key failed; it lapses with its lease", e);
        }
    }

    private static ResponseEntity<String> replay(IdempotencyResult result) {
        return Reply.of(result.status(), result.body()).response();
    }

    private static String requestHash(String operation, String scope, Caller caller, String body) {
        String canonical = String.join("\n", operation, scope, caller.endUser().map(Principal::id).orElse(""),
                body == null ? "" : body);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("every JVM provides SHA-256", e);
        }
    }
}
