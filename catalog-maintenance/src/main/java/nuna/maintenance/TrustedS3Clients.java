package nuna.maintenance;

import nuna.catalog.objectstore.CaBundle;
import java.net.URI;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.iceberg.aws.AwsClientProperties;
import org.apache.iceberg.aws.HttpClientProperties;
import org.apache.iceberg.aws.s3.S3FileIOAwsClientFactory;
import org.apache.iceberg.aws.s3.S3FileIOProperties;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * The S3 clients Iceberg opens for the worker, trusting its stores' CA bundles beside the JVM's CAs (backlog
 * 21): its own ({@link Storage}) and those reading table files with the credentials the catalog vends it
 * ({@link Catalogs}). Iceberg builds the factory by name ({@code s3.client-factory-impl}), so the bundles are found
 * through the client property {@value #TRUST}, which {@link #register} hands out. A client trusts the bundle of the
 * endpoint it is opened to ({@code s3.endpoint}) and no other. Without a bundle the worker sets neither property, and
 * Iceberg opens its clients as it always has.
 */
public final class TrustedS3Clients implements S3FileIOAwsClientFactory {

    /** The client property naming the registered bundles. */
    static final String TRUST = "nuna.storage-trust";

    private static final Map<String, Map<String, CaBundle>> REGISTERED = new ConcurrentHashMap<>();

    private S3FileIOProperties s3;
    private HttpClientProperties http;
    private AwsClientProperties aws;
    private String trust;

    /**
     * Reads each store's CA bundle and makes them known, by endpoint, to the factories Iceberg builds, for the JVM's
     * life; returns the client properties that select them, or none when no store names a bundle.
     *
     * @throws IllegalArgumentException naming the store and the file, when a bundle cannot be read, holds no
     *                                  certificate or one that does not parse, or two stores on one endpoint name
     *                                  different bundles
     */
    static Map<String, String> register(Map<String, WorkerSettings.Store> stores) {
        Map<String, Path> files = new LinkedHashMap<>();
        Map<String, CaBundle> bundles = new LinkedHashMap<>();
        stores.forEach((name, store) -> {
            if (store.caBundle() == null) {
                return;
            }
            String setting = "nuna.maintenance.storage." + name + ".ca-bundle: ";
            String endpoint = endpoint(store.endpoint().toString());
            Path other = files.putIfAbsent(endpoint, store.caBundle());
            if (other != null && !other.equals(store.caBundle())) {
                throw new IllegalArgumentException(setting + "another store names " + other + " for the endpoint "
                        + endpoint);
            }
            try {
                bundles.put(endpoint, CaBundle.read(store.caBundle()));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(setting + e.getMessage(), e);
            }
        });
        if (bundles.isEmpty()) {
            return Map.of();
        }
        String id = UUID.randomUUID().toString();
        REGISTERED.put(id, Map.copyOf(bundles));
        return Map.of(S3FileIOProperties.CLIENT_FACTORY, TrustedS3Clients.class.getName(), TRUST, id);
    }

    @Override
    public void initialize(Map<String, String> properties) {
        s3 = new S3FileIOProperties(properties);
        http = new HttpClientProperties(properties);
        aws = new AwsClientProperties(properties);
        trust = properties.get(TRUST);
    }

    /**
     * The client Iceberg 1.12 opens ({@code DefaultS3FileIOAwsClientFactory.s3()}), step for step, except that a
     * client to an endpoint with a bundle gets an HTTP client trusting it in place of Iceberg's. Recheck the steps when
     * Iceberg is upgraded.
     */
    @Override
    public S3Client s3() {
        CaBundle bundle = s3.endpoint() == null ? null : registered().get(endpoint(s3.endpoint()));
        return S3Client.builder()
                .applyMutation(aws::applyClientRegionConfiguration)
                .applyMutation(aws::applyLegacyMd5Plugin)
                .applyMutation(builder -> {
                    if (bundle == null) {
                        http.applyHttpClientConfigurations(builder);
                    } else {
                        bundle.applyTo(builder);
                    }
                })
                .applyMutation(s3::applyEndpointConfigurations)
                .applyMutation(s3::applyServiceConfigurations)
                .applyMutation(builder -> s3.applyCredentialConfigurations(aws, builder))
                .applyMutation(s3::applySignerConfiguration)
                .applyMutation(s3::applyS3AccessGrantsConfigurations)
                .applyMutation(s3::applyUserAgentConfigurations)
                .applyMutation(s3::applyRetryConfigurations)
                .build();
    }

    /** Iceberg asks for one only with its analytics accelerator or CRT client, which the worker turns on nowhere. */
    @Override
    public S3AsyncClient s3Async() {
        throw new UnsupportedOperationException("the worker opens no asynchronous S3 client");
    }

    private Map<String, CaBundle> registered() {
        Map<String, CaBundle> registered = REGISTERED.get(trust);
        if (registered == null) {
            throw new IllegalStateException("no storage trust " + trust + " is registered");
        }
        return registered;
    }

    /** An endpoint as bundles are kept by: scheme, host and port, however it is written. */
    static String endpoint(String uri) {
        URI parsed = URI.create(uri);
        String scheme = parsed.getScheme().toLowerCase(Locale.ROOT);
        int port = parsed.getPort() != -1 ? parsed.getPort() : "https".equals(scheme) ? 443 : 80;
        return scheme + "://" + parsed.getHost().toLowerCase(Locale.ROOT) + ":" + port;
    }
}
