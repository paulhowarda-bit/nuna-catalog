package fidice.catalog.server.rest;

import fidice.catalog.objectstore.StorageProfile;
import fidice.catalog.objectstore.StoreKind;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The object store the catalog writes metadata files to ({@code fidice.storage.*}). In M4 one profile
 * serves every catalog; M6 gives each catalog its own (D21, D28).
 *
 * @param accessKeyId     with {@code secretAccessKey}, static credentials; without, the AWS SDK's
 *                        default provider chain
 * @param allowedPrefixes every location the catalog reads or writes lies beneath one of these
 */
@ConfigurationProperties("fidice.storage")
public record StorageSettings(
        StoreKind kind,
        String endpoint,
        @DefaultValue("us-east-1") String region,
        String tableRoot,
        List<String> allowedPrefixes,
        String accessKeyId,
        String secretAccessKey) {

    StorageProfile profile() {
        require(kind, "kind");
        require(endpoint, "endpoint");
        require(tableRoot, "table-root");
        require(allowedPrefixes, "allowed-prefixes");
        return StorageProfile.of(kind, endpoint, region, tableRoot, allowedPrefixes);
    }

    private static void require(Object value, String name) {
        if (value == null || value instanceof List<?> list && list.isEmpty()) {
            throw new IllegalStateException("fidice.storage." + name + " must be set");
        }
    }
}
