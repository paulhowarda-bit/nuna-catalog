package nuna.catalog.server.security;

import nuna.catalog.core.principal.Caller;
import nuna.catalog.security.Authorizer;
import nuna.catalog.security.Separation;
import nuna.catalog.security.TokenAuthenticator;
import nuna.catalog.server.rest.AuditPublisher;
import nuna.catalog.server.rest.Callers;
import nuna.catalog.server.rest.RestSettings;
import nuna.catalog.store.CatalogStore;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Authentication on every request, and the authorizer the routes ask (M5). */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({SecuritySettings.class, OpaSettings.class})
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

    @Bean
    OwnGroups ownGroups(SecuritySettings settings) {
        return new OwnGroups(settings.ownGroupPrincipals());
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
