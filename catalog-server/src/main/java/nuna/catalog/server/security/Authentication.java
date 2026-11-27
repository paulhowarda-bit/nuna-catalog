package nuna.catalog.server.security;

import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.JWTParser;
import nuna.catalog.audit.AuthFailure;
import nuna.catalog.audit.CatalogEvent;
import nuna.catalog.core.principal.Caller;
import nuna.catalog.core.principal.Principal;
import nuna.catalog.iceberg.Wire;
import nuna.catalog.security.AuthenticationFailure;
import nuna.catalog.security.TokenAuthenticator;
import nuna.catalog.security.TrustedEngine;
import nuna.catalog.server.rest.AuditPublisher;
import nuna.catalog.server.rest.RestSettings;
import nuna.catalog.server.StoreOutage;
import nuna.catalog.server.telemetry.RequestIds;
import nuna.catalog.server.ui.UiWiring;
import nuna.catalog.store.GovernanceStore;
import nuna.catalog.store.StoreException;
import nuna.catalog.store.StoreUnavailableException;
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
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authenticates every request but {@code /v1/oauth/tokens} (plan 2.2 step 1; D16, D17, D17a). The bearer token
 * names the principal; a registered trusted engine may also name the end user it acts for, in two headers:
 * {@value #END_USER} (the end user's id, as the issuers' user-id claim carries it) and {@value #END_USER_GROUPS}
 * (comma-separated groups, each percent-encoded). A request that cannot be authenticated answers 401 (403 when
 * an unregistered client names an end user, 503 with Retry-After when its token could not be checked because the
 * issuer's keys could not be fetched) and is audited as an {@link AuthFailure}; it never reaches a route.
 *
 * <p>An engine that passes its users' own tokens through, as Trino does (session USER), names itself instead in
 * {@value #ENGINE_AUTHORIZATION}, a bearer token of its own (D20, backlog 115): the {@code Authorization} token then
 * names the end user, with the groups their own token carries, and the engine's must be a registered trusted
 * engine's (403 if not). Both are verified; a header holding the same token as {@code Authorization} is the engine
 * acting as itself, and one beside {@value #END_USER} is refused.
 */
final class Authentication extends OncePerRequestFilter {

    static final String CALLER = Authentication.class.getName() + ".caller";
    static final String END_USER = "X-Nuna-End-User";
    static final String END_USER_GROUPS = "X-Nuna-End-User-Groups";
    static final String ENGINE_AUTHORIZATION = "X-Nuna-Engine-Authorization";

    private static final Logger LOG = LoggerFactory.getLogger(Authentication.class);

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
        // The fixed OAuth error names the IdP for clients that have no token yet (D13). The console's own files
        // are what a browser loads before it can sign in; the API calls they make carry a token. The documentation
        // site is published documentation, read before or without signing in.
        // OPA's calls from Trino carry no token of their own: OpaRoutes verifies the user's, which Trino forwards in
        // the body, and answers from it alone (D20, backlog 115).
        return path.equals("/v1/oauth/tokens") || UiWiring.isConsoleFile(path) || UiWiring.isDocsFile(path)
                || path.startsWith("/opa/");
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
        String engineToken = request.getHeader(ENGINE_AUTHORIZATION);
        if (engineToken != null) {
            if (!engineToken.regionMatches(true, 0, "Bearer ", 0, 7) || engineToken.substring(7).isBlank()) {
                refuse(request, response, 401, ENGINE_AUTHORIZATION + " is not a bearer token", null,
                        "Bearer error=\"invalid_request\"");
                return;
            }
            engineToken = engineToken.substring(7).trim();
            if (engineToken.equals(token)) {
                // The engine acting as itself, its config read with its own token (item 84).
                engineToken = null;
            } else if (endUser.isPresent()) {
                refuse(request, response, 401, ENGINE_AUTHORIZATION + " beside " + END_USER
                        + ": an engine names its end user one way", token, "Bearer error=\"invalid_request\"");
                return;
            }
        }
        Caller caller;
        String checking = token;
        try {
            if (engineToken == null) {
                caller = authenticator.authenticate(token, endUser, this::registered);
            } else {
                checking = engineToken;
                Caller engine = authenticator.authenticate(engineToken, Optional.empty(), this::registered);
                if (!engine.trustedEngine()) {
                    refuse(request, response, 403, "client " + engine.engine() + " is not a registered trusted"
                            + " engine, so it may not act for another user", engineToken, null);
                    return;
                }
                checking = token;
                Principal user = authenticator.authenticate(token, Optional.empty(), this::registered).principal();
                caller = new Caller(engine.principal(), engine.engine(), true, Optional.of(user));
            }
        } catch (AuthenticationFailure failure) {
            refuse(request, response, failure.status(), failure.getMessage(), checking,
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
        String requestId = RequestIds.of(request);
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
        if (status == 503) {
            // The issuer's keys could not be fetched: the token is not known to be bad, so the client sends it
            // again, after the wait a store outage asks for (D16, amended 2026-11-20).
            LOG.warn("{} (request {})", reason, requestId);
            response.setHeader("Retry-After", StoreOutage.RETRY_AFTER_SECONDS);
        }
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        // The reason stays in the audit record; the client learns only what it must fix.
        response.getWriter().write(switch (status) {
            case 401 -> Wire.error(status, "NotAuthorizedException",
                    "Not authorized: the bearer token is missing or was not accepted");
            case 503 -> Wire.error(status, "ServiceUnavailableException",
                    "The token could not be checked: its issuer's signing keys could not be fetched; retry later");
            default -> Wire.error(status, "ForbiddenException", "Forbidden: " + reason);
        });
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
