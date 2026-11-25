package nuna.catalog.server.governance;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Audit delivery ({@code nuna.audit.*}, D23, amended 2026-11-23). Every audit record goes to the log
 * {@code nuna.audit} ({@link AuditTrail}); Kafka is optional. Without {@code kafka-bootstrap-servers} the server
 * serves and vends as with it, the outbox relay writes changes to the log alone, and there is no audit topic, change
 * stream or archive. With it, records also go to the audit topic and the change stream, best effort.
 *
 * @param kafkaBootstrapServers the Kafka cluster for the audit topic, the change stream and the archive; optional
 * @param auditTopic            every audit record, best effort: the SIEM's feed and the archive's source
 * @param changeTopic           the CloudEvents change stream: changes to namespaces, tables and views
 * @param deliveryTimeout       how long a synchronous record or relayed event waits for {@code acks=all}
 * @param requestTimeout        Kafka's per-request timeout, below the delivery timeout
 * @param probeInterval         after Kafka failed to take a record, how long every record skips Kafka and goes
 *                              to the log alone before Kafka is tried again
 * @param relayInterval         how often the outbox relay looks for new entries
 * @param relayLease            the outbox lease a relay takes per batch; longer than a batch takes
 * @param relayBatch            the most entries one batch relays
 * @param archive               the hash-chained Object Lock archive
 */
@ConfigurationProperties("nuna.audit")
public record AuditSettings(
        String kafkaBootstrapServers,
        @DefaultValue("nuna.catalog.audit") String auditTopic,
        @DefaultValue("nuna.catalog.changes") String changeTopic,
        @DefaultValue("5s") Duration deliveryTimeout,
        @DefaultValue("2s") Duration requestTimeout,
        @DefaultValue("10s") Duration probeInterval,
        @DefaultValue("250ms") Duration relayInterval,
        @DefaultValue("30s") Duration relayLease,
        @DefaultValue("500") int relayBatch,
        @DefaultValue Archive archive) {

    /**
     * The archive ({@code nuna.audit.archive.*}). It runs where {@code bucket} is set, on the store of
     * storage profile {@code profile} ({@code nuna.storage.profiles.*}); the bucket must have been
     * created with Object Lock, or the server refuses to start. It reads the audit topic, so it needs
     * {@code kafka-bootstrap-servers}, and holds only what Kafka took.
     *
     * @param bucket          the Object Lock bucket
     * @param profile         the storage profile whose endpoint, region and keys reach the bucket
     * @param prefix          the key prefix of every chain
     * @param retention       how long each segment is locked; at least seven years (D23)
     * @param segmentEvents   the most records in one segment
     * @param segmentAge      the longest a record waits before its segment is written
     * @param consumerGroup   the Kafka consumer group the archive consumers share
     */
    public record Archive(
            String bucket,
            String profile,
            @DefaultValue("audit") String prefix,
            @DefaultValue("2557d") Duration retention,
            @DefaultValue("10000") int segmentEvents,
            @DefaultValue("5m") Duration segmentAge,
            @DefaultValue("nuna-audit-archive") String consumerGroup) {
    }

    boolean kafkaConfigured() {
        return kafkaBootstrapServers != null && !kafkaBootstrapServers.isBlank();
    }
}
