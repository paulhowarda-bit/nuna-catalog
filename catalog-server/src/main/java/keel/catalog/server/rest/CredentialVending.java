package keel.catalog.server.rest;

import keel.catalog.core.principal.Caller;
import keel.catalog.iceberg.RestResponse.StorageCredential;
import keel.catalog.store.CatalogRecord;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Storage access handed out with a table load (D21): scoped STS credentials, or none. */
@FunctionalInterface
public interface CredentialVending {

    CredentialVending NONE = request -> Vended.NONE;

    /**
     * @throws keel.catalog.core.CatalogError 503 when credentials cannot be issued, or their issue
     *                                          cannot be audited (D23)
     */
    Vended vend(Request request);

    /**
     * @param path        the table's namespace levels, then its name, as the request gives them
     * @param location    the table's location, as the catalog assigned or registered it
     * @param requestId   the request's id, carried into the STS session name and tags
     * @param delegation  the client's {@code X-Iceberg-Access-Delegation} header, if any
     * @param refreshPath the table's credentials route, relative to the catalog's base URI
     * @param write       whether the caller may write the table's data: a commit of data would be allowed, or a
     *                    staged create's caller may create the table. Otherwise the credentials only read (D21).
     */
    record Request(CatalogRecord catalog, UUID tableId, List<String> path, String location, Caller caller,
                   String requestId, Optional<String> delegation, String refreshPath, boolean write) {

        public Request {
            Objects.requireNonNull(catalog, "catalog");
            Objects.requireNonNull(tableId, "tableId");
            path = List.copyOf(path);
            Objects.requireNonNull(location, "location");
            Objects.requireNonNull(caller, "caller");
            Objects.requireNonNull(requestId, "requestId");
            Objects.requireNonNull(delegation, "delegation");
            Objects.requireNonNull(refreshPath, "refreshPath");
        }
    }

    /**
     * @param config      FileIO properties for the load response's {@code config}
     * @param epoch       changes whenever the credentials would; part of the ETag (D14)
     * @param expiresAt   when the credentials expire, if they do
     * @param cached      whether the credentials came from the credential cache, so none were issued for this vend
     */
    record Vended(Map<String, String> config, List<StorageCredential> credentials, String epoch,
                  Optional<Instant> expiresAt, boolean cached) {

        public static final Vended NONE = new Vended(Map.of(), List.of(), "none", Optional.empty(), false);

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
