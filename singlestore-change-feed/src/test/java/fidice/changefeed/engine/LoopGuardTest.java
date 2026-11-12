package fidice.changefeed.engine;

import static org.assertj.core.api.Assertions.assertThat;

import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.catalog.TableIdentifier;
import org.junit.jupiter.api.Test;

class LoopGuardTest {

    /** CONFIG_JSON of an Iceberg pipeline, as SingleStore 10.9.0 keeps it: the CONFIG clause is a string inside. */
    static final String ICEBERG_PIPELINE = """
            {"name":"orders_back","connection_string":"","link":null,"catalog_link":false,"source_type":"S3",\
            "config":"{\\"catalog_type\\": \\"REST\\", \\"catalog.uri\\": \\"http://catalog:8181\\", \
            \\"catalog.warehouse\\": \\"lakehouse\\",\\n \\"table_id\\": \\"markets.equities.eu.xetra.orders\\", \
            \\"ingest_mode\\": \\"one_time\\"}","credentials":"<CREDENTIALS REDACTED>"}""";

    /** CONFIG_JSON of the change feed's own pipeline: it names the table only in parts. */
    static final String CHANGE_FEED_PIPELINE = """
            {"name":"orders_to_lake","connection_string":"ChangeFeedConnector","source_type":"CONNECTOR",\
            "config":"{\\"singlestore.table\\": \\"orders\\", \\"iceberg.namespace\\": \\"markets.equities.eu.xetra\\", \
            \\"iceberg.table\\": \\"orders\\"}"}""";

    static final String TABLE = LoopGuard.name(TableIdentifier.of(Namespace.of("markets", "equities", "eu", "xetra"), "orders"));

    @Test
    void findsTheTableInsideAnIcebergPipelinesConfig() {
        assertThat(TABLE).isEqualTo("markets.equities.eu.xetra.orders");
        assertThat(LoopGuard.names(ICEBERG_PIPELINE, TABLE)).isTrue();
    }

    @Test
    void findsTheTableAsAnIcebergPipelinesSource() {
        assertThat(LoopGuard.names("{\"connection_string\":\"markets.equities.eu.xetra.orders\",\"source_type\":\"S3\"}", TABLE)).isTrue();
    }

    @Test
    void passesPipelinesOfOtherTables() {
        assertThat(LoopGuard.names(ICEBERG_PIPELINE, "markets.equities.eu.xetra.trades")).isFalse();
        assertThat(LoopGuard.names(CHANGE_FEED_PIPELINE, TABLE)).isFalse();
        assertThat(LoopGuard.names(null, TABLE)).isFalse();
    }
}
