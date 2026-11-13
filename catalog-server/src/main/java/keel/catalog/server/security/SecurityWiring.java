package keel.catalog.server.security;

import keel.catalog.core.principal.Caller;
import keel.catalog.security.Authorizer;
import keel.catalog.security.Separation;
import keel.catalog.security.TokenAuthenticator;
import keel.catalog.server.rest.AuditPublisher;
import keel.catalog.server.rest.Callers;
import keel.catalog.server.rest.RestSettings;
import keel.catalog.store.CatalogStore;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Authentication on every request, and the authorizer the routes ask (M5). */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SecuritySettings.class)
class SecurityWiring {

    @Bean
    TokenAuthenticator tokenAuthenticator(SecuritySettings settings, Clock clock) {
        return new TokenAuthenticator(settings.issuers().stream().map(SecuritySettings.Issuer::policy).toList(),
                settings.clockSkew(), clock);
    }

    @Bean
    Separation separation(SecuritySettings settings) {
        return new Separation(settings.grantAdminGroups(), settings.platformOperatorGroups());
    }

    @Bean
    Authorizer authorizer(CatalogStore store, Separation separation, SecuritySettings settings) {
        return new Authorizer(store, store, store, separation, settings.refuseIdentityLessCommits(),
                settings.maintenancePrincipals());
    }

    /** The caller the authentication filter established; routes are never reached without one. */
    @Bean
    Callers callers() {
        return request -> {
            if (!(request.getAttribute(Authentication.CALLER) instanceof Caller caller)) {
                throw new IllegalStateException("request " + request.getRequestURI() + " was not authenticated");
            }
            return caller;
        };
    }

    /** Spring Boot registers a filter bean for every request. */
    @Bean
    Authentication authentication(TokenAuthenticator authenticator, CatalogStore store, SecuritySettings settings,
                                  AuditPublisher audit, RestSettings rest, Clock clock) {
        return new Authentication(authenticator, store, settings, audit, rest, clock);
    }
}
