package nuna.catalog.server.health;

import com.mongodb.client.MongoClient;
import io.micrometer.core.instrument.Clock;
import javax.sql.DataSource;
import nuna.catalog.server.DatabaseSettings;
import nuna.catalog.server.StoreSelfCheck;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The console's health page: the registry it reads, this server's window over it, the other servers, and the
 * catalog's database as this server finds it.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(HealthSettings.class)
class HealthWiring {

    /**
     * A meter registry bean: Boot records every meter here as well, and without an exporting registry this is the
     * only one (Boot's own fallback registry steps aside for any registry bean).
     */
    @Bean
    HealthMeters healthMeters() {
        return new HealthMeters(Clock.SYSTEM);
    }

    @Bean(initMethod = "start", destroyMethod = "close")
    HealthWindow healthWindow(HealthMeters meters, java.time.Clock clock) {
        return new HealthWindow(meters, clock);
    }

    @Bean(destroyMethod = "close")
    Peers healthPeers(HealthSettings settings) {
        return new Peers(settings);
    }

    /** The backend's own connection: a DataSource, or on MongoDB, which has none, the client. */
    @Bean
    StoreFacts storeFacts(DatabaseSettings settings, ObjectProvider<DataSource> dataSource,
                          ObjectProvider<MongoClient> mongoClient, StoreSelfCheck selfCheck) {
        return new StoreFacts(settings.backend(), dataSource::getObject, mongoClient::getObject, selfCheck);
    }
}
