package keel.catalog.objectstore;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Who vended credentials are for, carried into the STS session so the store's own logs name them
 * (D21). The session name holds the principal and the request id; the session tags hold every field
 * in full, up to the STS limits.
 *
 * @param principal the principal the token names
 * @param endUser   the end user a trusted engine acts for, if any (D17)
 * @param engine    the client id the token was issued to
 * @param requestId the request that asked for the credentials
 * @param catalog   the catalog's name
 * @param table     the table's id
 */
public record SessionIdentity(String principal, Optional<String> endUser, String engine, String requestId,
                              String catalog, UUID table) {

    /** STS: a session name is 2 to 64 of these characters. */
    static final int NAME_MAX = 64;
    private static final Pattern NAME_OUTSIDE = Pattern.compile("[^\\w+=,.@-]");
    /** STS: a tag value is up to 256 of these characters. */
    static final int TAG_VALUE_MAX = 256;
    private static final Pattern TAG_OUTSIDE = Pattern.compile("[^\\p{L}\\p{Z}\\p{N}_.:/=+\\-@]");
    /** The request id part of the name is at most a UUID's length. */
    private static final int REQUEST_PART_MAX = 36;

    public SessionIdentity {
        Objects.requireNonNull(principal, "principal");
        Objects.requireNonNull(endUser, "endUser");
        Objects.requireNonNull(engine, "engine");
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(catalog, "catalog");
        Objects.requireNonNull(table, "table");
    }

    /** Whom the access is for: the end user when an engine acts for one. */
    public String subject() {
        return endUser.orElse(principal);
    }

    /**
     * {@code <subject>_<request id>}, characters STS does not allow replaced by {@code -}. The request id
     * keeps up to 36 characters and the subject is cut to fit 64; the tags carry both in full.
     */
    public String sessionName() {
        String request = clean(requestId, NAME_OUTSIDE);
        request = request.substring(0, Math.min(request.length(), REQUEST_PART_MAX));
        String subject = clean(subject(), NAME_OUTSIDE);
        subject = subject.substring(0, Math.min(subject.length(), NAME_MAX - 1 - request.length()));
        return subject + "_" + request;
    }

    /** Session tags, in a fixed order; values cleaned like the name and cut at 256 characters. */
    public Map<String, String> tags() {
        Map<String, String> tags = new LinkedHashMap<>();
        tags.put("keel-principal", tagValue(principal));
        endUser.ifPresent(user -> tags.put("keel-end-user", tagValue(user)));
        tags.put("keel-engine", tagValue(engine));
        tags.put("keel-request", tagValue(requestId));
        tags.put("keel-catalog", tagValue(catalog));
        tags.put("keel-table", table.toString());
        return tags;
    }

    private static String tagValue(String value) {
        String clean = clean(value, TAG_OUTSIDE);
        return clean.substring(0, Math.min(clean.length(), TAG_VALUE_MAX));
    }

    private static String clean(String value, Pattern outside) {
        return outside.matcher(value).replaceAll("-");
    }
}
