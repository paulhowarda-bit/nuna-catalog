package keel.catalog.server.rest;

import keel.catalog.audit.Actor;
import keel.catalog.audit.CatalogEvent;
import keel.catalog.audit.Commit;
import keel.catalog.audit.EventCodec;
import keel.catalog.audit.EventData;
import keel.catalog.audit.PointerState;
import keel.catalog.audit.Securable;
import keel.catalog.core.commit.CommitEvents;
import keel.catalog.store.CatalogObject;
import keel.catalog.store.OutboxEvent;
import keel.catalog.store.Pointer;
import keel.catalog.store.PointerKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The audit events a change writes to the outbox in its own transaction (D23). An outbox row holds the
 * event's type name and the whole CloudEvent, encoded as the relay will publish it (M7).
 */
@Component
public class AuditEvents {

    private final RestSettings settings;

    AuditEvents(RestSettings settings) {
        this.settings = settings;
    }

    OutboxEvent event(Instant at, EventData data) {
        CatalogEvent event = new CatalogEvent(UUID.randomUUID(), settings.auditSource(), at, data);
        return new OutboxEvent(data.type().typeName(), new String(EventCodec.encode(event), StandardCharsets.UTF_8));
    }

    /** The commit events of one request against {@code path} (namespace levels, then the name) in {@code catalog}. */
    public CommitEvents commits(String catalog, List<String> path, Actor actor) {
        return commit -> List.of(event(commit.at(), new Commit(commit.commitId(),
                securable(commit.object(), catalog, path), PointerKey.MAIN,
                commit.before().map(AuditEvents::state).orElse(null), state(commit.after()),
                commit.updatesJson(), actor)));
    }

    static Securable securable(CatalogObject object, String catalog, List<String> path) {
        return new Securable(object.kind() == CatalogObject.Kind.TABLE ? Securable.Kind.TABLE : Securable.Kind.VIEW,
                object.id(), catalog, path);
    }

    static Securable namespace(UUID id, String catalog, List<String> levels) {
        return new Securable(Securable.Kind.NAMESPACE, id, catalog, levels);
    }

    static List<String> path(List<String> namespace, String name) {
        List<String> path = new ArrayList<>(namespace);
        path.add(name);
        return path;
    }

    private static PointerState state(Pointer pointer) {
        return new PointerState(pointer.metadataLocation(), pointer.version(), pointer.sha256());
    }
}
