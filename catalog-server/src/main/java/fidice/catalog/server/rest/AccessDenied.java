package fidice.catalog.server.rest;

/**
 * A request authorization refused (403), already audited. It is deliberately not a {@code CatalogError}:
 * an idempotency key does not record it, so a retry after the grant arrives runs afresh.
 */
public final class AccessDenied extends RuntimeException {

    public AccessDenied(String message) {
        super(message);
    }
}
