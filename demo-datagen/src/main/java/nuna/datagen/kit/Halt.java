package nuna.datagen.kit;

/**
 * An engine self-check failed (schema 13 J3, J4): a defect in the engine or a booking scheme that escaped the loader.
 * Posting stops; no financial transaction is rejected for it.
 */
public final class Halt extends RuntimeException {

    public Halt(String message) {
        super(message);
    }
}
