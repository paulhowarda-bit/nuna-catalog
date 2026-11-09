package fidice.catalog.server.storage;

import fidice.catalog.audit.CatalogEvent;
import fidice.catalog.audit.CredentialIssue;
import fidice.catalog.audit.EventCodec;
import java.nio.charset.StandardCharsets;
import java.net.URI;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Publishes a credential issue before the credentials are handed out (D23). If it throws, they are not
 * handed out. M7 replaces the logging default with the synchronous audit publisher.
 */
@FunctionalInterface
public interface CredentialAudit {

    void issued(CredentialIssue issue);

    /** Logs each issue as its CloudEvent. */
    static CredentialAudit log(URI source) {
        Logger log = LoggerFactory.getLogger(CredentialAudit.class);
        return issue -> log.info("audit {}", new String(EventCodec.encode(
                new CatalogEvent(UUID.randomUUID(), source, Instant.now(), issue)), StandardCharsets.UTF_8));
    }
}
