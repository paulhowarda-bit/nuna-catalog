package nuna.catalog.server;

import nuna.catalog.core.CatalogError;
import nuna.catalog.iceberg.RestRequest;
import nuna.catalog.iceberg.Wire;
import nuna.catalog.objectstore.LocationRejectedException;
import nuna.catalog.objectstore.MetadataNotFoundException;
import nuna.catalog.server.rest.AccessDenied;
import nuna.catalog.store.StoreUnavailableException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Turns failures into Iceberg error responses. */
@RestControllerAdvice
class ErrorResponses {

    private static final Logger LOG = LoggerFactory.getLogger(ErrorResponses.class);

    @ExceptionHandler(CatalogError.class)
    ResponseEntity<String> definite(CatalogError error) {
        return body(error.status(), error.type(), error.getMessage());
    }

    /** A request body or path that is not a valid Iceberg request: 400, or 422 for clashing property keys. */
    @ExceptionHandler(RestRequest.InvalidRequest.class)
    ResponseEntity<String> invalid(RestRequest.InvalidRequest error) {
        return body(error.status(), error.type(), error.getMessage());
    }

    /** A location outside the storage profile's approved prefixes, e.g. a registered metadata file (D21). */
    @ExceptionHandler(LocationRejectedException.class)
    ResponseEntity<String> location(LocationRejectedException error) {
        return body(400, "BadRequestException", error.getMessage());
    }

    /** Authorization refused the request; the denial is already audited (D18). */
    @ExceptionHandler(AccessDenied.class)
    ResponseEntity<String> denied(AccessDenied error) {
        return body(403, "ForbiddenException", error.getMessage());
    }

    /** A metadata file that is not in the store, worded as iceberg-core words it. */
    @ExceptionHandler(MetadataNotFoundException.class)
    ResponseEntity<String> missingFile(MetadataNotFoundException error) {
        return body(404, "NotFoundException", error.getMessage());
    }

    /** The store could not be reached and nothing was applied: 503 with Retry-After, so the client repeats. */
    @ExceptionHandler(StoreUnavailableException.class)
    ResponseEntity<String> unavailable(StoreUnavailableException error) {
        LOG.warn("catalog store unavailable: {}", error.getMessage());
        return ResponseEntity.status(StoreOutage.STATUS)
                .header(HttpHeaders.RETRY_AFTER, StoreOutage.RETRY_AFTER_SECONDS)
                .contentType(MediaType.APPLICATION_JSON)
                .body(StoreOutage.body("Catalog store unavailable; nothing was applied: " + error.getMessage()));
    }

    /** Spring's own request errors (unknown route, wrong method, bad parameter) keep their status. */
    @ExceptionHandler(Exception.class)
    ResponseEntity<String> other(Exception error) {
        if (error instanceof org.springframework.web.ErrorResponse framework) {
            int status = framework.getStatusCode().value();
            if (status < 500) {
                String type = status == 404 ? "NotFoundException" : "BadRequestException";
                return body(status, type, String.valueOf(framework.getBody().getDetail()));
            }
        }
        LOG.error("unhandled request failure", error);
        return body(500, "ServiceFailureException", "Internal server error");
    }

    private static ResponseEntity<String> body(int status, String type, String message) {
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Wire.error(status, type, message));
    }
}
