package nuna.audittables;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/** The writer's settings as Spring binds them, and the Iceberg and Kafka client settings it makes of them. */
class AuditTablesSettingsTest {

    /** Sample secrets, made per run so no credential-shaped literal sits in the source. */
    private static final String SECRET = "cs-" + UUID.randomUUID();
    private static final String ARCHIVE_SECRET = "sk-" + UUID.randomUUID();

    private static Map<String, String> required() {
        Map<String, String> properties = new HashMap<>();
        properties.put("nuna.audit-tables.catalog-uri", "http://nuna-catalog:8181/");
        properties.put("nuna.audit-tables.principal", "svc-audit-tables");
        properties.put("nuna.audit-tables.token.endpoint", "https://idp.example/oauth2/token");
        properties.put("nuna.audit-tables.token.client-id", "audit-tables");
        properties.put("nuna.audit-tables.token.client-secret", SECRET);
        properties.put("nuna.audit-tables.kafka.bootstrap-servers", "kafka:9092");
        return properties;
    }

    private static AuditTablesSettings bind(Map<String, String> properties) {
        return new Binder(new MapConfigurationPropertySource(properties)).bind("nuna.audit-tables",
                AuditTablesSettings.class).get();
    }

    private static Throwable rootCause(Map<String, String> properties) {
        Throwable thrown = Assertions.catchThrowable(() -> bind(properties));
        assertThat(thrown).isNotNull();
        while (thrown.getCause() != null) {
            thrown = thrown.getCause();
        }
        return thrown;
    }

    @Test
    void defaultsToTheAuditCatalogAndTheAuditTopic() {
        AuditTablesSettings settings = bind(required());

        assertThat(settings.catalog()).isEqualTo("nuna_audit");
        assertThat(settings.namespace()).isEqualTo("audit");
        assertThat(settings.commitInterval()).isEqualTo(Duration.ofMinutes(1));
        assertThat(settings.maxRows()).isEqualTo(100_000);
        assertThat(settings.kafka().topic()).isEqualTo("nuna.catalog.audit");
        assertThat(settings.kafka().group()).isEqualTo("nuna-audit-tables");
        assertThat(settings.source()).isEqualTo(AuditTablesSettings.Source.KAFKA);
    }

    @Test
    void theSourceKafkaNeedsAKafkaCluster() {
        Map<String, String> properties = required();
        properties.remove("nuna.audit-tables.kafka.bootstrap-servers");

        assertThat(rootCause(properties)).hasMessageContaining("nuna.audit-tables.kafka.bootstrap-servers");
    }

    @Test
    void theSourceOtlpListensOn4318AndNeedsNoKafka() {
        Map<String, String> properties = required();
        properties.remove("nuna.audit-tables.kafka.bootstrap-servers");
        properties.put("nuna.audit-tables.source", "otlp");
        properties.put("nuna.audit-tables.otlp.token", "a".repeat(32));

        AuditTablesSettings settings = bind(properties);

        assertThat(settings.source()).isEqualTo(AuditTablesSettings.Source.OTLP);
        assertThat(settings.otlp().port()).isEqualTo(4318);
        assertThat(settings.otlp().dedupWindow()).isEqualTo(Duration.ofMinutes(15));
        assertThat(settings.otlp().toString()).doesNotContain("a".repeat(32));
    }

    @Test
    void anArchiveIsFedFromTheLogLockedSevenYearsAndKeepsItsSecretOutOfItsName() {
        Map<String, String> properties = required();
        properties.put("nuna.audit-tables.source", "otlp");
        properties.put("nuna.audit-tables.otlp.token", "a".repeat(32));
        properties.put("nuna.audit-tables.archive.bucket", "nuna-audit-archive");
        properties.put("nuna.audit-tables.archive.access-key-id", "AKIAEXAMPLE");
        properties.put("nuna.audit-tables.archive.secret-access-key", ARCHIVE_SECRET);

        AuditTablesSettings.Archive archive = bind(properties).archive();

        assertThat(archive.configured()).isTrue();
        assertThat(archive.prefix()).isEqualTo("audit");
        assertThat(archive.retention()).isEqualTo(Duration.ofDays(2557));
        assertThat(archive.segmentAge()).isEqualTo(Duration.ofMinutes(1));
        assertThat(archive.checkpointInterval()).isEqualTo(Duration.ofMinutes(5));
        assertThat(archive.toString()).doesNotContain(ARCHIVE_SECRET);
        assertThat(bind(required()).archive().configured()).as("none unless a bucket is named").isFalse();
    }

    @Test
    void anArchiveNeedsTheSourceOtlpAndBothHalvesOfAKey() {
        Map<String, String> kafka = required();
        kafka.put("nuna.audit-tables.archive.bucket", "nuna-audit-archive");
        assertThat(rootCause(kafka)).hasMessageContaining("source otlp");

        Map<String, String> halfAKey = required();
        halfAKey.put("nuna.audit-tables.source", "otlp");
        halfAKey.put("nuna.audit-tables.otlp.token", "a".repeat(32));
        halfAKey.put("nuna.audit-tables.archive.bucket", "nuna-audit-archive");
        halfAKey.put("nuna.audit-tables.archive.access-key-id", "AKIAEXAMPLE");
        assertThat(rootCause(halfAKey)).hasMessageContaining("secret-access-key");
    }

    @Test
    void theSourceOtlpNeedsALongToken() {
        Map<String, String> properties = required();
        properties.put("nuna.audit-tables.source", "otlp");

        assertThat(rootCause(properties)).hasMessageContaining("nuna.audit-tables.otlp.token");
        properties.put("nuna.audit-tables.otlp.token", "too-short");
        assertThat(rootCause(properties)).hasMessageContaining("nuna.audit-tables.otlp.token");
        properties.put("nuna.audit-tables.otlp.token", "a".repeat(31) + " ");
        assertThat(rootCause(properties)).hasMessageContaining("nuna.audit-tables.otlp.token");
    }

    @Test
    void signsInWithClientCredentialsAndWritesWithVendedCredentials() {
        Map<String, String> properties = required();
        properties.put("nuna.audit-tables.token.audience", "nuna-catalog");
        properties.put("nuna.audit-tables.client-properties.[s3.endpoint]", "http://silo:8333");

        Map<String, String> client = AuditTablesApplication.clientProperties(bind(properties));

        assertThat(client).containsEntry("uri", "http://nuna-catalog:8181/")
                .containsEntry("warehouse", "nuna_audit")
                .containsEntry("credential", "audit-tables:" + SECRET)
                .containsEntry("oauth2-server-uri", "https://idp.example/oauth2/token")
                .containsEntry("token-exchange-enabled", "false")
                .containsEntry("audience", "nuna-catalog")
                .containsEntry("header.X-Iceberg-Access-Delegation", "vended-credentials")
                .containsEntry("s3.endpoint", "http://silo:8333")
                .doesNotContainKey("scope");
    }

    @Test
    void ownsTheConsumerSettingsThatKeepItsOffsetsInTheTable() {
        Map<String, String> properties = required();
        properties.put("nuna.audit-tables.kafka.properties.[security.protocol]", "SSL");

        Map<String, Object> consumer = AuditTablesApplication.consumerConfig(bind(properties).kafka());

        assertThat(consumer).containsEntry(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false")
                .containsEntry(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
                .containsEntry(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed")
                .containsEntry(ConsumerConfig.GROUP_ID_CONFIG, "nuna-audit-tables")
                .containsEntry("security.protocol", "SSL");

        properties.put("nuna.audit-tables.kafka.properties.[enable.auto.commit]", "true");
        assertThat(rootCause(properties)).hasMessageContaining("may not set [enable.auto.commit]");
    }

    @Test
    void needsItsOwnPrincipalToLeaveItsRecordsOut() {
        Map<String, String> properties = required();
        properties.remove("nuna.audit-tables.principal");

        assertThat(rootCause(properties)).hasMessageContaining("nuna.audit-tables.principal");
    }

    @Test
    void needsAClientSecretToSignIn() {
        Map<String, String> properties = required();
        properties.remove("nuna.audit-tables.token.client-secret");

        assertThatThrownBy(() -> bind(properties)).rootCause().hasMessageContaining("client-credentials");
    }
}
