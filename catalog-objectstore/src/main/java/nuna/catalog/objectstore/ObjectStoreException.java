package nuna.catalog.objectstore;

/** The object store refused or failed a request; the message names the location. */
public final class ObjectStoreException extends RuntimeException {

    ObjectStoreException(String message, Throwable cause) {
        super(message, cause);
    }
}
