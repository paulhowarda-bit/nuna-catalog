package keel.catalog.server.governance;

import keel.catalog.core.CatalogError;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Table maintenance policy (D30): what the maintenance worker (v1.x) does to a table, set on a
 * catalog, namespace, table or the deployment's defaults, key by key. A level sets only the keys it
 * names; every other key is inherited from the level above. The resolved policy then passes the
 * guardrails: holds and the DR freeze stop cleanup, a retention class raises the snapshot age, and
 * orphan removal never touches a file younger than the database's PITR window.
 */
public final class MaintenancePolicy {

    public static final String EXPIRY_ENABLED = "snapshot-expiry.enabled";
    public static final String EXPIRY_MAX_AGE = "snapshot-expiry.max-snapshot-age";
    public static final String EXPIRY_MIN_SNAPSHOTS = "snapshot-expiry.min-snapshots-to-keep";
    public static final String ORPHAN_ENABLED = "orphan-removal.enabled";
    public static final String ORPHAN_OLDER_THAN = "orphan-removal.older-than";
    public static final String COMPACTION_ENABLED = "compaction.enabled";
    public static final String COMPACTION_TARGET_FILE_SIZE = "compaction.target-file-size-bytes";
    /**
     * How many data files under 75% of the target size one partition must hold for the table to need compaction
     * (D30, amended 2026-11-15); rewrite_data_files' {@code min-input-files}, 5 by default.
     */
    public static final String COMPACTION_MIN_INPUT_FILES = "compaction.min-input-files";
    /**
     * The share of its data files that its delete files (position, equality, deletion vectors) must pass for a table
     * to need compaction; 0.3 by default, rewrite_data_files' {@code delete-ratio-threshold}.
     */
    public static final String COMPACTION_DELETE_FILE_RATIO = "compaction.delete-file-ratio";
    public static final String REWRITE_ENABLED = "manifest-rewrite.enabled";
    public static final String REWRITE_MIN_MANIFESTS = "manifest-rewrite.min-manifests";

    private enum Type { BOOLEAN, DURATION, COUNT, RATIO }

    /** Every key a policy may set, with its type; the order is the order a resolved policy lists them. */
    private static final Map<String, Type> KEYS = keys();

    /** What a policy starts from when the deployment sets no defaults: nothing runs. */
    static final Map<String, String> BUILT_IN = Map.ofEntries(
            Map.entry(EXPIRY_ENABLED, "false"), Map.entry(EXPIRY_MAX_AGE, "P5D"), Map.entry(EXPIRY_MIN_SNAPSHOTS, "1"),
            Map.entry(ORPHAN_ENABLED, "false"), Map.entry(ORPHAN_OLDER_THAN, "P14D"),
            Map.entry(COMPACTION_ENABLED, "false"), Map.entry(COMPACTION_TARGET_FILE_SIZE, "536870912"),
            Map.entry(COMPACTION_MIN_INPUT_FILES, "5"), Map.entry(COMPACTION_DELETE_FILE_RATIO, "0.3"),
            Map.entry(REWRITE_ENABLED, "false"), Map.entry(REWRITE_MIN_MANIFESTS, "100"));

    private static Map<String, Type> keys() {
        Map<String, Type> keys = new LinkedHashMap<>();
        keys.put(EXPIRY_ENABLED, Type.BOOLEAN);
        keys.put(EXPIRY_MAX_AGE, Type.DURATION);
        keys.put(EXPIRY_MIN_SNAPSHOTS, Type.COUNT);
        keys.put(ORPHAN_ENABLED, Type.BOOLEAN);
        keys.put(ORPHAN_OLDER_THAN, Type.DURATION);
        keys.put(COMPACTION_ENABLED, Type.BOOLEAN);
        keys.put(COMPACTION_TARGET_FILE_SIZE, Type.COUNT);
        keys.put(COMPACTION_MIN_INPUT_FILES, Type.COUNT);
        keys.put(COMPACTION_DELETE_FILE_RATIO, Type.RATIO);
        keys.put(REWRITE_ENABLED, Type.BOOLEAN);
        keys.put(REWRITE_MIN_MANIFESTS, Type.COUNT);
        return Collections.unmodifiableMap(keys);
    }

    /**
     * One level's setting of one key in a resolved policy.
     *
     * @param value  the value in force
     * @param source where it was set: {@code default}, or the level's kind and id; or {@code guardrail}
     * @param reason why a guardrail changed it, or null
     */
    public record Setting(String value, String source, String reason) {
    }

    private MaintenancePolicy() {
    }

    /**
     * A level's own keys, checked and in canonical form (ISO-8601 durations, plain integers, plain decimals,
     * lower-case booleans), sorted by key.
     *
     * @throws CatalogError 400 for an unknown key or a value of the wrong type
     */
    public static Map<String, String> canonical(Map<String, String> policy, GovernanceSettings settings) {
        Map<String, String> canonical = new TreeMap<>();
        for (Map.Entry<String, String> entry : policy.entrySet()) {
            Type type = KEYS.get(entry.getKey());
            if (type == null) {
                throw CatalogError.badRequest("Unknown maintenance policy key " + entry.getKey() + "; known keys: "
                        + KEYS.keySet());
            }
            canonical.put(entry.getKey(), parse(entry.getKey(), type, entry.getValue()));
        }
        Duration minimum = settings.minimumSnapshotAge();
        if (canonical.containsKey(EXPIRY_MAX_AGE) && Duration.parse(canonical.get(EXPIRY_MAX_AGE)).compareTo(minimum) < 0) {
            throw CatalogError.badRequest(EXPIRY_MAX_AGE + " must be at least " + minimum + ", the minimum snapshot age");
        }
        if (canonical.containsKey(ORPHAN_OLDER_THAN)
                && Duration.parse(canonical.get(ORPHAN_OLDER_THAN)).compareTo(settings.pitrWindow()) <= 0) {
            throw CatalogError.badRequest(ORPHAN_OLDER_THAN + " must be longer than the database PITR window "
                    + settings.pitrWindow() + ", so a restore never needs a removed file");
        }
        return canonical;
    }

    /**
     * Resolves the policy for a level and applies the guardrails.
     *
     * @param levels         each level's own keys, outermost (catalog) first, keyed by a source label
     * @param held           a legal hold covers the level (D24)
     * @param frozen         a DR freeze covers the level (D37)
     * @param retentionClass the nearest retention class's minimum snapshot age, if it has one
     */
    public static Map<String, Setting> resolve(GovernanceSettings settings, List<Map.Entry<String, Map<String, String>>> levels,
                                               boolean held, boolean frozen, Duration retentionClass) {
        Map<String, Setting> resolved = new LinkedHashMap<>();
        for (String key : KEYS.keySet()) {
            resolved.put(key, new Setting(BUILT_IN.get(key), "built-in", null));
        }
        for (Map.Entry<String, String> entry : settings.maintenanceDefaults().entrySet()) {
            resolved.put(entry.getKey(), new Setting(entry.getValue(), "default", null));
        }
        for (Map.Entry<String, Map<String, String>> level : levels) {
            level.getValue().forEach((key, value) -> resolved.put(key, new Setting(value, level.getKey(), null)));
        }
        Duration minimumAge = retentionClass == null || retentionClass.compareTo(settings.minimumSnapshotAge()) < 0
                ? settings.minimumSnapshotAge() : retentionClass;
        if (Duration.parse(resolved.get(EXPIRY_MAX_AGE).value()).compareTo(minimumAge) < 0) {
            resolved.put(EXPIRY_MAX_AGE, new Setting(minimumAge.toString(), "guardrail",
                    "raised to the minimum snapshot age" + (minimumAge.equals(retentionClass)
                            ? " of the retention class" : "")));
        }
        Duration orphanFloor = settings.pitrWindow().plusDays(1);
        if (Duration.parse(resolved.get(ORPHAN_OLDER_THAN).value()).compareTo(settings.pitrWindow()) <= 0) {
            resolved.put(ORPHAN_OLDER_THAN, new Setting(orphanFloor.toString(), "guardrail",
                    "raised above the database PITR window " + settings.pitrWindow()));
        }
        if (frozen) {
            for (String key : List.of(EXPIRY_ENABLED, ORPHAN_ENABLED, COMPACTION_ENABLED, REWRITE_ENABLED)) {
                resolved.put(key, new Setting("false", "guardrail", "frozen for DR reconcile"));
            }
        } else if (held) {
            for (String key : List.of(EXPIRY_ENABLED, ORPHAN_ENABLED)) {
                resolved.put(key, new Setting("false", "guardrail", "under legal hold: nothing may be removed"));
            }
        }
        return resolved;
    }

    /** The keys a policy may set. */
    public static List<String> keyNames() {
        return new ArrayList<>(KEYS.keySet());
    }

    private static String parse(String key, Type type, String value) {
        if (value == null) {
            throw CatalogError.badRequest("Maintenance policy key " + key + " needs a value");
        }
        switch (type) {
            case BOOLEAN -> {
                if (!value.equals("true") && !value.equals("false")) {
                    throw CatalogError.badRequest(key + " is true or false, not " + value);
                }
                return value;
            }
            case DURATION -> {
                try {
                    Duration duration = Duration.parse(value);
                    if (duration.isNegative() || duration.isZero()) {
                        throw CatalogError.badRequest(key + " must be positive, not " + value);
                    }
                    return duration.toString();
                } catch (DateTimeParseException e) {
                    throw CatalogError.badRequest(key + " is an ISO-8601 duration such as P7D or PT12H, not " + value);
                }
            }
            case RATIO -> {
                try {
                    BigDecimal ratio = new BigDecimal(value);
                    if (ratio.signum() <= 0) {
                        throw CatalogError.badRequest(key + " must be above 0, not " + value);
                    }
                    return ratio.stripTrailingZeros().toPlainString();
                } catch (NumberFormatException e) {
                    throw CatalogError.badRequest(key + " is a decimal number such as 0.3, not " + value);
                }
            }
            default -> {
                try {
                    long count = Long.parseLong(value);
                    if (count < 1) {
                        throw CatalogError.badRequest(key + " must be at least 1, not " + value);
                    }
                    return Long.toString(count);
                } catch (NumberFormatException e) {
                    throw CatalogError.badRequest(key + " is a whole number, not " + value);
                }
            }
        }
    }
}
