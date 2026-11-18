package nuna.catalog.load;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import nuna.catalog.security.TestIdp;
import nuna.catalog.tests.kafka.TestKafka;
import nuna.catalog.tests.s3.TestS3;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.testcontainers.kafka.KafkaContainer;

/**
 * Everything a catalog server needs besides its database: Kafka for audit (a load that vends credentials
 * waits for Kafka's ack), the run's S3 store ({@link TestS3}) for metadata files, and a token issuer. Servers run as separate processes
 * ({@link ServerProcess}) from the runnable jar, as they are deployed.
 */
final class Stack implements AutoCloseable {

    static final String CATALOG = "m8";
    static final String WRITERS = "m8-writers";
    static final String OPERATORS = "platform-ops";
    /** Numbers each stack's bucket: the run's S3 store is shared, and a DR drill must see only its own tables. */
    private static final AtomicInteger STACKS = new AtomicInteger();
    private static final String PROFILE = "s3";
    private static final String ISSUER = "https://idp.test/m8";
    private static final String AUDIENCE = "nuna-catalog";

    final PostgresCluster postgres;
    private final KafkaContainer kafka;
    private final TestS3 s3;
    private final String bucket;
    private final TestIdp idp;
    private final RSAKey key;
    private final List<ServerProcess> servers = new ArrayList<>();

    Stack(boolean withDr) {
        postgres = new PostgresCluster(withDr);
        kafka = TestKafka.container("384m");
        kafka.start();
        s3 = TestS3.get();
        bucket = s3.bucket("warehouse-" + STACKS.incrementAndGet());
        try {
            idp = new TestIdp(ISSUER, AUDIENCE);
            key = TestIdp.rsaKey("m8");
            idp.publish(key);
        } catch (Exception e) {
            throw new IllegalStateException("the token issuer did not start", e);
        }
    }

    /** A bearer token valid for six hours. */
    String token(String subject, String group) {
        Instant now = Instant.now();
        try {
            return TestIdp.sign(key, JWSAlgorithm.RS256, new JWTClaimsSet.Builder()
                    .issuer(ISSUER).audience(AUDIENCE).subject(subject).claim("groups", List.of(group))
                    .claim("azp", "m8-load")
                    .issueTime(Date.from(now.minusSeconds(5))).expirationTime(Date.from(now.plusSeconds(6 * 3600)))
                    .build());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** A server of this release on the cluster's primary. */
    ServerProcess server(String name) {
        return server(name, Path.of(System.getProperty("m8.server-jar")), postgres.serverUrl());
    }

    /** A server from {@code jar} on {@code jdbcUrl}; the first one to start creates the catalog. */
    ServerProcess server(String name, Path jar, String jdbcUrl) {
        List<String> properties = new ArrayList<>();
        s3.serverProperties(PROFILE, bucket).forEach((key, value) -> properties.add("--" + key + "=" + value));
        properties.addAll(List.of(
                "--spring.datasource.url=" + jdbcUrl,
                "--spring.datasource.username=" + PostgresCluster.USER,
                "--spring.datasource.password=" + PostgresCluster.USER,
                "--spring.datasource.hikari.maximum-pool-size=40",
                "--nuna.storage.catalogs." + CATALOG + ".profile=" + PROFILE,
                "--nuna.storage.catalogs." + CATALOG + ".kms-key-set=test-keys",
                "--nuna.storage.catalogs." + CATALOG + ".legal-entity=Test Entity",
                "--nuna.security.issuers[0].issuer=" + ISSUER,
                "--nuna.security.issuers[0].jwks-uri=" + idp.jwksUri(),
                "--nuna.security.issuers[0].audiences=" + AUDIENCE,
                "--nuna.security.issuers[0].algorithms=RS256",
                "--nuna.security.issuers[0].user-id-claim=sub",
                "--nuna.security.issuers[0].groups-claim=groups",
                "--nuna.security.issuers[0].client-id-claim=azp",
                "--nuna.security.platform-operator-groups=" + OPERATORS,
                "--nuna.audit.kafka-bootstrap-servers=" + kafka.getBootstrapServers(),
                "--logging.level.root=WARN",
                "--logging.level.nuna.audit.fallback=ERROR"));
        ServerProcess server = ServerProcess.start(name, jar, s3.jvmOptions(), properties);
        servers.add(server);
        server.awaitReady(token("m8-writer", WRITERS), CATALOG);
        return server;
    }

    /** Lets the writers' group create and commit anywhere in the catalog. */
    void grantWriters() {
        String catalog = "(SELECT id FROM catalog WHERE name_norm = '" + CATALOG + "')";
        for (String privilege : List.of("create", "modify", "write", "select")) {
            postgres.execute("INSERT INTO grant_record (catalog_id, securable_kind, securable_id, group_name, privilege,"
                    + " granted_by, granted_at) SELECT " + catalog + ", 'CATALOG', " + catalog + ", '" + WRITERS + "', '"
                    + privilege + "', 'm8', now() WHERE NOT EXISTS (SELECT 1 FROM grant_record WHERE group_name = '"
                    + WRITERS + "' AND privilege = '" + privilege + "')");
        }
    }

    @Override
    public void close() {
        servers.forEach(ServerProcess::close);
        idp.close();
        kafka.stop();
        postgres.close();
    }
}
