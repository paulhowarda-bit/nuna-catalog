package nuna.datagen.kit;

/**
 * The ledger refused a financial transaction (schema 13 PostingRejected): DuplicateTransaction, NoBookingScheme,
 * NegativeAmount, LotNotHeld, Unbalanced or BalanceBelowZero, and the demo kit's FractionalShares (item 155: a split
 * that would leave a lot holding shares finer than the unit, 10^-5). A rejection changes nothing.
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
