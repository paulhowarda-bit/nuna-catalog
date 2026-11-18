package nuna.catalog.server.security;

import nuna.catalog.audit.Actor;
import nuna.catalog.audit.CatalogEvent;
import nuna.catalog.audit.Denial;
import nuna.catalog.audit.Securable;
import nuna.catalog.server.rest.AccessDenied;
import nuna.catalog.server.rest.AuditPublisher;
import nuna.catalog.server.rest.RestSettings;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Every refusal goes through here: it publishes a {@link Denial} (D18: every denial audited) and answers 403.
 * An audit outage does not turn a refusal into anything else; it is logged (D23 blocks only credential issuance).
 */
@Component
public class Denials {

    private static final Logger LOG = LoggerFactory.getLogger(Denials.class);

    private final AuditPublisher audit;
    private final RestSettings settings;
    private final Clock clock;

    Denials(AuditPublisher audit, RestSettings settings, Clock clock) {
        this.audit = audit;
        this.settings = settings;
        this.clock = clock;
    }

    /**
     * @param securable what was refused, or null for the trusted-engine registry
     * @param privilege the missing privilege's name, or null when the refusal is not about one
     * @param reason    a short code, then the message
     */
    public AccessDenied refuse(Actor actor, String operation, Securable securable, String privilege, String reason,
                               String message) {
        Denial denial = new Denial(operation, securable, privilege, reason + ": " + message, actor);
        try {
            audit.publish(CatalogEvent.create(settings.auditSource(), clock.instant(), denial));
        } catch (RuntimeException e) {
            LOG.error("audit of a denial failed: {} on {} for {}", operation,
                    securable == null ? "the engine registry" : securable.id(), actor, e);
        }
        return new AccessDenied(message);
    }
}
