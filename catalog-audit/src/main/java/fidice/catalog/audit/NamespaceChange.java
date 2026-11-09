package fidice.catalog.audit;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * A namespace created, dropped, or its properties changed (D23).
 *
 * @param action    what happened
 * @param namespace the namespace; its path is the levels as given
 * @param set       the properties set: all of them on create, the updated ones on a change, none on drop;
 *                  kept sorted by key so the encoding is stable
 * @param removed   the property keys removed, sorted
 * @param actor     who changed it
 */
public record NamespaceChange(Action action, Securable namespace, Map<String, String> set, List<String> removed,
                              Actor actor) implements EventData {

    public enum Action { CREATED, PROPERTIES_CHANGED, DROPPED }

    public NamespaceChange {
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(namespace, "namespace");
        Objects.requireNonNull(set, "set");
        Objects.requireNonNull(removed, "removed");
        Objects.requireNonNull(actor, "actor");
        set = Collections.unmodifiableMap(new TreeMap<>(set));
        removed = removed.stream().sorted().toList();
    }

    @Override
    public EventType type() {
        return EventType.NAMESPACE_CHANGE;
    }

    @Override
    public String subject() {
        return namespace.id().toString();
    }
}
