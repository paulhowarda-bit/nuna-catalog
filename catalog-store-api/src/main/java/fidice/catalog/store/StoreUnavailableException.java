package fidice.catalog.store;

/**
 * The backend could not be reached, or lost the connection, before anything was sent to commit: the
 * database is down, failing over, refusing connections or out of them. Nothing was applied, so the
 * request may be repeated once the backend is back; the REST layer answers 503 with Retry-After.
 *
 * <p>A connection lost after COMMIT was sent is a {@link CommitOutcomeUnknownException}, never this.
 */
public class StoreUnavailableException extends StoreException {

    public StoreUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
