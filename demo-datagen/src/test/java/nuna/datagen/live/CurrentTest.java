package nuna.datagen.live;

import static java.util.stream.Collectors.joining;
import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import nuna.datagen.world.Feed;
import nuna.datagen.world.LakeSchemas;
import nuna.datagen.world.Played;
import org.apache.iceberg.types.Types;
import org.junit.jupiter.api.Test;

/**
 * The current-state views (item 152), run in H2 against two weeks of the world's back office, each feed's rows split
 * between its table through Kafka and its copy through SingleStore: one row per key, its latest version.
 */
class CurrentTest {

    @Test
    void aVersionedFeedNeverSendsAKeysVersionTwice() {
        for (Feed f : LakeSchemas.VERSIONED) {
            Set<String> seen = new HashSet<>();
            for (Map<String, Object> r : Played.seeded().of(f)) {
                assertThat(seen.add(r.get(LakeSchemas.key(f)) + " " + r.get("version"))).as(f + " " + r).isTrue();
            }
            // Two weeks of 400 accounts bring no complaint; the demo's 1,000 accounts bring a few a month.
            if (f != Feed.COMPLAINTS) {
                assertThat(seen).as(f.name()).isNotEmpty();
            }
        }
    }

    @Test
    void eachViewHoldsEveryKeyOnceAtItsLatestVersion() throws SQLException {
        Played p = Played.seeded();
        try (Connection c = DriverManager.getConnection("jdbc:h2:mem:lakehouse;DATABASE_TO_LOWER=TRUE")) {
            for (Feed f : LakeSchemas.VERSIONED) {
                load(c, f, p.of(f));
            }
            List<Current.View> views = Current.views("lakehouse", t -> true);
            assertThat(views).extracting(Current.View::name)
                .containsExactlyElementsOf(LakeSchemas.VERSIONED.stream().map(f -> f.table + "_current").toList());
            for (Current.View v : views) {
                Feed f = LakeSchemas.VERSIONED.stream().filter(x -> v.name().equals(x.table + "_current")).findFirst()
                    .orElseThrow();
                assertThat(v.namespace().toString()).isEqualTo(f.namespace);
                assertThat(v.sql()).contains("lakehouse.\"" + f.namespace + "\"." + f.table)
                    .contains("lakehouse.\"" + f.lakeTable(Feed.Path.SINGLESTORE).replaceAll("\\.[^.]*$", "") + "\".");
                String key = LakeSchemas.key(f);
                Map<Object, Integer> latest = new HashMap<>();
                for (Map<String, Object> r : p.of(f)) {
                    latest.merge(r.get(key), (Integer) r.get("version"), Math::max);
                }
                try (Statement s = c.createStatement(); ResultSet r = s.executeQuery(v.sql())) {
                    ResultSetMetaData md = r.getMetaData();
                    List<String> labels = new ArrayList<>();
                    for (int i = 1; i <= md.getColumnCount(); i++) {
                        labels.add(md.getColumnLabel(i).toLowerCase());
                    }
                    assertThat(labels).as(v.name())
                        .isEqualTo(v.schema().columns().stream().map(Types.NestedField::name).toList());
                    Map<Object, Integer> held = new HashMap<>();
                    while (r.next()) {
                        Object k = r.getObject(key);
                        assertThat(held.put(k instanceof Number n && !(k instanceof Long) ? n.longValue() : k,
                            r.getInt("version"))).as(v.name() + " " + k).isNull();
                    }
                    assertThat(held).as(v.name()).isEqualTo(latest);
                }
            }
        }
    }

    /** A feed's two tables, and its rows: alternate ones through each way. */
    private static void load(Connection c, Feed f, List<Map<String, Object>> rows) throws SQLException {
        List<Types.NestedField> columns = LakeSchemas.schema(f).columns();
        List<PreparedStatement> inserts = new ArrayList<>();
        for (Feed.Path path : Feed.Path.values()) {
            String table = f.lakeTable(path);
            int dot = table.lastIndexOf('.');
            String name = "\"" + table.substring(0, dot) + "\"." + table.substring(dot + 1);
            try (Statement s = c.createStatement()) {
                s.execute("CREATE SCHEMA IF NOT EXISTS \"" + table.substring(0, dot) + "\"");
                s.execute("CREATE TABLE " + name + " (" + columns.stream()
                    .map(x -> "\"" + x.name() + "\" " + Current.trino(x.type()))
                    .collect(joining(", ")) + ")");
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
}
