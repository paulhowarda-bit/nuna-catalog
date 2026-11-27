package nuna.datagen.live;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import nuna.datagen.world.Feed;
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
     * The three views over the lake tables that exist.
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
                + " net of its customers' fills, in shares and money. No rows when they agree.",
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
                CAST(CASE WHEN template_id IN (41, 64) THEN -fill_amount ELSE gross_amount END AS decimal(20, 2))
              FROM %s f WHERE template_id IN (41, 44, 61, 64)
            ) t
            WHERE trade_date < current_date
            GROUP BY trade_date, security_id
            HAVING SUM(cns_quantity) <> SUM(fill_quantity) OR SUM(cns_amount) <> SUM(fill_amount)""".formatted(cns,
                fills)));
        return views;
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
