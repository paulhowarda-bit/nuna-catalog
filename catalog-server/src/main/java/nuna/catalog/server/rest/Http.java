package nuna.catalog.server.rest;

import nuna.catalog.core.CatalogError;
import nuna.catalog.iceberg.RestResponse;
import nuna.catalog.iceberg.Wire;
import nuna.catalog.server.StoreOutage;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/** Response and error helpers shared by the routes. */
final class Http {

    static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    static final String ACCESS_DELEGATION = "X-Iceberg-Access-Delegation";
    static final String SERVICE_FAILURE = "ServiceFailureException";

    private Http() {
    }

    static ResponseEntity<String> json(int status, String body) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(body);
    }

    static ResponseEntity<String> error(int status, String type, String message) {
        return json(status, Wire.error(status, type, message));
    }

    /** 503 with Retry-After: the store could not be reached and nothing was applied ({@link StoreOutage}). */
    static ResponseEntity<String> unavailable(String message) {
        return ResponseEntity.status(StoreOutage.STATUS)
                .header(HttpHeaders.RETRY_AFTER, StoreOutage.RETRY_AFTER_SECONDS)
                .contentType(MediaType.APPLICATION_JSON)
                .body(StoreOutage.body(message));
    }

    /** {@code ns.orders}, as iceberg-core prints an identifier in its messages. */
    static String qualified(List<String> namespace, String name) {
        return RestResponse.dotted(namespace) + "." + name;
    }

    static String dotted(List<String> namespace) {
        return RestResponse.dotted(namespace);
    }

    static CatalogError noSuchNamespace(List<String> namespace) {
        return CatalogError.of(404, "NoSuchNamespaceException", "Namespace does not exist: " + dotted(namespace));
    }

    static CatalogError noSuchTable(String qualified) {
        return CatalogError.of(404, "NoSuchTableException", "Table does not exist: " + qualified);
    }

    static CatalogError noSuchView(String qualified) {
        return CatalogError.of(404, "NoSuchViewException", "View does not exist: " + qualified);
    }

    static CatalogError forbidden(String message) {
        return CatalogError.of(403, "ForbiddenException", message);
    }

    /** A write whose COMMIT may or may not have landed, that a read-back could not confirm (D9). */
    static CatalogError outcomeUnknown(String what, RuntimeException cause) {
        return CatalogError.of(500, SERVICE_FAILURE, what + " may or may not have been applied: " + cause.getMessage());
    }

    /** A read that confirms a write whose outcome was unknown; a failed read confirms nothing. */
    static boolean readBack(BooleanSupplier applied) {
        try {
            return applied.getAsBoolean();
        } catch (RuntimeException e) {
            return false;
        }
    }
}
