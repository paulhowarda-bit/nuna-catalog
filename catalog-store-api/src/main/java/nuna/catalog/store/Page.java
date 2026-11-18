package nuna.catalog.store;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** One page of a listing; {@code nextToken} is empty on the last page. Tokens are opaque. */
public record Page<T>(List<T> items, Optional<String> nextToken) {

    public Page {
        items = List.copyOf(items);
        Objects.requireNonNull(nextToken, "nextToken");
    }
}
