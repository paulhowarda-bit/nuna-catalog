package nuna.catalog.server.governance;

import static nuna.catalog.server.governance.Api.expect;
import static nuna.catalog.server.governance.Api.expectError;
import static nuna.catalog.server.governance.Api.ns;
import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import nuna.catalog.audit.Commit;
import nuna.catalog.audit.EventType;
import nuna.catalog.server.S3ForTests;
import nuna.catalog.tests.s3.TestS3;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * The DR rewind (D37, amended 2026-11-20): the store is behind the database, as a DR store that replicates more
 * slowly than its database would be: the verifier found version 2 of a table whole, and version 3's metadata file
 * never arrived. The operators' readout shows the table behind; the reconcile takes no file past the watermark and
 * says to rewind; the rewind lists the commit it gives up and, only under a DR freeze, moves the table back to
 * version 2's metadata as version 4, published like any commit.
 */
@Tag("heavy")
@GovernanceServer
class DrRewindRouteTest {

    private static final String AUTH = "Authorization";

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

    private String commit(String ns, String value) {
        return JsonPath.read(expect(api.rest("POST", "/namespaces/" + ns(ns) + "/tables/orders",
                Api.SET_PROPERTY.formatted(value)), 200).body(), "$.metadata-location");
    }

    @Test
    void aFrozenTableAboveItsWatermarkIsRewoundToIt() {
        String ns = api.namespace();
        api.table(ns, "orders");
        String id = api.id(ns, "tables", "orders");
        String v2 = commit(ns, "a");
        String v3 = commit(ns, "b");
        expect(api.manage("PUT", "/maintenance/tables/" + id + "/dr-watermark",
                "{\"version\":2,\"metadata-location\":\"" + v2 + "\"}", AUTH, api.maintenance()), 204);
        try (S3Client s3 = objectStore.client()) {
            s3.deleteObject(b -> b.bucket(S3ForTests.bucket())
                    .key(v3.substring(("s3://" + S3ForTests.bucket() + "/").length())));
        }

        String watermarks = expect(api.send("GET", "/management/v1/dr/watermarks?pageSize=200", null, AUTH,
                api.operator()), 200).body();
        String listed = "$.tables[?(@.id == '" + id + "')]";
        assertThat(JsonPath.<List<Integer>>read(watermarks, listed + ".pointer-version")).containsExactly(3);
        assertThat(JsonPath.<List<Integer>>read(watermarks, listed + ".watermark-version")).containsExactly(2);
        assertThat(JsonPath.<List<Integer>>read(watermarks, listed + ".versions-behind")).containsExactly(1);
        assertThat(JsonPath.<List<String>>read(watermarks, listed + ".name")).containsExactly("orders");

        String table = "$.tables[?(@.id == '" + id + "')]";
        HttpResponse<String> forward = expect(api.manage("POST", "/dr-reconcile", null, AUTH, api.operator()), 200);
        assertThat(JsonPath.<List<String>>read(forward.body(), table + ".problem")).singleElement().asString()
                .contains("above the DR watermark, version 2").contains("\"rewind\": true");

        HttpResponse<String> dryRun = expect(api.manage("POST", "/dr-reconcile", "{\"rewind\":true}", AUTH,
                api.operator()), 200);
        assertThat(JsonPath.<List<String>>read(dryRun.body(), table + ".watermark.metadata-location"))
                .containsExactly(v2);
        assertThat(JsonPath.<List<Integer>>read(dryRun.body(), table + ".lost[*].version")).containsExactly(3);
        assertThat(JsonPath.<List<String>>read(dryRun.body(), table + ".lost[*].metadata-location"))
                .containsExactly(v3);
        assertThat(JsonPath.<List<String>>read(dryRun.body(), table + ".lost[*].principal")).containsExactly("tester");
        assertThat(location(ns)).isEqualTo(v3);

        expectError(api.manage("POST", "/dr-reconcile", "{\"rewind\":true,\"accept\":[\"" + v3 + "\"]}", AUTH,
                api.operator()), 400, "BadRequestException");
        HttpResponse<String> unfrozen = expect(api.manage("POST", "/dr-reconcile", "{\"rewind\":true,\"apply\":true}",
                AUTH, api.operator()), 200);
        assertThat(JsonPath.<List<String>>read(unfrozen.body(), table + ".apply-problem")).singleElement()
                .asString().contains("not frozen");
        assertThat(location(ns)).isEqualTo(v3);

        expect(api.manage("PUT", "/namespaces/" + ns(ns) + "/dr-freeze", "{\"reason\":\"DR drill\"}",
                AUTH, api.operator()), 200);
        String requestId = "d37-rewind-" + UUID.randomUUID();
        HttpResponse<String> applied = expect(api.manage("POST", "/dr-reconcile", "{\"rewind\":true,\"apply\":true}",
                AUTH, api.operator(), Management.REQUEST_ID, requestId), 200);
        assertThat(JsonPath.<List<Integer>>read(applied.body(), table + ".rewound-to.version")).containsExactly(4);
        String v4 = location(ns);
        assertThat(v4).contains("/metadata/00004-");
        assertThat(JsonPath.<List<String>>read(applied.body(), table + ".rewound-to.metadata-location"))
                .containsExactly(v4);
        assertThat(JsonPath.<String>read(load(ns), "$.metadata.properties.k")).as("version 2's metadata")
                .isEqualTo("a");
        Commit published = (Commit) api.awaitRecord(json -> json.contains(requestId) && json.contains(v4)
                && json.contains("\"type\":\"" + EventType.COMMIT.typeName() + "\"")).data();
        assertThat(published.actor().principal()).isEqualTo("ops");
        HttpResponse<String> again = expect(api.manage("POST", "/dr-reconcile", "{\"rewind\":true}", AUTH,
                api.operator()), 200);
        assertThat(JsonPath.<List<String>>read(again.body(), table + ".problem")).as("the watermark is still 2,"
                + " until the verifier passes version 4").singleElement().asString()
                .contains("already holds the DR watermark's metadata");

        expect(api.manage("DELETE", "/namespaces/" + ns(ns) + "/dr-freeze", null, AUTH, api.operator()), 204);
        assertThat(commit(ns, "c")).contains("/metadata/00005-");
    }

    @Test
    void onlyAPlatformOperatorRewinds() {
        expectError(api.manage("POST", "/dr-reconcile", "{\"rewind\":true,\"apply\":true}"), 403, "ForbiddenException");
        expectError(api.manage("POST", "/dr-reconcile", "{\"rewind\":true,\"apply\":true}", AUTH, api.maintenance()),
                403, "ForbiddenException");
    }

    private String load(String ns) {
        return expect(api.rest("GET", "/namespaces/" + ns(ns) + "/tables/orders", null), 200).body();
    }

    private String location(String ns) {
        return JsonPath.read(load(ns), "$.metadata-location");
    }
}
