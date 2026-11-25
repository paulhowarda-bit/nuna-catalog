package nuna.catalog.audit.archive;

import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.function.Consumer;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CommonPrefix;
import software.amazon.awssdk.services.s3.model.DeleteMarkerEntry;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectResponse;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectVersionsRequest;
import software.amazon.awssdk.services.s3.model.ListObjectVersionsResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.ObjectVersion;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.paginators.ListObjectVersionsIterable;
import software.amazon.awssdk.services.s3.paginators.ListObjectsV2Iterable;
import software.amazon.awssdk.utils.builder.SdkBuilder;

/**
 * One versioned bucket in memory, answering the calls {@link ObjectLockArchive} makes to write and read its chains,
 * and no others, so archive reads can be tested without a store. Every page is the last.
 */
final class MemoryS3 {

    /** One object version: its bytes and user metadata, in the order written. */
    record Version(String key, String id, byte[] bytes, Map<String, String> metadata, Instant at) {
    }

    private final List<Version> versions = new ArrayList<>();
    /** Delete markers, as versions with no bytes. */
    private final List<Version> markers = new ArrayList<>();
    private long writes;
    /** When the store takes each write; null keeps the epoch plus a second a write. */
    private final java.time.Clock clock;

    MemoryS3() {
        this(null);
    }

    /** A store that stamps each version with {@code clock}'s time, a nanosecond apart from the write before it. */
    MemoryS3(java.time.Clock clock) {
        this.clock = clock;
    }

    private Instant now() {
        return clock == null ? Instant.EPOCH.plusSeconds(writes) : clock.instant().plusNanos(writes);
    }
    private final Map<String, RuntimeException> unreadable = new HashMap<>();
    private RuntimeException failure;
    private long fetched;
    private int open;
    private int mostOpen;

    private int lostAnswers;

    /** The next PUT is stored, and its answer lost on the way back, as a connection that drops after the write. */
    void loseTheNextPutsAnswer() {
        lostAnswers++;
    }

    /** Every later call fails with {@code failure}, as a store that cannot be reached. */
    void failWith(RuntimeException failure) {
        this.failure = failure;
    }

    /** Every later GET of {@code key} fails with {@code failure}, as a store that cannot serve that one object. */
    void failGetOf(String key, RuntimeException failure) {
        unreadable.put(key, failure);
    }

    /** How many bytes of stored objects GETs have handed out, streamed or whole. */
    long fetched() {
        return fetched;
    }

    /** The most streamed GETs open at once. */
    int mostOpen() {
        return mostOpen;
    }

    /** Replaces the stored bytes of {@code key}'s versions, as a fault or a hand behind the archive's back would. */
    void overwrite(String key, byte[] bytes) {
        versions.replaceAll(v -> v.key().equals(key) ? new Version(v.key(), v.id(), bytes, v.metadata(), v.at()) : v);
    }

    /** The stored bytes of {@code key}'s first version, read behind the client's back: no GET, nothing fetched. */
    byte[] bytes(String key) {
        return versions.stream().filter(v -> v.key().equals(key)).findFirst().orElseThrow().bytes();
    }

    /** The keys written, in order. */
    List<String> keys() {
        return versions.stream().map(Version::key).toList();
    }

    S3Client client() {
        return (S3Client) Proxy.newProxyInstance(S3Client.class.getClassLoader(), new Class<?>[] {S3Client.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "close" -> {
                            return null;
                        }
                        case "toString" -> {
                            return "MemoryS3";
                        }
                        case "hashCode" -> {
                            return System.identityHashCode(proxy);
                        }
                        case "equals" -> {
                            return proxy == args[0];
                        }
                        default -> {
                        }
                    }
                    if (failure != null) {
                        throw failure;
                    }
                    return switch (method.getName()) {
                        case "putObject" -> {
                            PutObjectResponse put = put(request(PutObjectRequest.builder(), args[0]),
                                    (RequestBody) args[1]);
                            if (lostAnswers > 0) {
                                lostAnswers--;
                                throw software.amazon.awssdk.core.exception.SdkClientException.create(
                                        "the connection dropped before the answer");
                            }
                            yield put;
                        }
                        case "deleteObject" -> delete(request(DeleteObjectRequest.builder(), args[0]));
                        case "listObjectsV2Paginator" ->
                                new ListObjectsV2Iterable((S3Client) proxy, request(ListObjectsV2Request.builder(), args[0]));
                        case "listObjectsV2" -> list((ListObjectsV2Request) args[0]);
                        case "listObjectVersionsPaginator" -> new ListObjectVersionsIterable((S3Client) proxy,
                                request(ListObjectVersionsRequest.builder(), args[0]));
                        case "listObjectVersions" -> versions((ListObjectVersionsRequest) args[0]);
                        case "headObject" -> head(request(HeadObjectRequest.builder(), args[0]));
                        case "getObjectAsBytes" -> get(request(GetObjectRequest.builder(), args[0]));
                        case "getObject" -> {
                            if (args.length != 1) {
                                throw new UnsupportedOperationException("the archive streams a GET, it does not save one");
                            }
                            yield stream(request(GetObjectRequest.builder(), args[0]));
                        }
                        default -> throw new UnsupportedOperationException("the archive does not call " + method.getName());
                    };
                });
    }

    /** The request a call carries, or builds through the consumer it was given. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static <R> R request(SdkBuilder builder, Object argument) {
        if (argument instanceof Consumer consumer) {
            consumer.accept(builder);
            return (R) builder.build();
        }
        return (R) argument;
    }

    /** The key's current version, or none when it has none or a delete marker is newest. */
    private Optional<Version> current(String key) {
        Optional<Version> newest = versions.stream().filter(v -> v.key().equals(key))
                .max(java.util.Comparator.comparing(Version::at));
        Optional<Version> marker = markers.stream().filter(v -> v.key().equals(key))
                .max(java.util.Comparator.comparing(Version::at));
        return newest.filter(v -> marker.isEmpty() || v.at().isAfter(marker.get().at()));
    }

    /** A delete marker for a delete with no version, as on a versioned bucket; a version's own delete is refused. */
    private DeleteObjectResponse delete(DeleteObjectRequest request) {
        if (request.versionId() != null) {
            throw (S3Exception) S3Exception.builder().statusCode(403).message("Access Denied: the version is locked")
                    .build();
        }
        String id = "v" + (++writes);
        markers.add(new Version(request.key(), id, new byte[0], Map.of(), now()));
        return DeleteObjectResponse.builder().deleteMarker(true).versionId(id).build();
    }

    private PutObjectResponse put(PutObjectRequest request, RequestBody body) {
        if ("*".equals(request.ifNoneMatch()) && current(request.key()).isPresent()) {
            throw (S3Exception) S3Exception.builder().statusCode(412).message("At least one of the pre-conditions you"
                    + " specified did not hold").build();
        }
        byte[] bytes;
        try (InputStream in = body.contentStreamProvider().newStream()) {
            bytes = in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        String id = "v" + (++writes);
        versions.add(new Version(request.key(), id, bytes, Map.copyOf(request.metadata()),
                now()));
        return PutObjectResponse.builder().versionId(id).build();
    }

    private ListObjectsV2Response list(ListObjectsV2Request request) {
        TreeSet<String> prefixes = new TreeSet<>();
        for (Version version : versions) {
            if (version.key().startsWith(request.prefix())) {
                int slash = version.key().indexOf(request.delimiter(), request.prefix().length());
                if (slash >= 0) {
                    prefixes.add(version.key().substring(0, slash + 1));
                }
            }
        }
        return ListObjectsV2Response.builder().isTruncated(false)
                .commonPrefixes(prefixes.stream().map(p -> CommonPrefix.builder().prefix(p).build()).toList()).build();
    }

    private ListObjectVersionsResponse versions(ListObjectVersionsRequest request) {
        return ListObjectVersionsResponse.builder().isTruncated(false)
                // As S3 lists them: by key, only keys after the key marker when one is given.
                .versions(versions.stream().filter(v -> v.key().startsWith(request.prefix()))
                        .filter(v -> request.keyMarker() == null || v.key().compareTo(request.keyMarker()) > 0)
                        .sorted(java.util.Comparator.comparing(Version::key))
                        .map(v -> ObjectVersion.builder().key(v.key()).versionId(v.id()).lastModified(v.at()).build())
                        .toList())
                .deleteMarkers(markers.stream().filter(v -> v.key().startsWith(request.prefix()))
                        .filter(v -> request.keyMarker() == null || v.key().compareTo(request.keyMarker()) > 0)
                        .sorted(java.util.Comparator.comparing(Version::key))
                        .map(v -> DeleteMarkerEntry.builder().key(v.key()).versionId(v.id()).lastModified(v.at())
                                .build())
                        .toList())
                .build();
    }

    private Version find(String key, String versionId) {
        Optional<Version> found = versions.stream()
                .filter(v -> v.key().equals(key) && v.id().equals(versionId)).findFirst();
        return found.orElseThrow(() -> (S3Exception) S3Exception.builder().statusCode(404)
                .message("no version " + versionId + " of " + key).build());
    }

    private HeadObjectResponse head(HeadObjectRequest request) {
        return HeadObjectResponse.builder().metadata(find(request.key(), request.versionId()).metadata()).build();
    }

    private ResponseBytes<GetObjectResponse> get(GetObjectRequest request) {
        byte[] bytes = readable(request).bytes();
        fetched += bytes.length;
        return ResponseBytes.fromByteArray(GetObjectResponse.builder().build(), bytes);
    }

    /** The object's bytes as the SDK streams them, counting each byte as the reader takes it. */
    private ResponseInputStream<GetObjectResponse> stream(GetObjectRequest request) {
        InputStream bytes = new ByteArrayInputStream(readable(request).bytes());
        open++;
        mostOpen = Math.max(mostOpen, open);
        InputStream counted = new FilterInputStream(bytes) {
            private boolean closed;

            @Override
            public int read() throws IOException {
                int b = super.read();
                fetched += b == -1 ? 0 : 1;
                return b;
            }

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                int n = super.read(b, off, len);
                fetched += Math.max(n, 0);
                return n;
            }

            @Override
            public void close() throws IOException {
                if (!closed) {
                    closed = true;
                    open--;
                }
                super.close();
            }
        };
        return new ResponseInputStream<>(GetObjectResponse.builder().build(), counted);
    }

    private Version readable(GetObjectRequest request) {
        RuntimeException refused = unreadable.get(request.key());
        if (refused != null) {
            throw refused;
        }
        if (request.versionId() == null) {
            return current(request.key()).orElseThrow(() -> NoSuchKeyException.builder().statusCode(404)
                    .message("no current version of " + request.key()).build());
        }
        return find(request.key(), request.versionId());
    }
}
