package keel.catalog.server;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import javax.sql.DataSource;
import keel.catalog.store.CatalogStore;
import keel.catalog.store.mongo.MongoCatalogStore;
import keel.catalog.store.oracle.OracleCatalogStore;
import keel.catalog.store.sql.SqlCatalogStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The catalog store on the database {@code keel.database.backend} names (D7), migrated before the server takes
 * requests. Server code reaches the database only through this store.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(DatabaseSettings.class)
class StoreWiring {

    @Bean
    CatalogStore catalogStore(DatabaseSettings settings, ObjectProvider<DataSource> dataSource,
                              ObjectProvider<MongoClient> mongoClient) {
        CatalogStore store = switch (settings.backend()) {
            case POSTGRESQL -> new SqlCatalogStore(dataSource.getObject());
            case ORACLE -> new OracleCatalogStore(dataSource.getObject());
            case MONGODB -> new MongoCatalogStore(mongoClient.getObject(),
                    settings.mongodb().connectionString().getDatabase());
        };
        store.migrate();
        return store;
    }

    /** The MongoDB backend's client, closed when the server stops. */
    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(name = "keel.database.backend", havingValue = "mongodb")
    MongoClient mongoClient(DatabaseSettings settings) {
        return MongoClients.create(settings.mongodb().connectionString());
    }
}
