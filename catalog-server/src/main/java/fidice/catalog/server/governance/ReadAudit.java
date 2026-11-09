package fidice.catalog.server.governance;

import fidice.catalog.audit.CredentialIssue;
import fidice.catalog.audit.ObjectRead;
import fidice.catalog.audit.PointerState;
import fidice.catalog.audit.Securable;
import fidice.catalog.iceberg.RestResponse.StorageCredential;
import fidice.catalog.server.rest.CredentialVending;
import fidice.catalog.store.CatalogObject;
import fidice.catalog.store.CatalogRecord;
import fidice.catalog.store.Pointer;
import fidice.catalog.store.PointerKey;
import fidice.catalog.objectstore.StorageProfile;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * The read-side audit seam the REST routes call (D23). A load, including a 304, is recorded with
 * {@link #read}, which never fails the request. Credentials are recorded with {@link #credentials}
 * before the response carries them, and when Kafka does not acknowledge the record the request fails
 * with 503 and nothing is handed out.
 */
@Component
public class ReadAudit {

    private final AuditTrail trail;
    private final Management management;
    private final StorageProfile profile;
    private final Clock clock;

    ReadAudit(AuditTrail trail, Management management, StorageProfile profile, Clock clock) {
        this.trail = trail;
        this.management = management;
        this.profile = profile;
        this.clock = clock;
    }

    /**
     * Records that {@code object}'s metadata at {@code served} was returned, or confirmed by a 304.
     *
     * @param path the namespace levels and name, as the request named the object
     */
    public void read(HttpServletRequest http, CatalogRecord catalog, List<String> path, CatalogObject object,
                     Pointer served) {
        trail.record(new ObjectRead(securable(catalog, path, object.kind() == CatalogObject.Kind.TABLE
                ? Securable.Kind.TABLE : Securable.Kind.VIEW, object.id()), PointerKey.MAIN, state(served),
                management.request(http).actor()));
    }

    /**
     * Records credentials for a table before they are handed out; does nothing when none were vended.
     *
     * @throws fidice.catalog.audit.publish.AuditUnavailableException when Kafka does not acknowledge the
     *                                                                record: the route answers 503
     */
    public void credentials(HttpServletRequest http, CatalogRecord catalog, List<String> path, UUID tableId,
                            CredentialVending.Vended vended) {
        if (vended.credentials().isEmpty()) {
            return;
        }
        String prefixes = vended.credentials().stream().map(StorageCredential::prefix).collect(Collectors.joining(","));
        trail.requireRecorded(new CredentialIssue(securable(catalog, path, Securable.Kind.TABLE, tableId),
                profile.kind() + " " + profile.tableRoot(), prefixes, CredentialIssue.Access.READ_WRITE, vended.epoch(),
                vended.expiresAt().orElse(clock.instant()), management.request(http).actor()));
    }

    static PointerState state(Pointer pointer) {
        return new PointerState(pointer.metadataLocation(), pointer.version(), pointer.sha256());
    }

    private static Securable securable(CatalogRecord catalog, List<String> path, Securable.Kind kind, UUID id) {
        return new Securable(kind, id, catalog.name(), path);
    }
}
