package keel.catalog.objectstore;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CredentialCacheTest {

    private static final Instant T0 = Instant.parse("2026-11-08T10:00:00Z");
    private static final Duration LIFETIME = Duration.ofMinutes(30);
    private static final CredentialCache.Key ALICE =
            new CredentialCache.Key("lake", "s3://lake/t/orders", Access.READ_WRITE, "alice", "alice", "spark");

    private final MutableClock clock = new MutableClock(T0);
    private final AtomicInteger minted = new AtomicInteger();

    private final Supplier<VendedCredentials> mint = () -> {
        int n = minted.incrementAndGet();
        return new VendedCredentials("AK" + n, "secret", "token", clock.instant().plus(LIFETIME),
                "s3://lake/t/orders", Access.READ_WRITE, "alice_r" + n);
    };

    @Test
    @DisplayName("credentials are reused until renewBefore of their expiry, then replaced")
    void reusedForMostOfTheLifetime() {
        CredentialCache cache = new CredentialCache(Duration.ofMinutes(8), 100, clock);

        VendedCredentials first = cache.get(ALICE, mint);
        clock.set(T0.plus(Duration.ofMinutes(21)));
        assertThat(cache.get(ALICE, mint)).isSameAs(first);
        clock.set(T0.plus(Duration.ofMinutes(22)));
        VendedCredentials second = cache.get(ALICE, mint);

        assertThat(second.accessKeyId()).isEqualTo("AK2");
        assertThat(minted).hasValue(2);
    }

    @Test
    @DisplayName("another caller, access level or engine never receives someone else's credentials")
    void keyedByCallerAndAccess() {
        CredentialCache cache = new CredentialCache(Duration.ofMinutes(8), 100, clock);

        VendedCredentials alice = cache.get(ALICE, mint);
        List<CredentialCache.Key> others = List.of(
                new CredentialCache.Key("lake", "s3://lake/t/orders", Access.READ_WRITE, "bob", "bob", "spark"),
                new CredentialCache.Key("lake", "s3://lake/t/orders", Access.READ_WRITE, "svc", "alice", "spark"),
                new CredentialCache.Key("lake", "s3://lake/t/orders", Access.READ, "alice", "alice", "spark"),
                new CredentialCache.Key("lake", "s3://lake/t/orders", Access.READ_WRITE, "alice", "alice", "trino"),
                new CredentialCache.Key("lake", "s3://lake/t/orders2", Access.READ_WRITE, "alice", "alice", "spark"),
                new CredentialCache.Key("other", "s3://lake/t/orders", Access.READ_WRITE, "alice", "alice", "spark"));

        for (CredentialCache.Key other : others) {
            assertThat(cache.get(other, mint)).as(other.toString()).isNotSameAs(alice);
        }
        assertThat(minted).hasValue(1 + others.size());
    }

    @Test
    @DisplayName("concurrent requests for one key mint once")
    void singleFlight() throws Exception {
        CredentialCache cache = new CredentialCache(Duration.ofMinutes(8), 100, clock);
        CountDownLatch start = new CountDownLatch(1);
        Supplier<VendedCredentials> slowMint = () -> {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return mint.get();
        };
        List<Future<VendedCredentials>> results = new ArrayList<>();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < 16; i++) {
                results.add(executor.submit(() -> {
                    start.await();
                    return cache.get(ALICE, slowMint);
                }));
            }
            start.countDown();
            for (Future<VendedCredentials> result : results) {
                assertThat(result.get().accessKeyId()).isEqualTo("AK1");
            }
        }
        assertThat(minted).hasValue(1);
    }

    @Test
    @DisplayName("past its bound the cache drops what expires first")
    void bounded() {
        CredentialCache cache = new CredentialCache(Duration.ofMinutes(8), 2, clock);

        for (int i = 0; i < 5; i++) {
            clock.set(T0.plusSeconds(i));
            cache.get(new CredentialCache.Key("lake", "s3://lake/t/" + i, Access.READ, "a", "a", "e"), mint);
        }

        assertThat(cache.size()).isEqualTo(2);
        VendedCredentials newest = cache.get(new CredentialCache.Key("lake", "s3://lake/t/4", Access.READ, "a", "a", "e"),
                mint);
        assertThat(newest.accessKeyId()).as("the newest entry was kept").isEqualTo("AK5");
    }

    /** A clock tests move by hand. */
    static final class MutableClock extends Clock {

        private volatile Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void set(Instant instant) {
            now = instant;
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }
    }
}
