package keel.catalog.server.storage;

import keel.catalog.server.rest.AuditPublisher;
import keel.catalog.server.rest.CredentialVending;
import keel.catalog.server.rest.RestSettings;
import keel.catalog.server.security.SecuritySettings;
import keel.catalog.store.CatalogStore;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Each catalog's storage profile, and STS credential vending audited through the {@link AuditPublisher} before hand-out (M6). */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(StorageSettings.class)
class StorageWiring {

    /** Writes profiles and declared catalogs to the store, so it waits for the schema. */
    @Bean
    @DependsOnDatabaseInitialization
    CatalogStorage catalogStorage(StorageSettings settings, CatalogStore store, RestSettings rest, Clock clock) {
        return new CatalogStorage(settings, store, rest, clock);
    }

    @Bean
    CredentialVending credentialVending(CatalogStorage storage, AuditPublisher audit, StorageSettings settings,
                                        SecuritySettings security, RestSettings rest, Clock clock) {
        return new StsCredentialVending(storage, audit, rest.auditSource(), settings, security,
                rest.credentialRefreshMargin(), clock);
    }
}
