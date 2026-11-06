package fidice.catalog.server;

import fidice.catalog.core.CatalogError;
import fidice.catalog.iceberg.Wire;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
