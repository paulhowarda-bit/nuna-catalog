package fidice.catalog.store;

import java.util.Objects;
import java.util.UUID;

/** What grants, governance attributes and legal holds attach to, by id (D18). */
public record Securable(Kind kind, UUID id) {

    public enum Kind { CATALOG, NAMESPACE, TABLE, VIEW }

    public Securable {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(id, "id");
    }
}
