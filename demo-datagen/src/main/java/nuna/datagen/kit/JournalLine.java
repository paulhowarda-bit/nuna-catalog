package nuna.datagen.kit;

import java.math.BigDecimal;

/**
 * A journal line as posted (schema 13 Posting): its sub-ledger, definition, side, a positive amount (J11, J12), the
 * security and lot of its ledger account where its key has them, and the ledger account it resolved to.
 */
public record JournalLine(int subLedger, int definition, Kit.Side side, BigDecimal amount, Long security, Long lot,
                          long ledgerAccountId) {}
