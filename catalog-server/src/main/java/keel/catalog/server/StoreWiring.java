package keel.catalog.server;

import keel.catalog.store.CatalogStore;
import keel.catalog.store.sql.SqlCatalogStore;

import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Chooses the storage backend. M0 has the PostgreSQL-wire backend only. */
@Configuration(proxyBeanMethods = false)
class StoreWiring {

    /**
     * The store, its schema brought up to date before anything uses it. The store migrates with its
     * backend's own steps: on PostgreSQL that includes the journal's partitioning (V4) and its partitions
     * ahead, which the shared Flyway location alone never reaches.
     */
    @Bean
    CatalogStore catalogStore(DataSource dataSource) {
        SqlCatalogStore store = new SqlCatalogStore(dataSource);
        store.migrate();
        return store;
    }
}
