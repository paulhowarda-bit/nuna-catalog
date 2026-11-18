package nuna.catalog.objectstore;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * An {@code s3://bucket/key} location in its one canonical spelling. Anything another component
 * could read differently is rejected rather than normalized: another scheme or its upper-case
 * spelling, an invalid bucket name (which also rules out user info and ports), empty and dot
 * segments, percent escapes, backslashes, IAM wildcards and policy variables, and any character
 * outside the S3 safe set. So the bucket and key parsed here are the ones the store will address,
 * and prefix checks can compare whole segments.
 *
 * @param bucket   S3 bucket name
 * @param segments the key split on {@code /}; empty for the whole bucket
 */
public record ObjectLocation(String bucket, List<String> segments) {

    private static final String SCHEME = "s3://";
    /** S3 bucket naming: 3 to 63 characters, lower case, digits, dots and hyphens, alphanumeric at both ends. */
    private static final Pattern BUCKET = Pattern.compile("[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]");
    /** The S3 safe characters for keys, less {@code *}, which IAM policies read as a wildcard. */
    private static final Pattern SEGMENT = Pattern.compile("[A-Za-z0-9!_.'()-]*");

    public ObjectLocation {
        segments = List.copyOf(segments);
        String text = render(bucket, segments);
        if (!BUCKET.matcher(bucket).matches() || bucket.contains("..")) {
            throw new LocationRejectedException(text, "\"" + bucket + "\" is not a valid S3 bucket name");
        }
        for (String segment : segments) {
            if (segment.isEmpty()) {
                throw new LocationRejectedException(text, "empty path segment");
            }
            if (segment.equals(".") || segment.equals("..")) {
                throw new LocationRejectedException(text, "dot segment \"" + segment + "\"");
            }
            if (!SEGMENT.matcher(segment).matches()) {
                throw new LocationRejectedException(text,
                        "segment \"" + segment + "\" has a character outside [A-Za-z0-9!_.'()-]");
            }
        }
    }

    /** Parses a location; throws {@link LocationRejectedException} unless it is canonical. */
    public static ObjectLocation parse(String location) {
        if (!location.startsWith(SCHEME)) {
            throw new LocationRejectedException(location, "scheme must be s3://");
        }
        String path = location.substring(SCHEME.length());
        int slash = path.indexOf('/');
        if (slash < 0) {
            return new ObjectLocation(path, List.of());
        }
        return new ObjectLocation(path.substring(0, slash), List.of(path.substring(slash + 1).split("/", -1)));
    }

    /** The object key: the segments joined by {@code /}. */
    public String key() {
        return String.join("/", segments);
    }

    /** The location one segment further down. */
    public ObjectLocation child(String segment) {
        List<String> longer = new ArrayList<>(segments);
        longer.add(segment);
        return new ObjectLocation(bucket, longer);
    }

    /**
     * True when this location lies strictly beneath {@code prefix}: same bucket, and the prefix's
     * segments are a proper prefix of this one's. {@code s3://b/abc} is not beneath {@code s3://b/ab}.
     */
    public boolean isBeneath(ObjectLocation prefix) {
        return bucket.equals(prefix.bucket)
                && segments.size() > prefix.segments.size()
                && segments.subList(0, prefix.segments.size()).equals(prefix.segments);
    }

    @Override
    public String toString() {
        return render(bucket, segments);
    }

    private static String render(String bucket, List<String> segments) {
        return segments.isEmpty() ? SCHEME + bucket : SCHEME + bucket + "/" + String.join("/", segments);
    }
}
