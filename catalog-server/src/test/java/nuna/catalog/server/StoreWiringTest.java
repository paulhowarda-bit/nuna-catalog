package nuna.catalog.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import java.util.Map;
import javax.sql.DataSource;
import nuna.catalog.store.mongo.MongoCatalogStore;
import nuna.catalog.store.oracle.OracleCatalogStore;
import nuna.catalog.store.sql.SqlCatalogStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;

/**
 * Each backend {@code nuna.database.backend} names gets its own store (D7), without a database: only the store's
 * {@code migrate()} reaches one. OracleBackendTest and MongodbBackendTest run the server on each; a server given
 * the wrong store does not start, so they cannot tell that apart from a database that is not up.
 */
class StoreWiringTest {

    private static final String MONGODB_URI = "mongodb://h1,h2,h3/catalog?replicaSet=rs0&socketTimeoutMS=30000";

    @Test
    void eachBackendGetsItsOwnStore() {
        ObjectProvider<DataSource> dataSource = provider(DataSource.class, new SimpleDriverDataSource());
        ObjectProvider<MongoClient> none = provider(MongoClient.class, null);

        assertThat(StoreWiring.store(settings(DatabaseSettings.Backend.POSTGRESQL), dataSource, none))
                .isInstanceOf(SqlCatalogStore.class);
        assertThat(StoreWiring.store(settings(DatabaseSettings.Backend.ORACLE), dataSource, none))
                .isInstanceOf(OracleCatalogStore.class);
        // A client connects in the background, when first asked for something; the store asks for nothing here.
        try (MongoClient client = MongoClients.create(MONGODB_URI)) {
            assertThat(StoreWiring.store(new DatabaseSettings(DatabaseSettings.Backend.MONGODB,
                    new DatabaseSettings.Mongodb(MONGODB_URI)), provider(DataSource.class, null),
                    provider(MongoClient.class, client)))
                    .isInstanceOf(MongoCatalogStore.class);
        }
    }

    private static DatabaseSettings settings(DatabaseSettings.Backend backend) {
        return new DatabaseSettings(backend, null);
    }

    private static <T> ObjectProvider<T> provider(Class<T> type, T bean) {
        StaticListableBeanFactory beans = bean == null ? new StaticListableBeanFactory()
                : new StaticListableBeanFactory(Map.of("bean", bean));
        return beans.getBeanProvider(type);
    }
}
