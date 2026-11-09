package fidice.catalog.server.rest;

import fidice.catalog.iceberg.RestResponse.StorageCredential;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Storage access handed out with a table load (D21). M6 vends scoped STS credentials; until then
 * nothing is vended and clients bring their own storage access.
 */
@FunctionalInterface
public interface CredentialVending {

    CredentialVending NONE = (catalogId, tableId, location, delegation) ->
            new Vended(Map.of(), List.of(), "none", Optional.empty());

    /**
     * @param location   the table's location, as the catalog assigned or registered it
     * @param delegation the client's {@code X-Iceberg-Access-Delegation} header, if any
     */
    Vended vend(UUID catalogId, UUID tableId, String location, Optional<String> delegation);

    /**
     * @param config      FileIO properties for the load response's {@code config}
     * @param epoch       changes whenever the credentials would; part of the ETag (D14)
     * @param expiresAt   when the credentials expire, if they do
     */
    record Vended(Map<String, String> config, List<StorageCredential> credentials, String epoch,
                  Optional<Instant> expiresAt) {

        public Vended {
            config = Map.copyOf(config);
            credentials = List.copyOf(credentials);
            Objects.requireNonNull(epoch, "epoch");
            Objects.requireNonNull(expiresAt, "expiresAt");
        }

        /** Whether the credentials expire before {@code instant}: a cached load would outlive them. */
        boolean expireBefore(Instant instant) {
            return expiresAt.map(at -> at.isBefore(instant)).orElse(false);
        }
    }
}
