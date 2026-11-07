package fidice.catalog.store.suite;

import static java.nio.charset.StandardCharsets.UTF_8;

import fidice.catalog.store.CommitBatch;
import fidice.catalog.store.CommitHook;
import fidice.catalog.store.IdempotencyResult;
import fidice.catalog.store.JournalEntry;
import fidice.catalog.store.OutboxEvent;
import fidice.catalog.store.Pointer;
import fidice.catalog.store.PointerKey;
import fidice.catalog.store.PointerSwap;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Builds the pointers, swaps and batches the suite commits. */
public final class Commits {

    private Commits() {
    }

    public static PointerKey newKey() {
        return new PointerKey(UUID.randomUUID(), PointerKey.MAIN);
    }

    /** A pointer to {@code name}'s metadata file; the same name always gives the same location. */
    public static Pointer pointer(String name, long version) {
        String location = "s3://suite-warehouse/" + name + ".metadata.json";
        return new Pointer(location, version, sha256(location), ("{\"version\":" + version + "}").getBytes(UTF_8));
    }

    public static PointerSwap swap(PointerKey key, Optional<Pointer> expected, Pointer next) {
        JournalEntry journal = new JournalEntry("svc-suite", "end-user-" + next.version(), "suite-engine",
                "[{\"type\":\"assert-ref-snapshot-id\"}]", "[{\"action\":\"set-location\"}]");
        return new PointerSwap(key, expected, next, journal);
    }

    /** Swaps {@code key} from {@code expected} to a pointer named {@code name} at the next version. */
    public static PointerSwap advance(PointerKey key, Optional<Pointer> expected, String name) {
        return swap(key, expected, pointer(name, expected.map(Pointer::version).orElse(0L) + 1));
    }

    /** A batch of swaps with no outbox events and no idempotency result. */
    public static CommitBatch batch(PointerSwap... swaps) {
        return new CommitBatch(UUID.randomUUID(), Instant.now(), List.of(swaps), List.of(), Optional.empty());
    }

    /** A batch of swaps with two outbox events and an idempotency result: every record a commit writes. */
    public static CommitBatch fullBatch(PointerSwap... swaps) {
        UUID commitId = UUID.randomUUID();
        Instant now = Instant.now();
        List<OutboxEvent> outbox = List.of(
                new OutboxEvent("table.committed", "{\"commit\":\"" + commitId + "\"}"),
                new OutboxEvent("audit.recorded", "{\"principal\":\"svc-suite\"}"));
        IdempotencyResult idempotency = new IdempotencyResult("key-" + commitId, "svc-suite",
                sha256(commitId.toString()), 200, "{\"metadata-location\":\"" + swaps[0].next().metadataLocation() + "\"}",
                now.plus(Duration.ofHours(24)));
        return new CommitBatch(commitId, now, List.of(swaps), outbox, Optional.of(idempotency));
    }

    /** A hook that runs {@code fault} just before {@code batch}'s COMMIT and does nothing for other commits. */
    public static CommitHook before(CommitBatch batch, Runnable fault) {
        return commitId -> {
            if (commitId.equals(batch.commitId())) {
                fault.run();
            }
        };
    }

    public static Pointer next(CommitBatch batch) {
        return batch.swaps().getFirst().next();
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
