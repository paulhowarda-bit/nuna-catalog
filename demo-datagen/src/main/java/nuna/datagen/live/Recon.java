package nuna.datagen.live;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import nuna.datagen.world.Feed;
import nuna.datagen.world.MonthEnd;
import org.apache.iceberg.Schema;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.types.Type;
import org.apache.iceberg.types.Types;

/**
 * The reconciliation views in {@code brokerage.recon} (docs/design/brokerage-world.md, "It ties out"; item 151): each
 * returns no rows on a consistent world, and the seeded break's rows when it has one. Ties 1, 3 and 4:
 * <ol>
 *   <li>{@code stock_record_vs_ledger}: Share Position [Control] (3002) less Short Position [Control] (3102), per
 *       account, security and day, against the stock record's customer position and the open lots' shares (the share
 *       lots, 3001, less those pending delivery, 3003, less the short lots, 3101, net of those pending cover, 3103).
 *   <li>{@code executions_vs_fills}: every execution of the order management system is one ledger fill (templates 41,
 *       44, 61, 64) with the same fill id, quantity and price.
 *   <li>{@code cns_vs_fills}: the firm's CNS net obligation per security and trade date (whose settlement date it
 *       shows) against the net of its customers' fills, in shares and in money.
 * </ol>
 *
 * <p>Each feed is read from both of its lake tables, through Kafka and through SingleStore (D50), as far as each
 * exists, so the views hold whichever way the world's route took. A view reads only the days before today: a day's
 * rows reach the lake by two ways at two paces until its end. The SQL is portable (UNION ALL and GROUP BY, explicit
 * casts), so the fast build runs it in H2 against the world's own rows; Trino runs it in the demo.
 *
 * <p>Item 153 adds ties 2 and 5 to 9: {@code tax_lots_vs_ledger}, {@code payments_vs_ledger} and
 * {@code bank_vs_ledger}, {@code gl_vs_subledger}, {@code statements_vs_ledger}, {@code realized_vs_1099b} and
 * {@code cat_vs_orders}; and tie 4 counts a trade bust (template 92) against its buy.
 */
final class Recon {

    static final Namespace NAMESPACE = Namespace.of("brokerage", "recon");

    /** One view: its name, comment, columns and Trino SQL. */
    record View(String name, String comment, Schema schema, String sql) {}

    private static final Type MONEY_SUM = Types.DecimalType.of(20, 2);
    private static final Type SHARES_SUM = Types.DecimalType.of(20, 5);
    private static final Type SHARES = Types.DecimalType.of(18, 5);
    private static final Type PRICE = Types.DecimalType.of(18, 6);

    private Recon() {
    }

    /**
     * The views over the lake tables that exist.
     *
     * @param catalog the engine's name for the warehouse ({@code lakehouse} in Trino)
     * @param exists  whether a lake table, by its dotted name, exists
     */
    static List<View> views(String catalog, Predicate<String> exists) {
        String balances = union(catalog, exists, Feed.BALANCES_EOD,
            "business_date, brokerage_account, security_id, definition_id, balance");
        String stockRecord = union(catalog, exists, Feed.STOCK_RECORD,
            "business_date, brokerage_account, security_id, category, quantity");
        String fills = union(catalog, exists, Feed.FINANCIAL_TRANSACTIONS,
            "template_id, fill_id, trade_date, security_id, quantity, fill_price, fill_amount, gross_amount");
        String executions = union(catalog, exists, Feed.EXECUTIONS, "fill_id, trade_date, last_quantity, last_price");
        String cns = union(catalog, exists, Feed.CNS_OBLIGATIONS,
            "trade_date, settlement_date, security_id, net_quantity, net_amount");
        List<View> views = new ArrayList<>();
        views.add(new View("stock_record_vs_ledger",
            "Tie 1: Share Position [Control] (3002) less Short Position [Control] (3102) per account, security and day,"
                + " against the stock record's customer position and the open lots' shares. No rows when they agree.",
            schema(field("business_date", Types.DateType.get()), field("brokerage_account", Types.LongType.get()),
                field("security_id", Types.LongType.get()), field("ledger_position", SHARES_SUM),
                field("lot_shares", SHARES_SUM), field("stock_record_position", SHARES_SUM)),
            """
            SELECT business_date, brokerage_account, security_id,
              CAST(SUM(ledger) AS decimal(20, 5)) AS ledger_position,
              CAST(SUM(lots) AS decimal(20, 5)) AS lot_shares,
              CAST(SUM(stock_record) AS decimal(20, 5)) AS stock_record_position
            FROM (
              SELECT business_date, brokerage_account, security_id,
                CAST(CASE definition_id WHEN 3002 THEN balance WHEN 3102 THEN -balance ELSE 0 END AS decimal(20, 5)) AS ledger,
                CAST(CASE definition_id WHEN 3001 THEN balance WHEN 3003 THEN -balance WHEN 3101 THEN -balance
                  WHEN 3103 THEN balance ELSE 0 END AS decimal(20, 5)) AS lots,
                CAST(0 AS decimal(20, 5)) AS stock_record
              FROM %s b WHERE definition_id IN (3001, 3002, 3003, 3101, 3102, 3103)
              UNION ALL
              SELECT business_date, brokerage_account, security_id, CAST(0 AS decimal(20, 5)), CAST(0 AS decimal(20, 5)),
                CAST(quantity AS decimal(20, 5))
              FROM %s s WHERE category = 'customer'
            ) t
            WHERE business_date < current_date
            GROUP BY business_date, brokerage_account, security_id
            HAVING SUM(ledger) <> SUM(stock_record) OR SUM(lots) <> SUM(stock_record)""".formatted(balances, stockRecord)));
        views.add(new View("executions_vs_fills",
            "Tie 3: every execution of the order management system is one ledger fill (templates 41, 44, 61, 64) with"
                + " the same fill id, quantity and price. No rows when they agree.",
            schema(field("fill_id", Types.StringType.get()), field("trade_date", Types.DateType.get()),
                field("executions", Types.LongType.get()), field("fills", Types.LongType.get()),
                field("execution_quantity", SHARES), field("fill_quantity", SHARES),
                field("execution_price", PRICE), field("fill_price", PRICE)),
            """
            SELECT fill_id, trade_date,
              CAST(SUM(executions) AS bigint) AS executions, CAST(SUM(fills) AS bigint) AS fills,
              CAST(SUM(execution_quantity) AS decimal(18, 5)) AS execution_quantity,
              CAST(SUM(fill_quantity) AS decimal(18, 5)) AS fill_quantity,
              CAST(SUM(execution_price) AS decimal(18, 6)) AS execution_price,
              CAST(SUM(fill_price) AS decimal(18, 6)) AS fill_price
            FROM (
              SELECT fill_id, trade_date, CAST(1 AS bigint) AS executions, CAST(0 AS bigint) AS fills,
                CAST(last_quantity AS decimal(18, 5)) AS execution_quantity, CAST(0 AS decimal(18, 5)) AS fill_quantity,
                CAST(last_price AS decimal(18, 6)) AS execution_price, CAST(0 AS decimal(18, 6)) AS fill_price
              FROM %s e
              UNION ALL
              SELECT fill_id, trade_date, CAST(0 AS bigint), CAST(1 AS bigint), CAST(0 AS decimal(18, 5)),
                CAST(quantity AS decimal(18, 5)), CAST(0 AS decimal(18, 6)), CAST(fill_price AS decimal(18, 6))
              FROM %s f WHERE template_id IN (41, 44, 61, 64)
            ) t
            WHERE trade_date < current_date
            GROUP BY fill_id, trade_date
            HAVING SUM(executions) <> 1 OR SUM(fills) <> 1 OR SUM(execution_quantity) <> SUM(fill_quantity)
              OR SUM(execution_price) <> SUM(fill_price)""".formatted(executions, fills)));
        views.add(new View("cns_vs_fills",
            "Tie 4: the firm's CNS net obligation per security and trade date (settling on settlement_date) against the"
                + " net of its customers' fills, a busted buy (template 92) taken off, in shares and money. No rows when"
                + " they agree.",
            schema(field("trade_date", Types.DateType.get()), field("security_id", Types.LongType.get()),
                field("settlement_date", Types.DateType.get()), field("cns_net_quantity", SHARES_SUM),
                field("fills_net_quantity", SHARES_SUM), field("cns_net_amount", MONEY_SUM),
                field("fills_net_amount", MONEY_SUM)),
            """
            SELECT trade_date, security_id, MAX(settlement_date) AS settlement_date,
              CAST(SUM(cns_quantity) AS decimal(20, 5)) AS cns_net_quantity,
              CAST(SUM(fill_quantity) AS decimal(20, 5)) AS fills_net_quantity,
              CAST(SUM(cns_amount) AS decimal(20, 2)) AS cns_net_amount,
              CAST(SUM(fill_amount) AS decimal(20, 2)) AS fills_net_amount
            FROM (
              SELECT trade_date, security_id, settlement_date,
                CAST(net_quantity AS decimal(20, 5)) AS cns_quantity, CAST(0 AS decimal(20, 5)) AS fill_quantity,
                CAST(net_amount AS decimal(20, 2)) AS cns_amount, CAST(0 AS decimal(20, 2)) AS fill_amount
              FROM %s c
              UNION ALL
              SELECT trade_date, security_id, CAST(NULL AS date), CAST(0 AS decimal(20, 5)),
                CAST(CASE WHEN template_id IN (41, 64) THEN quantity ELSE -quantity END AS decimal(20, 5)),
                CAST(0 AS decimal(20, 2)),
                CAST(CASE WHEN template_id IN (41, 64) THEN -fill_amount WHEN template_id = 92 THEN fill_amount
                  ELSE gross_amount END AS decimal(20, 2))
              FROM %s f WHERE template_id IN (41, 44, 61, 64, 92)
            ) t
            WHERE trade_date < current_date
            GROUP BY trade_date, security_id
            HAVING SUM(cns_quantity) <> SUM(fill_quantity) OR SUM(cns_amount) <> SUM(fill_amount)""".formatted(cns,
                fills)));
        views.addAll(books(catalog, exists, balances, fills));
        views.addAll(finance(catalog, exists));
        return views;
    }

    /** A view of {@code brokerage.finance} ({@link Finance}), by its name. */
    private static String finance(String catalog, String view) {
        return catalog + ".\"" + String.join(".", Finance.NAMESPACE.levels()) + "\"." + view;
    }

    /**
     * Ties 5 and 6 (item 153), over the treasury's histories once the CDC leg has made them: the bank's payments and
     * the General Ledger, read through their views in {@code brokerage.finance}. The treasury holds what the last load
     * wrote (Main cdc, after a business day's end), which the General Ledger's last day marks: tie 5's bank side and
     * tie 6 compare only the days up to it.
     */
    private static List<View> finance(String catalog, Predicate<String> exists) {
        List<View> views = new ArrayList<>();
        if (!exists(exists, Feed.PAYMENTS) || !exists(exists, Feed.FINANCIAL_TRANSACTIONS)) {
            return views;
        }
        String payments = union(catalog, exists, Feed.PAYMENTS,
            "payment_id, financial_transaction_id, template_id, status, amount, business_date");
        String transactions = union(catalog, exists, Feed.FINANCIAL_TRANSACTIONS,
            "financial_transaction_id, template_id, amount, business_date");
        views.add(new View("payments_vs_ledger",
            "Tie 5: every payment version the ledger booked (templates 30 to 35, 70 to 76) is one money transaction"
                + " with the same template and amount. No rows when they agree.",
            schema(field("business_date", Types.DateType.get()), field("financial_transaction_id", Types.LongType.get()),
                field("template_id", Types.IntegerType.get()), field("payment_versions", Types.LongType.get()),
                field("ledger_transactions", Types.LongType.get()), field("payment_amount", MONEY_SUM),
                field("ledger_amount", MONEY_SUM)),
            """
            SELECT business_date, financial_transaction_id, template_id,
              CAST(SUM(payment) AS bigint) AS payment_versions, CAST(SUM(ledger) AS bigint) AS ledger_transactions,
              CAST(SUM(payment_amount) AS decimal(20, 2)) AS payment_amount,
              CAST(SUM(ledger_amount) AS decimal(20, 2)) AS ledger_amount
            FROM (
              SELECT business_date, financial_transaction_id, template_id, CAST(1 AS bigint) AS payment,
                CAST(0 AS bigint) AS ledger, CAST(amount AS decimal(20, 2)) AS payment_amount,
                CAST(0 AS decimal(20, 2)) AS ledger_amount
              FROM %s p WHERE financial_transaction_id IS NOT NULL
              UNION ALL
              SELECT business_date, financial_transaction_id, template_id, CAST(0 AS bigint), CAST(1 AS bigint),
                CAST(0 AS decimal(20, 2)), CAST(amount AS decimal(20, 2))
              FROM %s f WHERE template_id BETWEEN 30 AND 35 OR template_id BETWEEN 70 AND 76
            ) t
            WHERE business_date < current_date
            GROUP BY business_date, financial_transaction_id, template_id
            HAVING SUM(payment) <> 1 OR SUM(ledger) <> 1 OR SUM(payment_amount) <> SUM(ledger_amount)"""
                .formatted(payments, transactions)));
        boolean gl = exists.test(Sources.Table.GL_BALANCES.history());
        if (gl && exists.test(Sources.Table.BANK_PAYMENTS.history())) {
            String bank = finance(catalog, Sources.Table.BANK_PAYMENTS.table);
            String last = "(SELECT MAX(business_date) FROM " + finance(catalog, Sources.Table.GL_BALANCES.table) + ")";
            views.add(new View("bank_vs_ledger",
                "Tie 5: per value date, the bank's net of the ACH and wires it settled (in less out) against the"
                    + " ledger's settlements of them (templates 32 and 70 less 34 and 72), up to the treasury's last"
                    + " load; a deposit returned is in neither. No rows when they agree.",
                schema(field("value_date", Types.DateType.get()), field("bank_net", MONEY_SUM),
                    field("ledger_net", MONEY_SUM)),
                """
                SELECT value_date, CAST(SUM(bank) AS decimal(20, 2)) AS bank_net,
                  CAST(SUM(ledger) AS decimal(20, 2)) AS ledger_net
                FROM (
                  SELECT value_date,
                    CAST(CASE WHEN direction = 'incoming' THEN amount ELSE -amount END AS decimal(20, 2)) AS bank,
                    CAST(0 AS decimal(20, 2)) AS ledger
                  FROM %1$s b WHERE status = 'settled'
                  UNION ALL
                  SELECT f.business_date, CAST(0 AS decimal(20, 2)),
                    CAST(CASE WHEN f.template_id IN (32, 70) THEN f.amount ELSE -f.amount END AS decimal(20, 2))
                  FROM %2$s f JOIN %3$s p ON p.financial_transaction_id = f.financial_transaction_id
                  WHERE f.template_id IN (32, 34, 70, 72)
                    AND p.payment_id NOT IN (SELECT r.payment_id FROM %3$s r
                      WHERE r.status = 'returned' AND r.business_date <= %4$s)
                ) t
                WHERE value_date < current_date AND value_date <= %4$s
                GROUP BY value_date
                HAVING SUM(bank) <> SUM(ledger)""".formatted(bank, transactions, payments, last)));
        }
        if (gl && exists(exists, Feed.BALANCES_EOD)) {
            String balances = union(catalog, exists, Feed.BALANCES_EOD, "business_date, definition_id, measure, balance");
            String ledger = finance(catalog, Sources.Table.GL_BALANCES.table);
            views.add(new View("gl_vs_subledger",
                "Tie 6 (ControlOfTotal): each General Ledger control account at each day's end against the sum of every"
                    + " brokerage account's balance of the definition it controls; Customer Cash Balances [Control]"
                    + " (GL-1001) against Cash (1001). No rows when they agree.",
                schema(field("business_date", Types.DateType.get()), field("gl_account", Types.StringType.get()),
                    field("definition_id", Types.IntegerType.get()), field("gl_balance", MONEY_SUM),
                    field("subledger_balance", MONEY_SUM)),
                """
                SELECT business_date, 'GL-' || CAST(definition_id AS varchar) AS gl_account, definition_id,
                  CAST(SUM(gl) AS decimal(20, 2)) AS gl_balance, CAST(SUM(sub) AS decimal(20, 2)) AS subledger_balance
                FROM (
                  SELECT business_date, definition_id, CAST(balance AS decimal(20, 2)) AS gl,
                    CAST(0 AS decimal(20, 2)) AS sub
                  FROM %1$s g
                  UNION ALL
                  SELECT business_date, definition_id, CAST(0 AS decimal(20, 2)), CAST(balance AS decimal(20, 2))
                  FROM %2$s b
                  WHERE measure = 'Money' AND definition_id BETWEEN 1000 AND 1999
                    AND business_date IN (SELECT business_date FROM %1$s d)
                ) t
                WHERE business_date < current_date
                GROUP BY business_date, definition_id
                HAVING SUM(gl) <> SUM(sub)""".formatted(ledger, balances)));
        }
        return views;
    }

    /** A close table (item 153), written by the generator alone: one lake table. */
    private static String close(String catalog, MonthEnd.Table t) {
        return catalog + ".\"" + t.namespace + "\"." + t.table;
    }

    /**
     * Ties 2 and 7 to 9 (item 153), each over the lake tables that exist: tie 2 needs the tax lots, 7 and 8 the
     * month-end close's tables, 9 the CAT events.
     */
    private static List<View> books(String catalog, Predicate<String> exists, String balances, String fills) {
        List<View> views = new ArrayList<>();
        if (exists(exists, Feed.TAX_LOTS)) {
            String lots = union(catalog, exists, Feed.TAX_LOTS, "business_date, brokerage_account, security_id, cost");
            views.add(new View("tax_lots_vs_ledger",
                "Tie 2: Securities at Cost (1002) per account, security and day against the open tax lots' cost."
                    + " No rows when they agree.",
                schema(field("business_date", Types.DateType.get()), field("brokerage_account", Types.LongType.get()),
                    field("security_id", Types.LongType.get()), field("ledger_cost", MONEY_SUM),
                    field("tax_lot_cost", MONEY_SUM)),
                """
                SELECT business_date, brokerage_account, security_id,
                  CAST(SUM(ledger) AS decimal(20, 2)) AS ledger_cost, CAST(SUM(lots) AS decimal(20, 2)) AS tax_lot_cost
                FROM (
                  SELECT business_date, brokerage_account, security_id, CAST(balance AS decimal(20, 2)) AS ledger,
                    CAST(0 AS decimal(20, 2)) AS lots
                  FROM %s b WHERE definition_id = 1002
                  UNION ALL
                  SELECT business_date, brokerage_account, security_id, CAST(0 AS decimal(20, 2)),
                    CAST(cost AS decimal(20, 2))
                  FROM %s l
                ) t
                WHERE business_date < current_date
                GROUP BY business_date, brokerage_account, security_id
                HAVING SUM(ledger) <> SUM(lots)""".formatted(balances, lots)));
        }
        if (exists.test(MonthEnd.Table.STATEMENTS.lakeTable())
            && exists.test(MonthEnd.Table.STATEMENT_POSITIONS.lakeTable())) {
            String statements = close(catalog, MonthEnd.Table.STATEMENTS);
            String positions = close(catalog, MonthEnd.Table.STATEMENT_POSITIONS);
            views.add(new View("statements_vs_ledger",
                "Tie 7: each month-end statement's cash, unsettled sales and purchases and positions against the"
                    + " ledger's balances (1001, 1003, 1101, 3002 less 3102) at the period's end. No rows when they"
                    + " agree; after a restatement, the restated account's corrected lines.",
                schema(field("period_end", Types.DateType.get()), field("brokerage_account", Types.LongType.get()),
                    field("item", Types.StringType.get()), field("security_id", Types.LongType.get()),
                    field("statement_amount", SHARES_SUM), field("ledger_amount", SHARES_SUM)),
                """
                SELECT period_end, brokerage_account, item, security_id,
                  CAST(SUM(statement_amount) AS decimal(20, 5)) AS statement_amount,
                  CAST(SUM(ledger_amount) AS decimal(20, 5)) AS ledger_amount
                FROM (
                  SELECT period_end, brokerage_account, 'cash' AS item, CAST(NULL AS bigint) AS security_id,
                    CAST(cash_balance AS decimal(20, 5)) AS statement_amount, CAST(0 AS decimal(20, 5)) AS ledger_amount
                  FROM %1$s
                  UNION ALL
                  SELECT period_end, brokerage_account, 'unsettled_sales', CAST(NULL AS bigint),
                    CAST(unsettled_sales_receivable AS decimal(20, 5)), CAST(0 AS decimal(20, 5))
                  FROM %1$s
                  UNION ALL
                  SELECT period_end, brokerage_account, 'unsettled_purchases', CAST(NULL AS bigint),
                    CAST(unsettled_purchases_payable AS decimal(20, 5)), CAST(0 AS decimal(20, 5))
                  FROM %1$s
                  UNION ALL
                  SELECT s.period_end, p.brokerage_account, 'position', p.security_id,
                    CAST(p.quantity AS decimal(20, 5)), CAST(0 AS decimal(20, 5))
                  FROM %2$s p JOIN %1$s s ON s.statement_id = p.statement_id
                  UNION ALL
                  SELECT b.business_date, b.brokerage_account,
                    CASE b.definition_id WHEN 1001 THEN 'cash' WHEN 1003 THEN 'unsettled_sales'
                      WHEN 1101 THEN 'unsettled_purchases' ELSE 'position' END,
                    CASE WHEN b.definition_id IN (3002, 3102) THEN b.security_id END,
                    CAST(0 AS decimal(20, 5)),
                    CAST(CASE b.definition_id WHEN 3102 THEN -b.balance ELSE b.balance END AS decimal(20, 5))
                  FROM %3$s b
                  WHERE b.definition_id IN (1001, 1003, 1101, 3002, 3102)
                    AND EXISTS (SELECT 1 FROM %1$s s WHERE s.period_end = b.business_date
                      AND s.brokerage_account = b.brokerage_account)
                ) t
                GROUP BY period_end, brokerage_account, item, security_id
                HAVING SUM(statement_amount) <> SUM(ledger_amount)""".formatted(statements, positions, balances)));
        }
        if (exists.test(MonthEnd.Table.FORM_1099B.lakeTable()) && exists.test(MonthEnd.Table.STATEMENTS.lakeTable())
            && exists(exists, Feed.JOURNAL_LINES)) {
            String lines = union(catalog, exists, Feed.JOURNAL_LINES,
                "financial_transaction_id, definition_id, side, amount, business_date");
            views.add(new View("realized_vs_1099b",
                "Tie 8: each sale's Realized Gain/Loss (1202) against its 1099-B lines' gain or loss with the wash"
                    + " sale loss disallowed added back and the basis adjustment taken out, for the months closed."
                    + " No rows when they agree.",
                schema(field("financial_transaction_id", Types.LongType.get()), field("ledger_gain", MONEY_SUM),
                    field("form_1099b_gain", MONEY_SUM)),
                """
                SELECT financial_transaction_id,
                  CAST(SUM(ledger) AS decimal(20, 2)) AS ledger_gain, CAST(SUM(reported) AS decimal(20, 2)) AS form_1099b_gain
                FROM (
                  SELECT financial_transaction_id,
                    CAST(CASE side WHEN 'Credit' THEN amount ELSE -amount END AS decimal(20, 2)) AS ledger,
                    CAST(0 AS decimal(20, 2)) AS reported
                  FROM %s j
                  WHERE definition_id = 1202 AND business_date <= (SELECT MAX(period_end) FROM %s)
                  UNION ALL
                  SELECT financial_transaction_id, CAST(0 AS decimal(20, 2)),
                    CAST(gain_or_loss - wash_sale_loss_disallowed + basis_wash_adjustment AS decimal(20, 2))
                  FROM %s f
                ) t
                GROUP BY financial_transaction_id
                HAVING SUM(ledger) <> SUM(reported)""".formatted(lines, close(catalog, MonthEnd.Table.STATEMENTS),
                    close(catalog, MonthEnd.Table.FORM_1099B))));
        }
        if (exists(exists, Feed.CAT_EVENTS) && exists(exists, Feed.ORDER_EVENTS) && exists(exists, Feed.ROUTES)
            && exists(exists, Feed.EXECUTIONS)) {
            String events = union(catalog, exists, Feed.ORDER_EVENTS, "order_id, event_type, trade_date");
            String routes = union(catalog, exists, Feed.ROUTES, "route_id, trade_date");
            String executions = union(catalog, exists, Feed.EXECUTIONS, "exec_id, trade_date");
            String cat = union(catalog, exists, Feed.CAT_EVENTS, "event_type, order_id, route_id, exec_id, trade_date");
            views.add(new View("cat_vs_orders",
                "Tie 9: every order accepted, route and execution is exactly one CAT event (MENO, MEOR, the venue's"
                    + " trade). No rows when they agree.",
                schema(field("trade_date", Types.DateType.get()), field("event", Types.StringType.get()),
                    field("event_key", Types.StringType.get()), field("source_events", Types.LongType.get()),
                    field("cat_events", Types.LongType.get())),
                """
                SELECT trade_date, event, event_key,
                  CAST(SUM(source) AS bigint) AS source_events, CAST(SUM(cat) AS bigint) AS cat_events
                FROM (
                  SELECT trade_date, 'new_order' AS event, CAST(order_id AS varchar) AS event_key,
                    CAST(1 AS bigint) AS source, CAST(0 AS bigint) AS cat
                  FROM %s o WHERE event_type = 'accepted'
                  UNION ALL
                  SELECT trade_date, 'route', route_id, CAST(1 AS bigint), CAST(0 AS bigint) FROM %s r
                  UNION ALL
                  SELECT trade_date, 'trade', exec_id, CAST(1 AS bigint), CAST(0 AS bigint) FROM %s e
                  UNION ALL
                  SELECT trade_date,
                    CASE event_type WHEN 'MENO' THEN 'new_order' WHEN 'MEOR' THEN 'route' ELSE 'trade' END,
                    CASE event_type WHEN 'MENO' THEN CAST(order_id AS varchar) WHEN 'MEOR' THEN route_id ELSE exec_id END,
                    CAST(0 AS bigint), CAST(1 AS bigint)
                  FROM %s c
                ) t
                WHERE trade_date < current_date
                GROUP BY trade_date, event, event_key
                HAVING SUM(source) <> 1 OR SUM(cat) <> 1""".formatted(events, routes, executions, cat)));
        }
        return views;
    }

    private static boolean exists(Predicate<String> exists, Feed feed) {
        for (Feed.Path p : Feed.Path.values()) {
            if (exists.test(feed.lakeTable(p))) {
                return true;
            }
        }
        return false;
    }

    /** A feed's lake tables that exist, as one relation with the given columns. */
    private static String union(String catalog, Predicate<String> exists, Feed feed, String columns) {
        List<String> selects = new ArrayList<>();
        for (Feed.Path p : Feed.Path.values()) {
            String table = feed.lakeTable(p);
            if (exists.test(table)) {
                int dot = table.lastIndexOf('.');
                selects.add("SELECT " + columns + " FROM " + catalog + ".\"" + table.substring(0, dot) + "\"."
                    + table.substring(dot + 1));
            }
        }
        if (selects.isEmpty()) {
            throw new IllegalStateException("no lake table of " + feed + " exists yet: run the bootstrap");
        }
        return "(" + String.join(" UNION ALL ", selects) + ")";
    }

    private static Types.NestedField field(String name, Type type) {
        return Types.NestedField.optional(0, name, type);
    }

    private static Schema schema(Types.NestedField... fields) {
        List<Types.NestedField> numbered = new ArrayList<>();
        int id = 1;
        for (Types.NestedField f : fields) {
            numbered.add(Types.NestedField.optional(id++, f.name(), f.type()));
        }
        return new Schema(numbered);
    }
}
