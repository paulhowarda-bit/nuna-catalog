package nuna.datagen.world;

import java.util.Arrays;
import java.util.List;

/**
 * Each kind of row the world produces, and the two ways it can reach the lake (D50, amended 2026-11-24). Through Kafka,
 * it goes out on its topic and the demo's Iceberg sink routes each record to its table by {@link #ROUTE_FIELD}; through
 * SingleStore, it is written to a table in database {@code brokerage} whose change feed copies it into the lake. Each
 * feed has a home path, the one it takes while both are up: the order management system's SingleStore, every other
 * feed Kafka.
 *
 * <p>Every feed but the orders is numbered: an append-only stream whose records each way are counted, so a restart or a
 * change of route sends none twice ({@code Publisher}). The orders are SingleStore's current state of each order,
 * upserted, and go nowhere else: their every change is a row of {@link #ORDER_EVENTS}, which goes either way.
 */
public enum Feed {
    CALENDAR(Path.KAFKA, "brokerage.reference.calendar", "brokerage.reference", "calendar"),
    SECURITIES(Path.KAFKA, "brokerage.reference.securities", "brokerage.reference", "securities"),
    CHART_OF_ACCOUNTS(Path.KAFKA, "brokerage.ledger.chart-of-accounts", "brokerage.ledger", "chart_of_accounts"),
    BOOKING_SCHEMES(Path.KAFKA, "brokerage.ledger.booking-schemes", "brokerage.ledger", "booking_schemes"),
    ACCOUNTS(Path.KAFKA, "brokerage.party.accounts", "brokerage.party", "accounts"),
    BARS_5M(Path.KAFKA, "brokerage.markets.bars-5m", "brokerage.markets", "bars_5m"),
    PRICES_EOD(Path.KAFKA, "brokerage.markets.prices-eod", "brokerage.markets", "prices_eod"),
    FINANCIAL_TRANSACTIONS(Path.KAFKA, "brokerage.ledger.financial-transactions", "brokerage.ledger", "financial_transactions"),
    JOURNAL_LINES(Path.KAFKA, "brokerage.ledger.journal-lines", "brokerage.ledger", "journal_lines"),
    POSTING_REJECTIONS(Path.KAFKA, "brokerage.ledger.posting-rejections", "brokerage.ledger", "posting_rejections"),
    BALANCES_EOD(Path.KAFKA, "brokerage.ledger.balances-eod", "brokerage.ledger", "balances_eod"),
    ORDERS(Path.SINGLESTORE, null, "brokerage.trading", "orders"),
    // The order management system (item 151): an event per change of an order, a route per venue an order is sent
    // to, an execution per fill, each fill the ledger's (kit templates 41, 44, 61, 64: the same fill id, price and
    // quantity).
    ORDER_EVENTS(Path.SINGLESTORE, "brokerage.oms.order-events", "brokerage.trading", "order_events"),
    ROUTES(Path.SINGLESTORE, "brokerage.oms.routes", "brokerage.trading", "routes"),
    EXECUTIONS(Path.SINGLESTORE, "brokerage.oms.executions", "brokerage.trading", "executions"),
    // Clearing and custody (item 151): the firm's CNS obligations, their settlement and fails, the stock record and
    // the locates of short sales.
    CNS_OBLIGATIONS(Path.KAFKA, "brokerage.clearing.cns-obligations", "brokerage.clearing", "cns_obligations"),
    SETTLEMENTS(Path.KAFKA, "brokerage.clearing.settlements", "brokerage.clearing", "settlements"),
    FAILS(Path.KAFKA, "brokerage.clearing.fails", "brokerage.clearing", "fails"),
    STOCK_RECORD(Path.KAFKA, "brokerage.clearing.stock-record", "brokerage.clearing", "stock_record"),
    LOCATES(Path.KAFKA, "brokerage.clearing.locates", "brokerage.clearing", "locates"),
    // The back office (item 152): the parties behind the accounts and their KYC records, each change of a party, a
    // suitability profile or a trusted contact a new version; beneficial_owners has a sink connector of its own
    // (demo/brokerage/owners-connector.json), as its table lands unclassified in a regulated namespace (D27).
    PARTIES(Path.KAFKA, "brokerage.party.parties", "brokerage.party", "parties"),
    ACCOUNT_ROLES(Path.KAFKA, "brokerage.party.account-roles", "brokerage.party", "account_roles"),
    CIP_CHECKS(Path.KAFKA, "brokerage.party.cip-checks", "brokerage.party", "cip_checks"),
    SUITABILITY_PROFILES(Path.KAFKA, "brokerage.party.suitability-profiles", "brokerage.party", "suitability_profiles"),
    BENEFICIAL_OWNERS(Path.KAFKA, "brokerage.party.beneficial-owners", "brokerage.party", "beneficial_owners"),
    TRUSTED_CONTACTS(Path.KAFKA, "brokerage.party.trusted-contacts", "brokerage.party", "trusted_contacts"),
    // Money movement (item 152): each payment's every change of status, tied to the ledger's money transactions (kit
    // templates 30 to 35 and 70 to 77: the same payment id and amount), ACH returns (74, 75) and ACATS transfers (82
    // to 85: the same control number).
    PAYMENTS(Path.KAFKA, "brokerage.money-movement.payments", "brokerage.money_movement", "payments"),
    ACH_RETURNS(Path.KAFKA, "brokerage.money-movement.ach-returns", "brokerage.money_movement", "ach_returns"),
    ACATS_TRANSFERS(Path.KAFKA, "brokerage.money-movement.acats-transfers", "brokerage.money_movement", "acats_transfers"),
    // Compliance (item 152): the synthetic sanctions list in OFAC's SDN layout and every screen against it, AML alerts
    // and cases, surveillance alerts and complaints. Read only by demo-compliance (demo.sh lake_grants).
    SANCTIONS_LIST(Path.KAFKA, "brokerage.compliance.sanctions-list", "brokerage.compliance", "sanctions_list"),
    SANCTIONS_SCREENS(Path.KAFKA, "brokerage.compliance.sanctions-screens", "brokerage.compliance", "sanctions_screens"),
    AML_ALERTS(Path.KAFKA, "brokerage.compliance.aml-alerts", "brokerage.compliance", "aml_alerts"),
    AML_CASES(Path.KAFKA, "brokerage.compliance.aml-cases", "brokerage.compliance", "aml_cases"),
    SURVEILLANCE_ALERTS(Path.KAFKA, "brokerage.compliance.surveillance-alerts", "brokerage.compliance", "surveillance_alerts"),
    COMPLAINTS(Path.KAFKA, "brokerage.compliance.complaints", "brokerage.compliance", "complaints"),
    // The firm's books and reporting (item 153). Asset servicing: each corporate action as announced, each account's
    // entitlement at the ex-date and each payment.
    CORPORATE_ACTIONS(Path.KAFKA, "brokerage.reference.corporate-actions", "brokerage.reference", "corporate_actions"),
    ENTITLEMENTS(Path.KAFKA, "brokerage.asset-servicing.entitlements", "brokerage.asset_servicing", "entitlements"),
    INCOME_PAYMENTS(Path.KAFKA, "brokerage.asset-servicing.income-payments", "brokerage.asset_servicing",
        "income_payments"),
    // Margin: the house requirement of each security, each margin account at the day's end, and the calls.
    HOUSE_REQUIREMENTS(Path.KAFKA, "brokerage.margin.house-requirements", "brokerage.margin", "house_requirements"),
    ACCOUNT_MARGIN_EOD(Path.KAFKA, "brokerage.margin.account-margin-eod", "brokerage.margin", "account_margin_eod"),
    MARGIN_CALLS(Path.KAFKA, "brokerage.margin.margin-calls", "brokerage.margin", "margin_calls"),
    // Tax: the open tax lots at each day's end, each lot's part of a sale, and the wash sales.
    TAX_LOTS(Path.KAFKA, "brokerage.tax.tax-lots", "brokerage.tax", "tax_lots"),
    REALIZED_GAINS(Path.KAFKA, "brokerage.tax.realized-gains", "brokerage.tax", "realized_gains"),
    WASH_SALES(Path.KAFKA, "brokerage.tax.wash-sales", "brokerage.tax", "wash_sales"),
    // Client reporting: a confirmation per order and trade date (10b-10); the statements are the month-end close's.
    CONFIRMS(Path.KAFKA, "brokerage.client-reporting.confirms", "brokerage.client_reporting", "confirms"),
    // Regulatory: the CAT events of every order, route and fill, and the weekly 15c3-3 reserve computation.
    CAT_EVENTS(Path.KAFKA, "brokerage.regulatory.cat-events", "brokerage.regulatory", "cat_events"),
    RESERVE_COMPUTATION(Path.KAFKA, "brokerage.regulatory.reserve-computation", "brokerage.regulatory",
        "reserve_computation");

    public enum Path { KAFKA, SINGLESTORE }

    /** The field of a Kafka record that names its table (the sink's iceberg.tables.route-field). */
    public static final String ROUTE_FIELD = "lake_table";

    /** Where the change feeds of the Kafka feeds' SingleStore tables make their copies. */
    public static final String SINGLESTORE_COPIES = "brokerage.singlestore";

    /** Where the SingleStore feeds land when they go through Kafka: the mirror of {@link #SINGLESTORE_COPIES}. */
    public static final String KAFKA_COPIES = "brokerage.kafka";

    /**
     * Where the compliance feeds' SingleStore copies land (item 152): under {@code brokerage.compliance}, whose grants,
     * classification and regulated mark they inherit, so only its readers (demo-compliance) read them.
     */
    public static final String COMPLIANCE = "brokerage.compliance", COMPLIANCE_COPIES = COMPLIANCE + ".singlestore";

    /** The path the feed takes while both are up. */
    public final Path path;
    /** The Kafka topic the feed goes out on when it goes through Kafka; none for the orders. */
    public final String topic;
    public final String namespace;
    /** The feed's table in SingleStore's database {@code brokerage}, and in the lake under {@link #namespace}. */
    public final String table;

    Feed(Path path, String topic, String namespace, String table) {
        this.path = path;
        this.topic = topic;
        this.namespace = namespace;
        this.table = table;
    }

    /** Every feed but the orders: the append-only streams, counted each way. */
    public static List<Feed> numbered() {
        return Arrays.stream(values()).filter(f -> f != ORDERS).toList();
    }

    /**
     * The lake table the feed lands in through a path: through its home path, {@code namespace.table}; through the other,
     * the copy under {@link #SINGLESTORE_COPIES} (a Kafka feed through SingleStore; a compliance feed's under
     * {@link #COMPLIANCE_COPIES}) or {@link #KAFKA_COPIES} (a SingleStore feed through Kafka). The orders go
     * through SingleStore only.
     */
    public String lakeTable(Path via) {
        if (via == path) {
            return namespace + "." + table;
        }
        if (this == ORDERS) {
            throw new IllegalArgumentException("the orders go through SingleStore only");
        }
        if (namespace.equals(COMPLIANCE)) {
            return COMPLIANCE_COPIES + "." + table;
        }
        return (path == Path.KAFKA ? SINGLESTORE_COPIES : KAFKA_COPIES) + "." + table;
    }

    /** Whether the feed carries the world's days (and is tagged at each end of day), not reference data sent once. */
    public boolean daily() {
        return switch (this) {
            case CALENDAR, SECURITIES, CHART_OF_ACCOUNTS, BOOKING_SCHEMES, ACCOUNTS, SANCTIONS_LIST, ORDERS -> false;
            default -> true;
        };
    }
}
