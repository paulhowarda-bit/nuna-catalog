package nuna.catalog.store;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * One of the maintenance worker's storage identities as it reports it at each scan (backlog 250): the kind of store
 * and its endpoint, which together name the identity, and the prefixes the identity covers there. For each prefix,
 * whether the store let the worker list at most one key beneath it at the scan's start, and the deletes beneath it
 * since then, done, refused by the store, or failed without its answer. It names locations and the store's error codes
 * only: never an object key, a credential or a message.
 *
 * @param kind     the kind of store, {@code s3}
 * @param endpoint the store's endpoint as the worker's settings name it; empty for AWS S3's own
 * @param prefixes the prefixes the identity covers, each once
 */
public record WorkerStorage(String kind, Optional<String> endpoint, List<Prefix> prefixes) {

    /** How the store answered the worker's listing beneath a prefix. */
    public enum Listed {
        /** It listed. */
        OK,
        /** It answered with an error code: {@code AccessDenied}, {@code NoSuchBucket}, ... */
        REFUSED,
        /** No answer came: the store could not be reached. The code names the client's failure. */
        FAILED
    }

    /** A store's kind. */
    static final Pattern KIND = Pattern.compile("[a-z][a-z0-9-]{0,19}");
    /** A store's error code as it gives it, or the name of the client's failure. */
    static final Pattern CODE = Pattern.compile("[A-Za-z0-9._-]{1,100}");
    /** An endpoint, a prefix or a group: up to 1,000 characters, none of them a control character. */
    static final Pattern TEXT = Pattern.compile("\\P{Cntrl}{1,1000}");
    private static final int MAX_PREFIXES = 1000;

    public WorkerStorage {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(endpoint, "endpoint");
        prefixes = List.copyOf(prefixes);
        if (!KIND.matcher(kind).matches()) {
            throw new IllegalArgumentException("a store's kind is a label such as s3: " + kind);
        }
        if (endpoint.isPresent() && !TEXT.matcher(endpoint.get()).matches()) {
            throw new IllegalArgumentException("an endpoint is 1 to 1000 characters, none a control character");
        }
        if (prefixes.size() > MAX_PREFIXES) {
            throw new IllegalArgumentException("an identity covers at most " + MAX_PREFIXES + " prefixes");
        }
        Set<String> seen = new HashSet<>();
        for (Prefix prefix : prefixes) {
            if (!seen.add(prefix.location())) {
                throw new IllegalArgumentException("a prefix is reported once per identity: " + prefix.location());
            }
        }
    }

    /**
     * One prefix an identity covers, as one scan found it.
     *
     * @param location      the prefix, e.g. {@code s3://lake/tables}
     * @param listed        how the store answered the listing of at most one key beneath it, at the scan's start
     * @param listCode      the store's code when it refused the listing, or the client's failure when it failed;
     *                      empty when it listed
     * @param deleted       objects deleted beneath it since the scan started; final once the scan has ended
     * @param deleteRefused deletes beneath it the store refused
     * @param deleteFailed  deletes beneath it that got no answer from the store
     * @param deleteCode    the code of the first delete refused or failed; empty when none was
     */
    public record Prefix(String location, Listed listed, Optional<String> listCode, long deleted, long deleteRefused,
                         long deleteFailed, Optional<String> deleteCode) {

        public Prefix {
            Objects.requireNonNull(location, "location");
            Objects.requireNonNull(listed, "listed");
            Objects.requireNonNull(listCode, "listCode");
            Objects.requireNonNull(deleteCode, "deleteCode");
            if (!TEXT.matcher(location).matches()) {
                throw new IllegalArgumentException("a prefix is 1 to 1000 characters, none a control character");
            }
            if (listCode.isPresent() == (listed == Listed.OK)) {
                throw new IllegalArgumentException("a listing has a code exactly when it was refused or failed");
            }
            if (deleted < 0 || deleteRefused < 0 || deleteFailed < 0) {
                throw new IllegalArgumentException("delete counts are not negative");
            }
            if (deleteCode.isPresent() != (deleteRefused + deleteFailed > 0)) {
                throw new IllegalArgumentException("deletes have a code exactly when one was refused or failed");
            }
            if (!Stream.concat(listCode.stream(), deleteCode.stream()).allMatch(code -> CODE.matcher(code).matches())) {
                throw new IllegalArgumentException("a code is 1 to 100 letters, digits, dots, hyphens or underscores");
            }
        }
    }

    /** The identity's prefix that {@code location} lies beneath or is, with the identity. */
    public record Covering(WorkerStorage store, Prefix prefix) {
    }

    /**
     * The longest reported prefix that {@code location} lies beneath, or is, as the worker matches the locations it
     * lists and deletes: {@code s3a://} and {@code s3n://} read as {@code s3://}, and a prefix covers what lies
     * beneath it by whole segments, so {@code s3://b/ab} does not cover {@code s3://b/abc}. Empty when none does.
     */
    public static Optional<Covering> covering(List<WorkerStorage> storage, String location) {
        String wanted = normalize(location);
        return storage.stream()
                .flatMap(store -> store.prefixes().stream().map(prefix -> new Covering(store, prefix)))
                .filter(covering -> covers(normalize(covering.prefix().location()), wanted))
                .max(Comparator.comparingInt(covering -> normalize(covering.prefix().location()).length()));
    }

    private static boolean covers(String prefix, String location) {
        return location.equals(prefix) || location.startsWith(prefix.endsWith("/") ? prefix : prefix + "/");
    }

    private static String normalize(String location) {
        return location.startsWith("s3a://") || location.startsWith("s3n://") ? "s3://" + location.substring(6)
                : location;
    }

    // ---- as the SQL and Oracle stores keep it ----

    private static final String STORE = "store";
    private static final String PREFIX = "prefix";

    /**
     * The identities as one text: a line {@code store, kind, endpoint} for each, then a line {@code prefix, location,
     * listed, list code, deleted, refused, failed, delete code} for each of its prefixes, the fields separated by tabs
     * and an empty field for an empty value. No field holds a tab or a line break, so the stores carry no JSON library
     * for it; {@link #read} reads only what this writes.
     */
    public static String write(List<WorkerStorage> storage) {
        List<String> lines = new ArrayList<>();
        for (WorkerStorage store : storage) {
            lines.add(String.join("\t", STORE, store.kind(), store.endpoint().orElse("")));
            for (Prefix prefix : store.prefixes()) {
                lines.add(String.join("\t", PREFIX, prefix.location(), prefix.listed().name(),
                        prefix.listCode().orElse(""), Long.toString(prefix.deleted()),
                        Long.toString(prefix.deleteRefused()), Long.toString(prefix.deleteFailed()),
                        prefix.deleteCode().orElse("")));
            }
        }
        return String.join("\n", lines);
    }

    public static List<WorkerStorage> read(String text) {
        List<WorkerStorage> storage = new ArrayList<>();
        if (text.isEmpty()) {
            return storage;
        }
        String kind = null;
        Optional<String> endpoint = Optional.empty();
        List<Prefix> prefixes = new ArrayList<>();
        for (String line : text.split("\n", -1)) {
            String[] fields = line.split("\t", -1);
            if (fields[0].equals(STORE) && fields.length == 3) {
                if (kind != null) {
                    storage.add(new WorkerStorage(kind, endpoint, prefixes));
                }
                kind = fields[1];
                endpoint = optional(fields[2]);
                prefixes = new ArrayList<>();
            } else if (fields[0].equals(PREFIX) && fields.length == 8 && kind != null) {
                prefixes.add(new Prefix(fields[1], Listed.valueOf(fields[2]), optional(fields[3]),
                        Long.parseLong(fields[4]), Long.parseLong(fields[5]), Long.parseLong(fields[6]),
                        optional(fields[7])));
            } else {
                throw new IllegalArgumentException("worker storage: not a store or prefix line: " + line);
            }
        }
        storage.add(new WorkerStorage(kind, endpoint, prefixes));
        return storage;
    }

    private static Optional<String> optional(String field) {
        return field.isEmpty() ? Optional.empty() : Optional.of(field);
    }
}
