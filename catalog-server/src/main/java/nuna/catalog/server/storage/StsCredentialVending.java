package nuna.catalog.server.storage;

import nuna.catalog.audit.Actor;
import nuna.catalog.audit.CatalogEvent;
import nuna.catalog.audit.CredentialIssue;
import nuna.catalog.audit.Securable;
import nuna.catalog.core.CatalogError;
import nuna.catalog.core.principal.Caller;
import nuna.catalog.core.principal.Principal;
import nuna.catalog.iceberg.RestResponse.StorageCredential;
import nuna.catalog.objectstore.Access;
import nuna.catalog.objectstore.CredentialCache;
import nuna.catalog.objectstore.LocationRejectedException;
import nuna.catalog.objectstore.ObjectStoreException;
import nuna.catalog.objectstore.SessionIdentity;
import nuna.catalog.objectstore.StoreKind;
import nuna.catalog.objectstore.VendedCredentials;
import nuna.catalog.server.rest.AuditPublisher;
import nuna.catalog.server.rest.CredentialVending;
import nuna.catalog.server.tracing.Observed;
import io.micrometer.common.KeyValues;
import io.micrometer.observation.ObservationRegistry;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Vends STS credentials scoped to one table's prefix (D21): read-write to a caller who may write the table's data,
 * read-only to anyone else. None of them deletes, a maintenance principal's included: the maintenance worker deletes
 * with its own storage identity (D30). Each caller's credentials are cached until they come within an issuance window
 * and the refresh margin of expiring; each new set is audited before it is first handed out, and if the audit fails it is
 * thrown away (D23). Any caller that asks for no delegation gets none, trusted engine or not (D21, amended
 * 2026-11-19): no AssumeRole and no credential issue to audit. Each AssumeRole is observed as
 * {@value #ASSUME_ROLE} (docs/observability/spans.md); a cached set calls nothing, and its vend says it was cached.
 * A vend's credential epoch is the issuance window it falls in, so every server gives the same ETag (D14, item 15).
 */
public final class StsCredentialVending implements CredentialVending {

    public static final String ASSUME_ROLE = "nuna.sts";

    private static final Logger LOG = LoggerFactory.getLogger(StsCredentialVending.class);

    private final CatalogStorage storage;
    private final AuditPublisher audit;
    private final URI source;
    private final Clock clock;
    private final Duration window;
    private final Duration refreshMargin;
    private final CredentialCache cache;
    private final ObservationRegistry observations;

    /**
     * The issuance window is a quarter of the credential lifetime, so servers with the same lifetime share it.
     *
     * @param refreshMargin no 304 when credentials expire within this (D14). Cached credentials are renewed when they
     *                      have less than a window, this margin and a minute left, so every set handed out in a window
     *                      lasts through the window's end and the margin after it, with a minute to spare for clocks
     */
    public StsCredentialVending(CatalogStorage storage, AuditPublisher audit, URI source, StorageSettings settings,
                                Duration refreshMargin, Clock clock, ObservationRegistry observations) {
        this.storage = storage;
        this.audit = audit;
        this.observations = observations;
        this.source = source;
        this.clock = clock;
        this.window = settings.credentialLifetime().dividedBy(4);
        this.refreshMargin = refreshMargin;
        this.cache = new CredentialCache(window.plus(refreshMargin).plusMinutes(1),
                settings.credentialCacheEntries(), clock);
    }

    @Override
    public Vended vend(Request request) {
        if (declined(request.delegation())) {
            return Vended.NONE;
        }
        Caller caller = request.caller();
        CatalogStorage.Bound bound = storage.forCatalog(request.catalog().id());
        Access access = request.write() ? Access.READ_WRITE : Access.READ;
        String subject = caller.endUser().map(Principal::id).orElse(caller.principal().id());
        CredentialCache.Key key = new CredentialCache.Key(bound.name(), request.location(), access,
                caller.principal().id(), subject, caller.engine(), request.writeUnder());
        AtomicBoolean minted = new AtomicBoolean();
        VendedCredentials credentials;
        try {
            credentials = cache.get(key, () -> {
                minted.set(true);
                return mint(bound, request, access);
            });
        } catch (LocationRejectedException e) {
            throw CatalogError.of(403, "ForbiddenException", "Table location " + e.location()
                    + " is outside storage profile " + bound.name());
        } catch (ObjectStoreException | AuditFailedException e) {
            LOG.warn("no credentials for table {}: {}", request.tableId(), e.getMessage(), e.getCause());
            throw CatalogError.of(503, "ServiceUnavailableException",
                    "Storage credentials cannot be issued now; retry later");
        }
        return vended(bound, credentials, epoch(key, credentials), request.refreshPath(), !minted.get());
    }

    private VendedCredentials mint(CatalogStorage.Bound bound, Request request, Access access) {
        Caller caller = request.caller();
        SessionIdentity identity = new SessionIdentity(caller.principal().id(),
                caller.endUser().map(Principal::id), caller.engine(), request.requestId(), request.catalog().name(),
                request.tableId());
        VendedCredentials credentials = Observed.call(observations, ASSUME_ROLE, "sts assume-role",
                KeyValues.of("profile", bound.name(), "access", access.name().toLowerCase(Locale.ROOT).replace('_', '-')),
                () -> bound.vendor().vend(request.location(), access, identity, request.writeUnder()),
                issued -> "issued");
        Actor actor = new Actor(caller.principal().id(), caller.endUser().map(Principal::id).orElse(null),
                caller.engine(), request.requestId());
        try {
            audit.publish(new CatalogEvent(UUID.randomUUID(), source, clock.instant(), new CredentialIssue(new Securable(Securable.Kind.TABLE, request.tableId(),
                    request.catalog().name(), request.path()), bound.name(), credentials.location(),
                    CredentialIssue.Access.valueOf(access.name()), credentials.sessionName(),
                    credentials.expiresAt(), actor,
                    credentials.writeUnder().map(under -> credentials.location() + "/" + under).orElse(null))));
        } catch (RuntimeException e) {
            throw new AuditFailedException(e);
        }
        return credentials;
    }

    /**
     * The load response's FileIO config and storage credential. With the profile's KMS key, the config tells
     * Iceberg's S3FileIO to write with it ({@code s3.sse.type} {@code kms}, {@code s3.sse.key} the key ARN), as the
     * session policy demands (D26); an engine whose client reads neither must be set up to name the key itself.
     */
    static Vended vended(CatalogStorage.Bound bound, VendedCredentials credentials, String epoch,
                         String refreshPath, boolean cached) {
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
        bound.profile().kmsKey().ifPresent(key -> {
            config.put("s3.sse.type", "kms");
            config.put("s3.sse.key", key);
        });
        config.put("client.refresh-credentials-endpoint", refreshPath);
        config.put("client.refresh-credentials-enabled", "true");
        return new Vended(config, List.of(new StorageCredential(credentials.location(), secrets)),
                epoch, Optional.of(credentials.expiresAt()), cached);
    }

    /** Only a header that lists nothing but {@code none} declines delegation. */
    private static boolean declined(Optional<String> delegation) {
        return delegation.map(header -> Arrays.stream(header.split(","))
                .map(mechanism -> mechanism.trim().toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet())
                .equals(Set.of("none"))).orElse(false);
    }

    /**
     * The credential epoch in the ETag (D14, amended 2026-11-19, item 15): the issuance window this vend falls in,
     * {@code floor(now / window)} on this server's clock, with whose credentials they are, for what. Every server
     * vending one caller the same access to one table in one window gives the same epoch, so the same ETag for the same
     * table state, and a client that switches servers still gets its 304. Credentials that do not last through the
     * window's end and the refresh margin after it (a margin close to the lifetime, or STS's clock far behind this
     * one) get an epoch of their own instead, as before item 15: another server would otherwise answer 304 for them
     * until the window ends, inside their margin.
     */
    private String epoch(CredentialCache.Key key, VendedCredentials credentials) {
        long index = Math.floorDiv(clock.millis(), window.toMillis());
        Instant ends = Instant.ofEpochMilli(Math.multiplyExact(index + 1, window.toMillis()));
        if (credentials.expiresAt().isBefore(ends.plus(refreshMargin))) {
            return hash("credentials", credentials.accessKeyId());
        }
        return hash("window", Long.toString(index), key.profile(), key.location(), key.access().name(),
                key.principal(), key.subject(), key.engine());
    }

    /** Hashes the fields, each prefixed by its length so no two lists give the same input; reveals nothing of them. */
    private static String hash(String... fields) {
        StringBuilder canonical = new StringBuilder();
        for (String field : fields) {
            canonical.append(field.length()).append(':').append(field);
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
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
