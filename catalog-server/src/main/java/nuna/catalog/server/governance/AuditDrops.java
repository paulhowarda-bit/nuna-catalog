package nuna.catalog.server.governance;

import nuna.audittables.EventsReader;
import nuna.catalog.audit.AuditRead;
import nuna.catalog.audit.AuditRow;
import nuna.catalog.audit.EventType;
import nuna.catalog.security.Authorizer;
import nuna.catalog.security.Privilege;
import nuna.catalog.server.governance.Management.Request;
import nuna.catalog.store.CatalogObject;
import nuna.catalog.store.CatalogRecord;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Who dropped a table or view (backlog 170), from its drop's record in the audit table, which the feed reads
 * ({@link AuditFeed}): the catalog keeps no actor with the object. Only for a caller who reads the feed, holding
 * {@code select} on the audit catalog (D23, amended 2026-11-22: audit readers only), so the read shows no one a
 * record the feed would not; asked, not required, so another caller is refused nothing and gets no {@code dropped-by}.
 * The record is looked for by the object's path, the drop's type and its time, which is the object's
 * {@code dropped-at}, and matched by the object's id. Each lookup is audited as an {@link AuditRead} of the object, as
 * a feed page is of its catalog. Where the audit tables are not set up or cannot be read, there is no
 * {@code dropped-by}: the read still answers.
 */
final class AuditDrops implements RefRoutes.Drops {

    private static final Logger LOG = LoggerFactory.getLogger(AuditDrops.class);
    /** How far past {@code dropped-at} the lookup reads: the record's time is the drop's, to the millisecond. */
    private static final Duration WINDOW = Duration.ofSeconds(1);
    private static final int ROWS = 10;

    private final AuditFeed feed;
    private final Authorizer authorizer;
    private final AuditTrail trail;

    AuditDrops(AuditFeed feed, Authorizer authorizer, AuditTrail trail) {
        this.feed = feed;
        this.authorizer = authorizer;
        this.trail = trail;
    }

    @Override
    public Optional<RefRoutes.Dropper> droppedBy(Request request, CatalogRecord catalog, CatalogObject object,
                                                 nuna.catalog.audit.Securable named) {
        Optional<Instant> at = object.droppedAt();
        Optional<CatalogRecord> audit = feed.auditCatalog();
        if (at.isEmpty() || audit.isEmpty() || !authorizer.decide(request.caller(),
                new Authorizer.Target.Catalog(audit.get().id()),
                new Authorizer.Operation("readAuditFeed", Privilege.SELECT, false)).allowed()) {
            return Optional.empty();
        }
        Optional<AuditRow> row;
        try {
            row = feed.page(new EventsReader.Query(catalog.name(), Set.of(EventType.DROP.typeName()), null,
                            named.path(), at.get(), at.get().plus(WINDOW), Optional.empty(), ROWS)).page().rows()
                    .stream().filter(each -> object.id().toString().equals(each.objectId())).findFirst();
        } catch (RuntimeException e) {
            LOG.debug("the drop of {} could not be read from the audit table: {}", object.id(), e.getMessage());
            return Optional.empty();
        }
        trail.record(new AuditRead(named, row.isPresent() ? 1 : 0, request.actor()));
        return row.filter(each -> each.principal() != null)
                .map(each -> new RefRoutes.Dropper(each.principal(), each.endUser()));
    }
}
