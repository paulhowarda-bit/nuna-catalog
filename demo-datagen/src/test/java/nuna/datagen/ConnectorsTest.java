package nuna.datagen;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import nuna.datagen.live.Sources;
import nuna.datagen.world.Feed;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The Kafka Connect configs and demo.sh's lists agree with the code (item 152): the world's two sinks between them take
 * every topic, beneficial_owners' alone in its own; the CDC leg's sources capture the tables {@link Sources} makes, and
 * its sinks write each into its history table.
 */
class ConnectorsTest {

    private static final Path DEMO = Path.of("..", "demo");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static Map<String, String> config(String path) throws IOException {
        JsonNode node = JSON.readTree(Files.readString(DEMO.resolve(path)));
        Map<String, String> config = new LinkedHashMap<>();
        node.properties().forEach(e -> config.put(e.getKey(), e.getValue().asString()));
        return config;
    }

    private static List<String> list(String csv) {
        return Arrays.asList(csv.split(","));
    }

    private static List<Sources.Table> of(Sources.Db db) {
        return Arrays.stream(Sources.Table.values()).filter(t -> t.db == db).toList();
    }

    @Test
    void theWorldsSinksTakeEveryTopicAndBeneficialOwnersHasItsOwn() throws IOException {
        Map<String, String> world = config("brokerage/connector.json");
        Map<String, String> owners = config("brokerage/owners-connector.json");
        List<String> topics = new ArrayList<>();
        for (Feed f : Feed.numbered()) {
            if (f.topic != null && f != Feed.BENEFICIAL_OWNERS) {
                topics.add(f.topic);
            }
        }
        assertThat(list(world.get("topics"))).isEqualTo(topics);
        assertThat(world.get("iceberg.control.topic")).isEqualTo(Main.CONTROL_TOPIC);
        assertThat(owners.get("topics")).isEqualTo(Feed.BENEFICIAL_OWNERS.topic);
        assertThat(owners.get("iceberg.control.topic")).isEqualTo(Main.OWNERS_CONTROL_TOPIC)
            .isNotEqualTo(Main.CONTROL_TOPIC);
        // Otherwise the same sink: the owners' must not drift from the world's.
        Map<String, String> rest = new LinkedHashMap<>(owners), worldRest = new LinkedHashMap<>(world);
        for (String key : List.of("topics", "iceberg.control.topic")) {
            rest.remove(key);
            worldRest.remove(key);
        }
        assertThat(rest).isEqualTo(worldRest);
    }

    @Test
    void theBackOfficesConnectorsCaptureEachTableIntoItsHistoryTable() throws IOException {
        Map<String, String> source = config("cdc/connectors/backoffice-source.json");
        Map<String, String> sink = config("cdc/connectors/backoffice-history.json");
        List<Sources.Table> tables = of(Sources.Db.BACKOFFICE);
        assertThat(list(source.get("table.include.list"))).isEqualTo(tables.stream().map(Sources.Table::qualified).toList());
        assertThat(list(sink.get("topics")))
            .isEqualTo(tables.stream().map(t -> source.get("topic.prefix") + "." + t.qualified()).toList());
        assertThat(list(sink.get("iceberg.tables"))).isEqualTo(tables.stream().map(Sources.Table::history).toList());
        assertThat(sink.get("iceberg.tables.route-field")).isEqualTo("_cdc.source");
        for (Sources.Table t : tables) {
            String regex = sink.get("iceberg.table." + t.history() + ".route-regex");
            assertThat(regex).as(t.history()).isNotNull();
            // The transform's _cdc.source is schema.table: each regex takes its own table's and no other's.
            for (Sources.Table other : tables) {
                assertThat(other.qualified().matches(regex)).as(regex + " on " + other.qualified()).isEqualTo(other == t);
            }
        }
    }

    @Test
    void treasurysConnectorsCaptureBankPaymentsWithLowerCaseColumns() throws IOException {
        Map<String, String> source = config("cdc/connectors/oracle-source.json");
        Map<String, String> sink = config("cdc/connectors/oracle-history.json");
        Sources.Table t = Sources.Table.BANK_PAYMENTS;
        assertThat(of(Sources.Db.TREASURY)).containsExactly(t);
        String upper = t.qualified().toUpperCase(Locale.ROOT);
        assertThat(source.get("table.include.list")).isEqualTo(upper);
        assertThat(sink.get("topics")).isEqualTo(source.get("topic.prefix") + "." + upper);
        assertThat(sink.get("iceberg.tables")).isEqualTo(t.history());
        List<String> columns = new ArrayList<>(t.columnNames());
        columns.add("updated_at");
        assertThat(list(sink.get("transforms.lowercase.renames")))
            .isEqualTo(columns.stream().map(c -> c.toUpperCase(Locale.ROOT) + ":" + c).toList());
        assertThat(sink.get("transforms.lowercasekey.renames")).isEqualTo("PAYMENT_ID:payment_id");
    }

    /** demo.sh waits on each source table's history and makes each history namespace before its sink writes. */
    @Test
    void demoShListsEverySourceTableAndHistoryNamespace() throws IOException {
        String sh = Files.readString(DEMO.resolve("demo.sh"));
        assertThat(quoted(array(sh, "BACKOFFICE_TABLES")))
            .isEqualTo(of(Sources.Db.BACKOFFICE).stream().map(t -> t.schema + " " + t.table).toList());
        assertThat(array(sh, "TREASURY_TABLES").trim()).isEqualTo(Sources.Table.BANK_PAYMENTS.table);
        Matcher m = Pattern.compile("(?m)^HISTORY_NAMESPACES=\"([^\"]*)\"").matcher(sh);
        assertThat(m.find()).isTrue();
        List<String> namespaces = Arrays.stream(m.group(1).trim().split("\\s+")).map(e -> e.substring(0, e.indexOf(':')))
            .toList();
        assertThat(namespaces).containsExactlyInAnyOrderElementsOf(Arrays.stream(Sources.Table.values())
            .map(t -> t.history().substring("history.".length(), t.history().lastIndexOf('.'))).distinct().toList());
    }

    private static String array(String sh, String name) {
        Matcher m = Pattern.compile("(?ms)^" + name + "=\\((.*?)\\)$").matcher(sh);
        assertThat(m.find()).as(name + " in demo.sh").isTrue();
        return m.group(1);
    }

    private static List<String> quoted(String items) {
        List<String> out = new ArrayList<>();
        Matcher m = Pattern.compile("\"([^\"]*)\"").matcher(items);
        while (m.find()) {
            out.add(m.group(1));
        }
        return out;
    }
}
