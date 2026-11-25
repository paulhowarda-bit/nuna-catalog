package nuna.catalog.store;

import java.time.Instant;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;

/**
 * One scan of the work list by a maintenance worker, as it reports it to the catalog (D30, D38): when it started, and
 * once it has ended, when and how. A scan succeeds when it read the whole work list; a job failing in it is counted in
 * {@code failures} by kind, and the scan still succeeds (docs/observability/worker-metrics.md). And what the worker can
 * reach (backlog 250): the groups its token carried, and the storage it reached, by identity and prefix.
 *
 * @param id        the worker's id for the scan
 * @param worker    the maintenance principal that reported it
 * @param startedAt when it started, by the worker's clock
 * @param endedAt   when it ended; empty while it runs
 * @param outcome   how it ended; empty while it runs
 * @param failures  failed jobs and work list reads, by kind ({@code work-list}, {@code purge}, ...)
 * @param error     why a failed scan failed, at most {@value #MAX_ERROR} characters
 * @param groups    the groups the worker's token carried when it reported, which its grants are held by; empty for a
 *                  scan kept before the catalog recorded them
 * @param storage   the worker's storage identities, each with its prefixes, as it reported them; empty when it
 *                  reported none, as a worker of an earlier release does
 */
public record MaintenanceScan(UUID id, String worker, Instant startedAt, Optional<Instant> endedAt,
                              Optional<Outcome> outcome, Map<String, Integer> failures, Optional<String> error,
                              Optional<SortedSet<String>> groups, Optional<List<WorkerStorage>> storage) {

    public enum Outcome { SUCCEEDED, FAILED }

    public static final int MAX_ERROR = 1000;
    private static final int MAX_KINDS = 32;
    private static final int MAX_GROUPS = 1000;
    private static final int MAX_STORES = 32;

    public MaintenanceScan {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(worker, "worker");
        Objects.requireNonNull(startedAt, "startedAt");
        Objects.requireNonNull(endedAt, "endedAt");
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(error, "error");
        Objects.requireNonNull(groups, "groups");
        Objects.requireNonNull(storage, "storage");
        failures = Collections.unmodifiableMap(new TreeMap<>(failures));
        groups = groups.map(names -> Collections.unmodifiableSortedSet(new TreeSet<>(names)));
        storage = storage.map(List::copyOf);
        if (endedAt.isPresent() != outcome.isPresent()) {
            throw new IllegalArgumentException("a scan has an outcome exactly when it has ended");
        }
        if (endedAt.isPresent() && endedAt.get().isBefore(startedAt)) {
            throw new IllegalArgumentException("a scan ends after it starts");
        }
        if (failures.size() > MAX_KINDS || !failures.entrySet().stream().allMatch(failure ->
                TableAssessment.LABEL.matcher(failure.getKey()).matches() && failure.getValue() > 0)) {
            throw new IllegalArgumentException("failures are at most " + MAX_KINDS + " kinds such as work-list, each"
                    + " counted above 0: " + failures);
        }
        if (error.isPresent() && (error.get().isEmpty() || error.get().length() > MAX_ERROR)) {
            throw new IllegalArgumentException("an error is 1 to " + MAX_ERROR + " characters");
        }
        if (groups.isPresent() && (groups.get().size() > MAX_GROUPS
                || !groups.get().stream().allMatch(group -> WorkerStorage.TEXT.matcher(group).matches()))) {
            throw new IllegalArgumentException("groups are at most " + MAX_GROUPS + ", each 1 to 1000 characters,"
                    + " none a control character");
        }
        if (storage.isPresent()) {
            Set<List<Object>> identities = new HashSet<>();
            if (storage.get().size() > MAX_STORES || !storage.get().stream().allMatch(store ->
                    identities.add(List.of(store.kind(), store.endpoint())))) {
                throw new IllegalArgumentException("storage is at most " + MAX_STORES + " identities, each kind and"
                        + " endpoint once");
            }
        }
    }

    /** A scan just started, reporting nothing of what it reaches. */
    public static MaintenanceScan started(UUID id, String worker, Instant startedAt) {
        return new MaintenanceScan(id, worker, startedAt, Optional.empty(), Optional.empty(), Map.of(),
                Optional.empty(), Optional.empty(), Optional.empty());
    }

    /**
     * The reported prefix that {@code location} lies beneath, or is, and its identity, matched as the worker matches
     * ({@link WorkerStorage#covering}); empty when the scan reported no storage or no prefix covers it.
     */
    public Optional<WorkerStorage.Covering> covering(String location) {
        return storage.flatMap(stores -> WorkerStorage.covering(stores, location));
    }

    /** The groups as the SQL and Oracle stores keep them: one line each, in order; none is the empty text. */
    public static String writeGroups(SortedSet<String> groups) {
        return String.join("\n", groups);
    }

    public static SortedSet<String> readGroups(String text) {
        return text.isEmpty() ? new TreeSet<>() : new TreeSet<>(List.of(text.split("\n", -1)));
    }
}
