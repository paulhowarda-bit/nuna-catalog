package nuna.datagen.world;

/**
 * Each kind of row the world produces, and the two ways it can reach the lake (D50, amended 2026-11-24). Through Kafka,
 * it goes out on its topic and the demo's Iceberg sink routes each record to its table by {@link #ROUTE_FIELD}; through
 * SingleStore, it is written to a table in database {@code brokerage} whose change feed copies it into the lake. Each
 * feed has a home path, the one it takes while both are up: the orders SingleStore, every other feed Kafka.
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
    ORDERS(Path.SINGLESTORE, "brokerage.trading.order-events", "brokerage.trading", "orders");

    public enum Path { KAFKA, SINGLESTORE }

    /** The field of a Kafka record that names its table (the sink's iceberg.tables.route-field). */
    public static final String ROUTE_FIELD = "lake_table";

    /** Where the change feeds of the Kafka feeds' SingleStore tables make their copies. */
    public static final String SINGLESTORE_COPIES = "brokerage.singlestore";

    /** The path the feed takes while both are up. */
    public final Path path;
    /** The Kafka topic the feed goes out on when it goes through Kafka. */
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

    /**
     * The lake table the feed lands in through a path: through its home path, {@code namespace.table}; the orders
     * through Kafka, {@code brokerage.trading.order_events}, every change appended (the sink cannot update a row); any
     * other feed through SingleStore, its change feed's copy under {@link #SINGLESTORE_COPIES}.
     */
    public String lakeTable(Path via) {
        if (via == path) {
            return namespace + "." + table;
        }
        return this == ORDERS ? namespace + ".order_events" : SINGLESTORE_COPIES + "." + table;
    }

    /** Whether the feed carries the world's days (and is tagged at each end of day), not reference data sent once. */
    public boolean daily() {
        return switch (this) {
            case BARS_5M, PRICES_EOD, FINANCIAL_TRANSACTIONS, JOURNAL_LINES, POSTING_REJECTIONS, BALANCES_EOD -> true;
            default -> false;
        };
    }
}
