package nuna.changefeed.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.iceberg.types.Types;
import org.junit.jupiter.api.Test;

class SourceTableTest {

    private static SourceTable.Column column(String dataType, String columnType, int precision, int scale) {
        return SourceTable.column("ops.t.c", "c", dataType, columnType, precision, scale, true);
    }

    @Test
    void mapsSingleStoreTypesToIceberg() {
        assertThat(column("tinyint", "tinyint(1)", 3, 0).icebergType()).isEqualTo(Types.BooleanType.get());
        assertThat(column("tinyint", "tinyint(4)", 3, 0).icebergType()).isEqualTo(Types.IntegerType.get());
        assertThat(column("int", "int(10) unsigned", 10, 0).icebergType()).isEqualTo(Types.LongType.get());
        assertThat(column("bigint", "bigint(20)", 19, 0).icebergType()).isEqualTo(Types.LongType.get());
        assertThat(column("decimal", "decimal(18,6)", 18, 6).icebergType()).isEqualTo(Types.DecimalType.of(18, 6));
        assertThat(column("enum", "enum('BUY','SELL')", 0, 0).icebergType()).isEqualTo(Types.StringType.get());
        assertThat(column("datetime", "datetime(6)", 0, 0).icebergType()).isEqualTo(Types.TimestampType.withoutZone());
        assertThat(column("varbinary", "varbinary(16)", 0, 0).icebergType()).isEqualTo(Types.BinaryType.get());
    }

    @Test
    void refusesTypesWhoseMeaningACopyCouldChange() {
        assertThatThrownBy(() -> column("timestamp", "timestamp(6)", 0, 0)).hasMessageContaining("no Iceberg type is mapped");
        assertThatThrownBy(() -> column("bigint", "bigint(20) unsigned", 20, 0)).hasMessageContaining("2^63");
        assertThatThrownBy(() -> column("decimal", "decimal(65,2)", 65, 2)).hasMessageContaining("38 digits");
        assertThatThrownBy(() -> column("geography", "geography", 0, 0)).hasMessageContaining("ops.t.c is geography");
    }

    @Test
    void readsThePipelinesConfiguration() {
        FeedConfig config = FeedConfig.from(Map.of(
                "singlestore.url", "jdbc:mariadb://localhost:3306/ops", "singlestore.user", "root",
                "singlestore.database", "ops", "singlestore.table", "orders",
                "iceberg.catalog.uri", "http://catalog:8181", "iceberg.catalog.token", "t",
                "iceberg.namespace", "markets.equities", "iceberg.table", "orders", "iceberg.partition-by", " bucket(order_id, 4) "));

        assertThat(config.catalogProperties()).containsEntry("uri", "http://catalog:8181").containsEntry("token", "t")
                .containsEntry("type", "rest").doesNotContainKey("iceberg.catalog.uri");
        assertThat(config.icebergTable().namespace().levels()).containsExactly("markets", "equities");
        assertThat(config.partitionBy()).isEqualTo("bucket(order_id, 4)");
        assertThat(config.commitIntervalMs()).isEqualTo(5000);
        assertThat(config.formatVersion()).isEqualTo(2);
        assertThat(config.source()).isEqualTo("ops.orders");
        assertThat(config.appendOnly()).isFalse();
        assertThatThrownBy(() -> FeedConfig.from(Map.of("singlestore.url", "u"))).hasMessageContaining("missing iceberg.catalog.uri");
    }

    @Test
    void appendOnlyTakesTrueOrFalse() {
        Map<String, String> props = new HashMap<>(Map.of(
                "singlestore.url", "jdbc:mariadb://localhost:3306/ops", "singlestore.user", "root",
                "singlestore.database", "ledger", "singlestore.table", "fills",
                "iceberg.catalog.uri", "http://catalog:8181", "iceberg.namespace", "ledger", "iceberg.table", "fills"));
        props.put("singlestore.append-only", " true ");
        assertThat(FeedConfig.from(props).appendOnly()).isTrue();
        props.put("singlestore.append-only", "false");
        assertThat(FeedConfig.from(props).appendOnly()).isFalse();
        props.put("singlestore.append-only", "yes");
        assertThatThrownBy(() -> FeedConfig.from(props)).hasMessage("singlestore.append-only is yes; it takes true or false");
    }

    @Test
    void thePrimaryKeyMustEqualTheShardKeyUnlessTheTableIsAppendOnly() {
        List<String> primaryKey = List.of("sequencer", "sequence_number");
        Set<String> shardKey = Set.of("sequence_number");

        assertThatCode(() -> SourceTable.checkKeys("ops.orders", List.of("order_id"), Set.of("order_id"), false))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> SourceTable.checkKeys("ledger.fills", primaryKey, shardKey, false))
                .hasMessageContaining("must equal the shard key").hasMessageContaining("singlestore.append-only");
        assertThatCode(() -> SourceTable.checkKeys("ledger.fills", primaryKey, shardKey, true)).doesNotThrowAnyException();
        assertThatThrownBy(() -> SourceTable.checkKeys("ledger.fills", List.of("sequencer"), shardKey, true))
                .hasMessageContaining("must contain the shard key");
        assertThatThrownBy(() -> SourceTable.checkKeys("ledger.fills", List.of(), shardKey, true))
                .hasMessageContaining("has no primary key");
    }

    @Test
    void resumesAfterTheGivenOffsets() {
        SourceTable table = Fixtures.orders(2);
        assertThat(ObserveReader.observeSql(table, new String[] {null, null})).isEqualTo("OBSERVE * FROM `ops`.`orders`");
        assertThat(ObserveReader.observeSql(table, new String[] {"0a", "0b"}))
                .isEqualTo("OBSERVE * FROM `ops`.`orders` BEGIN AT ('0a','0b')");
    }
}
