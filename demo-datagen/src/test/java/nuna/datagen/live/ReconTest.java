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
        Feed.FINANCIAL_TRANSACTIONS, Feed.EXECUTIONS, Feed.CNS_OBLIGATIONS);

    @Test
    void onACleanWorldEveryViewIsEmpty() throws SQLException {
        Map<String, List<Map<String, Object>>> views = recon(Played.clean());
        assertThat(views).containsOnlyKeys("stock_record_vs_ledger", "executions_vs_fills", "cns_vs_fills");
        assertThat(views).allSatisfy((name, rows) -> assertThat(rows).as(name).isEmpty());
    }

    @Test
    void theSeededFailShowsAsItsCloseOutInCnsAlone() throws SQLException {
        Map<String, List<Map<String, Object>>> views = recon(Played.seeded());
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

    /** The world's rows in an H2 warehouse named lakehouse, and each view's rows by name. */
    private static Map<String, List<Map<String, Object>>> recon(Played p) throws SQLException {
        try (Connection c = DriverManager.getConnection("jdbc:h2:mem:lakehouse;DATABASE_TO_LOWER=TRUE")) {
            for (Feed f : READ) {
                load(c, f, p.of(f));
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
        List<PreparedStatement> inserts = new ArrayList<>();
        for (Feed.Path path : Feed.Path.values()) {
            String table = f.lakeTable(path);
            int dot = table.lastIndexOf('.');
            String name = "\"" + table.substring(0, dot) + "\"." + table.substring(dot + 1);
            try (Statement s = c.createStatement()) {
                s.execute("CREATE SCHEMA IF NOT EXISTS \"" + table.substring(0, dot) + "\"");
                s.execute("CREATE TABLE " + name + " ("
                    + columns.stream().map(x -> "\"" + x.name() + "\" " + sql(x.type())).collect(joining(", ")) + ")");
            }
            inserts.add(c.prepareStatement("INSERT INTO " + name + " VALUES ("
                + columns.stream().map(x -> "?").collect(joining(", ")) + ")"));
        }
        for (int i = 0; i < rows.size(); i++) {
            PreparedStatement insert = inserts.get(i % inserts.size());
            for (int k = 0; k < columns.size(); k++) {
                Object v = rows.get(i).get(columns.get(k).name());
                insert.setObject(k + 1, v instanceof Instant at ? at.atOffset(ZoneOffset.UTC) : v);
            }
            insert.addBatch();
        }
        for (PreparedStatement insert : inserts) {
            insert.executeBatch();
            insert.close();
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
