package fidice.catalog.store;

/** A storage backend failed in a way the caller cannot correct. */
public class StoreException extends RuntimeException {

    public StoreException(String message, Throwable cause) {
        super(message, cause);
    }
}
