package nuna.catalog.server.rest;

import nuna.catalog.audit.Actor;
import nuna.catalog.audit.CatalogEvent;
import nuna.catalog.audit.Commit;
import nuna.catalog.audit.EventCodec;
import nuna.catalog.audit.EventData;
import nuna.catalog.audit.PointerState;
import nuna.catalog.audit.Securable;
import nuna.catalog.audit.Undrop;
import nuna.catalog.audit.Unregister;
import nuna.catalog.core.commit.CommitEvents;
import nuna.catalog.store.CatalogObject;
import nuna.catalog.store.OutboxEvent;
import nuna.catalog.store.Pointer;
import nuna.catalog.store.PointerKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
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
        return commits(catalog, object -> path, actor);
    }

    /**
     * The commit events of a request that changes several objects, each named by {@code path}. A dropped
     * object brought back by a register is also an undrop; an unregister moves no pointer, so it is only that.
     */
    CommitEvents commits(String catalog, Function<CatalogObject, List<String>> path, Actor actor) {
        return commit -> {
            Securable object = securable(commit.object(), catalog, path.apply(commit.object()));
            OutboxEvent committed = event(commit.at(), new Commit(commit.commitId(), object, PointerKey.MAIN,
                    commit.before().map(AuditEvents::state).orElse(null), state(commit.after()),
                    commit.updatesJson(), actor));
            return switch (commit.kind()) {
                case COMMIT -> List.of(committed);
                case UNDROP -> List.of(event(commit.at(), new Undrop(object, actor)), committed);
                case UNREGISTER -> List.of(event(commit.at(), new Unregister(object, state(commit.after()), actor)));
            };
        };
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
