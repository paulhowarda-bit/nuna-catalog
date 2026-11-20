package nuna.catalog.server.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.RSAKey;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import nuna.catalog.audit.AuthFailure;
import nuna.catalog.audit.CatalogEvent;
import nuna.catalog.core.principal.Caller;
import nuna.catalog.security.TestIdp;
import nuna.catalog.security.TokenAuthenticator;
import nuna.catalog.server.StoreOutage;
import nuna.catalog.server.rest.RestSettings;
import nuna.catalog.server.telemetry.RequestIds;
import nuna.catalog.store.GovernanceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;

/**
 * What a client is told when the catalog cannot fetch its issuer's signing keys (D16, amended 2026-11-20; backlog
 * 90): 503 with Retry-After in the Iceberg error shape, no Bearer challenge, the request never reaching a route, and
 * one audited authentication failure that names the reason. A token bad on its own is still 401 with the challenge.
 * The filter and authenticator are the real ones over a test IdP, the engine registry a double that knows no engine,
 * and the audit a list, so this needs only Java.
 */
class IdpOutageTest {

    private static final String ISSUER = "https://login.example.test/entra/v2.0";
    private static final String AUDIENCE = "api://nuna-catalog";

    private final TestIdp idp;
    private final RSAKey key;
    private final List<CatalogEvent> audit = new ArrayList<>();
    private final Authentication filter;

    IdpOutageTest() throws Exception {
        idp = new TestIdp(ISSUER, AUDIENCE);
        key = TestIdp.rsaKey("entra-1");
        idp.publish(key);
        SecuritySettings settings = new SecuritySettings(List.of(new SecuritySettings.Issuer(ISSUER, idp.jwksUri(),
                Set.of(AUDIENCE), Set.of(SignatureAlgorithm.RS256), "upn", "groups", "azp")),
                TestIdp.SKEW, Set.of(), Set.of(), Set.of(), false);
        TokenAuthenticator authenticator = new TokenAuthenticator(
                settings.issuers().stream().map(SecuritySettings.Issuer::policy).toList(), TestIdp.SKEW, TestIdp.CLOCK);
        RestSettings rest = new RestSettings(100, 1000, 1_048_576, 1000, 268_435_456, Duration.ofDays(30),
                Duration.ofMinutes(5), "the identity provider", URI.create("urn:nuna:test"), 100, null);
        filter = new Authentication(authenticator, noEngines(), settings, audit::add, rest, TestIdp.CLOCK);
    }

    @AfterEach
    void stop() {
        idp.close();
    }

    @Test
    void keysThatCannotBeFetchedAnswer503WithRetryAfter() throws Exception {
        idp.failFetches();

        Answer answer = send(token(key));

        assertThat(answer.response().getStatus()).isEqualTo(503);
        assertThat(answer.response().getHeader("Retry-After")).isEqualTo(StoreOutage.RETRY_AFTER_SECONDS);
        assertThat(answer.response().getHeader("WWW-Authenticate")).as("the token is not called bad").isNull();
        assertThat(answer.response().getContentType()).startsWith("application/json");
        String body = answer.response().getContentAsString();
        assertThat(JsonPath.<Integer>read(body, "$.error.code")).isEqualTo(503);
        assertThat(JsonPath.<String>read(body, "$.error.type")).isEqualTo("ServiceUnavailableException");
        assertThat(JsonPath.<String>read(body, "$.error.message"))
                .isEqualTo("The token could not be checked: its issuer's signing keys could not be fetched; retry later");
        assertThat(answer.reachedRoute()).isFalse();
        AuthFailure failure = onlyAuthFailure();
        assertThat(failure.reason()).contains(ISSUER, "keys could not be fetched", "HTTP 503");
        assertThat(failure.issuer()).isEqualTo(ISSUER);
        assertThat(failure.claimedSubject()).isEqualTo("alice@corp.example");
        assertThat(failure.requestId()).isEqualTo("request-1");
    }

    @Test
    void aBadTokenIs401WhileTheKeysAreReachable() throws Exception {
        String wrongSignature = token(TestIdp.rsaKey(key.getKeyID()));

        Answer answer = send(wrongSignature);

        assertThat(answer.response().getStatus()).isEqualTo(401);
        assertThat(answer.response().getHeader("WWW-Authenticate")).isEqualTo("Bearer error=\"invalid_token\"");
        assertThat(answer.response().getHeader("Retry-After")).isNull();
        String body = answer.response().getContentAsString();
        assertThat(JsonPath.<String>read(body, "$.error.type")).isEqualTo("NotAuthorizedException");
        assertThat(answer.reachedRoute()).isFalse();
        assertThat(onlyAuthFailure().reason()).contains(ISSUER, "rejected", "Invalid signature");
        assertThat(idp.fetches()).isEqualTo(1);
    }

    @Test
    void aGoodTokenPassesWhileTheKeysAreReachable() throws Exception {
        Answer answer = send(token(key));

        assertThat(answer.reachedRoute()).isTrue();
        assertThat(answer.request().getAttribute(Authentication.CALLER)).isInstanceOfSatisfying(Caller.class,
                caller -> assertThat(caller.principal().id()).isEqualTo("alice@corp.example"));
        assertThat(audit).isEmpty();
    }

    private Answer send(String token) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/config");
        // What RequestIds would have set before authentication ran.
        request.setAttribute(RequestIds.class.getName(), "request-1");
        request.addHeader("Authorization", "Bearer " + token);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, response, chain);
        return new Answer(request, response, chain.getRequest() != null);
    }

    private String token(RSAKey signer) throws Exception {
        return TestIdp.sign(signer, JWSAlgorithm.RS256, idp.claims()
                .claim("upn", "alice@corp.example")
                .claim("azp", "pyiceberg-client")
                .build());
    }

    private AuthFailure onlyAuthFailure() {
        assertThat(audit).hasSize(1);
        assertThat(audit.getFirst().data()).isInstanceOf(AuthFailure.class);
        return (AuthFailure) audit.getFirst().data();
    }

    /** The engine registry, read only once a token is accepted: it knows no engine. */
    private static GovernanceStore noEngines() {
        return (GovernanceStore) Proxy.newProxyInstance(GovernanceStore.class.getClassLoader(),
                new Class<?>[] {GovernanceStore.class}, (proxy, method, args) -> {
                    if (method.getName().equals("findTrustedEngine")) {
                        return Optional.empty();
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    private record Answer(MockHttpServletRequest request, MockHttpServletResponse response, boolean reachedRoute) {
    }
}
