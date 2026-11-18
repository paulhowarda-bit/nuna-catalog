package nuna.catalog.server.governance;

import nuna.catalog.audit.publish.AuditUnavailableException;
import nuna.catalog.iceberg.Wire;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** A record that had to be audited before the response, and was not: 503, and nothing handed out (D23). */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
class AuditUnavailableResponses {

    private static final Logger LOG = LoggerFactory.getLogger(AuditUnavailableResponses.class);

    @ExceptionHandler(AuditUnavailableException.class)
    ResponseEntity<String> unavailable(AuditUnavailableException error) {
        LOG.warn("refused a request whose audit record was not acknowledged", error);
        return ResponseEntity.status(503)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Wire.error(503, "ServiceUnavailableException",
                        "Audit is unavailable, so nothing that must be audited first is handed out; retry later"));
    }
}
