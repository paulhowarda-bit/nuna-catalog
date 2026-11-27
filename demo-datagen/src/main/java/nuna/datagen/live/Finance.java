package nuna.datagen.live;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.apache.iceberg.Schema;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.types.Types;

/**
 * The firm's finance in {@code brokerage.finance} (item 153): views over the CDC leg's Oracle histories, each record as
 * the treasury database holds it now. The Iceberg sink appends every change, so each view keeps each key's newest
 * change unless it is a delete, each column cast to its type in {@link Sources} (Oracle's DATE arrives as a timestamp,
 * its NUMBER as a wider decimal). The General Ledger's journal and balances ({@link GeneralLedger}) and the bank's
 * payments without the customers' bank account numbers, so the views hold nothing a flag would close.
 */
final class Finance {

    static final Namespace NAMESPACE = Namespace.of("brokerage", "finance");

    /** One view: its name, comment, columns and Trino SQL. */
    record View(String name, String comment, Schema schema, String sql) {}

    /** The bank's columns the view keeps: not the counterparty's routing and account numbers, nor their name. */
    private static final List<String> BANK = List.of("payment_id", "version", "brokerage_account", "rail", "direction",
        "status", "amount", "firm_bank_account", "value_date", "event_time");

    private Finance() {
    }

    /**
     * The views over the treasury histories that exist.
     *
     * @param catalog the engine's name for the warehouse ({@code lakehouse} in Trino)
     * @param exists  whether a lake table, by its dotted name, exists
     */
    static List<View> views(String catalog, Predicate<String> exists) {
        List<View> views = new ArrayList<>();
        add(views, catalog, exists, Sources.Table.GL_JOURNAL, Sources.Table.GL_JOURNAL.columnNames(),
            "The General Ledger's journal: per business day and GL control account, the day's debits and credits of"
                + " the customer sub-ledger definition it controls (Oracle treasury.gl_journal, item 153).");
        add(views, catalog, exists, Sources.Table.GL_BALANCES, Sources.Table.GL_BALANCES.columnNames(),
            "The General Ledger's balances: each GL control account at each day's end, on its normal side (Oracle"
                + " treasury.gl_balances, item 153).");
        add(views, catalog, exists, Sources.Table.BANK_PAYMENTS, BANK,
            "Each ACH and wire as the firm's bank sees it now, without the counterparty's bank details (Oracle"
                + " treasury.bank_payments, item 152).");
        return views;
    }

    private static void add(List<View> views, String catalog, Predicate<String> exists, Sources.Table t,
                            List<String> keep, String comment) {
        String history = t.history();
        if (!exists.test(history)) {
            return;
        }
        List<Types.NestedField> fields = new ArrayList<>();
        List<String> casts = new ArrayList<>();
        for (Sources.Column c : t.columns) {
            if (keep.contains(c.name())) {
                fields.add(Types.NestedField.optional(fields.size() + 1, c.name(), c.type()));
                casts.add("CAST(h.\"" + c.name() + "\" AS " + Current.trino(c.type()) + ") AS \"" + c.name() + "\"");
            }
        }
        int dot = history.lastIndexOf('.');
        String sql = "SELECT " + casts.stream().collect(Collectors.joining(", "))
            + "\nFROM (SELECT s.*, row_number() OVER (PARTITION BY s.\"" + t.key() + "\" ORDER BY s.\"_cdc\".ts DESC,"
            + " s.\"_cdc\".\"offset\" DESC) AS newest\n  FROM " + catalog + ".\"" + history.substring(0, dot) + "\"."
            + history.substring(dot + 1) + " s) h\nWHERE h.newest = 1 AND h.\"_cdc\".op <> 'D'";
        views.add(new View(t.table, comment, new Schema(fields), sql));
    }
}
