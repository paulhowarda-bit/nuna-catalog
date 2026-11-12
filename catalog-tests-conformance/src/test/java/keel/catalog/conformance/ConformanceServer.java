package keel.catalog.conformance;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import keel.catalog.security.TestIdp;
import keel.catalog.server.CatalogServer;
import keel.catalog.tests.s3.TestS3;

import java.net.URI;
import java.time.Instant;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * One catalog server on PostgreSQL and the run's S3 store ({@link TestS3}, chosen by {@code -Ps3}) for the whole
 * test JVM, with the RCK's warehouse seeded. Clients reach the same store through {@link #clientStorage()}, and authenticate with
 * {@link #token()}: a local test issuer's token for a group that holds create and modify on the warehouse (M5).
 * {@link #stewardToken()} is the same issuer's token for a separate group that holds manage-tags there.
 */
final class ConformanceServer {

    /** The warehouse name the RCK client asks for by default. */
    static final String WAREHOUSE = "rck_warehouse";

    private static final String PROFILE = "s3";
    private static final String ISSUER = "https://idp.test/conformance";
    private static final String AUDIENCE = "keel-catalog";
    private static final String GROUP = "conformance-clients";
    /** Tag stewards: manage-tags on the warehouse, which places legal holds (D24). D18 keeps it from the writers' group. */
    private static final String STEWARDS = "conformance-stewards";

    private static URI uri;
    private static String token;
    private static String stewardToken;

    private ConformanceServer() {
    }

    static synchronized URI start() {
        if (uri != null) {
            return uri;
        }
        PostgreSQLContainer postgres =
                new PostgreSQLContainer(DockerImageName.parse(System.getProperty("postgres.image")));
        postgres.start();
        TestS3 s3 = TestS3.get();
        // Audit is on (M7): every load hands out credentials, and a credential issue needs Kafka's ack.
        KafkaContainer kafka = new KafkaContainer(DockerImageName.parse(System.getProperty("kafka.image")))
                .withEnv("KAFKA_HEAP_OPTS", "-Xms256m -Xmx256m");
        kafka.start();
        TestIdp idp;
        RSAKey key;
        try {
            idp = new TestIdp(ISSUER, AUDIENCE);
            key = TestIdp.rsaKey("conformance");
            idp.publish(key);
            Instant now = Instant.now();
            token = TestIdp.sign(key, JWSAlgorithm.RS256, new JWTClaimsSet.Builder()
                    .issuer(ISSUER).audience(AUDIENCE).subject("conformance").claim("groups", List.of(GROUP))
                    .claim("azp", "conformance-clients")
                    .issueTime(Date.from(now.minusSeconds(5))).expirationTime(Date.from(now.plusSeconds(6 * 3600)))
                    .build());
            stewardToken = TestIdp.sign(key, JWSAlgorithm.RS256, new JWTClaimsSet.Builder()
                    .issuer(ISSUER).audience(AUDIENCE).subject("steward").claim("groups", List.of(STEWARDS))
                    .claim("azp", "conformance-stewards")
                    .issueTime(Date.from(now.minusSeconds(5))).expirationTime(Date.from(now.plusSeconds(6 * 3600)))
                    .build());
        } catch (Exception e) {
            throw new IllegalStateException("the conformance token issuer did not start", e);
        }
        Map<String, Object> storage = new HashMap<>(s3.serverProperties(PROFILE, bucket()));
        ConfigurableApplicationContext server = new SpringApplicationBuilder(CatalogServer.class)
                .properties(storage)
                .properties(
                        "spring.datasource.url=" + postgres.getJdbcUrl(),
                        "spring.datasource.username=" + postgres.getUsername(),
                        "spring.datasource.password=" + postgres.getPassword(),
                        "keel.storage.catalogs." + WAREHOUSE + ".profile=" + PROFILE,
                        "keel.storage.catalogs." + WAREHOUSE + ".kms-key-set=test-keys",
                        "keel.storage.catalogs." + WAREHOUSE + ".legal-entity=Test Entity",
                        "keel.security.issuers[0].issuer=" + ISSUER,
                        "keel.security.issuers[0].jwks-uri=" + idp.jwksUri(),
                        "keel.security.issuers[0].audiences=" + AUDIENCE,
                        "keel.security.issuers[0].algorithms=RS256",
                        "keel.security.issuers[0].user-id-claim=sub",
                        "keel.security.issuers[0].groups-claim=groups",
                        "keel.security.issuers[0].client-id-claim=azp",
                        "keel.audit.kafka-bootstrap-servers=" + kafka.getBootstrapServers())
                // A free port, as an argument: builder properties are only defaults, and application.yaml's 8181
                // would win over them, clashing with any catalog already running here (the demo).
                .run("--server.port=0");
        JdbcTemplate jdbc = server.getBean(JdbcTemplate.class);
        UUID catalog = jdbc.queryForObject("SELECT id FROM catalog WHERE name_norm = ?", UUID.class, WAREHOUSE);
        for (String privilege : List.of("create", "modify")) {
            jdbc.update("INSERT INTO grant_record (catalog_id, securable_kind, securable_id, group_name, privilege,"
                    + " granted_by, granted_at) VALUES (?, 'CATALOG', ?, ?, ?, 'conformance', now())",
                    catalog, catalog, GROUP, privilege);
        }
        jdbc.update("INSERT INTO grant_record (catalog_id, securable_kind, securable_id, group_name, privilege,"
                + " granted_by, granted_at) VALUES (?, 'CATALOG', ?, ?, 'manage-tags', 'conformance', now())",
                catalog, catalog, STEWARDS);
        int port = server.getEnvironment().getRequiredProperty("local.server.port", Integer.class);
        uri = URI.create("http://localhost:" + port + "/");
        return uri;
    }

    /** The bearer token every conformance client sends (the Iceberg client's {@code token} property). */
    static String token() {
        start();
        return token;
    }

    /** The bearer token of a tag steward, for the management API's legal holds; it holds no data access. */
    static String stewardToken() {
        start();
        return stewardToken;
    }

    /** The bucket the server's tables live in: {@code warehouse}, or on AWS this run's name for it. */
    static String bucket() {
        return TestS3.get().bucket("warehouse");
    }

    /**
     * Catalog properties that give a client's S3FileIO direct access to the run's store, for clients that do
     * not take vended credentials. On AWS the SDK's default chain finds the CLI profile {@code TestS3} set.
     */
    static Map<String, String> clientStorage() {
        start();
        TestS3 s3 = TestS3.get();
        Map<String, String> properties = new HashMap<>(Map.of(
                "io-impl", "org.apache.iceberg.aws.s3.S3FileIO",
                "client.region", TestS3.REGION,
                "http-client.type", "apache"));
        if (s3.kind != TestS3.Kind.AWS) {
            properties.put("s3.endpoint", s3.endpoint.toString());
            properties.put("s3.path-style-access", "true");
        }
        s3.keys().ifPresent(keys -> {
            properties.put("s3.access-key-id", keys.accessKeyId());
            properties.put("s3.secret-access-key", keys.secretAccessKey());
        });
        return Map.copyOf(properties);
    }
}
