package nuna.audittables;

import nuna.catalog.audit.archive.ArchiveSink;
import nuna.catalog.audit.archive.ObjectLockArchive;
import nuna.catalog.objectstore.CaBundle;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
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
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.apache5.Apache5HttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;

/**
 * The audit table writer as a service (D23, amended 2026-11-20 and 2026-11-23): no web server, and one thread
 * committing to the audit catalog every {@code nuna.audit-tables.commit-interval}. It takes the records from the
 * source {@code nuna.audit-tables.source} names: the audit topic, which that thread reads ({@link Loop}), or the
 * catalog's log, which the catalog's OTLP log exporter sends to its logs receiver ({@link LogLoop}). One replica: a
 * second would only fail the first's commits and its own in turn (see {@link EventsTable}), and a second archive
 * writer would find its segments' keys taken. An outage of the catalog or of Kafka delays rows; no request waits for
 * the writer.
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
    SmartLifecycle loop(AuditTablesSettings settings, RESTCatalog catalog, Clock clock) {
        return switch (settings.source()) {
            case KAFKA -> new Loop(settings, catalog, clock);
            case OTLP -> new LogLoop(settings, catalog, clock);
        };
    }

    /**
     * The archive where {@code nuna.audit-tables.archive.bucket} is set; its bucket must have Object Lock, and a CA
     * bundle it cannot read stops startup, naming the setting and the file.
     */
    static Optional<ObjectLockArchive> archive(AuditTablesSettings.Archive settings, Clock clock) {
        if (!settings.configured()) {
            return Optional.empty();
        }
        S3ClientBuilder s3 = S3Client.builder().region(Region.of(settings.region()))
                .credentialsProvider(settings.accessKeyId() == null ? DefaultCredentialsProvider.builder().build()
                        : StaticCredentialsProvider.create(AwsBasicCredentials.create(settings.accessKeyId(),
                        settings.secretAccessKey())))
                .httpClientBuilder(Apache5HttpClient.builder());
        if (settings.endpoint() != null && !settings.endpoint().isBlank()) {
            s3.endpointOverride(URI.create(settings.endpoint())).forcePathStyle(true);
        }
        if (settings.caBundle() != null) {
            try {
                CaBundle.read(settings.caBundle()).applyTo(s3);
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException("nuna.audit-tables.archive.ca-bundle: " + e.getMessage(), e);
            }
        }
        ObjectLockArchive archive = new ObjectLockArchive(s3.build(), settings.bucket(), settings.prefix(),
                settings.retention(), clock);
        archive.checkBucket();
        return Optional.of(archive);
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

    /**
     * The source {@code otlp}: the logs receiver takes records into the {@link LogWriter}, and one non-daemon thread,
     * which keeps the service alive, commits them. With an archive, the receiver hands each record to the {@link
     * ArchiveSink} first, and a second thread writes its segments and checkpoints. Stopping stops the receiver, then
     * writes what waits for the archive and commits what waits for the table.
     */
    static final class LogLoop implements SmartLifecycle {

        private static final Logger LOG = LoggerFactory.getLogger(LogLoop.class);
        private static final Duration TICK = Duration.ofSeconds(1);
        private static final Duration RETRY = Duration.ofSeconds(10);

        private final AuditTablesSettings settings;
        private final LogWriter writer;
        private final Optional<ArchiveSink> archive;
        private volatile boolean running;
        private OtlpLogsReceiver receiver;
        private Thread thread;
        private Thread archiving;

        LogLoop(AuditTablesSettings settings, RESTCatalog catalog, Clock clock) {
            this(settings, catalog, clock, archive(settings.archive(), clock));
        }

        LogLoop(AuditTablesSettings settings, RESTCatalog catalog, Clock clock, Optional<ObjectLockArchive> archive) {
            this.settings = settings;
            Namespace namespace = Namespace.of(settings.namespace());
            this.writer = new LogWriter(() -> EventsTable.open(catalog, namespace, LogWriter.SOURCE),
                    settings.principal(), settings.commitInterval(), settings.maxRows(), settings.otlp().dedupWindow(),
                    clock);
            AuditTablesSettings.Archive a = settings.archive();
            this.archive = archive.map(locked -> new ArchiveSink(locked, a.segmentEvents(), a.segmentAge(),
                    a.checkpointInterval(), a.maxWaiting(), clock));
        }

        @Override
        public synchronized void start() {
            running = true;
            receiver = OtlpLogsReceiver.start(settings.otlp().port(), settings.otlp().token(), writer,
                    archive.map(sink -> sink::offer));
            thread = Thread.ofPlatform().name("nuna-audit-tables").daemon(false).start(this::run);
            LOG.info("Writing the log {} taken on port {} at {} to {}.{}.{} at {} every {}",
                    OtlpLogsReceiver.AUDIT_LOGGER, receiver.port(), OtlpLogsReceiver.PATH, settings.catalog(),
                    settings.namespace(), EventsTable.NAME, settings.catalogUri(), settings.commitInterval());
            archive.ifPresent(sink -> {
                archiving = Thread.ofPlatform().name("nuna-audit-archive").daemon(false).start(() -> archiveUntilStopped(sink));
                LOG.info("Archiving the log {} to s3://{}/{} as writer {}, a checkpoint at least every {}",
                        OtlpLogsReceiver.AUDIT_LOGGER, settings.archive().bucket(), settings.archive().prefix(),
                        sink.writer(), settings.archive().checkpointInterval());
            });
        }

        /** Writes the archive's due segments and checkpoints every second until stopped. */
        private void archiveUntilStopped(ArchiveSink sink) {
            while (running) {
                try {
                    report(sink.tick(), sink);
                    Thread.sleep(TICK);
                } catch (InterruptedException e) {
                    return;
                } catch (RuntimeException e) {
                    LOG.error("The audit archive writer failed a round; {} records wait and it tries again",
                            sink.waiting(), e);
                }
            }
        }

        private static void report(ArchiveSink.Round round, ArchiveSink sink) {
            round.problems().forEach(problem -> LOG.error("The audit archive: {}; {} records wait", problem,
                    sink.waiting()));
        }

        private void run() {
            while (running) {
                try {
                    writer.commitIfDue();
                    Thread.sleep(TICK);
                } catch (InterruptedException e) {
                    return;
                } catch (RuntimeException e) {
                    LOG.error("The audit table writer could not commit {} records from the log; it keeps them and"
                            + " retries in {}", writer.waiting(), RETRY, e);
                    try {
                        Thread.sleep(RETRY);
                    } catch (InterruptedException stopped) {
                        return;
                    }
                }
            }
        }

        @Override
        public synchronized void stop() {
            running = false;
            receiver.close();
            thread.interrupt();
            if (archiving != null) {
                archiving.interrupt();
                try {
                    archiving.join(Duration.ofMinutes(1));
                    ArchiveSink sink = archive.orElseThrow();
                    report(sink.finish(), sink);
                    if (sink.waiting() > 0) {
                        LOG.error("The audit archive writer stopped with {} records not archived", sink.waiting());
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (RuntimeException e) {
                    LOG.error("The audit archive writer could not write its last segments", e);
                }
            }
            try {
                thread.join(Duration.ofMinutes(1));
                writer.commit();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (RuntimeException e) {
                LOG.error("The audit table writer stopped with {} records from the log not written", writer.waiting(),
                        e);
            }
        }

        @Override
        public synchronized boolean isRunning() {
            return running;
        }
    }
}
