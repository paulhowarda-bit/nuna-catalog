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
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
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
    private final Map<String, RuntimeException> unreadable = new HashMap<>();
    private RuntimeException failure;
    private long fetched;
    private int open;
    private int mostOpen;

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
                        case "putObject" -> put(request(PutObjectRequest.builder(), args[0]), (RequestBody) args[1]);
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

    private PutObjectResponse put(PutObjectRequest request, RequestBody body) {
        if ("*".equals(request.ifNoneMatch()) && versions.stream().anyMatch(v -> v.key().equals(request.key()))) {
            throw (S3Exception) S3Exception.builder().statusCode(412).message("At least one of the pre-conditions you"
                    + " specified did not hold").build();
        }
        byte[] bytes;
        try (InputStream in = body.contentStreamProvider().newStream()) {
            bytes = in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        String id = "v" + (versions.size() + 1);
        versions.add(new Version(request.key(), id, bytes, Map.copyOf(request.metadata()),
                Instant.EPOCH.plusSeconds(versions.size())));
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
        return find(request.key(), request.versionId());
    }
}
