package nuna.audittables;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The audit table writer's settings ({@code nuna.audit-tables.*}, D23). The writer signs in to the catalog as its own
 * principal, whose group holds create and write on the audit catalog and nothing else, and writes with the
 * credentials the catalog vends it. It takes the audit records from one source: the audit topic ({@code kafka}, the
 * default) or the catalog's log over OTLP/HTTP ({@code otlp}; D23, amended 2026-11-23).
 *
 * @param catalogUri       the catalog's base URI, e.g. {@code http://nuna-catalog:8181/}
 * @param catalog          the audit catalog, declared in the catalog's {@code nuna.storage.catalogs}
 * @param namespace        the namespace in it holding the table {@code events}; the writer creates both
 * @param principal        the writer's own principal id as the catalog records it (the value of its token's
 *                         user-id claim): the records of its own requests are left out of the table
 * @param commitInterval   how often waiting rows are committed; rows are about this far behind the topic
 * @param maxRows          rows that make a commit before the interval has passed, bounding the writer's memory
 * @param token            how the writer signs in
 * @param clientProperties more Iceberg client properties (TLS, an S3 endpoint override); keys with dots in brackets
 * @param source           where the records come from: {@code kafka} or {@code otlp}
 * @param kafka            the audit topic, for the source {@code kafka}
 * @param otlp             the OTLP/HTTP logs receiver, for the source {@code otlp}
 * @param archive          the Object Lock archive the receiver also feeds, for the source {@code otlp} (D23, amended
 *                         2026-11-25)
 */
@ConfigurationProperties("nuna.audit-tables")
public record AuditTablesSettings(
        URI catalogUri,
        @DefaultValue("nuna_audit") String catalog,
        @DefaultValue("audit") String namespace,
        String principal,
        @DefaultValue("PT1M") Duration commitInterval,
        @DefaultValue("100000") int maxRows,
        Token token,
        @DefaultValue Map<String, String> clientProperties,
        @DefaultValue("kafka") Source source,
        @DefaultValue Kafka kafka,
        @DefaultValue Otlp otlp,
        @DefaultValue Archive archive) {

    /** Where the writer takes the audit records from. */
    public enum Source {
        /** The catalog's audit topic, read in the writer's own consumer group. */
        KAFKA,
        /** The catalog's log {@code nuna.audit}, sent to the writer's OTLP/HTTP logs receiver. */
        OTLP
    }

    public AuditTablesSettings {
        Objects.requireNonNull(catalogUri, "nuna.audit-tables.catalog-uri names no catalog");
        if (principal == null || principal.isBlank()) {
            throw new IllegalArgumentException("nuna.audit-tables.principal must name the writer's own principal id,"
                    + " as the catalog records it: the records of its own requests are left out of the table");
        }
        Objects.requireNonNull(token, "nuna.audit-tables.token: set endpoint, client-id and client-secret");
        if (commitInterval.isNegative() || commitInterval.isZero()) {
            throw new IllegalArgumentException("nuna.audit-tables.commit-interval must be positive");
        }
        if (maxRows < 1) {
            throw new IllegalArgumentException("nuna.audit-tables.max-rows must be at least 1");
        }
        clientProperties = Map.copyOf(clientProperties);
        if (source == Source.KAFKA && (kafka.bootstrapServers() == null || kafka.bootstrapServers().isBlank())) {
            throw new IllegalArgumentException("nuna.audit-tables.kafka.bootstrap-servers names no Kafka cluster, and"
                    + " nuna.audit-tables.source is kafka");
        }
        if (source == Source.OTLP) {
            otlp.requireToken();
        }
        if (archive.configured() && source != Source.OTLP) {
            throw new IllegalArgumentException("nuna.audit-tables.archive.bucket is set, and nuna.audit-tables.source is"
                    + " kafka: the archive is fed from the catalog's log, so it needs the source otlp");
        }
    }

    /**
     * The OAuth 2 client-credentials grant against the identity provider's token endpoint, made and renewed by
     * Iceberg's client.
     *
     * @param endpoint     the identity provider's token endpoint
     * @param clientId     the writer's client id there
     * @param clientSecret its secret
     * @param scope        the scope to ask for; Iceberg's client asks for {@code catalog} when none is set
     * @param audience     an audience to ask for, if the provider needs one (the catalog checks {@code aud})
     */
    public record Token(URI endpoint, String clientId, String clientSecret, String scope, String audience) {

        public Token {
            if (endpoint == null || clientId == null || clientSecret == null) {
                throw new IllegalArgumentException("nuna.audit-tables.token needs endpoint, client-id and"
                        + " client-secret: the client-credentials grant");
            }
        }
    }

    /**
     * The audit topic, read in the writer's own consumer group.
     *
     * @param bootstrapServers the Kafka cluster
     * @param topic            the catalog's audit topic ({@code nuna.audit.audit-topic})
     * @param group            the writer's consumer group; its offsets only show lag, the table holds the real ones
     * @param properties       further Kafka client settings (TLS, SASL); they may not set the ones the writer owns
     */
    public record Kafka(String bootstrapServers, @DefaultValue("nuna.catalog.audit") String topic,
                        @DefaultValue("nuna-audit-tables") String group,
                        @DefaultValue Map<String, String> properties) {

        /** Consumer settings this writer owns. */
        static final Set<String> OWNED = Set.of(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, ConsumerConfig.GROUP_ID_CONFIG,
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,
                ConsumerConfig.ISOLATION_LEVEL_CONFIG, ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG);

        public Kafka {
            Set<String> overridden = new TreeSet<>(properties.keySet());
            overridden.retainAll(OWNED);
            if (!overridden.isEmpty()) {
                throw new IllegalArgumentException("nuna.audit-tables.kafka.properties may not set " + overridden);
            }
            properties = Map.copyOf(properties);
        }
    }

    /**
     * The hash-chained Object Lock archive ({@code nuna.audit-tables.archive.*}; D23, amended 2026-11-25): where
     * {@code bucket} is set, every record the logs receiver takes is also archived, before the table's filters, in one
     * chain per catalog server run, with a locked checkpoint every {@code checkpoint-interval}. The bucket must have
     * been created with Object Lock, or the writer refuses to start.
     *
     * @param bucket             the Object Lock bucket
     * @param prefix             the key prefix of every chain and checkpoint
     * @param region             the bucket's region
     * @param endpoint           an S3-compatible store's endpoint, reached path-style; none for AWS
     * @param accessKeyId        a static key, with {@code secretAccessKey}; none for the default credential chain
     * @param secretAccessKey    its secret
     * @param caBundle           a PEM file of CAs to trust beside the JVM's, for a store with a private CA
     * @param retention          how long each segment and checkpoint is locked; at least seven years (D23)
     * @param segmentEvents      the most records in one segment
     * @param segmentAge         the longest a record waits before its segment is written
     * @param checkpointInterval how often a checkpoint is written when no segment was; the check reports a checkpoint
     *                           older than twice this as late
     * @param maxWaiting         the most records waiting for their segment before the receiver answers 503
     */
    public record Archive(String bucket, @DefaultValue("audit") String prefix,
                          @DefaultValue("us-east-1") String region, String endpoint, String accessKeyId,
                          String secretAccessKey, java.nio.file.Path caBundle,
                          @DefaultValue("2557d") Duration retention, @DefaultValue("10000") int segmentEvents,
                          @DefaultValue("1m") Duration segmentAge, @DefaultValue("5m") Duration checkpointInterval,
                          @DefaultValue("100000") int maxWaiting) {

        public Archive {
            if ((accessKeyId == null) != (secretAccessKey == null)) {
                throw new IllegalArgumentException("nuna.audit-tables.archive: set access-key-id and"
                        + " secret-access-key together, or neither for the default credential chain");
            }
        }

        /** Whether an archive is configured. */
        public boolean configured() {
            return bucket != null && !bucket.isBlank();
        }

        @Override
        public String toString() {
            return "Archive[bucket=" + bucket + ", prefix=" + prefix + ", region=" + region + ", endpoint=" + endpoint
                    + ", accessKeyId=" + accessKeyId + ", caBundle=" + caBundle + ", retention=" + retention
                    + ", segmentEvents=" + segmentEvents + ", segmentAge=" + segmentAge + ", checkpointInterval="
                    + checkpointInterval + ", maxWaiting=" + maxWaiting + "]";
        }
    }

    /**
     * The OTLP/HTTP logs receiver ({@link OtlpLogsReceiver}): {@code POST /v1/logs} on {@code port}, which keeps the
     * records of the catalog's log {@code nuna.audit}. Rows from it are deduplicated by CloudEvent id ({@link
     * LogWriter}).
     *
     * @param port        the port it listens on, OTLP/HTTP's usual 4318
     * @param token       the shared token every request must carry in {@code X-Nuna-Audit-Token}: at least 32
     *                    characters, letters, digits, {@code -}, {@code _}, {@code .} or {@code ~}, so it travels in
     *                    {@code OTEL_EXPORTER_OTLP_LOGS_HEADERS} unencoded
     * @param dedupWindow how long a record's id is remembered in memory to leave out a second copy of it; a copy whose
     *                    record time is older than this is looked up in the table, in its record's day, before the
     *                    commit, so it is left out however late it comes (backlog 263)
     */
    public record Otlp(@DefaultValue("4318") int port, String token, @DefaultValue("15m") Duration dedupWindow) {

        private static final java.util.regex.Pattern TOKEN = java.util.regex.Pattern.compile("[A-Za-z0-9._~-]{32,}");

        @Override
        public String toString() {
            return "Otlp[port=" + port + ", dedupWindow=" + dedupWindow + "]";
        }

        void requireToken() {
            if (token == null || !TOKEN.matcher(token).matches()) {
                throw new IllegalArgumentException("nuna.audit-tables.otlp.token must be set, at least 32 letters,"
                        + " digits, '-', '_', '.' or '~': the source otlp takes audit records only from requests that"
                        + " carry it in X-Nuna-Audit-Token");
            }
            if (dedupWindow.isNegative() || dedupWindow.isZero()) {
                throw new IllegalArgumentException("nuna.audit-tables.otlp.dedup-window must be positive");
            }
        }
    }
}
