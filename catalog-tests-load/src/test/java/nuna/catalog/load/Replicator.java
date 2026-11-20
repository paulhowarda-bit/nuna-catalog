package nuna.catalog.load;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.S3Object;

/**
 * A bucket's asynchronous replication to another store, as the DR region's storage gets it (D37): a thread copying
 * each object it has not copied yet to the same bucket and key there, one round after another, until the test pauses
 * it. A round lists the bucket, then copies what it listed, so once a round ends the other store holds every object
 * written before that round's listing. After {@link #pause}, nothing more arrives.
 */
final class Replicator implements AutoCloseable {

    private static final Duration PERIOD = Duration.ofMillis(200);
    private static final Duration PATIENCE = Duration.ofMinutes(2);

    private final S3Client from;
    private final S3Client to;
    private final String bucket;
    private final Set<String> copied = ConcurrentHashMap.newKeySet();
    private final Thread thread;
    /** Rounds ended; guarded by this. */
    private long rounds;
    /** Guarded by this. */
    private boolean paused;
    /** Why the thread stopped, if it failed; guarded by this. */
    private RuntimeException failure;
    private volatile boolean closed;

    /** Starts copying {@code bucket} from {@code from} to {@code to}; it closes both clients when closed. */
    Replicator(S3Client from, S3Client to, String bucket) {
        this.from = from;
        this.to = to;
        this.bucket = bucket;
        thread = Thread.ofPlatform().name("replicator-" + bucket).daemon(true).start(this::run);
    }

    private void run() {
        try {
            while (!closed) {
                synchronized (this) {
                    if (!paused) {
                        round();
                        rounds++;
                        notifyAll();
                    }
                }
                Thread.sleep(PERIOD);
            }
        } catch (InterruptedException e) {
            // closed
        } catch (RuntimeException e) {
            synchronized (this) {
                failure = e;
                notifyAll();
            }
        }
    }

    private void round() {
        List<S3Object> listed = new ArrayList<>();
        from.listObjectsV2Paginator(r -> r.bucket(bucket)).contents().forEach(listed::add);
        for (S3Object object : listed) {
            if (copied.contains(object.key())) {
                continue;
            }
            byte[] bytes = from.getObjectAsBytes(r -> r.bucket(bucket).key(object.key())).asByteArray();
            to.putObject(r -> r.bucket(bucket).key(object.key()), RequestBody.fromBytes(bytes));
            copied.add(object.key());
        }
    }

    /**
     * Waits until a round that listed the bucket after this call has ended. No round runs while this holds the lock, so
     * the next to end starts after it.
     */
    synchronized void awaitRound() throws InterruptedException {
        long target = rounds + 1;
        long deadline = System.nanoTime() + PATIENCE.toNanos();
        while (rounds < target) {
            check();
            long left = deadline - System.nanoTime();
            if (left <= 0) {
                throw new IllegalStateException("no replication round ended in " + PATIENCE);
            }
            wait(Math.max(1, left / 1_000_000));
        }
        check();
    }

    /**
     * Stops replicating once the round in progress has ended (taking the lock waits for it): nothing written from now
     * on reaches the other store. Returns every location it copied, as {@code s3://} URIs.
     */
    synchronized Set<String> pause() {
        check();
        paused = true;
        return copied.stream().map(key -> "s3://" + bucket + "/" + key).collect(Collectors.toUnmodifiableSet());
    }

    private void check() {
        if (failure != null) {
            throw new IllegalStateException("replicating bucket " + bucket + " failed", failure);
        }
    }

    @Override
    public void close() throws InterruptedException {
        closed = true;
        thread.interrupt();
        thread.join(PATIENCE.toMillis());
        from.close();
        to.close();
    }
}
