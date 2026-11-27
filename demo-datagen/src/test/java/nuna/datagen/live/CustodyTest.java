package nuna.datagen.live;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import nuna.datagen.kit.Kit;
import nuna.datagen.world.Feed;
import nuna.datagen.world.Played;
import nuna.datagen.world.World;
import org.junit.jupiter.api.Test;

/**
 * The CDC leg's custody (item 151): CockroachDB's clearing tables as demo/cdc/cockroach.sql makes them and their
 * history tables as demo.sh makes them, both from {@link Custody}, and the books the custody load writes.
 */
class CustodyTest {

    private static final Path CDC = Path.of("..", "demo", "cdc");

    @Test
    void cockroachSqlMakesTheCustodyTablesAndDemoShTheirHistoryTables() throws Exception {
        assertThat(Files.readString(CDC.resolve("cockroach.sql"))).as("demo/cdc/cockroach.sql")
            .contains(Custody.ddl()).contains("CREATE USER clearing WITH PASSWORD '${PASSWORD_CLEARING}'")
            .doesNotContain("CREATE SCHEMA positions");
        for (Custody.Table t : Custody.Table.values()) {
            assertThat(Files.readString(CDC.resolve("custody-history").resolve(t.table + ".json")))
                .as("demo/cdc/custody-history: ./gradlew :demo-datagen:imageLib, then java -cp"
                    + " 'demo-datagen/build/image/lib/*' nuna.datagen.Main history demo/cdc/custody-history")
                .isEqualTo(Custody.history(t));
        }
    }

    /**
     * The books after the world's tenth day: that day's stock record, the last five days' obligations and locates,
     * and the seeded fail at its latest state, delivered, with its close-out's price and amount.
     */
    @Test
    void theBooksHoldTheLastDaysStockRecordFiveDaysOfObligationsAndLocatesAndEachFailsLatestState() {
        World world = new World(Played.SETTINGS, Kit.load());
        Custody.Book book = new Custody.Book();
        for (int i = 0; i < Played.DAYS; i++) {
            world.runDay(book);
        }
        Played p = Played.seeded();
        LocalDate last = p.days.getLast(), oldest = p.days.get(Played.DAYS - Custody.DAYS_KEPT);
        assertThat(book.last()).isEqualTo(last);
        assertThat(book.size(Custody.Table.STOCK_RECORD))
            .isEqualTo(p.of(Feed.STOCK_RECORD).stream().filter(r -> r.get("business_date").equals(last)).count());
        assertThat(book.size(Custody.Table.CNS_OBLIGATIONS)).isEqualTo(p.of(Feed.CNS_OBLIGATIONS).stream()
            .filter(r -> !((LocalDate) r.get("trade_date")).isBefore(oldest)).count());
        assertThat(book.size(Custody.Table.LOCATES)).isPositive().isEqualTo(p.of(Feed.LOCATES).stream()
            .filter(r -> !((LocalDate) r.get("business_date")).isBefore(oldest)).count());

        List<Map<String, Object>> fails = p.of(Feed.FAILS);
        Map<String, Object> closeOut = fails.get(2);
        List<Object> fail = book.rows.get(Custody.Table.FAILS).get((String) closeOut.get("fail_id"));
        assertThat(book.size(Custody.Table.FAILS)).isOne();
        List<String> columns = Custody.Table.FAILS.columns.stream().map(Custody.Column::name).toList();
        assertThat(fail.get(columns.indexOf("status"))).isEqualTo("delivered");
        assertThat(fail.get(columns.indexOf("close_out_date"))).isEqualTo(closeOut.get("business_date"));
        assertThat((BigDecimal) fail.get(columns.indexOf("close_out_price")))
            .isEqualByComparingTo((BigDecimal) closeOut.get("close_out_price"));
        assertThat((BigDecimal) fail.get(columns.indexOf("close_out_amount")))
            .isEqualByComparingTo((BigDecimal) closeOut.get("close_out_amount"));
        // A value reads the same whether the world made it or CockroachDB gave it back, so a reload writes nothing.
        assertThat(Custody.normal(new BigDecimal("12.50"))).isEqualTo(Custody.normal(new BigDecimal("12.5000")));
        assertThat(Custody.normal(java.sql.Date.valueOf(last))).isEqualTo(Custody.normal(last));
        assertThat(Custody.normal(7)).isEqualTo(Custody.normal(7L));
    }
}
