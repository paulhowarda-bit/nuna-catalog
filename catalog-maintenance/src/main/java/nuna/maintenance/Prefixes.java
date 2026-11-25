package nuna.maintenance;

import nuna.catalog.objectstore.LocationRejectedException;
import nuna.catalog.objectstore.ObjectLocation;
import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * The storage prefixes the catalog gave the worker at one scan (item 256; D30, amended 2026-11-24), each served by one
 * of the worker's own store identities: the only places it lists and deletes, strictly beneath a prefix, never at one.
 *
 * <p>The catalog names, for each storage profile with a maintenance principal, its store's kind and endpoint and its
 * prefixes ({@link Management#storageProfiles}). The worker serves a profile from the one identity in
 * {@code nuna.maintenance.storage} with that kind and endpoint; a profile that none has, or two, is refused whole. It
 * parses each prefix as the catalog's own canonical form ({@link ObjectLocation#parse}, after {@code s3a://} or
 * {@code s3n://} becomes {@code s3://}) and refuses one that fails: a bare {@code s3://}, a bucket holding {@code :},
 * an empty or dot segment, a wildcard. A prefix given for two stores is refused too. Each refusal is a sentence in
 * {@link #refused}, and the rest are kept.
 */
final class Prefixes {

    /** None: the worker lists and deletes nowhere. */
    static final Prefixes NONE = new Prefixes(List.of(), List.of());

    /** A prefix the worker reaches: where, the catalog's profile that gave it and the store identity serving it. */
    record Prefix(ObjectLocation location, String profile, String store) {

        @Override
        public String toString() {
            return location + " (profile " + profile + ", store " + store + ")";
        }
    }

    /** The prefixes, longest first, so a location is served by the nearest one above it. */
    private final List<Prefix> prefixes;
    private final List<String> refused;

    private Prefixes(List<Prefix> prefixes, List<String> refused) {
        this.prefixes = prefixes.stream().sorted(Comparator.comparingInt((Prefix prefix) -> prefix.location()
                .toString().length()).reversed()).toList();
        this.refused = List.copyOf(refused);
    }

    /** The catalog's profiles, each served by the identity in {@code stores} with its kind and endpoint. */
    static Prefixes resolve(List<Management.StorageProfile> profiles, Map<String, WorkerSettings.Store> stores) {
        List<String> refused = new ArrayList<>();
        Map<ObjectLocation, List<Prefix>> byLocation = new LinkedHashMap<>();
        for (Management.StorageProfile profile : profiles) {
            List<String> serving = stores.entrySet().stream().filter(store -> serves(store.getValue(), profile))
                    .map(Map.Entry::getKey).sorted().toList();
            if (serving.size() != 1) {
                refused.add("storage profile " + profile.name() + " (" + profile.kind() + " at " + profile.endpoint()
                        + "): " + (serving.isEmpty() ? "no store in nuna.maintenance.storage has its kind and endpoint"
                        : "the stores " + serving + " in nuna.maintenance.storage all have its kind and endpoint")
                        + ", so the worker lists and deletes nothing beneath " + profile.prefixes());
                continue;
            }
            for (String text : profile.prefixes()) {
                try {
                    byLocation.computeIfAbsent(parse(text), location -> new ArrayList<>())
                            .add(new Prefix(parse(text), profile.name(), serving.getFirst()));
                } catch (LocationRejectedException e) {
                    refused.add("prefix " + text + " of storage profile " + profile.name() + ": " + e.getMessage());
                }
            }
        }
        List<Prefix> reached = new ArrayList<>();
        byLocation.forEach((location, given) -> {
            List<String> storesOf = given.stream().map(Prefix::store).distinct().toList();
            if (storesOf.size() == 1) {
                reached.add(given.getFirst());
            } else {
                refused.add("prefix " + location + ": given for the stores " + storesOf + " at once");
            }
        });
        return new Prefixes(reached, refused);
    }

    /**
     * A prefix as the worker takes it: {@code s3a://} and {@code s3n://} as {@code s3://}, then the catalog's one
     * canonical spelling, or refused.
     *
     * @throws LocationRejectedException saying why
     */
    static ObjectLocation parse(String prefix) {
        return ObjectLocation.parse(Storage.normalize(prefix));
    }

    /** Whether the identity serves the profile: the same kind, and the same endpoint however either is written. */
    private static boolean serves(WorkerSettings.Store store, Management.StorageProfile profile) {
        if (!store.kind().name().equals(profile.kind().toUpperCase(Locale.ROOT))) {
            return false;
        }
        try {
            URI endpoint = URI.create(profile.endpoint());
            return endpoint.getHost() != null && TrustedS3Clients.endpoint(store.endpoint().toString())
                    .equals(TrustedS3Clients.endpoint(endpoint.toString()));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * The prefix a location lies strictly beneath, by whole segments, whatever S3 scheme it is written with; none for
     * a location at a prefix (with or without a {@code /} after it) or outside every one. A purge or orphan scan
     * rooted at a prefix would list and delete the whole profile.
     */
    Optional<Prefix> holding(String location) {
        String normalized = Storage.normalize(location);
        for (Prefix prefix : prefixes) {
            String under = prefix.location() + "/";
            if (normalized.startsWith(under) && normalized.length() > under.length()) {
                return Optional.of(prefix);
            }
        }
        return Optional.empty();
    }

    /** The prefixes the worker reaches, longest first. */
    List<Prefix> prefixes() {
        return prefixes;
    }

    /** What was refused, each a sentence naming the profile or prefix and why. */
    List<String> refused() {
        return refused;
    }

    /** Whether the worker reaches no prefix at all. */
    boolean isEmpty() {
        return prefixes.isEmpty();
    }

    @Override
    public String toString() {
        return prefixes.isEmpty() ? "none" : prefixes.stream().map(Prefix::toString).collect(Collectors.joining(", "));
    }
}
