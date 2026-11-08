package fidice.catalog.store;

import java.util.Objects;
import java.util.Optional;

/** Up to {@code size} items after the position {@code token} names; no token for the first page. */
public record PageRequest(Optional<String> token, int size) {

    public PageRequest {
        Objects.requireNonNull(token, "token");
        if (size < 1) {
            throw new IllegalArgumentException("page size must be at least 1, got " + size);
        }
    }

    public static PageRequest first(int size) {
        return new PageRequest(Optional.empty(), size);
    }

    /** The request for the page after {@code page}, which must not be the last. */
    public PageRequest after(Page<?> page) {
        return new PageRequest(Optional.of(page.nextToken().orElseThrow(
                () -> new IllegalArgumentException("the last page has no next page"))), size);
    }
}
