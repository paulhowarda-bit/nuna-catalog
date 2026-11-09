package fidice.catalog.server.storage;

import fidice.catalog.objectstore.StoreKind;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The deployment's storage profiles and the catalogs bound to them ({@code fidice.storage.*}, D21, D28).
 * Each catalog has exactly one profile, KMS key set and legal entity; that binding never changes once
 * the catalog exists. Profiles' allowed prefixes may not overlap.
 *
 * @param profiles               storage profiles by name
 * @param catalogs               catalogs by name, created at startup if they do not exist
 * @param credentialLifetime     how long vended credentials live, 15 to 60 minutes (D21, P10)
 * @param credentialCacheEntries how many callers' credentials are cached at once
 * @param requireTls             refuse a profile whose endpoint is plain http; only local tests turn it off
 */
@ConfigurationProperties("fidice.storage")
public record StorageSettings(
        @DefaultValue Map<String, Profile> profiles,
        @DefaultValue Map<String, Catalog> catalogs,
        @DefaultValue("30m") Duration credentialLifetime,
        @DefaultValue("10000") int credentialCacheEntries,
        @DefaultValue("true") boolean requireTls) {

    /**
     * One store and the prefixes in it that its catalogs may use.
     *
     * @param endpoint        the store's S3 endpoint; for AWS S3 the regional endpoint
     * @param tableRoot       where new table locations are assigned, within an allowed prefix
     * @param stsRole         the role STS assumes to vend credentials; MinIO needs none
     * @param accessKeyId     with {@code secretAccessKey}, the catalog's own static credentials; without,
     *                        the AWS SDK's default provider chain
     */
    public record Profile(
            StoreKind kind,
            String endpoint,
            @DefaultValue("us-east-1") String region,
            String tableRoot,
            List<String> allowedPrefixes,
            String stsRole,
            String accessKeyId,
            String secretAccessKey) {
    }

    /** A catalog's boundary: its storage profile, the KMS key set its data is encrypted under, its legal entity. */
    public record Catalog(String profile, String kmsKeySet, String legalEntity) {
    }
}
