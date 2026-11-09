package fidice.catalog.security;

import java.util.Arrays;
import java.util.Locale;

/**
 * What a grant gives a group on a securable and everything beneath it (D18). The ladder is
 * {@code describe < select < write < modify}: each rung implies the ones below. {@code create},
 * {@code manage-grants} and {@code manage-tags} stand apart. Every privilege implies
 * {@code describe}, since nothing can be done to what cannot be seen. Nothing implies
 * {@code manage-grants} or {@code manage-tags}, and neither implies data access.
 */
public enum Privilege {
    /** See that it exists: list, HEAD, load a namespace. */
    DESCRIBE,
    /** Read data: load a table or view, receive read credentials. */
    SELECT,
    /** Change data: commits that add or remove snapshots, statistics and refs. */
    WRITE,
    /** Change definitions: schema, partitioning, properties, view versions; drop and rename. */
    MODIFY,
    /** Create namespaces, tables and views inside it. */
    CREATE,
    /** Grant and revoke privileges on it. */
    MANAGE_GRANTS,
    /** Set classifications and other governance tags on it (D27). */
    MANAGE_TAGS;

    /** The name grants carry in the store and on the wire, e.g. {@code manage-grants}. */
    public String wireName() {
        return name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    /** @throws IllegalArgumentException for a name that is no privilege */
    public static Privilege fromWireName(String name) {
        return Arrays.stream(values()).filter(p -> p.wireName().equals(name)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown privilege '" + name + "'; known: "
                        + Arrays.stream(values()).map(Privilege::wireName).toList()));
    }

    /** Whether holding this privilege gives {@code needed}. */
    public boolean implies(Privilege needed) {
        if (this == needed || needed == DESCRIBE) {
            return true;
        }
        return switch (this) {
            case MODIFY -> needed == WRITE || needed == SELECT;
            case WRITE -> needed == SELECT;
            default -> false;
        };
    }
}
