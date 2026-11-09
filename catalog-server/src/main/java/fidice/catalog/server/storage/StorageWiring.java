package fidice.catalog.server.storage;

import fidice.catalog.server.rest.AuditPublisher;
import fidice.catalog.server.rest.CredentialVending;
import fidice.catalog.server.rest.RestSettings;
import fidice.catalog.server.security.SecuritySettings;
import fidice.catalog.store.CatalogStore;
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
