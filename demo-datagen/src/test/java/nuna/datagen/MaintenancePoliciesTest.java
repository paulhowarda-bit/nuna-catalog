package nuna.datagen;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import nuna.datagen.world.Feed;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * demo.sh's maintenance policies for the brokerage world (D30; item 154) are the ones this test holds: the streams'
 * namespaces keep their snapshots short and are compacted and their manifests rewritten sooner than the catalog's
 * policy has it, digital keeps 7 days, and nothing reaches the held compliance.aml_cases but its own policy, which
 * stops compaction and manifest rewrites there.
 */
class MaintenancePoliciesTest {

    private static final Path DEMO = Path.of("..", "demo");
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String HELD = "brokerage.compliance.aml_cases";

    private static final Map<String, Map<String, String>> EXPECTED = Map.of(
        "table " + HELD, Map.of("compaction.enabled", "false", "manifest-rewrite.enabled", "false"),
        "namespace brokerage.markets", Map.of("snapshot-expiry.max-snapshot-age", "PT1H",
            "snapshot-expiry.min-snapshots-to-keep", "1", "compaction.min-input-files", "2",
            "manifest-rewrite.min-manifests", "10"),
        "namespace brokerage.digital", Map.of("snapshot-expiry.max-snapshot-age", "P7D",
            "snapshot-expiry.min-snapshots-to-keep", "1", "compaction.min-input-files", "2",
            "manifest-rewrite.min-manifests", "10"));

    /** demo.sh's MAINTENANCE_POLICIES, by "kind target", in its order. */
    private static Map<String, Map<String, String>> policies() throws IOException {
        Matcher m = Pattern.compile("(?ms)^MAINTENANCE_POLICIES='(.*?)^'").matcher(Files.readString(DEMO.resolve("demo.sh")));
        assertThat(m.find()).as("MAINTENANCE_POLICIES in demo.sh").isTrue();
        Map<String, Map<String, String>> policies = new LinkedHashMap<>();
        for (String line : m.group(1).split("\n")) {
            if (line.isBlank()) {
                continue;
            }
            String[] parts = line.trim().split(" ", 3);
            assertThat(parts[0]).isIn("namespace", "table");
            Map<String, String> keys = new LinkedHashMap<>();
            JSON.readTree(parts[2]).properties().forEach(e -> keys.put(e.getKey(), e.getValue().asString()));
            assertThat(policies.put(parts[0] + " " + parts[1], keys)).as("one line for " + parts[1]).isNull();
        }
        return policies;
    }

    /** The keys the catalog accepts, from its own MaintenancePolicy (catalog-server). */
    private static Set<String> catalogKeys() throws IOException {
        String source = Files.readString(Path.of("..", "catalog-server", "src", "main", "java", "nuna", "catalog",
            "server", "governance", "MaintenancePolicy.java"));
        Matcher m = Pattern.compile("public static final String \\w+ = \"([a-z.-]+)\";").matcher(source);
        Set<String> keys = new TreeSet<>();
        while (m.find()) {
            keys.add(m.group(1));
        }
        assertThat(keys).contains("snapshot-expiry.max-snapshot-age", "compaction.min-input-files");
        return keys;
    }

    @Test
    void demoShSetsThePoliciesThisTestHoldsWithKeysTheCatalogKnows() throws IOException {
        Map<String, Map<String, String>> policies = policies();
        assertThat(policies).isEqualTo(EXPECTED);
        Set<String> known = catalogKeys();
        policies.values().forEach(keys -> assertThat(known).containsAll(keys.keySet()));
        // At its first failure brokerage_maintenance stops: the held table's own policy goes first.
        assertThat(policies.keySet().iterator().next()).isEqualTo("table " + HELD);
    }

    @Test
    void everyStreamsNamespaceKeepsSnapshotsShortAndIsCompactedSoonerThanTheCatalogsPolicy() throws IOException {
        Map<String, Map<String, String>> policies = policies();
        Set<String> streams = new TreeSet<>();
        for (Feed f : Feed.values()) {
            if (f.streaming()) {
                streams.add(f.namespace);
            }
        }
        assertThat(streams).containsExactly("brokerage.digital", "brokerage.markets");
        for (String ns : streams) {
            Map<String, String> keys = policies.get("namespace " + ns);
            assertThat(keys).as(ns + "'s policy").isNotNull();
            // lakehouse's (maintenance_policy): a partition of 5 small files, 100 manifests.
            assertThat(Integer.parseInt(keys.get("compaction.min-input-files"))).isLessThan(5);
            assertThat(Integer.parseInt(keys.get("manifest-rewrite.min-manifests"))).isLessThan(100);
            assertThat(Duration.parse(keys.get("snapshot-expiry.max-snapshot-age")))
                .isLessThanOrEqualTo(Duration.ofDays(7));
            // Nothing a stream's policy sets turns a job off.
            assertThat(keys).doesNotContainValue("false");
        }
        assertThat(Duration.parse(policies.get("namespace brokerage.digital").get("snapshot-expiry.max-snapshot-age")))
            .as("digital's 7-day retention (the design's namespace tree)").isEqualTo(Duration.ofDays(7));
    }

    @Test
    void nothingReachesTheHeldAmlCasesButItsOwnPolicyWhichStopsEveryJobThatWritesIt() throws IOException {
        Map<String, Map<String, String>> policies = policies();
        for (Map.Entry<String, Map<String, String>> p : policies.entrySet()) {
            String target = p.getKey().substring(p.getKey().indexOf(' ') + 1);
            boolean covers = HELD.equals(target) || HELD.startsWith(target + ".");
            if (!covers) {
                continue;
            }
            assertThat(p.getKey()).isEqualTo("table " + HELD);
            // Expiry and orphan removal the hold's guardrail stops; compaction and manifest rewrites commit new
            // snapshots, so its own policy stops them, and sets nothing else.
            assertThat(p.getValue()).isEqualTo(Map.of("compaction.enabled", "false", "manifest-rewrite.enabled", "false"));
        }
        assertThat(policies).containsKey("table " + HELD);
    }

    /** gen sets the policies after lake_grants, whose namespace grants give ana her modify there. */
    @Test
    void genSetsThemAfterTheNamespaceGrants() throws IOException {
        String sh = Files.readString(DEMO.resolve("demo.sh"));
        int gen = sh.indexOf("\ngen_start() {");
        String body = sh.substring(gen, sh.indexOf("\n}\n", gen));
        List<String> steps = Arrays.stream(body.split("\n")).map(String::trim).toList();
        int grants = steps.indexOf("lake_grants"), policies = -1;
        for (int i = 0; i < steps.size(); i++) {
            if (steps.get(i).startsWith("brokerage_maintenance ")) {
                policies = i;
            }
        }
        assertThat(grants).isPositive();
        assertThat(policies).isGreaterThan(grants);
    }
}
