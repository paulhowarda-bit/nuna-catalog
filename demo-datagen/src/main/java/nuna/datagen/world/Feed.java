package nuna.datagen.world;

/**
 * Where each kind of row the world produces goes, and the Iceberg table it lands in. Kafka feeds go out on their topic
 * and the demo's Iceberg sink routes each record to its table by {@link #ROUTE_FIELD}; the orders go into SingleStore,
 * whose change feed brings them into the lake.
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
    ORDERS(Path.SINGLESTORE, "orders", "brokerage.trading", "orders");

    public enum Path { KAFKA, SINGLESTORE }

    /** The field of a Kafka record that names its table (the sink's iceberg.tables.route-field). */
    public static final String ROUTE_FIELD = "lake_table";

    public final Path path;
    /** The Kafka topic, or the SingleStore table in database {@code brokerage}. */
    public final String source;
    public final String namespace;
    public final String table;

    Feed(Path path, String source, String namespace, String table) {
        this.path = path;
        this.source = source;
        this.namespace = namespace;
        this.table = table;
    }

    /** The table's name in the lake, as the sink routes to it. */
    public String lakeTable() {
        return namespace + "." + table;
    }

    /** Whether the feed carries the world's days (and is tagged at each end of day), not reference data sent once. */
    public boolean daily() {
        return switch (this) {
            case BARS_5M, PRICES_EOD, FINANCIAL_TRANSACTIONS, JOURNAL_LINES, POSTING_REJECTIONS, BALANCES_EOD -> true;
            default -> false;
        };
    }
}
