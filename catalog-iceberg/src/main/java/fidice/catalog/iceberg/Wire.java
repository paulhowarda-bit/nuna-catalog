package fidice.catalog.iceberg;

import org.apache.iceberg.rest.Endpoint;
import org.apache.iceberg.rest.responses.ConfigResponse;
import org.apache.iceberg.rest.responses.ConfigResponseParser;
import org.apache.iceberg.rest.responses.ErrorResponse;
import org.apache.iceberg.rest.responses.ErrorResponseParser;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Iceberg REST JSON, written by iceberg-core's own parsers so the wire format matches the
 * reference implementation exactly (build-plan.md P5). Callers pass plain values and get JSON.
 */
public final class Wire {

    private Wire() {
    }

    /**
     * The {@code GET /v1/config} body.
     *
     * @param endpoints each as {@code "<METHOD> <path>"}, e.g. {@code "GET /v1/{prefix}/namespaces"}
     */
    public static String config(Map<String, String> defaults, Map<String, String> overrides,
                                List<String> endpoints) {
        // The builder probes containsKey(null), which immutable maps (Map.of) reject; copy first.
        ConfigResponse response = ConfigResponse.builder()
                .withDefaults(new HashMap<>(defaults))
                .withOverrides(new HashMap<>(overrides))
                .withEndpoints(endpoints.stream().map(Endpoint::fromString).toList())
                .build();
        return ConfigResponseParser.toJson(response);
    }

    /** An Iceberg error body; {@code type} is the error class name, e.g. BadRequestException. */
    public static String error(int status, String type, String message) {
        ErrorResponse response = ErrorResponse.builder()
                .responseCode(status)
                .withType(type)
                .withMessage(message)
                .build();
        return ErrorResponseParser.toJson(response);
    }
}
