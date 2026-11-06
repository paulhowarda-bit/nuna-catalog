package fidice.catalog.server;

import fidice.catalog.store.CatalogStore;
import fidice.catalog.store.sql.SqlCatalogStore;

import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Chooses the storage backend. M0 has the PostgreSQL-wire backend only. */
@Configuration(proxyBeanMethods = false)
class StoreWiring {

    @Bean
    CatalogStore catalogStore(DataSource dataSource) {
        return new SqlCatalogStore(dataSource);
    }
}
