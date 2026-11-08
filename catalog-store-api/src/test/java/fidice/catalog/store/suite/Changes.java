package fidice.catalog.store.suite;

import static fidice.catalog.store.suite.Commits.pointer;
import static fidice.catalog.store.suite.Commits.swap;

import fidice.catalog.store.CatalogObject;
import fidice.catalog.store.Change;
import fidice.catalog.store.CommitBatch;
import fidice.catalog.store.IdempotencyResult;
import fidice.catalog.store.Name;
import fidice.catalog.store.ObjectCreation;
import fidice.catalog.store.OutboxEvent;
import java.text.Normalizer;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/** Builds the names, changes and creations the group suites write. */
public final class Changes {

    private Changes() {
    }

    /** Now, to the millisecond: every backend keeps times at least that precisely. */
    public static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MILLIS);
    }

    /** The core's rule (catalog-core Names): Unicode NFC, then locale-independent lower case. */
    public static Name name(String display) {
        return new Name(Normalizer.normalize(display, Normalizer.Form.NFC).toLowerCase(Locale.ROOT), display);
    }

    /** A change with one outbox event and no idempotency result. */
    public static Change change() {
        UUID id = UUID.randomUUID();
        return new Change(id, now(), List.of(new OutboxEvent("suite.changed", "{\"change\":\"" + id + "\"}")),
                Optional.empty());
    }

    /** A change with two outbox events and an idempotency result: every record a change writes. */
    public static Change fullChange() {
        UUID id = UUID.randomUUID();
        Instant now = now();
        return new Change(id, now, List.of(
                new OutboxEvent("suite.changed", "{\"change\":\"" + id + "\"}"),
                new OutboxEvent("audit.recorded", "{\"principal\":\"svc-suite\"}")),
                Optional.of(result("key-" + id, 200, now)));
    }

    /** A change at {@code at} that completes the idempotency key {@code key} with a 204. */
    public static Change keyedChange(String key, UUID id, Instant at) {
        return new Change(id, at, List.of(new OutboxEvent("suite.changed", "{\"change\":\"" + id + "\"}")),
                Optional.of(result(key, 204, at)));
    }

    public static IdempotencyResult result(String key, int status, Instant at) {
        return new IdempotencyResult(key, "svc-suite", "hash-of-" + key, status, "{\"status\":" + status + "}",
                at.plus(Duration.ofHours(24)));
    }

    /** The object's creation, whose first commit writes two outbox events and an idempotency result. */
    public static ObjectCreation creation(CatalogObject object) {
        CommitBatch full = Commits.fullBatch(swap(object.mainKey(), Optional.empty(), pointer("created-" + object.id(), 1)));
        return new ObjectCreation(object, new CommitBatch(full.commitId(), now(), full.swaps(), full.outbox(),
                full.idempotency()));
    }
}
