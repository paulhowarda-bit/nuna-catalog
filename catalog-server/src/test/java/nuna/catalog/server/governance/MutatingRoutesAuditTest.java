package nuna.catalog.server.governance;

import static nuna.catalog.server.governance.Api.expect;
import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import nuna.catalog.audit.CatalogEvent;
import nuna.catalog.audit.EventType;
import nuna.catalog.server.IssuerForTests;
import nuna.catalog.server.S3ForTests;
import nuna.catalog.tests.s3.TestS3;
import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The M7 gate, route by route: every route that changes the catalog writes its audit record, and the
 * relay delivers it to the audit topic; and every mutating management route that refuses a caller
 * records a Denial (D18). The routes are read from the server's own mappings, so a new mutating route
 * without a case here fails the test until it gets one.
 */
@Tag("heavy")
@GovernanceServer
class MutatingRoutesAuditTest {

    /** POST routes that change nothing in the catalog, and why. */
    private static final Map<String, String> NOT_MUTATING = Map.of(
            "POST /v1/{prefix}/namespaces/{namespace}/tables/{table}/metrics", "forwarded to telemetry, never stored",
            "POST /management/v1/catalogs/{catalog}/grants/check", "the caller's own batch check: stores nothing,"
                    + " and M5 does not audit its answers");

    /**
     * Management routes that keep the maintenance worker's reports of its own work (D38): bookkeeping, not changes to
     * the catalog, so they write no audit record; their refusals are audited like every other's.
     */
    private static final Map<String, String> REPORTS = Map.of(
            "PUT /management/v1/maintenance/scans/{id}", "a scan's start or end",
            "PUT /management/v1/maintenance/tables/{id}/assessment", "a table's assessment",
            "PUT /management/v1/maintenance/tables/{id}/compaction", "how a table's compaction stands",
            "PUT /management/v1/maintenance/tables/{id}/dr-watermark", "how far the DR store holds a table whole (D37)");

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
    private TestS3 objectStore;

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
        mutating.removeAll(REPORTS.keySet());

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
        cases.put("POST " + M + "/objects/{id}/purge-claim", id -> api.manage("POST",
                "/objects/" + dropped() + "/purge-claim", null, rid(id)));
        cases.put("POST " + M + "/objects/{id}/purge", id -> api.manage("POST", "/objects/" + dropped() + "/purge",
                null, rid(id)));
        cases.put("POST " + M + "/objects/{id}/pending-deletions", id -> api.manage("POST",
                "/objects/" + dropped() + "/pending-deletions", "{\"locations\":[\"s3://x/y\"]}", rid(id)));
        cases.put("POST " + M + "/objects/{id}/pending-deletions/complete", id -> api.manage("POST",
                "/objects/" + dropped() + "/pending-deletions/complete", "{\"ids\":[\"" + UUID.randomUUID() + "\"]}",
                rid(id)));
        cases.put("POST " + M + "/dr-reconcile", id -> api.manage("POST", "/dr-reconcile", null, rid(id)));
        cases.put("PUT /management/v1/maintenance/scans/{id}", id -> api.manage("PUT", "/maintenance/scans/"
                + UUID.randomUUID(), "{\"started-at\":\"2026-11-15T01:00:00Z\"}", rid(id)));
        cases.put("PUT /management/v1/maintenance/tables/{id}/assessment", id -> api.manage("PUT",
                "/maintenance/tables/" + api.id(withTable(), "tables", "t") + "/assessment", "{}", rid(id)));
        cases.put("PUT /management/v1/maintenance/tables/{id}/compaction", id -> api.manage("PUT",
                "/maintenance/tables/" + api.id(withTable(), "tables", "t") + "/compaction", "{}", rid(id)));
        cases.put("PUT /management/v1/maintenance/tables/{id}/dr-watermark", id -> api.manage("PUT",
                "/maintenance/tables/" + api.id(withTable(), "tables", "t") + "/dr-watermark", "{}", rid(id)));
        // The steward holds manage-tags on the catalog, not create (D33, choice 12).
        cases.put("POST " + M + "/refs", id -> api.manage("POST", "/refs", ref("m7-" + Api.suffix(), "tag"),
                rid(id, api.steward())));
        cases.put("POST " + M + "/refs/{ref}/renew", id -> api.manage("POST", "/refs/m7-" + Api.suffix() + "/renew",
                null, rid(id, api.steward())));
        cases.put("DELETE " + M + "/refs/{ref}", id -> api.manage("DELETE", "/refs/m7-" + Api.suffix(), null,
                rid(id, api.steward())));
        cases.put("POST /management/v1/maintenance/refs/{id}/warn", id -> api.manage("POST", "/maintenance/refs/"
                + UUID.randomUUID() + "/warn", null, rid(id)));
        cases.put("POST /management/v1/maintenance/refs/{id}/end", id -> api.manage("POST", "/maintenance/refs/"
                + UUID.randomUUID() + "/end", null, rid(id)));
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
            String file = copiedMetadataWithNewUuid(api.table(ns, "source"), "table-uuid");
            expect(api.rest("POST", "/namespaces/" + ns + "/register",
                    "{\"name\":\"registered\",\"metadata-location\":\"" + file + "\"}", rid(id)), 200);
            return EventType.COMMIT;
        });
        cases.put("POST /v1/{prefix}/namespaces/{namespace}/tables/{table}/unregister", id -> {
            expect(api.rest("POST", "/namespaces/" + withTable() + "/tables/t/unregister", null, rid(id)), 200);
            return EventType.UNREGISTER;
        });
        cases.put("POST /v1/{prefix}/namespaces/{namespace}/register-view", id -> {
            String ns = api.namespace();
            String source = JsonPath.read(expect(api.rest("POST", "/namespaces/" + ns + "/views",
                    Api.VIEW.formatted("source", System.currentTimeMillis())), 200).body(), "$.metadata-location");
            String file = copiedMetadataWithNewUuid(source, "view-uuid");
            expect(api.rest("POST", "/namespaces/" + ns + "/register-view",
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
        cases.put("POST /v1/{prefix}/transactions/commit", id -> {
            String ns = withTable();
            expect(api.rest("POST", "/transactions/commit", "{\"table-changes\":[{\"identifier\":{\"namespace\":[\""
                    + ns + "\"],\"name\":\"t\"}," + Api.SET_PROPERTY.formatted("1").substring(1) + "]}", rid(id)), 204);
            return EventType.COMMIT;
        });
        cases.put("POST /v1/{prefix}/views/rename", id -> {
            String ns = withView();
            expect(api.rest("POST", "/views/rename", rename(ns, "v", "v2"), rid(id)), 204);
            return EventType.RENAME;
        });

        LEVELS.forEach((level, kind) -> {
            cases.put("PUT " + M + level + "/attributes/{attribute}", id -> {
                String target = target(kind);
                expect(api.manage("PUT", target + "/attributes/classification",
                        "{\"value\":\"confidential\",\"provenance\":\"collibra:asset/1\"}", rid(id, api.steward())), 200);
                expect(api.manage("DELETE", target + "/attributes/classification", null, "Authorization",
                        api.steward()), 204);
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
        cases.put("POST " + M + "/objects/{id}/purge-claim", id -> {
            String object = dropped();
            clock.advance(Duration.ofDays(31));
            expect(api.manage("POST", "/objects/" + object + "/purge-claim", null, "X-Request-ID", id,
                    GovernanceServer.PRINCIPAL, "maintenance"), 200);
            return EventType.PURGE_CLAIM;
        });
        cases.put("POST " + M + "/objects/{id}/purge", id -> {
            String object = dropped();
            clock.advance(Duration.ofDays(31));
            expect(api.manage("POST", "/objects/" + object + "/purge-claim", null,
                    GovernanceServer.PRINCIPAL, "maintenance"), 200);
            expect(api.manage("POST", "/objects/" + object + "/purge", null, "X-Request-ID", id,
                    GovernanceServer.PRINCIPAL, "maintenance"), 204);
            return EventType.PURGE;
        });
        cases.put("POST " + M + "/objects/{id}/pending-deletions", id -> {
            String object = dropped();
            expect(api.manage("POST", "/objects/" + object + "/pending-deletions", deletions(object), "X-Request-ID", id,
                    GovernanceServer.PRINCIPAL, "maintenance"), 201);
            return EventType.FILE_DELETION;
        });
        cases.put("POST " + M + "/objects/{id}/pending-deletions/complete", id -> {
            String object = dropped();
            expect(api.manage("POST", "/objects/" + object + "/pending-deletions", deletions(object),
                    GovernanceServer.PRINCIPAL, "maintenance"), 201);
            String deletion = jdbc.queryForObject("SELECT id::text FROM pending_deletion WHERE object_id = ?::uuid",
                    String.class, object);
            expect(api.manage("POST", "/objects/" + object + "/pending-deletions/complete",
                    "{\"ids\":[\"" + deletion + "\"]}", "X-Request-ID", id, GovernanceServer.PRINCIPAL, "maintenance"), 204);
            return EventType.FILE_DELETION;
        });
        cases.put("POST " + M + "/dr-reconcile", id -> {
            String ns = behindItsStorage();
            String freeze = "/namespaces/" + Api.ns(ns) + "/dr-freeze";
            expect(api.manage("PUT", freeze, "{\"reason\":\"drill\"}", "Authorization", api.operator()), 200);
            expect(api.manage("POST", "/dr-reconcile", "{\"apply\":true}", rid(id, api.operator())), 200);
            expect(api.manage("DELETE", freeze, null, "Authorization", api.operator()), 204);
            return EventType.COMMIT;
        });

        cases.put("POST " + M + "/refs", id -> {
            expect(api.manage("POST", "/refs", ref("m7-" + Api.suffix(), "tag"), rid(id)), 201);
            return EventType.REF;
        });
        cases.put("POST " + M + "/refs/{ref}/renew", id -> {
            String name = "m7-" + Api.suffix();
            expect(api.manage("POST", "/refs", ref(name, "branch"), "X-Request-ID", "made-" + id), 201);
            // A renewal at the instant the branch was made would change nothing, and record nothing.
            clock.advance(Duration.ofDays(1));
            expect(api.manage("POST", "/refs/" + name + "/renew", null, rid(id)), 200);
            return EventType.REF;
        });
        cases.put("DELETE " + M + "/refs/{ref}", id -> {
            String name = "m7-" + Api.suffix();
            expect(api.manage("POST", "/refs", ref(name, "branch"), "X-Request-ID", "made-" + id), 201);
            expect(api.manage("DELETE", "/refs/" + name, null, rid(id)), 204);
            return EventType.REF;
        });
        cases.put("POST /management/v1/maintenance/refs/{id}/warn", id -> {
            String branch = JsonPath.read(expect(api.manage("POST", "/refs", ref("m7-" + Api.suffix(), "branch"),
                    "X-Request-ID", "made-" + id), 201).body(), "$.id");
            clock.advance(Duration.ofDays(24));
            expect(api.manage("POST", "/maintenance/refs/" + branch + "/warn", null, "X-Request-ID", id,
                    GovernanceServer.PRINCIPAL, "maintenance"), 200);
            return EventType.REF;
        });
        cases.put("POST /management/v1/maintenance/refs/{id}/end", id -> {
            String branch = JsonPath.read(expect(api.manage("POST", "/refs", ref("m7-" + Api.suffix(), "branch"),
                    "X-Request-ID", "made-" + id), 201).body(), "$.id");
            clock.advance(Duration.ofDays(31));
            expect(api.manage("POST", "/maintenance/refs/" + branch + "/end", null, "X-Request-ID", id,
                    GovernanceServer.PRINCIPAL, "maintenance"), 204);
            return EventType.REF;
        });
        return cases;
    }

    private static String ref(String name, String kind) {
        return "{\"name\":\"" + name + "\",\"kind\":\"" + kind + "\"}";
    }

    /** A namespace whose table's pointer row is put back to version 1 after two commits, as a DR replica lags. */
    private String behindItsStorage() {
        String ns = withTable();
        String id = api.id(ns, "tables", "t");
        Map<String, Object> first = jdbc.queryForMap("SELECT metadata_location, version, sha256, projection,"
                + " last_commit_id FROM pointer WHERE object_id = ?::uuid AND branch = 'main'", id);
        for (String value : List.of("1", "2")) {
            expect(api.rest("POST", "/namespaces/" + ns + "/tables/t", Api.SET_PROPERTY.formatted(value)), 200);
        }
        jdbc.update("UPDATE pointer SET metadata_location = ?, version = ?, sha256 = ?, projection = ?,"
                + " last_commit_id = ? WHERE object_id = ?::uuid AND branch = 'main'", first.get("metadata_location"),
                first.get("version"), first.get("sha256"), first.get("projection"), first.get("last_commit_id"), id);
        return ns;
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

    /** A pending-deletions body naming one file beneath the object's location. */
    private String deletions(String object) {
        String location = JsonPath.read(expect(api.manage("GET", "/objects/" + object + "/export", null), 200).body(),
                "$.object.location");
        return "{\"locations\":[\"" + location + "/data/expired.parquet\"]}";
    }

    private static String rename(String namespace, String from, String to) {
        return "{\"source\":{\"namespace\":[\"" + namespace + "\"],\"name\":\"" + from + "\"},"
                + "\"destination\":{\"namespace\":[\"" + namespace + "\"],\"name\":\"" + to + "\"}}";
    }

    /**
     * A metadata file like the one at {@code location}, written as the first file of a table or view the catalog
     * does not hold: a new UUID in {@code uuidField}, at the location the catalog assigns it.
     */
    private String copiedMetadataWithNewUuid(String location, String uuidField) {
        String key = location.substring(("s3://" + S3ForTests.bucket() + "/").length());
        UUID uuid = UUID.randomUUID();
        String root = "tables/" + uuid;
        String newKey = root + "/metadata/00000-" + uuid + ".metadata.json";
        try (S3Client s3 = objectStore.client()) {
            JsonMapper json = JsonMapper.builder().build();
            ObjectNode metadata = (ObjectNode) json.readTree(s3.getObjectAsBytes(b -> b.bucket(S3ForTests.bucket())
                    .key(key)).asUtf8String());
            metadata.put(uuidField, uuid.toString());
            metadata.put("location", "s3://" + S3ForTests.bucket() + "/" + root);
            s3.putObject(b -> b.bucket(S3ForTests.bucket()).key(newKey), RequestBody.fromString(
                    json.writeValueAsString(metadata)));
        }
        return "s3://" + S3ForTests.bucket() + "/" + newKey;
    }
}
