package nuna.datagen.world;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import nuna.datagen.kit.Engine;
import nuna.datagen.kit.Kit;
import org.apache.iceberg.Schema;
import org.junit.jupiter.api.Test;

/**
 * Margin's requirements and calls (item 153). The world's accounts are rarely levered enough to be called in a month,
 * so the calls are checked here directly: issued, met, expired and liquidation due, each row filling its table.
 */
class MarginTest {

    private static final Kit KIT = Kit.load();
    private static final MarketCalendar CALENDAR = new MarketCalendar();
    private static final LocalDate MONDAY = LocalDate.of(2026, 10, 5);
    private static final Instant AT = Instant.parse("2026-10-05T21:00:00Z");

    private final List<Map<String, Object>> rows = new ArrayList<>();
    private final Margin margin = new Margin((at, feed, row) -> {
        assertThat(feed).isEqualTo(Feed.MARGIN_CALLS);
        rows.add(row);
    }, new Engine(KIT), new Market(new Rng(1), 10), CALENDAR, s -> false);

    private LocalDate day(int n) {
        LocalDate d = MONDAY;
        for (int i = 0; i < n; i++) {
            d = CALENDAR.next(d);
        }
        return d;
    }

    @Test
    void finraShortMaintenanceIsTheGreaterOfPerShareAndPercent() {
        assertThat(Margin.finraShort(new BigDecimal("10"), new BigDecimal("100"))).isEqualByComparingTo("500");
        assertThat(Margin.finraShort(new BigDecimal("40"), new BigDecimal("100"))).isEqualByComparingTo("1200");
        assertThat(Margin.finraShort(new BigDecimal("2"), new BigDecimal("100"))).isEqualByComparingTo("250");
        assertThat(Margin.finraShort(new BigDecimal("4"), new BigDecimal("100"))).isEqualByComparingTo("400");
        assertThat(Margin.houseLongRate(new BigDecimal("2.99"))).isEqualByComparingTo("1");
        assertThat(Margin.houseLongRate(new BigDecimal("12"))).isEqualByComparingTo("0.30");
    }

    @Test
    void aMaintenanceCallNotMetByItsDueDateIsLiquidationDueAndOneMetCloses() {
        margin.calls(AT, day(0), 1, new BigDecimal("-500.00"), BigDecimal.ZERO);
        margin.calls(AT, day(1), 1, new BigDecimal("-400.00"), BigDecimal.ZERO);
        margin.calls(AT, day(2), 1, new BigDecimal("-300.00"), BigDecimal.ZERO);
        margin.calls(AT, day(0), 2, new BigDecimal("-100.00"), BigDecimal.ZERO);
        margin.calls(AT, day(1), 2, new BigDecimal("50.00"), BigDecimal.ZERO);
        assertThat(rows).extracting(r -> r.get("brokerage_account") + " " + r.get("event_type"))
            .containsExactly("1 issued", "1 liquidation_due", "1 issued", "2 issued", "2 met");
        assertThat(rows.getFirst().get("amount")).isEqualTo(new BigDecimal("500.00"));
        assertThat(rows.getFirst().get("due_date")).isEqualTo(day(2));
        assertThat(margin.openCalls(1)).isEqualTo(1);
        assertThat(margin.openCalls(2)).isZero();
    }

    @Test
    void anIntradayCallIsMetWhenTheSurplusRisesByItsAmountElseExpiresAfterFifteenBusinessDays() {
        margin.calls(AT, day(0), 1, new BigDecimal("1000.00"), new BigDecimal("300.00"));
        margin.calls(AT, day(1), 1, new BigDecimal("1200.00"), BigDecimal.ZERO);
        margin.calls(AT, day(2), 1, new BigDecimal("1300.00"), BigDecimal.ZERO);
        margin.calls(AT, day(0), 2, new BigDecimal("1000.00"), new BigDecimal("300.00"));
        for (int d = 1; d <= 15; d++) {
            margin.calls(AT, day(d), 2, new BigDecimal("1000.00"), BigDecimal.ZERO);
        }
        assertThat(rows).extracting(r -> r.get("brokerage_account") + " " + r.get("call_type") + " " + r.get("event_type"))
            .containsExactly("1 intraday issued", "1 intraday met", "2 intraday issued", "2 intraday expired");
        assertThat(rows.getLast().get("business_date")).isEqualTo(day(15));
    }

    @Test
    void everyCallRowFillsEveryColumnOfItsTable() {
        margin.calls(AT, day(0), 1, new BigDecimal("-500.00"), new BigDecimal("10.00"));
        Schema schema = LakeSchemas.schema(Feed.MARGIN_CALLS, KIT);
        for (Map<String, Object> r : rows) {
            assertThat(r.keySet()).containsExactlyInAnyOrderElementsOf(
                schema.columns().stream().map(c -> c.name()).toList());
            assertThat(r.values()).doesNotContainNull();
        }
    }
}
