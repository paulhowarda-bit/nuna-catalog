package nuna.datagen.world;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import nuna.datagen.kit.FinancialTransaction;
import nuna.datagen.kit.Kit;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.Schema;
import org.apache.iceberg.types.Type;
import org.apache.iceberg.types.Types;

/**
 * The Iceberg table each numbered feed lands in through Kafka: its columns, in the order the world writes them, and its
 * partitioning. The bootstrap creates the tables from these; the sink writes into them and never changes them (its
 * schema evolution is off), so a column missing here is a value dropped on the way in. SingleStore's tables have the
 * same columns ({@code SingleStoreTables}). The orders are not here: they go through SingleStore only, and its change
 * feed makes their table from SingleStore's.
 */
public final class LakeSchemas {

    /**
     * financial_transactions' column for a short sale's locate. The table starts without it; the generator adds it when
     * the world first sells short, so the table's history shows a schema change.
     */
    public static final String LOCATE_ID = "locate_id";

    static final Type MONEY = Types.DecimalType.of(18, 2);
    static final Type PRICE = Types.DecimalType.of(18, 6);
    static final Type SHARES = Types.DecimalType.of(18, 5);
    /** A journal line's or balance's amount: money to the cent or shares to 10^-5, as its measure says. */
    static final Type AMOUNT = Types.DecimalType.of(20, 5);
    static final Type BAR_PRICE = Types.DecimalType.of(18, 4);
    static final Type RATE = Types.DecimalType.of(9, 6);
    /** A percentage to the hundredth: a beneficial owner's share, a sanctions screen's match score. */
    static final Type PERCENT = Types.DecimalType.of(5, 2);
    static final Type LONG = Types.LongType.get();
    static final Type INT = Types.IntegerType.get();
    static final Type TEXT = Types.StringType.get();
    static final Type DATE = Types.DateType.get();
    static final Type FLAG = Types.BooleanType.get();
    static final Type TIME = Types.TimestampType.withZone();

    private LakeSchemas() {
    }

    /** The table's schema as the bootstrap creates it: the Kafka path's table. */
    public static Schema schema(Feed feed, Kit kit) {
        Columns c = new Columns();
        switch (feed) {
            case CALENDAR -> c.add("calendar_date", DATE).add("is_business_day", FLAG).add("holiday", TEXT)
                .add("settlement_date", DATE).add("is_month_end", FLAG);
            case SECURITIES -> c.add("security_id", LONG).add("symbol", TEXT).add("name", TEXT)
                .add("security_type", TEXT).add("sector", TEXT).add("cusip", TEXT).add("isin", TEXT)
                .add("currency_id", LONG).add("listed_on", TEXT).add("unit_exponent", INT);
            case CHART_OF_ACCOUNTS -> c.add("definition_id", INT).add("parent_id", INT).add("code", TEXT)
                .add("name", TEXT).add("natural_class", TEXT).add("measure", TEXT).add("level", TEXT)
                .add("normal_side", TEXT).add("may_reverse", FLAG).add("key_dimensions", TEXT).add("description", TEXT);
            case BOOKING_SCHEMES -> c.add("booking_scheme_id", INT).add("name", TEXT).add("template_id", INT)
                .add("template_name", TEXT).add("calculations", INT).add("journal_lines", INT).add("note", TEXT);
            case ACCOUNTS -> c.add("brokerage_account", LONG).add("account_kind", TEXT).add("persona", TEXT)
                .add("opened_on", DATE).add("disposal_method", TEXT).add("base_currency_id", LONG).add("status", TEXT)
                .add("registration_type", TEXT);
            case BARS_5M -> c.add("security_id", LONG).add("symbol", TEXT).add("bar_start", TIME)
                .add("business_date", DATE).add("open", BAR_PRICE).add("high", BAR_PRICE).add("low", BAR_PRICE)
                .add("close", BAR_PRICE).add("volume", LONG);
            case PRICES_EOD -> c.add("business_date", DATE).add("security_id", LONG).add("symbol", TEXT)
                .add("close_price", MONEY).add("currency_id", LONG);
            case FINANCIAL_TRANSACTIONS -> {
                c.add("financial_transaction_id", LONG).add("template_id", INT).add("template_name", TEXT)
                    .add("booking_scheme_id", INT).add("brokerage_account", LONG).add("security_id", LONG)
                    .add("currency_id", LONG).add("quantity", SHARES).add("originating_financial_transaction_id", LONG)
                    .add("effective_date", DATE).add("originator", TEXT).add("business_date", DATE)
                    .add("event_time", TIME);
                templateColumns(kit).forEach((column, name) -> {
                    if (!column.equals(LOCATE_ID)) {
                        c.addTemplateField(column, name);
                    }
                });
            }
            case JOURNAL_LINES -> c.add("journal_line_id", LONG).add("financial_transaction_id", LONG)
                .add("line_number", INT).add("booking_scheme_id", INT).add("template_id", INT)
                .add("sub_ledger_id", INT).add("sub_ledger", TEXT).add("definition_id", INT)
                .add("definition_name", TEXT).add("ledger_account_id", LONG).add("brokerage_account", LONG)
                .add("currency_id", LONG).add("security_id", LONG).add("lot", LONG).add("measure", TEXT)
                .add("side", TEXT).add("amount", AMOUNT).add("effective_date", DATE).add("business_date", DATE)
                .add("event_time", TIME);
            case POSTING_REJECTIONS -> c.add("financial_transaction_id", LONG).add("template_id", INT)
                .add("template_name", TEXT).add("brokerage_account", LONG)
                .add("originating_financial_transaction_id", LONG).add("reason", TEXT).add("detail", TEXT)
                .add("business_date", DATE).add("event_time", TIME);
            case BALANCES_EOD -> c.add("business_date", DATE).add("brokerage_account", LONG)
                .add("ledger_account_id", LONG).add("sub_ledger", TEXT).add("definition_id", INT)
                .add("definition_name", TEXT).add("security_id", LONG).add("lot", LONG).add("measure", TEXT)
                .add("normal_side", TEXT).add("balance", AMOUNT);
            case ORDERS -> throw new IllegalArgumentException("the orders go through SingleStore only");
            case ORDER_EVENTS -> c.add("event_id", LONG).add("order_id", LONG).add("order_ref", TEXT)
                .add("event_type", TEXT).add("brokerage_account", LONG).add("security_id", LONG).add("symbol", TEXT)
                .add("side", TEXT).add("order_type", TEXT).add("limit_price", PRICE).add("quantity", LONG)
                .add("filled_quantity", LONG).add("leaves_quantity", LONG).add("average_price", PRICE)
                .add("status", TEXT).add(LOCATE_ID, TEXT).add("route_id", TEXT).add("exec_id", TEXT)
                .add("trade_date", DATE).add("event_time", TIME);
            case ROUTES -> c.add("route_id", TEXT).add("order_id", LONG).add("order_ref", TEXT)
                .add("brokerage_account", LONG).add("security_id", LONG).add("symbol", TEXT).add("side", TEXT)
                .add("venue", TEXT).add("routed_quantity", LONG).add("limit_price", PRICE).add("trade_date", DATE)
                .add("sent_at", TIME);
            case EXECUTIONS -> c.add("exec_id", TEXT).add("order_id", LONG).add("order_ref", TEXT)
                .add("route_id", TEXT).add("fill_id", TEXT).add("financial_transaction_id", LONG)
                .add("brokerage_account", LONG).add("security_id", LONG).add("symbol", TEXT).add("side", TEXT)
                .add("venue", TEXT).add("last_quantity", LONG).add("last_price", PRICE).add("gross_amount", MONEY)
                .add("cum_quantity", LONG).add("leaves_quantity", LONG).add("trade_date", DATE)
                .add("settlement_date", DATE).add("executed_at", TIME);
            case CNS_OBLIGATIONS -> c.add("obligation_id", TEXT).add("security_id", LONG).add("symbol", TEXT)
                .add("cusip", TEXT).add("trade_date", DATE).add("settlement_date", DATE).add("buy_quantity", LONG)
                .add("buy_amount", MONEY).add("sell_quantity", LONG).add("sell_amount", MONEY)
                .add("short_sale_quantity", LONG).add("net_quantity", LONG).add("net_amount", MONEY)
                .add("direction", TEXT).add("computed_at", TIME);
            case SETTLEMENTS -> c.add("settlement_id", TEXT).add("obligation_id", TEXT).add("security_id", LONG)
                .add("symbol", TEXT).add("settlement_date", DATE).add("direction", TEXT).add("quantity_due", LONG)
                .add("quantity_settled", LONG).add("quantity_failed", LONG).add("net_amount", MONEY)
                .add("status", TEXT).add("settled_at", TIME);
            case FAILS -> c.add("fail_event_id", LONG).add("fail_id", TEXT).add("event_type", TEXT)
                .add("obligation_id", TEXT).add("security_id", LONG).add("symbol", TEXT).add("settlement_date", DATE)
                .add("quantity", LONG).add("reason", TEXT).add("reg_sho_rule", TEXT).add("close_out_deadline", TIME)
                .add("close_out_price", MONEY).add("close_out_amount", MONEY).add("business_date", DATE)
                .add("event_time", TIME);
            case STOCK_RECORD -> c.add("business_date", DATE).add("position_id", TEXT).add("security_id", LONG)
                .add("symbol", TEXT).add("cusip", TEXT).add("side", TEXT).add("category", TEXT)
                .add("brokerage_account", LONG).add("quantity", SHARES).add("close_price", MONEY)
                .add("market_value", MONEY);
            case LOCATES -> c.add("locate_id", TEXT).add("brokerage_account", LONG).add("security_id", LONG)
                .add("symbol", TEXT).add("quantity", LONG).add("source", TEXT).add("easy_to_borrow", FLAG)
                .add("borrow_rate", RATE).add("order_ref", TEXT).add("business_date", DATE)
                .add("requested_at", TIME).add("expires_at", TIME);
            case PARTIES -> c.add("party_id", LONG).add("version", INT).add("party_type", TEXT).add("legal_name", TEXT)
                .add("first_name", TEXT).add("last_name", TEXT).add("birth_date", DATE).add("tax_id", TEXT)
                .add("tax_id_type", TEXT).add("address_line", TEXT).add("city", TEXT).add("state", TEXT)
                .add("postal_code", TEXT).add("country", TEXT).add("email", TEXT).add("phone", TEXT)
                .add("employment_status", TEXT).add("employer", TEXT).add("occupation", TEXT)
                .add("control_person", FLAG).add("politically_exposed", FLAG).add("change_reason", TEXT)
                .add("effective_date", DATE).add("event_time", TIME);
            case ACCOUNT_ROLES -> c.add("role_id", LONG).add("brokerage_account", LONG).add("party_id", LONG)
                .add("role", TEXT).add("effective_date", DATE).add("event_time", TIME);
            case CIP_CHECKS -> c.add("cip_id", LONG).add("party_id", LONG).add("brokerage_account", LONG)
                .add("method", TEXT).add("evidence", TEXT).add("result", TEXT).add("checked_on", DATE)
                .add("event_time", TIME);
            case SUITABILITY_PROFILES -> c.add("brokerage_account", LONG).add("version", INT)
                .add("investment_objective", TEXT).add("risk_tolerance", TEXT).add("time_horizon", TEXT)
                .add("liquidity_needs", TEXT).add("investment_experience", TEXT).add("annual_income_band", TEXT)
                .add("net_worth_band", TEXT).add("liquid_net_worth_band", TEXT).add("change_reason", TEXT)
                .add("effective_date", DATE).add("event_time", TIME);
            case BENEFICIAL_OWNERS -> c.add("owner_id", LONG).add("brokerage_account", LONG)
                .add("entity_party_id", LONG).add("full_name", TEXT).add("birth_date", DATE).add("tax_id", TEXT)
                .add("address_line", TEXT).add("city", TEXT).add("state", TEXT).add("postal_code", TEXT)
                .add("ownership_percent", PERCENT).add("control_person", FLAG).add("certified_on", DATE)
                .add("event_time", TIME);
            case TRUSTED_CONTACTS -> c.add("contact_id", LONG).add("version", INT).add("brokerage_account", LONG)
                .add("full_name", TEXT).add("relationship", TEXT).add("phone", TEXT).add("email", TEXT)
                .add("change_reason", TEXT).add("effective_date", DATE).add("event_time", TIME);
            case PAYMENTS -> c.add("payment_id", TEXT).add("version", INT).add("brokerage_account", LONG)
                .add("method", TEXT).add("direction", TEXT).add("status", TEXT).add("amount", MONEY)
                .add("financial_transaction_id", LONG).add("template_id", INT).add("expected_settlement_date", DATE)
                .add("bank_routing_number", TEXT).add("bank_account_number", TEXT).add("counterparty_name", TEXT)
                .add("contra_brokerage_account", LONG).add("business_date", DATE).add("event_time", TIME);
            case ACH_RETURNS -> c.add("return_id", TEXT).add("payment_id", TEXT).add("brokerage_account", LONG)
                .add("return_code", TEXT).add("return_reason", TEXT).add("amount", MONEY)
                .add("after_settlement", FLAG).add("financial_transaction_id", LONG).add("business_date", DATE)
                .add("event_time", TIME);
            case ACATS_TRANSFERS -> c.add("acats_control_number", TEXT).add("brokerage_account", LONG)
                .add("direction", TEXT).add("transfer_type", TEXT).add("contra_firm_number", TEXT).add("status", TEXT)
                .add("requested_on", DATE).add("settlement_date", DATE).add("cash_amount", MONEY)
                .add("positions", INT).add("shares", SHARES).add("fee_amount", MONEY).add("event_time", TIME);
            case SANCTIONS_LIST -> c.add("list_version", TEXT).add("ent_num", LONG).add("sdn_name", TEXT)
                .add("sdn_type", TEXT).add("program", TEXT).add("title", TEXT).add("call_sign", TEXT)
                .add("vess_type", TEXT).add("tonnage", TEXT).add("grt", TEXT).add("vess_flag", TEXT)
                .add("vess_owner", TEXT).add("remarks", TEXT).add("published_on", DATE);
            case SANCTIONS_SCREENS -> c.add("screen_id", LONG).add("list_version", TEXT).add("trigger", TEXT)
                .add("subject_type", TEXT).add("subject_id", TEXT).add("subject_name", TEXT)
                .add("brokerage_account", LONG).add("payment_id", TEXT).add("result", TEXT)
                .add("matched_ent_num", LONG).add("match_score", PERCENT).add("disposition", TEXT)
                .add("business_date", DATE).add("screened_at", TIME);
            case AML_ALERTS -> c.add("alert_id", TEXT).add("version", INT).add("scenario", TEXT)
                .add("brokerage_account", LONG).add("party_id", LONG).add("status", TEXT).add("amount", MONEY)
                .add("detail", TEXT).add("case_id", TEXT).add("business_date", DATE).add("event_time", TIME);
            case AML_CASES -> c.add("case_id", TEXT).add("version", INT).add("case_type", TEXT)
                .add("brokerage_account", LONG).add("party_id", LONG).add("status", TEXT).add("alert_ids", TEXT)
                .add("opened_on", DATE).add("filing_type", TEXT).add("filing_due", DATE).add("filed_on", DATE)
                .add("filing_reference", TEXT).add("business_date", DATE).add("event_time", TIME);
            case SURVEILLANCE_ALERTS -> c.add("alert_id", TEXT).add("version", INT).add("model", TEXT)
                .add("brokerage_account", LONG).add("security_id", LONG).add("symbol", TEXT).add("trade_date", DATE)
                .add("buy_quantity", LONG).add("sell_quantity", LONG).add("status", TEXT).add("disposition", TEXT)
                .add("business_date", DATE).add("event_time", TIME);
            case COMPLAINTS -> c.add("complaint_id", TEXT).add("version", INT).add("brokerage_account", LONG)
                .add("party_id", LONG).add("channel", TEXT).add("category", TEXT).add("product", TEXT)
                .add("status", TEXT).add("received_on", DATE).add("closed_on", DATE).add("disposition", TEXT)
                .add("business_date", DATE).add("event_time", TIME);
        }
        return new Schema(c.fields);
    }

    /** The table's schema, for a feed whose columns do not depend on the kit (every one but financial_transactions). */
    public static Schema schema(Feed feed) {
        if (feed == Feed.FINANCIAL_TRANSACTIONS) {
            throw new IllegalArgumentException("financial_transactions' columns come from the kit's templates");
        }
        return schema(feed, null);
    }

    /**
     * The column that names what a back-office row is about (item 152): a party, an account's suitability profile, a
     * payment, an alert. A versioned feed has a row per version of it, numbered by {@code version}, and its
     * current-state view keeps the highest; every other feed has one row per key.
     */
    public static String key(Feed feed) {
        return switch (feed) {
            case PARTIES -> "party_id";
            case ACCOUNTS, SUITABILITY_PROFILES -> "brokerage_account";
            case ACCOUNT_ROLES -> "role_id";
            case CIP_CHECKS -> "cip_id";
            case BENEFICIAL_OWNERS -> "owner_id";
            case TRUSTED_CONTACTS -> "contact_id";
            case PAYMENTS -> "payment_id";
            case ACH_RETURNS -> "return_id";
            case ACATS_TRANSFERS -> "acats_control_number";
            case SANCTIONS_LIST -> "ent_num";
            case SANCTIONS_SCREENS -> "screen_id";
            case AML_ALERTS, SURVEILLANCE_ALERTS -> "alert_id";
            case AML_CASES -> "case_id";
            case COMPLAINTS -> "complaint_id";
            default -> throw new IllegalArgumentException(feed + " is not a back-office feed");
        };
    }

    /** The back-office feeds with a row per version of what they are about, and a current-state view each. */
    public static final List<Feed> VERSIONED = List.of(Feed.PARTIES, Feed.SUITABILITY_PROFILES, Feed.TRUSTED_CONTACTS,
        Feed.PAYMENTS, Feed.AML_ALERTS, Feed.AML_CASES, Feed.SURVEILLANCE_ALERTS, Feed.COMPLAINTS);

    /** The table's partitioning: the daily tables by business or trade date, small ones by month, reference data not. */
    public static PartitionSpec spec(Feed feed, Schema schema) {
        return switch (feed) {
            case BARS_5M, FINANCIAL_TRANSACTIONS, JOURNAL_LINES, BALANCES_EOD, STOCK_RECORD ->
                PartitionSpec.builderFor(schema).day("business_date").build();
            case ORDER_EVENTS, ROUTES, EXECUTIONS -> PartitionSpec.builderFor(schema).day("trade_date").build();
            case PRICES_EOD, LOCATES, PAYMENTS, SANCTIONS_SCREENS ->
                PartitionSpec.builderFor(schema).month("business_date").build();
            case CNS_OBLIGATIONS -> PartitionSpec.builderFor(schema).month("trade_date").build();
            case SETTLEMENTS -> PartitionSpec.builderFor(schema).month("settlement_date").build();
            default -> PartitionSpec.unpartitioned();
        };
    }

    /**
     * Every template field the world writes as a column of financial_transactions (the fields after the common ones,
     * which have columns of their own), by column, in template order.
     */
    public static Map<String, String> templateColumns(Kit kit) {
        Map<String, String> columns = new LinkedHashMap<>();
        for (Kit.Template t : new TreeMap<>(kit.templates()).values()) {
            t.fields().forEach((id, name) -> {
                if (id > FinancialTransaction.ORIGINATING_ID) {
                    columns.putIfAbsent(World.snake(name), name);
                }
            });
        }
        return columns;
    }

    /** The template fields that are whole numbers: the other brokerage account of a journal, a split's ratio. */
    private static final Set<String> WHOLE = Set.of("contraBrokerageAccount", "ratioNew", "ratioOld");

    /** Whether a template field's column is a long (the world writes the value as a Long). */
    public static boolean isWhole(String name) {
        return WHOLE.contains(name);
    }

    /** The type of a template field's column, from its name as the kit gives it; a nested type takes ids from ids[0]. */
    public static Type templateType(String name, int[] ids) {
        if (name.equals("lots")) {
            return Types.ListType.ofOptional(ids[0]++, Types.StructType.of(
                Types.NestedField.optional(ids[0]++, "lot", LONG),
                Types.NestedField.optional(ids[0]++, "shares", SHARES)));
        }
        if (name.equals("receivedLots")) {
            return Types.ListType.ofOptional(ids[0]++, Types.StructType.of(
                Types.NestedField.optional(ids[0]++, "lot", LONG),
                Types.NestedField.optional(ids[0]++, "shares", SHARES),
                Types.NestedField.optional(ids[0]++, "cost", MONEY),
                Types.NestedField.optional(ids[0]++, "acquired_date", DATE)));
        }
        if (name.endsWith("Date") || name.equals("periodStart") || name.equals("periodEnd")) {
            return DATE;
        }
        if (isWhole(name)) {
            return LONG;
        }
        if (name.endsWith("Id") || name.equals("venue") || name.endsWith("Code") || name.endsWith("Type")
            || name.endsWith("Number")) {
            return TEXT;
        }
        if (name.endsWith("Price")) {
            return PRICE;
        }
        return name.equals("rate") ? RATE : MONEY;
    }

    /** Field ids in order; nested ones take theirs from the same counter, so every id is unique. */
    private static final class Columns {
        final List<Types.NestedField> fields = new ArrayList<>();
        final int[] ids = {1};

        Columns add(String name, Type type) {
            fields.add(Types.NestedField.optional(ids[0]++, name, type));
            return this;
        }

        void addTemplateField(String column, String name) {
            int id = ids[0]++;
            fields.add(Types.NestedField.optional(id, column, templateType(name, ids)));
        }
    }
}
