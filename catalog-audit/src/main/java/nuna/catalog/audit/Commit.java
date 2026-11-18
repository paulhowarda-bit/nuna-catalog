package nuna.catalog.audit;

import java.util.Objects;
import java.util.UUID;
import tools.jackson.databind.annotation.JsonDeserialize;
import tools.jackson.databind.annotation.JsonSerialize;

/**
 * A pointer swap: who changed which table or view, from what to what (D23). A multi-table commit
 * writes one of these per swap, all with the same {@code commitId}.
 *
 * @param commitId the commit's id, generated before its transaction began (D8.1)
 * @param object   the table or view
 * @param branch   the branch whose pointer moved (D33)
 * @param before   the pointer it replaced, or null when the commit created the object
 * @param after    the pointer it wrote
 * @param updates  the client's updates, as a JSON array
 * @param actor    who committed
 */
public record Commit(UUID commitId, Securable object, String branch, PointerState before, PointerState after,
                     @JsonSerialize(using = RawJson.Writer.class)
                     @JsonDeserialize(using = RawJson.Reader.class) String updates,
                     Actor actor) implements EventData {

    public Commit {
        Objects.requireNonNull(commitId, "commitId");
        Objects.requireNonNull(object, "object");
        Objects.requireNonNull(branch, "branch");
        Objects.requireNonNull(after, "after");
        Objects.requireNonNull(updates, "updates");
        Objects.requireNonNull(actor, "actor");
    }

    @Override
    public EventType type() {
        return EventType.COMMIT;
    }

    @Override
    public String subject() {
        return object.id().toString();
    }
}
