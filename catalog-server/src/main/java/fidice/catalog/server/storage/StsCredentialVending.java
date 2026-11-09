package fidice.catalog.server.storage;

import fidice.catalog.audit.Actor;
import fidice.catalog.audit.CredentialIssue;
import fidice.catalog.audit.Securable;
import fidice.catalog.core.CatalogError;
import fidice.catalog.core.principal.Caller;
import fidice.catalog.core.principal.Principal;
import fidice.catalog.iceberg.RestResponse.StorageCredential;
import fidice.catalog.objectstore.Access;
import fidice.catalog.objectstore.CredentialCache;
import fidice.catalog.objectstore.LocationRejectedException;
import fidice.catalog.objectstore.ObjectStoreException;
import fidice.catalog.objectstore.SessionIdentity;
import fidice.catalog.objectstore.StoreKind;
import fidice.catalog.objectstore.VendedCredentials;
import fidice.catalog.server.rest.CredentialVending;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Vends STS credentials scoped to one table's prefix (D21). Each caller's credentials are cached for most
 * of their lifetime; each new set is audited before it is first handed out, and if the audit fails it is
 * thrown away (D23). A trusted engine that asks for no delegation gets none.
 */
public final class StsCredentialVending implements CredentialVending {

    private static final Logger LOG = LoggerFactory.getLogger(StsCredentialVending.class);

    private final CatalogStorage storage;
    private final CredentialAudit audit;
    private final CredentialCache cache;
    private final Set<String> maintenancePrincipals;

    /**
     * @param refreshMargin no 304 when credentials expire within this (D14); cached credentials are renewed
     *                      a minute before that, so a load normally carries credentials a client may cache
     */
    public StsCredentialVending(CatalogStorage storage, CredentialAudit audit, StorageSettings settings,
                                Duration refreshMargin, Clock clock) {
        this.storage = storage;
        this.audit = audit;
        Duration quarter = settings.credentialLifetime().dividedBy(4);
        Duration margin = refreshMargin.plusMinutes(1);
        this.cache = new CredentialCache(quarter.compareTo(margin) > 0 ? quarter : margin,
                settings.credentialCacheEntries(), clock);
        this.maintenancePrincipals = Set.copyOf(settings.maintenancePrincipals());
    }

    @Override
    public Vended vend(Request request) {
        Caller caller = request.caller();
        if (caller.trustedEngine() && declined(request.delegation())) {
            return Vended.NONE;
        }
        CatalogStorage.Bound bound = storage.forCatalog(request.catalog().id());
        Access access = caller.endUser().isEmpty() && maintenancePrincipals.contains(caller.principal().id())
                ? Access.READ_WRITE_DELETE : Access.READ_WRITE;
        String subject = caller.endUser().map(Principal::id).orElse(caller.principal().id());
        CredentialCache.Key key = new CredentialCache.Key(bound.name(), request.location(), access,
                caller.principal().id(), subject, caller.engine());
        VendedCredentials credentials;
        try {
            credentials = cache.get(key, () -> mint(bound, request, access));
        } catch (LocationRejectedException e) {
            throw CatalogError.of(403, "ForbiddenException", "Table location " + e.location()
                    + " is outside storage profile " + bound.name());
        } catch (ObjectStoreException | AuditFailedException e) {
            LOG.warn("no credentials for table {}: {}", request.tableId(), e.getMessage(), e.getCause());
            throw CatalogError.of(503, "ServiceUnavailableException",
                    "Storage credentials cannot be issued now; retry later");
        }
        return vended(bound, credentials, request.refreshPath());
    }

    private VendedCredentials mint(CatalogStorage.Bound bound, Request request, Access access) {
        Caller caller = request.caller();
        SessionIdentity identity = new SessionIdentity(caller.principal().id(),
                caller.endUser().map(Principal::id), caller.engine(), request.requestId(), request.catalog().name(),
                request.tableId());
        VendedCredentials credentials = bound.vendor().vend(request.location(), access, identity);
        Actor actor = new Actor(caller.principal().id(), caller.endUser().map(Principal::id).orElse(null),
                caller.engine(), request.requestId());
        try {
            audit.issued(new CredentialIssue(new Securable(Securable.Kind.TABLE, request.tableId(),
                    request.catalog().name(), request.path()), bound.name(), credentials.location(),
                    CredentialIssue.Access.valueOf(access.name()), credentials.sessionName(),
                    credentials.expiresAt(), actor));
        } catch (RuntimeException e) {
            throw new AuditFailedException(e);
        }
        return credentials;
    }

    private static Vended vended(CatalogStorage.Bound bound, VendedCredentials credentials, String refreshPath) {
        Map<String, String> secrets = new LinkedHashMap<>();
        secrets.put("s3.access-key-id", credentials.accessKeyId());
        secrets.put("s3.secret-access-key", credentials.secretAccessKey());
        secrets.put("s3.session-token", credentials.sessionToken());
        secrets.put("s3.session-token-expires-at-ms", Long.toString(credentials.expiresAt().toEpochMilli()));
        Map<String, String> config = new LinkedHashMap<>(secrets);
        config.put("client.region", bound.profile().region());
        if (bound.profile().kind() != StoreKind.S3) {
            config.put("s3.endpoint", bound.profile().endpoint().toString());
        }
        config.put("s3.path-style-access", Boolean.toString(bound.profile().kind().pathStyle()));
        config.put("client.refresh-credentials-endpoint", refreshPath);
        config.put("client.refresh-credentials-enabled", "true");
        return new Vended(config, List.of(new StorageCredential(credentials.location(), secrets)),
                epoch(credentials.accessKeyId()), Optional.of(credentials.expiresAt()));
    }

    /** Only a header that lists nothing but {@code none} declines delegation. */
    private static boolean declined(Optional<String> delegation) {
        return delegation.map(header -> Arrays.stream(header.split(","))
                .map(mechanism -> mechanism.trim().toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet())
                .equals(Set.of("none"))).orElse(false);
    }

    /** Changes with the credentials, and reveals nothing of them. */
    private static String epoch(String accessKeyId) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(accessKeyId.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 8);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static final class AuditFailedException extends RuntimeException {
        AuditFailedException(RuntimeException cause) {
            super("the credential issue could not be audited: " + cause.getMessage(), cause);
        }
    }
}
