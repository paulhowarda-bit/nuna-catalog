package fidice.catalog.server.governance;

import static fidice.catalog.server.governance.Api.expect;
import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import fidice.catalog.audit.CatalogEvent;
import fidice.catalog.audit.EventType;
import fidice.catalog.server.IssuerForTests;
import fidice.catalog.server.SiloForTests;
import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The M7 gate, route by route: every route that changes the catalog writes its audit record, and the
 * relay delivers it to the audit topic; and every mutating management route that refuses a caller
 * records a Denial (D18). The routes are read from the server's own mappings, so a new mutating route
 * without a case here fails the test until it gets one.
 */
@GovernanceServer
class MutatingRoutesAuditTest {

    /** POST routes that change nothing in the catalog, and why. */
    private static final Map<String, String> NOT_MUTATING = Map.of(
            "POST /v1/{prefix}/namespaces/{namespace}/tables/{table}/metrics", "forwarded to telemetry, never stored",
            "POST /management/v1/catalogs/{catalog}/grants/check", "the caller's own batch check: stores nothing,"
                    + " and M5 does not audit its answers");

    private static final String M = "/management/v1/catalogs/{catalog}";
    private static final Map<String, String> LEVELS = Map.of(
            "", "catalog",
            "/namespaces/{namespace}", "namespace",
            "/namespaces/{namespace}/tables/{table}", "table",
            "/namespaces/{namespace}/views/{view}", "view");

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping mapping;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private GovernanceServer.Broker broker;

    @Autowired
    private GovernanceServer.TestClock clock;

    @Autowired
    private SiloForTests.Silo silo;

    @Value("${local.server.port}")
    private int port;

    private Api api;

    @BeforeEach
    void connect() {
        api = new Api(port, jdbc, broker.bootstrapServers(), clock);
    }

    @AfterEach
    void disconnect() {
        api.close();
    }

    @Test
    void everyMutatingRouteEmitsItsAuditRecord() {
        Set<String> mutating = new TreeSet<>();
        mapping.getHandlerMethods().forEach((info, method) -> {
            for (String path : info.getPatternValues()) {
                for (RequestMethod m : info.getMethodsCondition().getMethods()) {
                    if (m == RequestMethod.POST || m == RequestMethod.PUT || m == RequestMethod.DELETE
                            || m == RequestMethod.PATCH) {
                        mutating.add(m.name() + " " + path);
                    }
                }
            }
        });
        mutating.removeAll(NOT_MUTATING.keySet());

        Map<String, Function<String, EventType>> cases = cases();
        assertThat(cases.keySet()).as("a case for every mutating route").containsExactlyInAnyOrderElementsOf(mutating);

        cases.forEach((route, run) -> {
            String requestId = "m7-gate-" + UUID.randomUUID();
            EventType expected = run.apply(requestId);
            // A create or commit also vends credentials under the same request id; that record comes first.
            CatalogEvent record = api.awaitRecord(json -> json.contains("\"" + requestId + "\"")
                    && json.contains("\"type\":\"" + expected.typeName() + "\""));
            assertThat(record.data().type()).as(route).isEqualTo(expected);
        });
    }

    @Test
    void everyMutatingManagementRouteAuditsItsRefusals() {
        Set<String> management = new TreeSet<>();
        mapping.getHandlerMethods().forEach((info, method) -> {
            for (String path : info.getPatternValues()) {
                for (RequestMethod m : info.getMethodsCondition().getMethods()) {
                    if (path.startsWith("/management/") && (m == RequestMethod.POST || m == RequestMethod.PUT
                            || m == RequestMethod.DELETE || m == RequestMethod.PATCH)) {
                        management.add(m.name() + " " + path);
                    }
                }
            }
        });
        management.removeAll(NOT_MUTATING.keySet());

        Map<String, Function<String, HttpResponse<String>>> cases = refusals();
        assertThat(cases.keySet()).as("a refusal case for every mutating management route")
                .containsExactlyInAnyOrderElementsOf(management);

        cases.forEach((route, run) -> {
            String requestId = "m7-refused-" + UUID.randomUUID();
            Api.expectError(run.apply(requestId), 403, "ForbiddenException");
            CatalogEvent record = api.awaitRequest(requestId);
            assertThat(record.data().type()).as(route).isEqualTo(EventType.DENIAL);
        });
    }

    /**
     * Each case sends the route once, with the request id, as a signed-in caller that may not call it: the
     * tester holds create and modify on the catalog, the steward manage-tags.
     */
    private Map<String, Function<String, HttpResponse<String>>> refusals() {
        Map<String, Function<String, HttpResponse<String>>> cases = new LinkedHashMap<>();
        LEVELS.forEach((level, kind) -> {
            cases.put("PUT " + M + level + "/attributes/{attribute}", id -> api.manage("PUT",
                    target(kind) + "/attributes/classification", "{\"value\":\"x\",\"provenance\":\"p\"}", rid(id)));
            cases.put("DELETE " + M + level + "/attributes/{attribute}", id -> api.manage("DELETE",
                    target(kind) + "/attributes/owner", null, rid(id)));
            cases.put("POST " + M + level + "/holds", id -> api.manage("POST", target(kind) + "/holds",
                    "{\"reason\":\"case 3\"}", rid(id)));
            cases.put("DELETE " + M + level + "/holds/{holdId}", id -> api.manage("DELETE",
                    target(kind) + "/holds/" + UUID.randomUUID(), null, rid(id)));
            cases.put("PUT " + M + level + "/maintenance-policy", id -> api.manage("PUT",
                    target(kind) + "/maintenance-policy", "{\"compaction.enabled\":true}", rid(id, api.steward())));
            cases.put("DELETE " + M + level + "/maintenance-policy", id -> api.manage("DELETE",
                    target(kind) + "/maintenance-policy", null, rid(id, api.steward())));
            cases.put("PUT " + M + level + "/dr-freeze", id -> api.manage("PUT", target(kind) + "/dr-freeze",
                    "{\"reason\":\"drill\"}", rid(id)));
            cases.put("DELETE " + M + level + "/dr-freeze", id -> api.manage("DELETE", target(kind) + "/dr-freeze",
                    null, rid(id)));
        });
        cases.put("POST " + M + "/grants", id -> api.manage("POST", "/grants", grant(api.namespace()), rid(id)));
        cases.put("POST " + M + "/grants/revoke", id -> api.manage("POST", "/grants/revoke", grant(api.namespace()),
                rid(id)));
        cases.put("PUT /management/v1/engines", id -> api.send("PUT", "/management/v1/engines",
                engine("m7-refused-" + Api.suffix()), rid(id)));
        cases.put("DELETE /management/v1/engines", id -> api.send("DELETE", "/management/v1/engines?issuer="
                + URLEncoder.encode(IssuerForTests.ISSUER, StandardCharsets.UTF_8) + "&client-id=" + Api.ENGINE_CLIENT,
                null, rid(id)));
        cases.put("POST " + M + "/objects/{id}/undrop", id -> api.manage("POST", "/objects/" + dropped() + "/undrop",
                null, rid(id, api.steward())));
        cases.put("POST " + M + "/objects/{id}/purge", id -> api.manage("POST", "/objects/" + dropped() + "/purge",
                null, rid(id)));
        return cases;
    }

    /** Each case makes its fixtures, sends the route once with the request id, and names the record it expects. */
    private Map<String, Function<String, EventType>> cases() {
        Map<String, Function<String, EventType>> cases = new LinkedHashMap<>();
        cases.put("POST /v1/{prefix}/namespaces", id -> {
            expect(api.rest("POST", "/namespaces", "{\"namespace\":[\"ns_" + Api.suffix() + "\"]}", rid(id)), 200);
            return EventType.NAMESPACE_CHANGE;
        });
        cases.put("DELETE /v1/{prefix}/namespaces/{namespace}", id -> {
            expect(api.rest("DELETE", "/namespaces/" + api.namespace(), null, rid(id)), 204);
            return EventType.NAMESPACE_CHANGE;
        });
        cases.put("POST /v1/{prefix}/namespaces/{namespace}/properties", id -> {
            expect(api.rest("POST", "/namespaces/" + api.namespace() + "/properties",
                    "{\"updates\":{\"owner\":\"desk\"}}", rid(id)), 200);
            return EventType.NAMESPACE_CHANGE;
        });
        cases.put("POST /v1/{prefix}/namespaces/{namespace}/tables", id -> {
            expect(api.rest("POST", "/namespaces/" + api.namespace() + "/tables", Api.TABLE.formatted("t"), rid(id)), 200);
            return EventType.COMMIT;
        });
        cases.put("POST /v1/{prefix}/namespaces/{namespace}/tables/{table}", id -> {
            String ns = withTable();
            expect(api.rest("POST", "/namespaces/" + ns + "/tables/t", Api.SET_PROPERTY.formatted("1"), rid(id)), 200);
            return EventType.COMMIT;
        });
        cases.put("DELETE /v1/{prefix}/namespaces/{namespace}/tables/{table}", id -> {
            expect(api.rest("DELETE", "/namespaces/" + withTable() + "/tables/t", null, rid(id)), 204);
            return EventType.DROP;
        });
        cases.put("POST /v1/{prefix}/tables/rename", id -> {
            String ns = withTable();
            expect(api.rest("POST", "/tables/rename", rename(ns, "t", "t2"), rid(id)), 204);
            return EventType.RENAME;
        });
        cases.put("POST /v1/{prefix}/namespaces/{namespace}/register", id -> {
            String ns = api.namespace();
            String file = copiedMetadataWithNewUuid(api.table(ns, "source"));
            expect(api.rest("POST", "/namespaces/" + ns + "/register",
                    "{\"name\":\"registered\",\"metadata-location\":\"" + file + "\"}", rid(id)), 200);
            return EventType.COMMIT;
        });
        cases.put("POST /v1/{prefix}/namespaces/{namespace}/views", id -> {
            expect(api.rest("POST", "/namespaces/" + api.namespace() + "/views",
                    Api.VIEW.formatted("v", System.currentTimeMillis()), rid(id)), 200);
            return EventType.COMMIT;
        });
        cases.put("POST /v1/{prefix}/namespaces/{namespace}/views/{view}", id -> {
            String ns = withView();
            expect(api.rest("POST", "/namespaces/" + ns + "/views/v", Api.SET_PROPERTY.formatted("1"), rid(id)), 200);
            return EventType.COMMIT;
        });
        cases.put("DELETE /v1/{prefix}/namespaces/{namespace}/views/{view}", id -> {
            expect(api.rest("DELETE", "/namespaces/" + withView() + "/views/v", null, rid(id)), 204);
            return EventType.DROP;
        });
        cases.put("POST /v1/{prefix}/views/rename", id -> {
            String ns = withView();
            expect(api.rest("POST", "/views/rename", rename(ns, "v", "v2"), rid(id)), 204);
            return EventType.RENAME;
        });

        LEVELS.forEach((level, kind) -> {
            cases.put("PUT " + M + level + "/attributes/{attribute}", id -> {
                expect(api.manage("PUT", target(kind) + "/attributes/classification",
                        "{\"value\":\"confidential\",\"provenance\":\"collibra:asset/1\"}", rid(id, api.steward())), 200);
                return EventType.GOVERNANCE_CHANGE;
            });
            cases.put("DELETE " + M + level + "/attributes/{attribute}", id -> {
                String target = target(kind);
                expect(api.manage("PUT", target + "/attributes/owner", "{\"value\":\"desk\",\"provenance\":\"hr\"}",
                        "Authorization", api.steward()), 200);
                expect(api.manage("DELETE", target + "/attributes/owner", null, rid(id, api.steward())), 204);
                return EventType.GOVERNANCE_CHANGE;
            });
            cases.put("POST " + M + level + "/holds", id -> {
                String target = target(kind);
                HttpResponse<String> placed = expect(api.manage("POST", target + "/holds",
                        "{\"reason\":\"case 1\"}", rid(id, api.steward())), 201);
                expect(api.manage("DELETE", target + "/holds/" + JsonPath.read(placed.body(), "$.id"), null,
                        "Authorization", api.steward()), 204);
                return EventType.HOLD_CHANGE;
            });
            cases.put("DELETE " + M + level + "/holds/{holdId}", id -> {
                String target = target(kind);
                HttpResponse<String> placed = expect(api.manage("POST", target + "/holds", "{\"reason\":\"case 2\"}",
                        "Authorization", api.steward()), 201);
                expect(api.manage("DELETE", target + "/holds/" + JsonPath.read(placed.body(), "$.id"), null,
                        rid(id, api.steward())), 204);
                return EventType.HOLD_CHANGE;
            });
            cases.put("PUT " + M + level + "/maintenance-policy", id -> {
                String target = target(kind);
                expect(api.manage("PUT", target + "/maintenance-policy", "{\"compaction.enabled\":true}", rid(id)), 200);
                expect(api.manage("DELETE", target + "/maintenance-policy", null), 204);
                return EventType.GOVERNANCE_CHANGE;
            });
            cases.put("DELETE " + M + level + "/maintenance-policy", id -> {
                String target = target(kind);
                expect(api.manage("PUT", target + "/maintenance-policy", "{\"compaction.enabled\":true}"), 200);
                expect(api.manage("DELETE", target + "/maintenance-policy", null, rid(id)), 204);
                return EventType.GOVERNANCE_CHANGE;
            });
            cases.put("PUT " + M + level + "/dr-freeze", id -> {
                String target = target(kind);
                expect(api.manage("PUT", target + "/dr-freeze", "{\"reason\":\"drill\"}", rid(id, api.operator())), 200);
                expect(api.manage("DELETE", target + "/dr-freeze", null, "Authorization", api.operator()), 204);
                return EventType.GOVERNANCE_CHANGE;
            });
            cases.put("DELETE " + M + level + "/dr-freeze", id -> {
                String target = target(kind);
                expect(api.manage("PUT", target + "/dr-freeze", "{\"reason\":\"drill\"}", "Authorization",
                        api.operator()), 200);
                expect(api.manage("DELETE", target + "/dr-freeze", null, rid(id, api.operator())), 204);
                return EventType.GOVERNANCE_CHANGE;
            });
        });

        cases.put("POST " + M + "/grants", id -> {
            expect(api.manage("POST", "/grants", grant(api.namespace()), rid(id, api.grantAdmin())), 204);
            return EventType.GRANT_CHANGE;
        });
        cases.put("POST " + M + "/grants/revoke", id -> {
            String body = grant(api.namespace());
            expect(api.manage("POST", "/grants", body, "Authorization", api.grantAdmin()), 204);
            expect(api.manage("POST", "/grants/revoke", body, rid(id, api.grantAdmin())), 204);
            return EventType.GRANT_CHANGE;
        });
        cases.put("PUT /management/v1/engines", id -> {
            expect(api.send("PUT", "/management/v1/engines", engine("m7-put-" + Api.suffix()),
                    rid(id, api.operator())), 204);
            return EventType.ENGINE_CHANGE;
        });
        cases.put("DELETE /management/v1/engines", id -> {
            String client = "m7-delete-" + Api.suffix();
            expect(api.send("PUT", "/management/v1/engines", engine(client), "Authorization", api.operator()), 204);
            expect(api.send("DELETE", "/management/v1/engines?issuer="
                    + URLEncoder.encode(IssuerForTests.ISSUER, StandardCharsets.UTF_8) + "&client-id=" + client, null,
                    rid(id, api.operator())), 204);
            return EventType.ENGINE_CHANGE;
        });

        cases.put("POST " + M + "/objects/{id}/undrop", id -> {
            String object = dropped();
            expect(api.manage("POST", "/objects/" + object + "/undrop", null, rid(id)), 200);
            return EventType.UNDROP;
        });
        cases.put("POST " + M + "/objects/{id}/purge", id -> {
            String object = dropped();
            clock.advance(Duration.ofDays(31));
            expect(api.manage("POST", "/objects/" + object + "/purge", null, "X-Request-ID", id,
                    GovernanceServer.PRINCIPAL, "maintenance"), 204);
            return EventType.PURGE;
        });
        return cases;
    }

    private static String[] rid(String requestId) {
        return new String[] {"X-Request-ID", requestId};
    }

    private static String[] rid(String requestId, String authorization) {
        return new String[] {"X-Request-ID", requestId, "Authorization", authorization};
    }

    /** SELECT on {@code namespace} for a group of its own, so no grant meets separation of duties. */
    private static String grant(String namespace) {
        return "{\"securable\":{\"type\":\"namespace\",\"namespace\":[\"" + namespace + "\"]},"
                + "\"group\":\"readers-" + Api.suffix() + "\",\"privilege\":\"select\"}";
    }

    private static String engine(String client) {
        return "{\"issuer\":\"" + IssuerForTests.ISSUER + "\",\"client-id\":\"" + client
                + "\",\"end-user-claim\":\"oid\",\"flags\":[]}";
    }

    /** A management target of this kind: the catalog itself, or a new namespace, table or view. */
    private String target(String kind) {
        return switch (kind) {
            case "catalog" -> "";
            case "namespace" -> "/namespaces/" + api.namespace();
            case "table" -> "/namespaces/" + withTable() + "/tables/t";
            default -> "/namespaces/" + withView() + "/views/v";
        };
    }

    private String withTable() {
        String ns = api.namespace();
        api.table(ns, "t");
        return ns;
    }

    private String withView() {
        String ns = api.namespace();
        api.view(ns, "v");
        return ns;
    }

    /** A dropped table's id. */
    private String dropped() {
        String ns = withTable();
        String id = api.id(ns, "tables", "t");
        expect(api.rest("DELETE", "/namespaces/" + ns + "/tables/t", null), 204);
        return id;
    }

    private static String rename(String namespace, String from, String to) {
        return "{\"source\":{\"namespace\":[\"" + namespace + "\"],\"name\":\"" + from + "\"},"
                + "\"destination\":{\"namespace\":[\"" + namespace + "\"],\"name\":\"" + to + "\"}}";
    }

    /** A metadata file like the one at {@code location}, written as the first file of a table the catalog does not hold. */
    private String copiedMetadataWithNewUuid(String location) {
        String key = location.substring(("s3://" + SiloForTests.BUCKET + "/").length());
        UUID uuid = UUID.randomUUID();
        String root = "tables/" + uuid;
        String newKey = root + "/metadata/00000-" + uuid + ".metadata.json";
        try (S3Client s3 = S3Client.builder()
                .endpointOverride(silo.endpoint())
                .region(Region.US_EAST_1)
                .forcePathStyle(true)
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(SiloForTests.ACCESS_KEY, SiloForTests.SECRET_KEY)))
                .build()) {
            JsonMapper json = JsonMapper.builder().build();
            ObjectNode metadata = (ObjectNode) json.readTree(s3.getObjectAsBytes(b -> b.bucket(SiloForTests.BUCKET)
                    .key(key)).asUtf8String());
            metadata.put("table-uuid", uuid.toString());
            metadata.put("location", "s3://" + SiloForTests.BUCKET + "/" + root);
            s3.putObject(b -> b.bucket(SiloForTests.BUCKET).key(newKey), RequestBody.fromString(
                    json.writeValueAsString(metadata)));
        }
        return "s3://" + SiloForTests.BUCKET + "/" + newKey;
    }
}
