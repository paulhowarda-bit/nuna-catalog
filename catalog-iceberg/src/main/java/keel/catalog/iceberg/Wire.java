package keel.catalog.iceberg;

import org.apache.iceberg.rest.Endpoint;
import org.apache.iceberg.rest.responses.ConfigResponse;
import org.apache.iceberg.rest.responses.ConfigResponseParser;
import org.apache.iceberg.rest.responses.ErrorResponse;
import org.apache.iceberg.rest.responses.ErrorResponseParser;

import com.fasterxml.jackson.databind.node.ObjectNode;
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
     * @param endpoints              each as {@code "<METHOD> <path>"}, e.g. {@code "GET /v1/{prefix}/namespaces"}
     * @param idempotencyKeyLifetime ISO-8601 duration for which a mutation's idempotency key is honoured
     *                               (D10); clients send keys only when it is advertised
     */
    public static String config(Map<String, String> defaults, Map<String, String> overrides,
                                List<String> endpoints, String idempotencyKeyLifetime) {
        // The builder probes containsKey(null), which immutable maps (Map.of) reject; copy first.
        ConfigResponse response = ConfigResponse.builder()
                .withDefaults(new HashMap<>(defaults))
                .withOverrides(new HashMap<>(overrides))
                .withEndpoints(endpoints.stream().map(Endpoint::fromString).toList())
                .withIdempotencyKeyLifetime(idempotencyKeyLifetime)
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

    /**
     * The OAuth2 error body ({@code error}, {@code error_description}) the client's token handler
     * reads; iceberg-core has a parser for it but no writer.
     */
    public static String oauthError(String error, String description) {
        ObjectNode body = Metadata.REQUESTS.createObjectNode();
        body.put("error", error);
        body.put("error_description", description);
        return body.toString();
    }
}
