package nuna.catalog.store;

import java.util.Objects;
import java.util.Optional;

/**
 * A ref whose cut names a table or view (D33): the pointer its cut took for it, and for a branch its own pointer for it
 * once one of its commits has changed it ({@code head}). A tag's head is always empty. Cleanup keeps every file either
 * reaches (item 108).
 */
public record RefPointers(Ref ref, RefEntry.At cut, Optional<RefEntry.At> head) {

    public RefPointers {
        Objects.requireNonNull(ref, "ref");
        Objects.requireNonNull(cut, "cut");
        Objects.requireNonNull(head, "head");
    }
}
