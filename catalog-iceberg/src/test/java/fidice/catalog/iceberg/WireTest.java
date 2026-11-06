package fidice.catalog.iceberg;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WireTest {

    @Test
    void configAcceptsImmutableMapsAndWritesEndpoints() {
        String json = Wire.config(Map.of(), Map.of("prefix", "trading"),
                List.of("GET /v1/{prefix}/namespaces"));

        assertThat(json).contains("\"prefix\":\"trading\"")
                .contains("\"endpoints\":[\"GET /v1/{prefix}/namespaces\"]");
    }

    @Test
    void errorHasTheIcebergShape() {
        assertThat(Wire.error(404, "NoSuchWarehouseException", "gone"))
                .isEqualTo("{\"error\":{\"message\":\"gone\",\"type\":\"NoSuchWarehouseException\",\"code\":404}}");
    }
}
