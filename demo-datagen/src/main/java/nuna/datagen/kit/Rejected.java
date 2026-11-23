package nuna.datagen.kit;

/**
 * The ledger refused a financial transaction (schema 13 PostingRejected): DuplicateTransaction, NoBookingScheme,
 * NegativeAmount, LotNotHeld, Unbalanced or BalanceBelowZero. A rejection changes nothing.
 */
public final class Rejected extends Exception {

    private final String reason;

    public Rejected(String reason, String detail) {
        super(reason + ": " + detail);
        this.reason = reason;
    }

    public String reason() {
        return reason;
    }
}
