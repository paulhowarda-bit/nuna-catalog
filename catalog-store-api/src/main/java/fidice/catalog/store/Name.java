package fidice.catalog.store;

/**
 * A name stored twice (D8.6): {@code normalized} for uniqueness, lookup and order, {@code display}
 * as the client gave it. The caller normalizes; the store compares normalized forms only.
 */
public record Name(String normalized, String display) {

    public Name {
        if (normalized == null || normalized.isEmpty() || display == null || display.isEmpty()) {
            throw new IllegalArgumentException("a name needs a non-empty normalized and display form");
        }
    }
}
