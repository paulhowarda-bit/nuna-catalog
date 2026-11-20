package nuna.catalog.objectstore;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.model.ChecksumAlgorithm;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * Writes and reads one storage profile's metadata.json files (plan 2.2 steps 4 and 6). Every
 * location passes the profile's guardrails before a request is sent.
 */
public final class MetadataFiles implements AutoCloseable {

    private static final HexFormat HEX = HexFormat.of();

    private final StorageProfile profile;
    private final S3Client s3;

    /**
     * Opens an S3 client on the profile's endpoint, region and addressing style, trusting the profile's CA bundle, if
     * it names one, beside the JVM's CAs.
     */
    public MetadataFiles(StorageProfile profile, AwsCredentialsProvider credentials, Optional<CaBundle> caBundle) {
        this.profile = profile;
        S3ClientBuilder builder = S3Client.builder()
                .endpointOverride(profile.endpoint())
                .region(Region.of(profile.region()))
                .forcePathStyle(profile.kind().pathStyle())
                .credentialsProvider(credentials);
        caBundle.ifPresent(bundle -> bundle.applyTo(builder));
        this.s3 = builder.build();
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

    /**
     * Whether the store holds an object at {@code location}, one {@code HEAD}: any file of a table, data included, for
     * the DR reconcile's check of what a step adds (D37, amended 2026-11-20).
     *
     * @throws LocationRejectedException when the location is not the profile's to read
     */
    public boolean exists(String location) {
        ObjectLocation file = profile.approve(location);
        try {
            s3.headObject(request -> request.bucket(file.bucket()).key(file.key()));
            return true;
        } catch (NoSuchKeyException e) {
            return false;
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                return false;
            }
            throw new ObjectStoreException("asking for " + file + " failed", e);
        } catch (SdkException e) {
            throw new ObjectStoreException("asking for " + file + " failed", e);
        }
    }

    /**
     * Any file of a table, read whole, unhashed: the DR reconcile reads manifest lists and manifests with it.
     *
     * @throws MetadataNotFoundException when no file is at {@code location}
     */
    public byte[] bytes(String location) {
        ObjectLocation file = profile.approve(location);
        try {
            return s3.getObjectAsBytes(request -> request.bucket(file.bucket()).key(file.key())).asByteArray();
        } catch (NoSuchKeyException e) {
            throw new MetadataNotFoundException(location, e);
        } catch (SdkException e) {
            throw new ObjectStoreException("reading " + file + " failed", e);
        }
    }

    /**
     * The metadata files directly in a table's metadata directory, as {@link #write} names them, in no
     * particular order.
     */
    public List<String> list(String tableLocation) {
        ObjectLocation directory = profile.approve(tableLocation).child("metadata");
        List<String> files = new ArrayList<>();
        listDirectory(directory, response -> response.contents().stream()
                .map(object -> object.key().substring(object.key().lastIndexOf('/') + 1))
                .filter(name -> name.endsWith(".metadata.json"))
                .forEach(name -> files.add(directory.child(name).toString())));
        return files;
    }

    /** Every location directly beneath the profile's table root: one per table or view it gave out. */
    public List<String> tableLocations() {
        ObjectLocation root = profile.tableRoot();
        List<String> tables = new ArrayList<>();
        listDirectory(root, response -> response.commonPrefixes().forEach(prefix -> {
            String key = prefix.prefix().substring(0, prefix.prefix().length() - 1);
            tables.add(root.child(key.substring(key.lastIndexOf('/') + 1)).toString());
        }));
        return tables;
    }

    private void listDirectory(ObjectLocation directory, Consumer<ListObjectsV2Response> page) {
        try {
            s3.listObjectsV2Paginator(request -> request.bucket(directory.bucket()).prefix(directory.key() + "/")
                    .delimiter("/")).forEach(page);
        } catch (SdkException e) {
            throw new ObjectStoreException("listing " + directory + " failed", e);
        }
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
