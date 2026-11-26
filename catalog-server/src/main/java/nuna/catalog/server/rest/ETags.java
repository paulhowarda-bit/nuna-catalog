package nuna.catalog.server.rest;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Table-load ETags (D14). The tag hashes what decides the response body without reading metadata:
 * the metadata file's location (a file never changes), the snapshot mode, the view chain the load is
 * scoped to, the credential epoch, the labels the load sends (D27, amended 2026-11-12), and the branch or tag a load
 * reads through (D33). So a 304 is
 * decided from the pointer and one governance read, and a label changed on the table or above it
 * changes the tag.
 */
final class ETags {

    private ETags() {
    }

    static String of(String metadataLocation, boolean refsOnly, String referencedBy, String credentialEpoch,
                     Map<String, String> labels) {
        return of(metadataLocation, refsOnly, referencedBy, credentialEpoch, labels, Optional.empty());
    }

    /**
     * {@link #of} for a load through a branch or tag, whose id the tag carries too (D33): the same file read through
     * main and through a tag is answered with different credentials. A load on main hashes what it always did.
     */
    static String of(String metadataLocation, boolean refsOnly, String referencedBy, String credentialEpoch,
                     Map<String, String> labels, Optional<UUID> ref) {
        return of(metadataLocation, refsOnly, referencedBy, credentialEpoch, labels, ref, Map.of());
    }

    /**
     * {@link #of} for a load that sets table properties of its own in the metadata it answers: a writer on one branch
     * only is told to write beneath the branch's place (backlog 113). With none it hashes what it always did.
     */
    static String of(String metadataLocation, boolean refsOnly, String referencedBy, String credentialEpoch,
                     Map<String, String> labels, Optional<UUID> ref, Map<String, String> places) {
        StringBuilder labelled = new StringBuilder();
        new TreeMap<>(labels).forEach((label, value) -> labelled.append(label.length()).append(':').append(label)
                .append(value.length()).append(':').append(value));
        String canonical = String.join("\n", metadataLocation, refsOnly ? "refs" : "all",
                referencedBy == null ? "" : referencedBy, credentialEpoch, labelled)
                + ref.map(id -> "\nref:" + id).orElse("")
                + (places.isEmpty() ? "" : "\nplaces:" + new TreeMap<>(places));
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
