package nuna.audittables;

import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.apache.iceberg.CatalogProperties;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.rest.RESTCatalog;
import org.apache.iceberg.rest.auth.OAuth2Properties;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.errors.WakeupException;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;

/**
 * The audit table writer as a service (D23, amended 2026-11-20): no web server, one thread reading the audit topic
 * and committing to the audit catalog every {@code nuna.audit-tables.commit-interval}. One replica: a second would
 * only fail the first's commits and its own in turn (see {@link EventsTable}). An outage of the catalog or of Kafka
 * delays rows; no request waits for the writer.
 */
@SpringBootApplication
@EnableConfigurationProperties(AuditTablesSettings.class)
public class AuditTablesApplication {

    public static void main(String[] args) {
        new SpringApplicationBuilder(AuditTablesApplication.class).web(WebApplicationType.NONE).run(args);
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    /** The writer's Iceberg client of the audit catalog, on the credentials the catalog vends it. */
    @Bean(destroyMethod = "close")
    RESTCatalog catalog(AuditTablesSettings settings) {
        RESTCatalog catalog = new RESTCatalog();
        catalog.initialize("nuna-audit-tables", clientProperties(settings));
        return catalog;
    }

    @Bean
    Loop loop(AuditTablesSettings settings, RESTCatalog catalog, Clock clock) {
        return new Loop(settings, catalog, clock);
    }

    static Map<String, String> clientProperties(AuditTablesSettings settings) {
        Map<String, String> properties = new HashMap<>();
        properties.put(CatalogProperties.URI, settings.catalogUri().toString());
        properties.put(CatalogProperties.WAREHOUSE_LOCATION, settings.catalog());
        AuditTablesSettings.Token token = settings.token();
        properties.put(OAuth2Properties.CREDENTIAL, token.clientId() + ":" + token.clientSecret());
        properties.put(OAuth2Properties.OAUTH2_SERVER_URI, token.endpoint().toString());
        // Renew with the client-credentials grant, which every provider has, not token exchange.
        properties.put(OAuth2Properties.TOKEN_EXCHANGE_ENABLED, "false");
        if (token.scope() != null) {
            properties.put(OAuth2Properties.SCOPE, token.scope());
        }
        if (token.audience() != null) {
            properties.put(OAuth2Properties.AUDIENCE, token.audience());
        }
        properties.put(CatalogProperties.FILE_IO_IMPL, "org.apache.iceberg.aws.s3.S3FileIO");
        properties.put("http-client.type", "apache");
        properties.put("header.X-Iceberg-Access-Delegation", "vended-credentials");
        properties.putAll(settings.clientProperties());
        return properties;
    }

    static Map<String, Object> consumerConfig(AuditTablesSettings.Kafka kafka) {
        Map<String, Object> config = new HashMap<>(kafka.properties());
        config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.bootstrapServers());
        config.put(ConsumerConfig.GROUP_ID_CONFIG, kafka.group());
        config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        config.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed");
        return config;
    }

    /**
     * Runs the writer on one non-daemon thread, which keeps the service alive. The Kafka consumer lives on that
     * thread alone; stopping wakes it.
     */
    static final class Loop implements SmartLifecycle {

        private static final Logger LOG = LoggerFactory.getLogger(Loop.class);
        private static final Duration POLL = Duration.ofSeconds(1);
        private static final Duration RETRY = Duration.ofSeconds(10);

        private final AuditTablesSettings settings;
        private final RESTCatalog catalog;
        private final Clock clock;
        private volatile boolean running;
        private volatile KafkaConsumer<byte[], byte[]> consumer;
        private Thread thread;

        Loop(AuditTablesSettings settings, RESTCatalog catalog, Clock clock) {
            this.settings = settings;
            this.catalog = catalog;
            this.clock = clock;
        }

        @Override
        public synchronized void start() {
            running = true;
            thread = Thread.ofPlatform().name("nuna-audit-tables").daemon(false).start(this::run);
            LOG.info("Writing {} to {}.{}.{} at {} every {}", settings.kafka().topic(), settings.catalog(),
                    settings.namespace(), EventsTable.NAME, settings.catalogUri(), settings.commitInterval());
        }

        private void run() {
            consumer = new KafkaConsumer<>(consumerConfig(settings.kafka()), new ByteArrayDeserializer(),
                    new ByteArrayDeserializer());
            Namespace namespace = Namespace.of(settings.namespace());
            try (TableWriter writer = new TableWriter(consumer, settings.kafka().topic(),
                    () -> EventsTable.open(catalog, namespace, settings.kafka().topic()), settings.principal(),
                    settings.commitInterval(), settings.maxRows(), clock)) {
                while (running) {
                    try {
                        writer.pollOnce(POLL);
                    } catch (WakeupException e) {
                        return;
                    } catch (RuntimeException e) {
                        LOG.error("The audit table writer failed; it reads the table's offsets again and retries in {}",
                                RETRY, e);
                        Thread.sleep(RETRY);
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        @Override
        public synchronized void stop() {
            running = false;
            KafkaConsumer<byte[], byte[]> open = consumer;
            if (open != null) {
                open.wakeup();
            }
            thread.interrupt();
            try {
                thread.join(Duration.ofMinutes(1));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        @Override
        public synchronized boolean isRunning() {
            return running;
        }
    }
}
