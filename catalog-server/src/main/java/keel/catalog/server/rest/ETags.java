package keel.catalog.server.rest;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Table-load ETags (D14). The tag hashes what decides the response body without reading metadata:
 * the metadata file's location (a file never changes), the snapshot mode, the view chain the load is
 * scoped to, and the credential epoch. So a 304 is decided from the pointer alone.
 */
final class ETags {

    private ETags() {
    }

    static String of(String metadataLocation, boolean refsOnly, String referencedBy, String credentialEpoch) {
        String canonical = String.join("\n", metadataLocation, refsOnly ? "refs" : "all",
                referencedBy == null ? "" : referencedBy, credentialEpoch);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return "\"" + HexFormat.of().formatHex(digest, 0, 16) + "\"";
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("every JVM provides SHA-256", e);
        }
    }

    /** Whether an {@code If-None-Match} header names {@code etag}: a list of tags, weak or strong, or {@code *}. */
    static boolean matches(String ifNoneMatch, String etag) {
        if (ifNoneMatch == null) {
            return false;
        }
        for (String part : ifNoneMatch.split(",")) {
            String tag = part.strip();
            if (tag.equals("*")) {
                return true;
            }
            if (tag.startsWith("W/")) {
                tag = tag.substring(2);
            }
            if (tag.equals(etag)) {
                return true;
            }
        }
        return false;
    }
}
