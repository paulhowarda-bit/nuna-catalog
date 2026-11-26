package nuna.catalog.objectstore;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Keeps vended credentials for most of their lifetime (D21): a caller asking again for the same table and
 * access gets the same credentials until they are within {@code renewBefore} of expiring. One caller's
 * credentials are never handed to another, since their session names the caller. Concurrent requests for
 * one key mint once.
 */
public final class CredentialCache {

    /**
     * @param principal the principal the token names
     * @param subject   whom the access is for (the end user, when an engine acts for one)
     */
    /** @param writeUnder where beneath the location the credentials may write, when not all of it (backlog 113) */
    public record Key(String profile, String location, Access access, String principal, String subject,
                      String engine, Optional<String> writeUnder) {

        public Key(String profile, String location, Access access, String principal, String subject, String engine) {
            this(profile, location, access, principal, subject, engine, Optional.empty());
        }

        public Key {
            Objects.requireNonNull(profile, "profile");
            Objects.requireNonNull(location, "location");
            Objects.requireNonNull(access, "access");
            Objects.requireNonNull(principal, "principal");
            Objects.requireNonNull(subject, "subject");
            Objects.requireNonNull(engine, "engine");
            Objects.requireNonNull(writeUnder, "writeUnder");
        }
    }

    private static final class Slot {
        private VendedCredentials credentials;
    }

    private final Duration renewBefore;
    private final int maxEntries;
    private final Clock clock;
    private final Map<Key, Slot> slots = new ConcurrentHashMap<>();

    /**
     * @param renewBefore credentials this close to expiring are not handed out again
     * @param maxEntries  past this many, the entries that expire first are dropped
     */
    public CredentialCache(Duration renewBefore, int maxEntries, Clock clock) {
        if (renewBefore.isNegative() || maxEntries < 1) {
            throw new IllegalArgumentException("renewBefore must not be negative and maxEntries must be positive");
        }
        this.renewBefore = renewBefore;
        this.maxEntries = maxEntries;
        this.clock = clock;
    }

    /** The cached credentials for {@code key} if they have more than {@code renewBefore} left, otherwise new ones from {@code mint}. */
    public VendedCredentials get(Key key, Supplier<VendedCredentials> mint) {
        Slot slot = slots.computeIfAbsent(key, k -> new Slot());
        VendedCredentials result;
        synchronized (slot) {
            if (slot.credentials == null || !clock.instant().plus(renewBefore).isBefore(slot.credentials.expiresAt())) {
                slot.credentials = mint.get();
            }
            result = slot.credentials;
        }
        if (slots.size() > maxEntries) {
            evict();
        }
        return result;
    }

    int size() {
        return slots.size();
    }

    /** Drops the slots that expire first until the cache is back at its bound; empty slots go first. */
    private void evict() {
        Comparator<Map.Entry<Key, Slot>> firstToExpire = Comparator.comparing(entry -> {
            VendedCredentials credentials = entry.getValue().credentials;
            return credentials == null ? Instant.MIN : credentials.expiresAt();
        });
        slots.entrySet().stream()
                .sorted(firstToExpire)
                .limit(Math.max(0, slots.size() - maxEntries))
                .map(Map.Entry::getKey)
                .toList()
                .forEach(slots::remove);
    }
}
