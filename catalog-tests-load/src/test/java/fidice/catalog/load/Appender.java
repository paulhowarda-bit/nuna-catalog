package fidice.catalog.load;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import tools.jackson.databind.JsonNode;

/**
 * Appends snapshots to one table as an Iceberg client does: each logical commit adds one snapshot on top of
 * the current main and moves main to it, asserting main has not moved. A 409 waits a jittered, growing
 * while (as Iceberg's commit retry does), reloads and tries again with a new body; a 5xx or an unreachable server sends the same body again under the same idempotency key, so an
 * attempt that did commit is answered from its key instead of committing twice. Every snapshot's summary
 * carries its logical commit's name, and the ledger records which commits were acknowledged.
 */
final class Appender {

    /** What became of each logical commit, by name. */
    static final class Ledger {
        final Map<String, Long> acknowledged = new ConcurrentHashMap<>();
        final Set<String> unknown = ConcurrentHashMap.newKeySet();
        final AtomicLong conflicts = new AtomicLong();
        final AtomicLong resent = new AtomicLong();
        final Latencies commitLatency = new Latencies();
    }

    private static final long MIN_BACKOFF_MS = 20;
    private static final long MAX_BACKOFF_MS = 2_000;

    /** The table as the writer last saw it. */
    private record State(long mainSnapshot, long lastSequence, long nextRowId, String location, int formatVersion) {
    }

    private final Catalog catalog;
    private final List<String> namespace;
    private final String table;
    private final Ledger ledger;
    private State state;

    Appender(Catalog catalog, List<String> namespace, String table, Ledger ledger) {
        this.catalog = catalog;
        this.namespace = namespace;
        this.table = table;
        this.ledger = ledger;
    }

    /**
     * One logical commit named {@code op}; true once it is acknowledged. False when it is still unknown after
     * {@code patience} of retrying (recorded as unknown).
     */
    boolean append(String op, Duration patience) {
        return append(List.of(op), patience);
    }

    /**
     * One commit adding a snapshot for each of {@code ops}, each the parent of the next, and moving main to the
     * last; every op is acknowledged together or none is.
     */
    boolean append(List<String> ops, Duration patience) {
        long[] snapshotIds = new long[ops.size()];
        for (int i = 0; i < snapshotIds.length; i++) {
            snapshotIds[i] = ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE);
        }
        long deadline = System.nanoTime() + patience.toNanos();
        long started = System.nanoTime();
        boolean triedOnce = false;
        int conflicts = 0;
        while (System.nanoTime() < deadline) {
            if (state == null) {
                if (!reload(snapshotIds, ops, triedOnce, started)) {
                    continue;
                }
                if (ledger.acknowledged.containsKey(ops.getFirst())) {
                    return true;
                }
            }
            String body = body(snapshotIds, ops);
            String key = UUID.randomUUID().toString();
            boolean resend = true;
            while (resend && System.nanoTime() < deadline) {
                resend = false;
                Catalog.Answer answer;
                try {
                    triedOnce = true;
                    answer = catalog.commit(namespace, table, body, key);
                } catch (Catalog.Unreachable e) {
                    ledger.resent.incrementAndGet();
                    ServerProcess.pause(Duration.ofMillis(200));
                    resend = true;
                    continue;
                }
                if (answer.status() == 200) {
                    acknowledge(ops, snapshotIds, started);
                    state = state(answer.json().get("metadata"));
                    return true;
                }
                if (answer.status() == 409) {
                    ledger.conflicts.incrementAndGet();
                    state = null;
                    long ceiling = Math.min(MAX_BACKOFF_MS, MIN_BACKOFF_MS << Math.min(conflicts++, 10));
                    ServerProcess.pause(Duration.ofMillis(ThreadLocalRandom.current().nextLong(MIN_BACKOFF_MS, ceiling + 1)));
                } else if (answer.status() >= 500) {
                    ledger.resent.incrementAndGet();
                    ServerProcess.pause(Duration.ofMillis(200));
                    resend = true;
                } else {
                    throw new IllegalStateException("commit " + ops.getFirst() + " answered " + answer.status() + ": "
                            + answer.body());
                }
            }
        }
        ledger.unknown.addAll(ops);
        return false;
    }

    /** Reloads; a commit already tried may have landed, which the reload shows. False if no server answered. */
    private boolean reload(long[] snapshotIds, List<String> ops, boolean triedOnce, long started) {
        Catalog.Answer answer;
        try {
            answer = catalog.load(namespace, table, Map.of());
        } catch (Catalog.Unreachable e) {
            ServerProcess.pause(Duration.ofMillis(200));
            return false;
        }
        if (answer.status() != 200) {
            ServerProcess.pause(Duration.ofMillis(200));
            return false;
        }
        JsonNode metadata = answer.json().get("metadata");
        state = state(metadata);
        if (triedOnce) {
            for (JsonNode snapshot : metadata.get("snapshots")) {
                if (snapshot.get("snapshot-id").asLong() == snapshotIds[0]) {
                    acknowledge(ops, snapshotIds, started);
                    return true;
                }
            }
        }
        return true;
    }

    private void acknowledge(List<String> ops, long[] snapshotIds, long started) {
        for (int i = 0; i < snapshotIds.length; i++) {
            ledger.acknowledged.put(ops.get(i), snapshotIds[i]);
            ledger.unknown.remove(ops.get(i));
        }
        ledger.commitLatency.record(System.nanoTime() - started);
    }

    private String body(long[] snapshotIds, List<String> ops) {
        StringBuilder updates = new StringBuilder();
        long parent = state.mainSnapshot();
        long sequence = state.lastSequence();
        long rowId = state.nextRowId();
        for (int i = 0; i < snapshotIds.length; i++) {
            String lineage = state.formatVersion() >= 3 ? ",\"first-row-id\":" + rowId + ",\"added-rows\":1" : "";
            updates.append("""
                    {"action":"add-snapshot","snapshot":{"snapshot-id":%d%s,"sequence-number":%d,
                      "timestamp-ms":%d,"manifest-list":"%s/metadata/snap-%d.avro",
                      "summary":{"operation":"append","m8-op":"%s"},"schema-id":0%s}},"""
                    .formatted(snapshotIds[i], parent < 0 ? "" : ",\"parent-snapshot-id\":" + parent, ++sequence,
                            System.currentTimeMillis(), state.location(), snapshotIds[i], ops.get(i), lineage));
            parent = snapshotIds[i];
            rowId++;
        }
        return """
                {"requirements":[{"type":"assert-ref-snapshot-id","ref":"main","snapshot-id":%s}],
                 "updates":[%s{"action":"set-snapshot-ref","ref-name":"main","snapshot-id":%d,"type":"branch"}]}"""
                .formatted(state.mainSnapshot() < 0 ? "null" : String.valueOf(state.mainSnapshot()), updates,
                        snapshotIds[snapshotIds.length - 1]);
    }

    private static State state(JsonNode metadata) {
        JsonNode main = metadata.path("refs").path("main");
        return new State(main.isMissingNode() ? -1 : main.get("snapshot-id").asLong(),
                metadata.path("last-sequence-number").asLong(0), metadata.path("next-row-id").asLong(0),
                metadata.get("location").asString(), metadata.get("format-version").asInt());
    }

    /**
     * Checks the table against the ledger: main's history holds every acknowledged commit exactly once and no
     * commit that was neither acknowledged nor left unknown. Returns how many snapshots main's history has.
     */
    static int verify(Catalog catalog, List<String> namespace, String table, Ledger ledger, Set<String> ops) {
        JsonNode metadata = catalog.load(namespace, table, Map.of()).json().get("metadata");
        Map<Long, JsonNode> snapshots = new HashMap<>();
        metadata.get("snapshots").forEach(s -> snapshots.put(s.get("snapshot-id").asLong(), s));
        JsonNode main = metadata.path("refs").path("main");
        List<String> history = new ArrayList<>();
        Long id = main.isMissingNode() ? null : main.get("snapshot-id").asLong();
        while (id != null) {
            JsonNode snapshot = snapshots.get(id);
            history.add(snapshot.path("summary").path("m8-op").asString());
            id = snapshot.has("parent-snapshot-id") ? snapshot.get("parent-snapshot-id").asLong() : null;
        }
        Set<String> seen = new java.util.HashSet<>();
        for (String op : history) {
            if (!seen.add(op)) {
                throw new AssertionError("commit " + op + " is in main's history twice");
            }
            if (!ledger.acknowledged.containsKey(op) && !ledger.unknown.contains(op)) {
                throw new AssertionError("commit " + op + " is in main's history but was never sent");
            }
        }
        for (String op : ops) {
            if (ledger.acknowledged.containsKey(op) && !seen.contains(op)) {
                throw new AssertionError("acknowledged commit " + op + " is missing from main's history");
            }
        }
        return history.size();
    }
}
