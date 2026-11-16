package keel.catalog.server;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import io.micrometer.observation.ObservationRegistry;
import java.util.Locale;
import javax.sql.DataSource;
import keel.catalog.server.tracing.ObservedStore;
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
 * requests. Server code reaches the database only through this store, and each of its calls is observed
 * (docs/observability/spans.md).
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(DatabaseSettings.class)
class StoreWiring {

    @Bean
    CatalogStore catalogStore(DatabaseSettings settings, ObjectProvider<DataSource> dataSource,
                              ObjectProvider<MongoClient> mongoClient, ObservationRegistry observations) {
        CatalogStore store = store(settings, dataSource, mongoClient);
        store.migrate();
        return ObservedStore.of(store, settings.backend().name().toLowerCase(Locale.ROOT), observations);
    }

    /** The store for the backend {@code settings} names, not yet migrated: making one reaches no database. */
    static CatalogStore store(DatabaseSettings settings, ObjectProvider<DataSource> dataSource,
                              ObjectProvider<MongoClient> mongoClient) {
        return switch (settings.backend()) {
            case POSTGRESQL -> new SqlCatalogStore(dataSource.getObject());
            case ORACLE -> new OracleCatalogStore(dataSource.getObject());
            case MONGODB -> new MongoCatalogStore(mongoClient.getObject(),
                    settings.mongodb().connectionString().getDatabase());
        };
    }

    /** The MongoDB backend's client, closed when the server stops. */
    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(name = "keel.database.backend", havingValue = "mongodb")
    MongoClient mongoClient(DatabaseSettings settings) {
        return MongoClients.create(settings.mongodb().connectionString());
    }
}
