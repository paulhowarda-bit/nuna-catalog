package keel.catalog.objectstore;

import keel.catalog.core.commit.CommitRule;
import keel.catalog.core.commit.CommitRule.Rejection;
import keel.catalog.core.commit.TableFiles;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Commit rules that keep storage under the catalog's control (D21, D28), judged with the core rules.
 */
public final class StorageRules {

    /**
     * Property namespaces some engine's file IO reads from table properties. PyIceberg, for one, merges
     * table properties into its file IO configuration, so a client that could set these would choose the
     * endpoint, proxy or signer other readers send vended credentials to.
     */
    static final List<String> FILE_IO_PREFIXES = List.of("s3.", "client.", "http-client.", "gcs.", "adls.");
    static final List<String> FILE_IO_KEYS = List.of("io-impl", "py-io-impl");

    private StorageRules() {
    }

    /** The rules for objects whose files {@code files} assigns. */
    public static List<CommitRule> standard(TableFiles files) {
        return List.of(change -> assignedOnCreate(change, files), StorageRules::noFileIoProperties);
    }

    /**
     * A new table or view, registered ones included, lives at the location the catalog assigns to its id.
     * Ids are unique, so no two objects' locations equal or contain each other.
     */
    static Optional<Rejection> assignedOnCreate(CommitRule.Change change, TableFiles files) {
        if (change.before() != null) {
            return Optional.empty();
        }
        String assigned = files.newLocation(change.object().id());
        if (!assigned.equals(change.object().locationRoot())) {
            return Optional.of(Rejection.badRequest("Location is assigned by the catalog: " + assigned
                    + ", not " + change.object().locationRoot()));
        }
        return Optional.empty();
    }

    /** No client sets or changes a property that file IO reads: endpoints, credentials, proxies, signers. */
    static Optional<Rejection> noFileIoProperties(CommitRule.Change change) {
        Map<String, String> before = change.before() == null ? Map.of() : change.before().properties();
        for (Map.Entry<String, String> property : change.after().properties().entrySet()) {
            String key = property.getKey();
            if (fileIo(key) && !Objects.equals(property.getValue(), before.get(key))) {
                return Optional.of(Rejection.badRequest("Property " + key
                        + " configures storage access, which the catalog alone sets"));
            }
        }
        return Optional.empty();
    }

    static boolean fileIo(String key) {
        return FILE_IO_KEYS.contains(key) || FILE_IO_PREFIXES.stream().anyMatch(key::startsWith);
    }
}
