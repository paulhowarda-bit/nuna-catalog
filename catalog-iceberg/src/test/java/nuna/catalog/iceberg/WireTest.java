package nuna.catalog.iceberg;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WireTest {

    @Test
    void configAcceptsImmutableMapsAndWritesEndpoints() {
        String json = Wire.config(Map.of(), Map.of("prefix", "trading"),
                List.of("GET /v1/{prefix}/namespaces"), "PT24H");

        assertThat(json).contains("\"prefix\":\"trading\"")
                .contains("\"endpoints\":[\"GET /v1/{prefix}/namespaces\"]")
                .contains("\"idempotency-key-lifetime\":\"PT24H\"");
    }

    @Test
    void oauthErrorHasTheOAuthShape() {
        assertThat(Wire.oauthError("unsupported_grant_type", "ask the IdP"))
                .isEqualTo("{\"error\":\"unsupported_grant_type\",\"error_description\":\"ask the IdP\"}");
    }

    @Test
    void errorHasTheIcebergShape() {
        assertThat(Wire.error(404, "NoSuchWarehouseException", "gone"))
                .isEqualTo("{\"error\":{\"message\":\"gone\",\"type\":\"NoSuchWarehouseException\",\"code\":404}}");
    }
}
