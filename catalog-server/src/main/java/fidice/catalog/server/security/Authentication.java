package fidice.catalog.server.security;

import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.JWTParser;
import fidice.catalog.audit.AuthFailure;
import fidice.catalog.audit.CatalogEvent;
import fidice.catalog.core.principal.Caller;
import fidice.catalog.core.principal.Principal;
import fidice.catalog.iceberg.Wire;
import fidice.catalog.security.AuthenticationFailure;
import fidice.catalog.security.TokenAuthenticator;
import fidice.catalog.security.TrustedEngine;
import fidice.catalog.server.rest.AuditPublisher;
import fidice.catalog.server.rest.RestSettings;
import fidice.catalog.server.StoreOutage;
import fidice.catalog.store.GovernanceStore;
import fidice.catalog.store.StoreException;
import fidice.catalog.store.StoreUnavailableException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authenticates every request but {@code /v1/oauth/tokens} (plan 2.2 step 1; D16, D17, D17a). The bearer token
 * names the principal; a registered trusted engine may also name the end user it acts for, in two headers:
 * {@value #END_USER} (the end user's id, as the issuers' user-id claim carries it) and {@value #END_USER_GROUPS}
 * (comma-separated groups, each percent-encoded). A request that cannot be authenticated answers 401 (403 when
 * an unregistered client names an end user) and is audited as an {@link AuthFailure}; it never reaches a route.
 */
final class Authentication extends OncePerRequestFilter {

    static final String CALLER = Authentication.class.getName() + ".caller";
    static final String END_USER = "X-Fidice-End-User";
    static final String END_USER_GROUPS = "X-Fidice-End-User-Groups";

    private static final Logger LOG = LoggerFactory.getLogger(Authentication.class);
    private static final String REQUEST_ID = "X-Request-ID";

    private final TokenAuthenticator authenticator;
    private final GovernanceStore registry;
    private final Map<String, SecuritySettings.Issuer> issuers;
    private final AuditPublisher audit;
    private final RestSettings rest;
    private final Clock clock;

    Authentication(TokenAuthenticator authenticator, GovernanceStore registry, SecuritySettings settings,
                   AuditPublisher audit, RestSettings rest, Clock clock) {
        this.authenticator = authenticator;
        this.registry = registry;
        this.issuers = settings.issuers().stream()
                .collect(Collectors.toUnmodifiableMap(SecuritySettings.Issuer::issuer, issuer -> issuer));
        this.audit = audit;
        this.rest = rest;
        this.clock = clock;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        // The fixed OAuth error names the IdP for clients that have no token yet (D13).
        return path.equals("/v1/oauth/tokens");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7) || header.substring(7).isBlank()) {
            refuse(request, response, 401, "no bearer token", null, "Bearer");
            return;
        }
        String token = header.substring(7).trim();
        Optional<Principal> endUser;
        try {
            endUser = endUser(request);
        } catch (IllegalArgumentException e) {
            refuse(request, response, 401, e.getMessage(), token, "Bearer error=\"invalid_request\"");
            return;
        }
        Caller caller;
        try {
            caller = authenticator.authenticate(token, endUser, this::registered);
        } catch (AuthenticationFailure failure) {
            refuse(request, response, failure.status(), failure.getMessage(), token,
                    failure.status() == 401 ? "Bearer error=\"invalid_token\"" : null);
            return;
        } catch (StoreException e) {
            // The engine registry could not be read: no answer about the caller, so not an authentication
            // failure. A filter's exception would reach Spring's error page; answer as the routes do.
            storeFailed(response, e);
            return;
        }
        request.setAttribute(CALLER, caller);
        chain.doFilter(request, response);
    }

    private static void storeFailed(HttpServletResponse response, StoreException e) throws IOException {
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        if (e instanceof StoreUnavailableException) {
            LOG.warn("catalog store unavailable while authenticating: {}", e.getMessage());
            response.setStatus(StoreOutage.STATUS);
            response.setHeader("Retry-After", StoreOutage.RETRY_AFTER_SECONDS);
            response.getWriter().write(StoreOutage.body("Catalog store unavailable; retry later"));
        } else {
            LOG.error("catalog store failed while authenticating", e);
            response.setStatus(500);
            response.getWriter().write(Wire.error(500, "ServiceFailureException", "Internal server error"));
        }
    }

    private boolean registered(TrustedEngine engine) {
        return registry.findTrustedEngine(engine.issuer(), engine.clientId()).isPresent();
    }

    /** The end user a client says it acts for; whether it may say so is the authenticator's decision. */
    private static Optional<Principal> endUser(HttpServletRequest request) {
        String id = request.getHeader(END_USER);
        String groups = request.getHeader(END_USER_GROUPS);
        if (id == null) {
            if (groups != null) {
                throw new IllegalArgumentException(END_USER_GROUPS + " without " + END_USER);
            }
            return Optional.empty();
        }
        Set<String> decoded = groups == null || groups.isBlank() ? Set.of() : Arrays.stream(groups.split(","))
                .map(g -> URLDecoder.decode(g.strip().replace("+", "%2B"), StandardCharsets.UTF_8))
                .collect(Collectors.toUnmodifiableSet());
        return Optional.of(new Principal(id, decoded));
    }

    private void refuse(HttpServletRequest request, HttpServletResponse response, int status, String reason,
                        String token, String challenge) throws IOException {
        String requestId = request.getHeader(REQUEST_ID);
        if (requestId == null || requestId.isBlank() || requestId.length() > 128) {
            requestId = UUID.randomUUID().toString();
        }
        Claimed claimed = claimed(token);
        try {
            audit.publish(CatalogEvent.create(rest.auditSource(), clock.instant(), new AuthFailure(claimed.issuer(),
                    claimed.subject(), claimed.clientId(), reason, requestId, request.getRemoteAddr())));
        } catch (RuntimeException e) {
            LOG.error("audit of an authentication failure failed: {} (request {})", reason, requestId, e);
        }
        response.setStatus(status);
        if (challenge != null) {
            response.setHeader("WWW-Authenticate", challenge);
        }
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        String type = status == 401 ? "NotAuthorizedException" : "ForbiddenException";
        // The reason stays in the audit record; the client learns only what it must fix.
        String message = status == 401 ? "Not authorized: the bearer token is missing or was not accepted"
                : "Forbidden: " + reason;
        response.getWriter().write(Wire.error(status, type, message));
    }

    /** What an unverified token claims, for the audit record only; it never authenticates anyone. */
    private Claimed claimed(String token) {
        if (token == null) {
            return new Claimed(null, null, null);
        }
        try {
            JWTClaimsSet claims = JWTParser.parse(token).getJWTClaimsSet();
            if (claims == null) {
                return new Claimed(null, null, null);
            }
            SecuritySettings.Issuer issuer = claims.getIssuer() == null ? null : issuers.get(claims.getIssuer());
            String subject = text(claims, issuer == null ? "sub" : issuer.userIdClaim());
            String clientId = issuer == null ? null : text(claims, issuer.clientIdClaim());
            return new Claimed(claims.getIssuer(), subject, clientId);
        } catch (java.text.ParseException | RuntimeException e) {
            return new Claimed(null, null, null);
        }
    }

    private static String text(JWTClaimsSet claims, String name) {
        Object value = claims.getClaim(name);
        return value instanceof String s ? s : null;
    }

    private record Claimed(String issuer, String subject, String clientId) {
    }
}
