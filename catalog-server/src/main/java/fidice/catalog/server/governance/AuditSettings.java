package fidice.catalog.server.governance;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Audit delivery ({@code fidice.audit.*}, D23). Without {@code kafka-bootstrap-servers} audit is
 * unavailable, as in a Kafka outage: changes wait in the outbox, reads and denials go to the
 * fallback log, and no credential is handed out.
 *
 * @param kafkaBootstrapServers the Kafka cluster; production always sets it
 * @param auditTopic            every audit record: the SIEM's feed and the archive's source
 * @param changeTopic           the CloudEvents change stream: changes to namespaces, tables and views
 * @param deliveryTimeout       how long a synchronous record or relayed event waits for {@code acks=all}
 * @param requestTimeout        Kafka's per-request timeout, below the delivery timeout
 * @param probeInterval         after a failed record that does not block, how long others skip Kafka and go
 *                              to the fallback log before Kafka is tried again
 * @param relayInterval         how often the outbox relay looks for new entries
 * @param relayLease            the outbox lease a relay takes per batch; longer than a batch takes
 * @param relayBatch            the most entries one batch relays
 * @param archive               the hash-chained Object Lock archive
 */
@ConfigurationProperties("fidice.audit")
public record AuditSettings(
        String kafkaBootstrapServers,
        @DefaultValue("fidice.catalog.audit") String auditTopic,
        @DefaultValue("fidice.catalog.changes") String changeTopic,
        @DefaultValue("5s") Duration deliveryTimeout,
        @DefaultValue("2s") Duration requestTimeout,
        @DefaultValue("10s") Duration probeInterval,
        @DefaultValue("250ms") Duration relayInterval,
        @DefaultValue("30s") Duration relayLease,
        @DefaultValue("500") int relayBatch,
        @DefaultValue Archive archive) {

    /**
     * The archive ({@code fidice.audit.archive.*}). It runs where {@code bucket} is set, on the store of
     * storage profile {@code profile} ({@code fidice.storage.profiles.*}); the bucket must have been
     * created with Object Lock, or the server refuses to start.
     *
     * @param bucket          the Object Lock bucket
     * @param profile         the storage profile whose endpoint, region and keys reach the bucket
     * @param prefix          the key prefix of every chain
     * @param retention       how long each segment is locked; at least five years (D23)
     * @param segmentEvents   the most records in one segment
     * @param segmentAge      the longest a record waits before its segment is written
     * @param consumerGroup   the Kafka consumer group the archive consumers share
     */
    public record Archive(
            String bucket,
            String profile,
            @DefaultValue("audit") String prefix,
            @DefaultValue("1827d") Duration retention,
            @DefaultValue("10000") int segmentEvents,
            @DefaultValue("5m") Duration segmentAge,
            @DefaultValue("fidice-audit-archive") String consumerGroup) {
    }

    boolean kafkaConfigured() {
        return kafkaBootstrapServers != null && !kafkaBootstrapServers.isBlank();
    }
}
