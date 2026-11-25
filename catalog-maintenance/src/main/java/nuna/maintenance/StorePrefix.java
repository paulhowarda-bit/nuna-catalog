package nuna.maintenance;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.apache.iceberg.aws.s3.S3FileIO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.s3.model.DeleteObjectsResponse;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.S3Error;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * One prefix the catalog gives the worker's own storage identity on a store at a scan ({@link OwnStorage}, item 256),
 * and what the store let it do beneath it (backlog 250): the listing of at most one key each scan starts with, and the deletes since then, done, refused, or
 * failed without the store's answer, with the code of the first not done. The deletes go to the store as S3FileIO
 * sends them, DeleteObjects on the identity's client a batch at a time, but here each key the store refuses comes back
 * with its code (AccessDenied), which S3FileIO counts and drops. Nothing here keeps an object key or a store's message.
 */
final class StorePrefix {

    private static final Logger LOG = LoggerFactory.getLogger(StorePrefix.class);
    /** Keys in one DeleteObjects call: S3FileIO's default ({@code s3.delete.batch-size}). */
    static final int DELETE_BATCH = 250;
    /** A store's error code as the catalog keeps it. */
    private static final Pattern CODE = Pattern.compile("[A-Za-z0-9._-]{1,100}");

    /** The prefix, normalized ({@link Storage#normalize}). */
    final String location;
    /** The store's kind as the settings name it, {@code s3}, {@code minio} or {@code ceph}. */
    final String kind;
    final Optional<URI> endpoint;
    final S3FileIO io;
    /** Set by the first {@link #check}, which comes before any {@link #reach}. */
    private Storage.Listed listed;
    private Optional<String> listCode = Optional.empty();
    private long deleted;
    private long refused;
    private long failed;
    private Optional<String> deleteCode = Optional.empty();

    StorePrefix(String location, String kind, Optional<URI> endpoint, S3FileIO io) {
        this.location = location;
        this.kind = kind;
        this.endpoint = endpoint;
        this.io = io;
    }

    /**
     * Lists at most one key beneath the prefix, as the worker's policy lets it list there, and counts its deletes
     * afresh from now. A refusal or failure is logged and reported, never thrown.
     */
    Storage.Reach check() {
        String[] parts = split(location);
        String key = parts[1].isEmpty() || parts[1].endsWith("/") ? parts[1] : parts[1] + "/";
        Storage.Listed result;
        Optional<String> code;
        try {
            io.client().listObjectsV2(request -> request.bucket(parts[0]).prefix(key).maxKeys(1));
            result = Storage.Listed.OK;
            code = Optional.empty();
        } catch (S3Exception e) {
            result = Storage.Listed.REFUSED;
            code = Optional.of(code(e));
            LOG.warn("The store refused the worker's listing of {}: {}", location, code.get());
        } catch (RuntimeException e) {
            result = Storage.Listed.FAILED;
            code = Optional.of(e.getClass().getSimpleName());
            LOG.warn("The worker could not list {}", location, e);
        }
        synchronized (this) {
            listed = result;
            listCode = code;
            deleted = 0;
            refused = 0;
            failed = 0;
            deleteCode = Optional.empty();
            return reach();
        }
    }

    /** What the store let the worker do beneath the prefix since the last {@link #check}. */
    synchronized Storage.Reach reach() {
        return new Storage.Reach(kind, endpoint, location, listed, listCode, deleted, refused, failed, deleteCode);
    }

    /**
     * Deletes these objects beneath the prefix, counting those the store deleted, refused and did not answer for.
     *
     * @throws IllegalStateException naming the prefix and the first code, when any was not deleted
     */
    void delete(List<String> locations) {
        Map<String, List<String>> byBucket = new LinkedHashMap<>();
        for (String object : locations) {
            String[] parts = split(object);
            byBucket.computeIfAbsent(parts[0], bucket -> new ArrayList<>()).add(key(parts[1]));
        }
        long done = 0;
        long notDone = 0;
        Optional<String> first = Optional.empty();
        for (Map.Entry<String, List<String>> bucket : byBucket.entrySet()) {
            List<String> keys = bucket.getValue();
            for (int from = 0; from < keys.size(); from += DELETE_BATCH) {
                Batch batch = delete(bucket.getKey(), keys.subList(from, Math.min(keys.size(), from + DELETE_BATCH)));
                done += batch.deleted();
                notDone += batch.refused() + batch.failed();
                first = first.or(batch::code);
                synchronized (this) {
                    deleted += batch.deleted();
                    refused += batch.refused();
                    failed += batch.failed();
                    deleteCode = deleteCode.or(batch::code);
                }
            }
        }
        if (notDone > 0) {
            throw new IllegalStateException("The store deleted " + done + " of " + (done + notDone) + " objects"
                    + " beneath " + location + ": " + first.orElseThrow());
        }
    }

    /** One DeleteObjects call's outcome: the code of its first key not deleted, when one was not. */
    private record Batch(long deleted, long refused, long failed, Optional<String> code) {
    }

    private Batch delete(String bucket, List<String> keys) {
        List<ObjectIdentifier> objects = keys.stream().map(key -> ObjectIdentifier.builder().key(key).build())
                .toList();
        try {
            DeleteObjectsResponse response = io.client().deleteObjects(request -> request.bucket(bucket)
                    .delete(delete -> delete.objects(objects)));
            List<S3Error> errors = response.hasErrors() ? response.errors() : List.of();
            return new Batch(keys.size() - errors.size(), errors.size(), 0,
                    errors.stream().findFirst().map(error -> code(error.code(), "Unknown")));
        } catch (S3Exception e) {
            return new Batch(0, keys.size(), 0, Optional.of(code(e)));
        } catch (RuntimeException e) {
            LOG.warn("The worker could not reach the store to delete {} objects beneath {}", keys.size(), location, e);
            return new Batch(0, 0, keys.size(), Optional.of(e.getClass().getSimpleName()));
        }
    }

    /** The store's code for a refusal; its HTTP status when it gave none. */
    private static String code(S3Exception e) {
        return code(e.awsErrorDetails() == null ? null : e.awsErrorDetails().errorCode(),
                Integer.toString(e.statusCode()));
    }

    private static String code(String code, String otherwise) {
        return code != null && CODE.matcher(code).matches() ? code : otherwise;
    }

    /** The bucket and the key of {@code location}, split as S3FileIO's S3URI splits it. */
    private static String[] split(String location) {
        int scheme = location.indexOf("://");
        String rest = scheme < 0 ? location : location.substring(scheme + 3);
        int slash = rest.indexOf('/');
        return slash < 0 ? new String[] {rest, ""} : new String[] {rest.substring(0, slash), rest.substring(slash + 1)};
    }

    /** An object's key as S3URI reads it: without a query or fragment. */
    private static String key(String path) {
        return path.split("\\?", -1)[0].split("#", -1)[0];
    }
}
