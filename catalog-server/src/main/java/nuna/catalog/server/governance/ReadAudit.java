package nuna.catalog.server.governance;

import nuna.catalog.audit.ObjectRead;
import nuna.catalog.audit.PointerState;
import nuna.catalog.audit.Securable;
import nuna.catalog.store.CatalogObject;
import nuna.catalog.store.CatalogRecord;
import nuna.catalog.store.Pointer;
import nuna.catalog.store.PointerKey;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The read-side audit seam the REST routes call (D23). A load, including a 304, is recorded with
 * {@link #read}, which never fails the request. Credential issues are recorded by M6's vending through
 * the {@code AuditPublisher} bean, which fails closed.
 */
@Component
public class ReadAudit {

    private final AuditTrail trail;
    private final Management management;

    ReadAudit(AuditTrail trail, Management management) {
        this.trail = trail;
        this.management = management;
    }

    /**
     * Records that {@code object}'s metadata at {@code served} was returned, or confirmed by a 304.
     *
     * @param path   the namespace levels and name, as the request named the object
     * @param branch what the read went through: {@link PointerKey#MAIN}, or the name of a branch or tag (D33)
     */
    public void read(HttpServletRequest http, CatalogRecord catalog, List<String> path, CatalogObject object,
                     Pointer served, String branch) {
        trail.record(new ObjectRead(securable(catalog, path, object.kind() == CatalogObject.Kind.TABLE
                ? Securable.Kind.TABLE : Securable.Kind.VIEW, object.id()), branch, state(served),
                management.request(http).actor()));
    }

    static PointerState state(Pointer pointer) {
        return new PointerState(pointer.metadataLocation(), pointer.version(), pointer.sha256());
    }

    private static Securable securable(CatalogRecord catalog, List<String> path, Securable.Kind kind, UUID id) {
        return new Securable(kind, id, catalog.name(), path);
    }
}
