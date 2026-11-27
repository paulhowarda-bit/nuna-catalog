package nuna.catalog.server;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import io.micrometer.observation.ObservationRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import nuna.catalog.server.tracing.ObservedStore;
import nuna.catalog.store.CatalogStore;
import nuna.catalog.store.SafetyFinding;
import nuna.catalog.store.mongo.MongoCatalogStore;
import nuna.catalog.store.oracle.OracleCatalogStore;
import nuna.catalog.store.sql.SqlCatalogStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The catalog store on the database {@code nuna.database.backend} names (D7), self-checked and migrated before the
 * server takes requests. Server code reaches the database only through this store, and each of its calls is
 * observed (docs/observability/spans.md). The one exception asks the database what it is, nothing of the catalog:
 * Health's Store panel reads its product and version on the same connection (backlog 139, {@code StoreFacts}).
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(DatabaseSettings.class)
class StoreWiring {

    private static final Logger LOG = LoggerFactory.getLogger(StoreWiring.class);

    /** The store, self-checked and migrated, and its self-check: the two beans below are taken from it. */
    record Started(CatalogStore store, StoreSelfCheck check) {
    }

    @Bean
    Started startedStore(DatabaseSettings settings, ObjectProvider<DataSource> dataSource,
                         ObjectProvider<MongoClient> mongoClient, Clock clock) {
        CatalogStore store = store(settings, dataSource, mongoClient);
        Instant checked = clock.instant();
        return new Started(store, new StoreSelfCheck(checked, ready(store, settings.allowUnsafe())));
    }

    @Bean
    CatalogStore catalogStore(Started started, DatabaseSettings settings, ObservationRegistry observations) {
        return ObservedStore.of(started.store(), settings.backend().name().toLowerCase(Locale.ROOT), observations);
    }

    @Bean
    StoreSelfCheck storeSelfCheck(Started started) {
        return started.check();
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

    /**
     * {@code store}, migrated once its database passes the self-check (D8.2). A finding refuses to start the server
     * (Paul, 2026-11-19: "Refuse to start"), unless {@code allowUnsafe} names its code, as a single-node test or demo
     * database's settings do; each finding allowed is logged as a warning. The check runs first, so a server that
     * refuses changes nothing in the database. A self-check that cannot run refuses too: its exception is the cause.
     *
     * @return the findings allowed, which Health's Store panel shows; none when the database passed
     */
    static List<SafetyFinding> ready(CatalogStore store, Set<String> allowUnsafe) {
        List<SafetyFinding> refused = new ArrayList<>();
        List<SafetyFinding> allowed = new ArrayList<>();
        for (SafetyFinding finding : store.selfCheck()) {
            if (allowUnsafe.contains(finding.code())) {
                LOG.warn("The catalog's database is unsafe, allowed by nuna.database.allow-unsafe: {}: {}",
                        finding.code(), finding.detail());
                allowed.add(finding);
            } else {
                refused.add(finding);
            }
        }
        if (!refused.isEmpty()) {
            // The rule behind this message: D8.2.
            throw new IllegalStateException("The catalog's database is unsafe, so the server does not start: "
                    + refused.stream().map(finding -> finding.code() + ": " + finding.detail())
                    .collect(Collectors.joining("; "))
                    + ". Make the database safe. Only a test or demo database may run so, by naming each code in"
                    + " nuna.database.allow-unsafe.");
        }
        store.migrate();
        return allowed;
    }

    /** The MongoDB backend's client, closed when the server stops. */
    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(name = "nuna.database.backend", havingValue = "mongodb")
    MongoClient mongoClient(DatabaseSettings settings) {
        return MongoClients.create(settings.mongodb().connectionString());
    }
}
