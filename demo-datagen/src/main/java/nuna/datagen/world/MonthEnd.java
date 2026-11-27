package nuna.datagen.world;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.Schema;
import org.apache.iceberg.types.Type;
import org.apache.iceberg.types.Types;

/**
 * The month-end close (item 153): what the firm publishes once a month ends, written by the generator itself in one
 * multi-table commit ({@code /transactions/commit}) and tagged {@code month-end-YYYY-MM}, not sent as a feed. Each table
 * is partitioned by its {@code period}, so a restatement replaces one month of it.
 *
 * @param period    the month closed
 * @param periodEnd its last business day
 * @param rows      each table's rows for the month, in the order of {@link Table}
 */
public record MonthEnd(YearMonth period, LocalDate periodEnd, Map<MonthEnd.Table, List<Map<String, Object>>> rows) {

    /** A table the close writes. */
    public enum Table {
        STATEMENTS("brokerage.client_reporting", "statements"),
        STATEMENT_POSITIONS("brokerage.client_reporting", "statement_positions"),
        FORM_1099B("brokerage.tax", "form_1099b"),
        FORM_1099DIV("brokerage.tax", "form_1099div"),
        RULE606_ROUTING("brokerage.regulatory", "rule606_routing"),
        NET_CAPITAL("brokerage.regulatory", "net_capital"),
        BEST_EX_REVIEWS("brokerage.trading", "best_ex_reviews");

        public final String namespace;
        public final String table;

        Table(String namespace, String table) {
            this.namespace = namespace;
            this.table = table;
        }

        public String lakeTable() {
            return namespace + "." + table;
        }

        /** The tables a restatement of the statements rewrites. */
        public boolean statement() {
            return this == STATEMENTS || this == STATEMENT_POSITIONS;
        }
    }

    /**
     * A correction of a month already closed: the month's statements and positions as they should have read, every
     * account's (the writer replaces the month's partition whole), on a catalog branch merged into main.
     *
     * @param id     what the correction is for (the busted fill's id), recorded in its snapshots
     * @param reason the restatement reason the corrected rows carry
     * @param at     when the correction was made, in business time
     */
    public record Restatement(String id, YearMonth period, String reason, long brokerageAccount, Instant at,
                              Map<Table, List<Map<String, Object>>> rows) {}

    public String tag() {
        return tag(period);
    }

    public static String tag(YearMonth period) {
        return "month-end-" + period;
    }

    private static final Type MONEY = LakeSchemas.MONEY, PRICE = LakeSchemas.PRICE, SHARES = LakeSchemas.SHARES,
        RATE = LakeSchemas.RATE, LONG = LakeSchemas.LONG, INT = LakeSchemas.INT, TEXT = LakeSchemas.TEXT,
        DATE = LakeSchemas.DATE, FLAG = LakeSchemas.FLAG, TIME = LakeSchemas.TIME;

    /** The table's columns; every table starts with {@code period}, its partition. */
    public static Schema schema(Table t) {
        List<Object[]> c = new ArrayList<>();
        col(c, "period", TEXT);
        switch (t) {
            case STATEMENTS -> {
                col(c, "statement_id", TEXT, "brokerage_account", LONG, "account_kind", TEXT, "period_start", DATE,
                    "period_end", DATE, "opening_equity", MONEY, "cash_balance", MONEY,
                    "unsettled_sales_receivable", MONEY, "unsettled_purchases_payable", MONEY,
                    "dividends_receivable", MONEY, "accrued_fees", MONEY, "accrued_interest", MONEY,
                    "payment_in_lieu_payable", MONEY, "long_market_value", MONEY, "short_market_value", MONEY,
                    "total_equity", MONEY, "net_deposits", MONEY, "dividends", MONEY, "realized_gain_loss", MONEY,
                    "account_fees", MONEY, "margin_interest", MONEY, "payments_in_lieu", MONEY,
                    "transaction_fees", MONEY, "securities_transferred", MONEY, "restated", FLAG,
                    "restatement_reason", TEXT, "generated_at", TIME);
            }
            case STATEMENT_POSITIONS -> col(c, "statement_id", TEXT, "brokerage_account", LONG, "security_id", LONG,
                "symbol", TEXT, "cusip", TEXT, "quantity", SHARES, "close_price", MONEY, "market_value", MONEY,
                "cost_basis", MONEY, "unrealized_gain_loss", MONEY);
            case FORM_1099B -> col(c, "tax_year", INT, "line_id", TEXT, "brokerage_account", LONG, "realization_id", TEXT,
                "financial_transaction_id", LONG, "lot", LONG, "description", TEXT, "security_id", LONG, "symbol", TEXT,
                "cusip", TEXT, "quantity", SHARES, "date_acquired", DATE, "date_sold", DATE, "proceeds", MONEY,
                "cost_basis", MONEY, "wash_sale_loss_disallowed", MONEY, "gain_or_loss", MONEY,
                "basis_wash_adjustment", MONEY, "term", TEXT, "covered", FLAG, "gross_proceeds_reported", FLAG);
            case FORM_1099DIV -> col(c, "tax_year", INT, "line_id", TEXT, "brokerage_account", LONG,
                "corporate_action_id", TEXT, "security_id", LONG, "symbol", TEXT, "pay_date", DATE,
                "ordinary_dividends", MONEY, "qualified_dividends", MONEY, "federal_tax_withheld", MONEY,
                "financial_transaction_id", LONG);
            case RULE606_ROUTING -> col(c, "security_class", TEXT, "order_category", TEXT, "venue", TEXT,
                "orders_routed", LONG, "percent_of_orders", RATE, "shares_routed", LONG, "net_payment_paid", MONEY,
                "net_payment_paid_per_hundred", PRICE, "net_payment_received", MONEY);
            case NET_CAPITAL -> col(c, "as_of_date", DATE, "method", TEXT, "total_ownership_equity", MONEY,
                "nonallowable_assets", MONEY, "haircuts", MONEY, "net_capital", MONEY, "aggregate_debit_items", MONEY,
                "minimum_net_capital", MONEY, "excess_net_capital", MONEY, "early_warning_level", MONEY);
            case BEST_EX_REVIEWS -> col(c, "venue", TEXT, "order_type", TEXT, "executions", LONG, "shares", LONG,
                "routed_shares", LONG, "fill_rate", RATE, "average_improvement_per_share", PRICE,
                "average_time_to_execution_ms", LONG, "rank", INT, "finding", TEXT, "reviewed_on", DATE);
        }
        List<Types.NestedField> fields = new ArrayList<>();
        int id = 1;
        for (Object[] f : c) {
            fields.add(Types.NestedField.optional(id++, (String) f[0], (Type) f[1]));
        }
        return new Schema(fields);
    }

    public static PartitionSpec spec(Schema schema) {
        return PartitionSpec.builderFor(schema).identity("period").build();
    }

    private static void col(List<Object[]> c, Object... nameType) {
        for (int i = 0; i < nameType.length; i += 2) {
            c.add(new Object[] {nameType[i], nameType[i + 1]});
        }
    }

    /** An empty set of rows for every table. */
    static Map<Table, List<Map<String, Object>>> emptyRows() {
        Map<Table, List<Map<String, Object>>> rows = new EnumMap<>(Table.class);
        for (Table t : Table.values()) {
            rows.put(t, new ArrayList<>());
        }
        return rows;
    }
}
