package nuna.catalog.audit;

import java.util.Objects;
import java.util.Set;

/**
 * An export of a catalog's whole access map (D18, D23; backlog 312): every grant in it, or every row filter and column
 * mask, for an access review. A read, not a change: it is published as the export is answered, like an
 * {@link AuditRead}.
 *
 * @param catalog  the catalog exported
 * @param what     {@code grants} or {@code row-column-rules}
 * @param returned how many grants or rules the export held
 * @param actor    who exported them
 */
public record Export(Securable catalog, String what, int returned, Actor actor) implements EventData {

    /** What an export holds. */
    public static final String GRANTS = "grants";
    public static final String ROW_COLUMN_RULES = "row-column-rules";

    public Export {
        Objects.requireNonNull(catalog, "catalog");
        Objects.requireNonNull(actor, "actor");
        if (!Set.of(GRANTS, ROW_COLUMN_RULES).contains(what)) {
            throw new IllegalArgumentException("not something exported: " + what);
        }
        if (returned < 0) {
            throw new IllegalArgumentException("returned must not be negative: " + returned);
        }
    }

    @Override
    public EventType type() {
        return EventType.EXPORT;
    }

    @Override
    public String subject() {
        return catalog.id().toString();
    }
}
