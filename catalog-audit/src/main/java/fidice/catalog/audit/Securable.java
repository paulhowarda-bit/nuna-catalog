package fidice.catalog.audit;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Something grants apply to: a catalog, namespace, table or view (D18). The id is stable across
 * renames; the names are for people reading the record.
 *
 * @param kind    what it is
 * @param id      its stable id
 * @param catalog the catalog's name as given
 * @param path    namespace levels, then the table or view name, as given; empty for a catalog
 */
public record Securable(Kind kind, UUID id, String catalog, List<String> path) {

    public enum Kind { CATALOG, NAMESPACE, TABLE, VIEW }

    public Securable {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(catalog, "catalog");
        path = List.copyOf(path);
    }
}
