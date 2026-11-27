package nuna.datagen.live;

import static java.util.stream.Collectors.joining;
import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import nuna.datagen.kit.Kit;
import nuna.datagen.world.Feed;
import nuna.datagen.world.LakeSchemas;
import nuna.datagen.world.MarketCalendar;
import nuna.datagen.world.MonthEnd;
import nuna.datagen.world.Played;
import org.apache.iceberg.types.Type;
import org.apache.iceberg.types.Types;
import org.junit.jupiter.api.Test;

/**
 * The reconciliation views' SQL, run in H2 against two weeks of the world's own rows, each feed's rows split between
 * its table through Kafka and its table through SingleStore as a route change leaves them: no rows on a clean world,
 * and on the seeded one the fail's close-out alone, in {@code cns_vs_fills}.
 */
class ReconTest {

    private static final Kit KIT = Kit.load();
    private static final List<Feed> READ = List.of(Feed.BALANCES_EOD, Feed.STOCK_RECORD,
        Feed.FINANCIAL_TRANSACTIONS, Feed.EXECUTIONS, Feed.CNS_OBLIGATIONS, Feed.TAX_LOTS, Feed.JOURNAL_LINES,
        Feed.ORDER_EVENTS, Feed.ROUTES, Feed.CAT_EVENTS);
    private static final List<String> VIEWS = List.of("stock_record_vs_ledger", "executions_vs_fills", "cns_vs_fills",
        "tax_lots_vs_ledger", "statements_vs_ledger", "realized_vs_1099b", "cat_vs_orders");

    @Test
    void onACleanWorldEveryViewIsEmpty() throws SQLException {
        Map<String, List<Map<String, Object>>> views = recon(Played.clean(), true);
        assertThat(views).containsOnlyKeys(VIEWS);
        assertThat(views).allSatisfy((name, rows) -> assertThat(rows).as(name).isEmpty());
    }

    @Test
    void aCleanWorldThatClosesAMonthTiesOut() throws SQLException {
        Played p = Played.closing(false);
        assertThat(p.closes).hasSize(1);
        assertThat(p.restatements).isEmpty();
        assertThat(p.closes.getFirst().rows().get(MonthEnd.Table.FORM_1099B)).isNotEmpty();
        Map<String, List<Map<String, Object>>> views = recon(p, true);
        assertThat(views).containsOnlyKeys(VIEWS);
        assertThat(views).allSatisfy((name, rows) -> assertThat(rows).as(name).isEmpty());
    }

    /**
     * After the bust's restatement is merged, main's statement for the busted account differs from the ledger's
     * balances at the month end in exactly two lines: its unsettled purchases and the busted security's position.
     * At the tag month-end-2026-09 (the statements as first published) every view is empty.
     */
    @Test
    void theSeededBustShowsAsTheRestatedLinesOnMainAndNothingAtTheTag() throws SQLException {
        Played p = Played.closing(true);
        assertThat(p.bustSeeded).isTrue();
        assertThat(p.restatements).hasSize(1);
        MonthEnd.Restatement r = p.restatements.getFirst();

        Map<String, List<Map<String, Object>>> atTag = recon(p, false);
        assertThat(atTag.get("statements_vs_ledger")).isEmpty();

        Map<String, List<Map<String, Object>>> onMain = recon(p, true);
        List<Map<String, Object>> rows = onMain.get("statements_vs_ledger");
        assertThat(rows).extracting(x -> x.get("item")).containsExactlyInAnyOrder("unsettled_purchases", "position");
        assertThat(rows).allSatisfy(x -> assertThat(((Number) x.get("brokerage_account")).longValue())
            .isEqualTo(r.brokerageAccount()));
        Map<String, Object> bust = p.of(Feed.FINANCIAL_TRANSACTIONS).stream()
            .filter(t -> Integer.valueOf(92).equals(t.get("template_id"))).findFirst().orElseThrow();
        Map<String, Object> purchases = rows.stream().filter(x -> x.get("item").equals("unsettled_purchases"))
            .findFirst().orElseThrow();
        assertThat(((BigDecimal) purchases.get("ledger_amount")).subtract((BigDecimal) purchases.get("statement_amount")))
            .isEqualByComparingTo((BigDecimal) bust.get("fill_amount"));
        Map<String, Object> position = rows.stream().filter(x -> x.get("item").equals("position")).findFirst()
            .orElseThrow();
        assertThat(((Number) position.get("security_id")).longValue()).isEqualTo(bust.get("security_id"));
        // The bust is taken off its buy in CNS: tie 4 shows the seeded fail alone, as before.
        assertThat(onMain.get("cns_vs_fills")).hasSize(1);
        for (String v : List.of("stock_record_vs_ledger", "executions_vs_fills", "tax_lots_vs_ledger",
            "realized_vs_1099b", "cat_vs_orders")) {
            assertThat(onMain.get(v)).as(v).isEmpty();
        }
    }

    @Test
    void theSeededFailShowsAsItsCloseOutInCnsAlone() throws SQLException {
        Map<String, List<Map<String, Object>>> views = recon(Played.seeded(), true);
        assertThat(views.get("stock_record_vs_ledger")).isEmpty();
        assertThat(views.get("executions_vs_fills")).isEmpty();
        assertThat(views.get("cns_vs_fills")).hasSize(1);

        Map<String, Object> close = Played.seeded().of(Feed.FAILS).stream()
            .filter(f -> f.get("event_type").equals("closed_out")).findFirst().orElseThrow();
        Map<String, Object> row = views.get("cns_vs_fills").getFirst();
        LocalDate day = (LocalDate) close.get("business_date");
        assertThat(row.get("trade_date")).isEqualTo(day);
        assertThat(row.get("settlement_date")).isEqualTo(new MarketCalendar().settlement(day));
        assertThat(((Number) row.get("security_id")).longValue()).isEqualTo(close.get("security_id"));
        BigDecimal q = BigDecimal.valueOf((Long) close.get("quantity"));
        assertThat(((BigDecimal) row.get("cns_net_quantity")).subtract((BigDecimal) row.get("fills_net_quantity")))
            .isEqualByComparingTo(q);
        assertThat(((BigDecimal) row.get("cns_net_amount")).subtract((BigDecimal) row.get("fills_net_amount")))
            .isEqualByComparingTo(((BigDecimal) close.get("close_out_amount")).negate());
    }

    /**
     * The world's rows in an H2 warehouse named lakehouse, and each view's rows by name.
     *
     * @param restated whether the close tables hold the restatements (main after the merge) or the closes as first
     *                 written (what each month-end tag keeps)
     */
    private static Map<String, List<Map<String, Object>>> recon(Played p, boolean restated) throws SQLException {
        try (Connection c = DriverManager.getConnection("jdbc:h2:mem:lakehouse;DATABASE_TO_LOWER=TRUE")) {
            for (Feed f : READ) {
                load(c, f, p.of(f));
            }
            for (MonthEnd.Table t : MonthEnd.Table.values()) {
                List<Map<String, Object>> rows = new ArrayList<>();
                for (MonthEnd close : p.closes) {
                    List<Map<String, Object>> month = close.rows().get(t);
                    for (MonthEnd.Restatement r : restated ? p.restatements : List.<MonthEnd.Restatement>of()) {
                        if (r.period().equals(close.period()) && t.statement()) {
                            month = r.rows().get(t);
                        }
                    }
                    rows.addAll(month);
                }
                create(c, t.lakeTable(), MonthEnd.schema(t).columns(), rows);
            }
            Map<String, List<Map<String, Object>>> views = new LinkedHashMap<>();
            for (Recon.View v : Recon.views("lakehouse", t -> true)) {
                List<Map<String, Object>> rows = new ArrayList<>();
                try (Statement s = c.createStatement(); ResultSet r = s.executeQuery(v.sql())) {
                    ResultSetMetaData md = r.getMetaData();
                    List<String> labels = new ArrayList<>();
                    for (int i = 1; i <= md.getColumnCount(); i++) {
                        labels.add(md.getColumnLabel(i).toLowerCase());
                    }
                    assertThat(labels).as(v.name())
                        .isEqualTo(v.schema().columns().stream().map(Types.NestedField::name).toList());
                    while (r.next()) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        for (int i = 1; i <= labels.size(); i++) {
                            Object value = r.getObject(i);
                            row.put(labels.get(i - 1), value instanceof java.sql.Date d ? d.toLocalDate() : value);
                        }
                        rows.add(row);
                    }
                }
                views.put(v.name(), rows);
            }
            return views;
        }
    }

    /** A feed's two tables, with its scalar columns, and its rows: alternate ones through each way. */
    private static void load(Connection c, Feed f, List<Map<String, Object>> rows) throws SQLException {
        List<Types.NestedField> columns = LakeSchemas.schema(f, KIT).columns().stream()
            .filter(x -> x.type().isPrimitiveType()).toList();
        List<List<Map<String, Object>>> split = List.of(new ArrayList<>(), new ArrayList<>());
        for (int i = 0; i < rows.size(); i++) {
            split.get(i % 2).add(rows.get(i));
        }
        for (Feed.Path path : Feed.Path.values()) {
            create(c, f.lakeTable(path), columns, split.get(path.ordinal()));
        }
    }

    /** A lake table in H2, with the given columns and rows. */
    private static void create(Connection c, String table, List<Types.NestedField> columns,
                               List<Map<String, Object>> rows) throws SQLException {
        int dot = table.lastIndexOf('.');
        String name = "\"" + table.substring(0, dot) + "\"." + table.substring(dot + 1);
        try (Statement s = c.createStatement()) {
            s.execute("CREATE SCHEMA IF NOT EXISTS \"" + table.substring(0, dot) + "\"");
            s.execute("CREATE TABLE " + name + " ("
                + columns.stream().map(x -> "\"" + x.name() + "\" " + sql(x.type())).collect(joining(", ")) + ")");
        }
        try (PreparedStatement insert = c.prepareStatement("INSERT INTO " + name + " VALUES ("
            + columns.stream().map(x -> "?").collect(joining(", ")) + ")")) {
            for (Map<String, Object> row : rows) {
                for (int k = 0; k < columns.size(); k++) {
                    Object v = row.get(columns.get(k).name());
                    insert.setObject(k + 1, v instanceof Instant at ? at.atOffset(ZoneOffset.UTC) : v);
                }
                insert.addBatch();
            }
            insert.executeBatch();
        }
    }

    private static String sql(Type type) {
        return switch (type.typeId()) {
            case LONG -> "BIGINT";
            case INTEGER -> "INTEGER";
            case STRING -> "VARCHAR";
            case BOOLEAN -> "BOOLEAN";
            case DATE -> "DATE";
            case TIMESTAMP -> "TIMESTAMP WITH TIME ZONE";
            case DECIMAL -> "DECIMAL(" + ((Types.DecimalType) type).precision() + ", "
                + ((Types.DecimalType) type).scale() + ")";
            default -> throw new IllegalArgumentException(type.toString());
        };
    }
}
