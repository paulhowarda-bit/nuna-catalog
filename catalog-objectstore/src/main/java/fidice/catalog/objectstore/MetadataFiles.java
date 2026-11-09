package fidice.catalog.objectstore;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ChecksumAlgorithm;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

/**
 * Writes and reads one storage profile's metadata.json files (plan 2.2 steps 4 and 6). Every
 * location passes the profile's guardrails before a request is sent.
 */
public final class MetadataFiles implements AutoCloseable {

    private static final HexFormat HEX = HexFormat.of();

    private final StorageProfile profile;
    private final S3Client s3;

    /** Opens an S3 client on the profile's endpoint, region and addressing style. */
    public MetadataFiles(StorageProfile profile, AwsCredentialsProvider credentials) {
        this.profile = profile;
        this.s3 = S3Client.builder()
                .endpointOverride(profile.endpoint())
                .region(Region.of(profile.region()))
                .forcePathStyle(profile.kind().pathStyle())
                .credentialsProvider(credentials)
                .build();
    }

    /**
     * Writes a new metadata file under {@code tableLocation} at a location the catalog assigns,
     * {@code <table>/metadata/<version>-<uuid>.metadata.json}, so no two writes share a file. The
     * table location must be the one the catalog assigned and recorded, never one taken from the
     * metadata or its properties. The store checks the bytes it receives against their SHA-256.
     *
     * @param version the pointer version this file will become
     */
    public StoredMetadata write(String tableLocation, long version, String json) {
        ObjectLocation file = profile.approve(tableLocation).child("metadata").child(fileName(version));
        byte[] bytes = json.getBytes(UTF_8);
        byte[] sha256 = sha256(bytes);
        put(file, bytes, sha256);
        return new StoredMetadata(file.toString(), HEX.formatHex(sha256));
    }

    /**
     * Reads a metadata file and hashes what was read. If the hash differs from
     * {@code expectedSha256}, {@code onMismatch} decides between failing closed and returning the
     * file flagged.
     *
     * @throws MetadataIntegrityException on a mismatch under {@link OnMismatch#FAIL}
     * @throws MetadataNotFoundException  when no file is at {@code location}
     */
    public LoadedMetadata read(String location, String expectedSha256, OnMismatch onMismatch) {
        ObjectLocation file = profile.approve(location);
        byte[] bytes;
        try {
            bytes = s3.getObjectAsBytes(request -> request.bucket(file.bucket()).key(file.key())).asByteArray();
        } catch (NoSuchKeyException e) {
            throw new MetadataNotFoundException(location, e);
        } catch (SdkException e) {
            throw new ObjectStoreException("reading " + file + " failed", e);
        }
        String actual = HEX.formatHex(sha256(bytes));
        boolean intact = actual.equals(expectedSha256);
        if (!intact && onMismatch == OnMismatch.FAIL) {
            throw new MetadataIntegrityException(location, expectedSha256, actual);
        }
        return new LoadedMetadata(location, new String(bytes, UTF_8), actual, intact);
    }

    @Override
    public void close() {
        s3.close();
    }

    /**
     * Sends the SHA-256 with the bytes, so the store refuses any that arrive changed. Ceph RGW
     * checks the hash only when the request also names the algorithm.
     */
    void put(ObjectLocation file, byte[] bytes, byte[] sha256) {
        try {
            s3.putObject(request -> request.bucket(file.bucket()).key(file.key())
                    .contentType("application/json")
                    .checksumAlgorithm(ChecksumAlgorithm.SHA256)
                    .checksumSHA256(Base64.getEncoder().encodeToString(sha256)),
                    RequestBody.fromBytes(bytes));
        } catch (SdkException e) {
            throw new ObjectStoreException("writing " + file + " failed", e);
        }
    }

    /** Iceberg's file naming, so engines and tools recognise the file: {@code 00042-<uuid>.metadata.json}. */
    static String fileName(long version) {
        return String.format(Locale.ROOT, "%05d-%s.metadata.json", version, UUID.randomUUID());
    }

    static byte[] sha256(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("every JVM provides SHA-256", e);
        }
    }
}
