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
 * credentials the catalog vends it.
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
 * @param kafka            the audit topic
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
        @DefaultValue Kafka kafka) {

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
            if (bootstrapServers == null || bootstrapServers.isBlank()) {
                throw new IllegalArgumentException("nuna.audit-tables.kafka.bootstrap-servers names no Kafka cluster");
            }
            Set<String> overridden = new TreeSet<>(properties.keySet());
            overridden.retainAll(OWNED);
            if (!overridden.isEmpty()) {
                throw new IllegalArgumentException("nuna.audit-tables.kafka.properties may not set " + overridden);
            }
            properties = Map.copyOf(properties);
        }
    }
}
