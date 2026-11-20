package nuna.audittables;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import nuna.catalog.audit.AuditRow;
import nuna.catalog.audit.AuditRows;
import nuna.catalog.audit.EventCodec;
import nuna.catalog.security.TestIdp;
import nuna.catalog.server.CatalogServer;
import nuna.catalog.tests.kafka.TestKafka;
import nuna.catalog.tests.s3.TestS3;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import org.apache.iceberg.CatalogProperties;
import org.apache.iceberg.DataFile;
import org.apache.iceberg.DataFiles;
import org.apache.iceberg.FileFormat;
import org.apache.iceberg.Schema;
import org.apache.iceberg.Table;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.data.IcebergGenerics;
import org.apache.iceberg.data.Record;
import org.apache.iceberg.exceptions.ForbiddenException;
import org.apache.iceberg.io.CloseableIterable;
import org.apache.iceberg.io.PositionOutputStream;
import org.apache.iceberg.rest.RESTCatalog;
import org.apache.iceberg.rest.auth.OAuth2Properties;
import org.apache.iceberg.types.Types;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * The audit table writer (D23, amended 2026-11-20) against a real catalog server on PostgreSQL, Kafka and the run's
 * S3 store ({@code -Ps3}): a row for each kind of request about two commit intervals behind, no gap and no repeat
 * after a writer dies mid-batch, and the audit catalog hidden from everyone but its readers' group.
 *
 * <p>The server declares two catalogs, {@value #LAKE} for data and {@value #AUDIT} for the audit table, both on one
 * storage profile. Grants: data writers create and modify on {@value #LAKE}, data readers select there; the writer's
 * group create and write on {@value #AUDIT}, the auditors select there. The grants are written straight to the
 * database here; a deployment makes them through the grants API.
 */
@Tag("heavy")
class AuditTablesTest {

    private static final String ISSUER = "https://idp.test/audit-tables";
    private static final String AUDIENCE = "nuna-catalog";
    private static final String PROFILE = "s3";
    private static final String LAKE = "lake";
    private static final String AUDIT = "nuna_audit";
    private static final String TOPIC = "nuna.catalog.audit";
    private static final String WRITER = "nuna-audit-tables";
    private static final String WRITERS = "audit-writers";
    private static final String AUDITORS = "auditors";
    private static final String DATA_WRITERS = "data-writers";
    private static final String DATA_READERS = "data-readers";
    private static final String OPERATORS = "platform-ops";
    private static final Duration INTERVAL = Duration.ofSeconds(5);
    private static final Duration PATIENCE = Duration.ofMinutes(1);
    private static final Schema SCHEMA = new Schema(Types.NestedField.required(1, "id", Types.LongType.get()));
    private static final HttpClient HTTP = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

    private static PostgreSQLContainer postgres;
    private static KafkaContainer kafka;
    private static TestIdp idp;
    private static RSAKey key;
    private static ConfigurableApplicationContext server;
    private static URI uri;

    /** This test's own names: its consumer group, its audit namespace, its request ids. */
    private final String run = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    private final List<AutoCloseable> closing = new ArrayList<>();
    private RESTCatalog auditor;

    @BeforeAll
    static void startCatalog() throws Exception {
        postgres = new PostgreSQLContainer(DockerImageName.parse(System.getProperty("postgres.image")));
        postgres.start();
        kafka = TestKafka.container("256m");
        kafka.start();
        idp = new TestIdp(ISSUER, AUDIENCE);
        key = TestIdp.rsaKey("audit-tables-tests");
        idp.publish(key);
        TestS3 s3 = TestS3.get();
        Map<String, Object> properties = new LinkedHashMap<>(s3.serverProperties(PROFILE, s3.bucket("warehouse")));
        properties.put("spring.datasource.url", postgres.getJdbcUrl());
        properties.put("spring.datasource.username", postgres.getUsername());
        properties.put("spring.datasource.password", postgres.getPassword());
        for (String catalog : List.of(LAKE, AUDIT)) {
            properties.put("nuna.storage.catalogs." + catalog + ".profile", PROFILE);
            properties.put("nuna.storage.catalogs." + catalog + ".kms-key-set", "test-keys");
            properties.put("nuna.storage.catalogs." + catalog + ".legal-entity", "Test Entity");
        }
        properties.put("nuna.security.issuers[0].issuer", ISSUER);
        properties.put("nuna.security.issuers[0].jwks-uri", idp.jwksUri().toString());
        properties.put("nuna.security.issuers[0].audiences", AUDIENCE);
        properties.put("nuna.security.issuers[0].algorithms", "RS256");
        properties.put("nuna.security.issuers[0].user-id-claim", "sub");
        properties.put("nuna.security.issuers[0].groups-claim", "groups");
        properties.put("nuna.security.issuers[0].client-id-claim", "azp");
        properties.put("nuna.security.platform-operator-groups", OPERATORS);
        properties.put("nuna.audit.kafka-bootstrap-servers", kafka.getBootstrapServers());
        properties.put("nuna.audit.relay-interval", "100ms");
        // A free port, as an argument: application.yaml's 8181 would win over a builder property.
        server = new SpringApplicationBuilder(CatalogServer.class).properties(properties).run("--server.port=0");
        uri = URI.create("http://localhost:" + server.getEnvironment().getRequiredProperty("local.server.port") + "/");
        JdbcTemplate jdbc = server.getBean(JdbcTemplate.class);
        grant(jdbc, LAKE, DATA_WRITERS, "create", "modify");
        grant(jdbc, LAKE, DATA_READERS, "select");
        grant(jdbc, AUDIT, WRITERS, "create", "write");
        grant(jdbc, AUDIT, AUDITORS, "select");
    }

    private static void grant(JdbcTemplate jdbc, String catalog, String group, String... privileges) {
        UUID id = jdbc.queryForObject("SELECT id FROM catalog WHERE name_norm = ?", UUID.class, catalog);
        for (String privilege : privileges) {
            jdbc.update("INSERT INTO grant_record (catalog_id, securable_kind, securable_id, group_name, privilege,"
                    + " granted_by, granted_at) VALUES (?, 'CATALOG', ?, ?, ?, 'audit-tables-tests', now())", id, id,
                    group, privilege);
        }
    }

    @AfterAll
    static void stopCatalog() {
        if (server != null) {
            server.close();
        }
        if (kafka != null) {
            kafka.stop();
        }
        if (postgres != null) {
            postgres.stop();
        }
        if (idp != null) {
            idp.close();
        }
    }

    @AfterEach
    void closeClients() throws Exception {
        for (AutoCloseable client : closing.reversed()) {
            client.close();
        }
    }

    // ---- callers ----

    private static String token(String subject, String group, Instant expires) {
        Instant now = Instant.now();
        try {
            return TestIdp.sign(key, JWSAlgorithm.RS256, new JWTClaimsSet.Builder().issuer(ISSUER).audience(AUDIENCE)
                    .subject(subject).claim("groups", List.of(group)).claim("azp", subject)
                    .issueTime(Date.from(now.minusSeconds(60))).expirationTime(Date.from(expires)).build());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String token(String subject, String group) {
        return token(subject, group, Instant.now().plus(Duration.ofHours(1)));
    }

    /** An Iceberg client of {@code catalog} on vended credentials, every request carrying {@code requestId}. */
    private RESTCatalog client(String catalog, String token, String requestId) {
        Map<String, String> properties = new HashMap<>();
        properties.put(CatalogProperties.URI, uri.toString());
        properties.put(CatalogProperties.WAREHOUSE_LOCATION, catalog);
        properties.put(OAuth2Properties.TOKEN, token);
        properties.put(OAuth2Properties.TOKEN_REFRESH_ENABLED, "false");
        properties.put(CatalogProperties.FILE_IO_IMPL, "org.apache.iceberg.aws.s3.S3FileIO");
        properties.put("http-client.type", "apache");
        properties.put("header.X-Iceberg-Access-Delegation", "vended-credentials");
        properties.put("header.X-Request-ID", requestId);
        RESTCatalog client = new RESTCatalog();
        client.initialize(requestId, properties);
        closing.add(client);
        return client;
    }

    private HttpResponse<String> get(String path, String token, String requestId) {
        HttpRequest request = HttpRequest.newBuilder(uri.resolve(path)).header("Authorization", "Bearer " + token)
                .header("X-Request-ID", requestId).GET().build();
        try {
            return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /** A small file beneath the table's data location, as a data file of it. */
    private static DataFile dataFile(Table table) {
        String location = table.locationProvider().newDataLocation(UUID.randomUUID() + ".parquet");
        try (PositionOutputStream out = table.io().newOutputFile(location).createOrOverwrite()) {
            out.write("rows".getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return DataFiles.builder(table.spec()).withPath(location).withFormat(FileFormat.PARQUET)
                .withFileSizeInBytes(4).withRecordCount(1).build();
    }

    // ---- the writer ----

    /** A writer of this test's audit table, driven from the test's thread, signed in as the writer's principal. */
    private final class Writer implements AutoCloseable {

        final KafkaConsumer<byte[], byte[]> consumer;
        final TableWriter writer;
        private boolean closed;

        Writer(Duration interval) {
            consumer = new KafkaConsumer<>(AuditTablesApplication.consumerConfig(new AuditTablesSettings.Kafka(
                    kafka.getBootstrapServers(), TOPIC, "audit-tables-" + run, Map.of())), new ByteArrayDeserializer(),
                    new ByteArrayDeserializer());
            RESTCatalog catalog = client(AUDIT, token(WRITER, WRITERS), run + "-writer");
            writer = new TableWriter(consumer, TOPIC, () -> EventsTable.open(catalog, namespace(), TOPIC), WRITER,
                    interval, 100_000, Clock.systemUTC());
            closing.add(this);
        }

        /** Polls until it has read every record the topic holds now. */
        void catchUp() {
            Instant deadline = Instant.now().plus(PATIENCE);
            Map<TopicPartition, Long> end = null;
            while (true) {
                writer.pollOnce(Duration.ofMillis(200));
                Set<TopicPartition> assigned = consumer.assignment();
                if (!assigned.isEmpty() && assigned.size() == consumer.partitionsFor(TOPIC).size()) {
                    if (end == null) {
                        end = consumer.endOffsets(assigned);
                    }
                    if (end.entrySet().stream().allMatch(e -> consumer.position(e.getKey()) >= e.getValue())) {
                        return;
                    }
                }
                assertThat(Instant.now()).as("the writer read the topic").isBefore(deadline);
            }
        }

        /** Polls for {@code duration}. */
        void pollFor(Duration duration) {
            Instant until = Instant.now().plus(duration);
            while (Instant.now().isBefore(until)) {
                writer.pollOnce(Duration.ofMillis(200));
            }
        }

        /** Stops as a crash would: leaves the group, and whatever waits is never written. */
        @Override
        public void close() {
            if (!closed) {
                closed = true;
                writer.close();
            }
        }
    }

    private Namespace namespace() {
        return Namespace.of("audit_" + run);
    }

    // ---- the table and the topic ----

    private Table events() {
        if (auditor == null) {
            auditor = client(AUDIT, token("avery", AUDITORS), run + "-auditor");
        }
        return auditor.loadTable(TableIdentifier.of(namespace(), EventsTable.NAME));
    }

    private List<Record> rows() {
        try (CloseableIterable<Record> records = IcebergGenerics.read(events()).build()) {
            List<Record> all = new ArrayList<>();
            records.forEach(all::add);
            return all;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static List<Record> rows(List<Record> rows, String field, Object value) {
        return rows.stream().filter(r -> Objects.equals(r.getField(field), value)).toList();
    }

    private static Set<Object> types(List<Record> rows) {
        Set<Object> types = new HashSet<>();
        rows.forEach(r -> types.add(r.getField("type")));
        return types;
    }

    /** Each partition's next offset as the table's current snapshot records it. */
    private Map<Integer, Long> committed() {
        return EventsTable.parse(events().currentSnapshot().summary().get(EventsTable.OFFSETS + TOPIC));
    }

    /** The topic's records before {@code upTo}, mapped as the writer maps them. */
    private static List<AuditRow> topic(Map<Integer, Long> upTo) {
        Map<String, Object> config = Map.of(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
                ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed");
        try (KafkaConsumer<byte[], byte[]> reader = new KafkaConsumer<>(config, new ByteArrayDeserializer(),
                new ByteArrayDeserializer())) {
            List<TopicPartition> partitions = reader.partitionsFor(TOPIC).stream()
                    .map(p -> new TopicPartition(TOPIC, p.partition())).toList();
            reader.assign(partitions);
            reader.seekToBeginning(partitions);
            List<AuditRow> rows = new ArrayList<>();
            Instant deadline = Instant.now().plus(PATIENCE);
            Predicate<TopicPartition> behind = p -> reader.position(p) < upTo.getOrDefault(p.partition(), 0L);
            while (partitions.stream().anyMatch(behind)) {
                for (ConsumerRecord<byte[], byte[]> record : reader.poll(Duration.ofMillis(500))) {
                    if (record.offset() < upTo.getOrDefault(record.partition(), 0L)) {
                        rows.add(AuditRows.of(EventCodec.decode(record.value()), record.partition(), record.offset()));
                    }
                }
                assertThat(Instant.now()).as("the topic was read").isBefore(deadline);
            }
            return rows;
        }
    }

    private record Place(int partition, long offset) {
    }

    // ---- tests ----

    @Test
    void aRowForEachKindOfRequestAboutTwoIntervalsBehind() {
        Writer writer = new Writer(INTERVAL);
        writer.catchUp();

        String writing = run + "-writing";
        RESTCatalog lake = client(LAKE, token("dana", DATA_WRITERS), writing);
        Namespace sales = Namespace.of("sales_" + run);
        TableIdentifier orders = TableIdentifier.of(sales, "orders");
        lake.createNamespace(sales);
        Table table = lake.buildTable(orders, SCHEMA).withProperty("format-version", "2").create();
        table.newAppend().appendFile(dataFile(table)).commit();
        lake.loadTable(orders);
        String reading = run + "-reading";
        RESTCatalog reader = client(LAKE, token("rory", DATA_READERS), reading);
        reader.loadTable(orders);
        assertThatThrownBy(() -> reader.dropTable(orders)).isInstanceOf(ForbiddenException.class);
        String unsigned = run + "-expired";
        assertThat(get("v1/config?warehouse=" + LAKE, token("dana", DATA_WRITERS, Instant.now().minusSeconds(120)),
                unsigned).statusCode()).isEqualTo(401);
        lake.dropTable(orders);
        Instant done = Instant.now();

        // Rows are committed an interval after the first of them arrives; one arriving just after a commit waits
        // for the next. Two intervals, and a little for the relay.
        Instant deadline = done.plus(INTERVAL.multipliedBy(2)).plusSeconds(2);
        Set<Object> expected = Set.of("nuna.catalog.namespace", "nuna.catalog.commit", "nuna.catalog.read",
                "nuna.catalog.credential-issue", "nuna.catalog.drop");
        List<Record> rows = List.of();
        while (Instant.now().isBefore(deadline)) {
            writer.pollFor(Duration.ofSeconds(1));
            rows = rows();
            if (types(rows(rows, "request_id", writing)).containsAll(expected)
                    && types(rows(rows, "request_id", reading)).contains("nuna.catalog.denial")
                    && !rows(rows, "request_id", unsigned).isEmpty()) {
                break;
            }
        }

        assertThat(types(rows(rows, "request_id", writing))).containsAll(expected);
        assertThat(rows(rows, "request_id", writing)).allSatisfy(row -> {
            assertThat(row.getField("principal")).isEqualTo("dana");
            assertThat(row.getField("catalog")).isEqualTo(LAKE);
        });
        assertThat(rows(rows, "request_id", writing)).filteredOn(r -> r.getField("type").equals("nuna.catalog.commit"))
                .allSatisfy(row -> assertThat(row.getField("object_path")).isEqualTo(List.of(sales.level(0), "orders")));
        assertThat(rows(rows, "request_id", reading)).filteredOn(r -> r.getField("type").equals("nuna.catalog.denial"))
                .singleElement().satisfies(row -> {
                    assertThat(row.getField("principal")).isEqualTo("rory");
                    assertThat(row.getField("privilege")).isEqualTo("modify");
                    assertThat(row.getField("object_path")).isEqualTo(List.of(sales.level(0), "orders"));
                });
        assertThat(rows(rows, "request_id", unsigned)).singleElement().satisfies(row -> {
            assertThat(row.getField("type")).isEqualTo("nuna.catalog.auth-failure");
            assertThat(row.getField("principal")).isNull();
            assertThat(row.getField("reason")).isNotNull();
        });
        assertThat(rows(rows, "principal", WRITER)).as("the writer's own requests are left out").isEmpty();
    }

    @Test
    void aWriterKilledMidBatchLeavesNoGapAndNoRepeat() {
        RESTCatalog lake = client(LAKE, token("dana", DATA_WRITERS), run + "-setup");
        Namespace sales = Namespace.of("sales_" + run);
        TableIdentifier orders = TableIdentifier.of(sales, "orders");
        lake.createNamespace(sales);
        lake.buildTable(orders, SCHEMA).withProperty("format-version", "2").create();
        RESTCatalog first = client(LAKE, token("dana", DATA_WRITERS), run + "-first");
        RESTCatalog second = client(LAKE, token("dana", DATA_WRITERS), run + "-second");
        RESTCatalog third = client(LAKE, token("dana", DATA_WRITERS), run + "-third");

        // A writer that commits only when told: one batch lands, the next waits in its memory when it dies.
        Writer dying = new Writer(Duration.ofHours(1));
        for (int i = 0; i < 5; i++) {
            first.loadTable(orders);
        }
        dying.catchUp();
        dying.writer.commit();
        for (int i = 0; i < 5; i++) {
            second.loadTable(orders);
        }
        dying.catchUp();
        dying.close();
        List<Record> landed = rows();
        assertThat(rows(landed, "request_id", run + "-first")).isNotEmpty();
        assertThat(rows(landed, "request_id", run + "-second")).as("the batch that died with it").isEmpty();

        Writer next = new Writer(Duration.ofSeconds(1));
        for (int i = 0; i < 5; i++) {
            third.loadTable(orders);
        }
        next.catchUp();
        next.pollFor(Duration.ofSeconds(3));

        List<Record> rows = rows();
        List<Place> written = rows.stream().map(r -> new Place((Integer) r.getField("topic_partition"),
                (Long) r.getField("topic_offset"))).toList();
        assertThat(written).as("no offset written twice").doesNotHaveDuplicates();
        List<Place> expected = topic(committed()).stream().filter(row -> !WRITER.equals(row.principal()))
                .map(row -> new Place(row.topicPartition(), row.topicOffset())).toList();
        assertThat(written).as("every record up to the offsets the table holds, but the writer's own")
                .containsExactlyInAnyOrderElementsOf(expected);
        for (String batch : List.of("-first", "-second", "-third")) {
            assertThat(rows(rows, "request_id", run + batch)).as(batch).isNotEmpty();
        }
    }

    /**
     * D18 (amended 2026-11-20, item 68) hides the audit catalog from a caller that holds nothing in it, as if it did
     * not exist; a platform operator sees its name but not its table; the auditors read the table.
     */
    @Test
    void onlyTheAuditorsFindTheAuditTable() {
        Writer writer = new Writer(INTERVAL);
        writer.catchUp();
        String config = "v1/config?warehouse=" + AUDIT;
        String table = "v1/" + AUDIT + "/namespaces/" + namespace().level(0) + "/tables/" + EventsTable.NAME;

        String dana = token("dana", DATA_WRITERS);
        assertThat(get(config, dana, run + "-dana").statusCode()).isEqualTo(404);
        assertThat(get(table, dana, run + "-dana").statusCode()).isEqualTo(404);
        String olive = token("olive", OPERATORS);
        assertThat(get(config, olive, run + "-olive").statusCode()).isEqualTo(200);
        assertThat(get(table, olive, run + "-olive").statusCode()).isEqualTo(404);
        assertThat(get(table, token("avery", AUDITORS), run + "-avery").statusCode()).isEqualTo(200);
    }
}
