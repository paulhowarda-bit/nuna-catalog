package fidice.catalog.iceberg;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import fidice.catalog.iceberg.Outcome.NewMetadata;
import fidice.catalog.iceberg.Outcome.NoChange;
import fidice.catalog.iceberg.Outcome.RequirementFailed;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Golden round trips (D32): request JSON through the adapter, compared byte for byte with what
 * iceberg-core's reference handlers write for the same request. The golden files hold the reference
 * output, so they are also the ratchet: after an iceberg-core bump, {@code referenceMatchesGolden} shows
 * every byte the reference changed, and {@code adapterMatchesGolden} shows whether the adapter followed.
 *
 * <p>Re-record only after reading that diff: {@code -Pgolden.record=true} rewrites the goldens from the
 * reference, in case order, so a changed base flows into the cases built on it.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class GoldenTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    static Stream<GoldenCase> cases() {
        return GoldenCase.ALL.stream();
    }

    @Order(1)
    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void referenceMatchesGolden(GoldenCase c) throws IOException {
        long from = System.currentTimeMillis();
        Outcome outcome = Reference.run(c);
        Outcome reference = Golden.clock(outcome, from, System.currentTimeMillis());
        if (c.uuid() != null) {
            reference = Golden.uuid(reference, c.uuid());
        }
        assertThat(reference).as("reference outcome").isInstanceOf(c.expected());
        String actual = Golden.render(reference);
        if (Golden.RECORD) {
            Golden.record(c, actual);
        }
        String golden = Golden.expected(c);
        assertThat(actual).as("iceberg-core reference against %s\n%s", Golden.file(c).getFileName(),
                Golden.differences(golden, actual)).isEqualTo(golden);
    }

    @Order(2)
    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void adapterMatchesGolden(GoldenCase c) {
        long from = System.currentTimeMillis();
        Outcome outcome = run(c);
        Outcome ours = Golden.clock(outcome, from, System.currentTimeMillis());
        String golden = Golden.expected(c);
        String actual = Golden.render(ours);
        assertThat(actual).as("adapter against %s\n%s", Golden.file(c).getFileName(),
                Golden.differences(golden, actual)).isEqualTo(golden);
        if (ours instanceof NewMetadata m) {
            assertThat(m.json()).as("adapter bytes").isEqualTo(Golden.compact(golden));
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void layoutOnlyAddsWhitespace(GoldenCase c) {
        if (c.expectsMetadata()) {
            String bytes = Golden.compact(Golden.expected(c));
            assertThat(Golden.compact(Golden.layout(bytes))).isEqualTo(bytes);
            assertThat(Golden.layout(bytes)).isEqualTo(Golden.expected(c));
        }
    }

    /**
     * Counts the update and requirement types in the vendored 1.12.0 spec (the {@code const} of every
     * {@code *Update} and {@code Assert*} schema), and checks that the goldens apply every update and both
     * pass and fail every requirement.
     */
    @Test
    void goldensCoverEverySpecType() throws IOException {
        List<String> spec = Files.readAllLines(Path.of(System.getProperty("basedir", "."), "..", "spec",
                "rest-catalog-open-api.yaml"));
        Set<String> actions = consts(spec, name -> name.endsWith("Update"));
        Set<String> requirements = consts(spec, name -> name.startsWith("Assert"));
        assertThat(actions).hasSize(25);
        assertThat(requirements).hasSize(9);

        Set<String> applied = new TreeSet<>();
        Set<String> held = new TreeSet<>();
        Set<String> failed = new TreeSet<>();
        Set<String> tableVersions = new TreeSet<>();
        for (GoldenCase c : GoldenCase.ALL) {
            JsonNode request = JSON.readTree(Golden.request(c));
            if (c.expectsMetadata()) {
                request.path("updates").forEach(u -> applied.add(u.get("action").asText()));
                if (!c.isView()) {
                    tableVersions.add(JSON.readTree(Golden.compact(Golden.expected(c))).get("format-version").asText());
                }
            }
            Set<String> types = new TreeSet<>();
            request.path("requirements").forEach(r -> types.add(r.get("type").asText()));
            if (c.expected() == RequirementFailed.class) {
                failed.addAll(types);
            } else if (c.expectsMetadata() || c.expected() == NoChange.class) {
                held.addAll(types);
            }
        }
        assertThat(applied).containsAll(actions);
        assertThat(held).containsAll(requirements);
        assertThat(failed).containsAll(requirements);
        assertThat(tableVersions).containsExactly("1", "2", "3");
        assertThat(GoldenCase.ALL).anyMatch(c -> c.expected() == NoChange.class && c.isView())
                .anyMatch(c -> c.expected() == NoChange.class && !c.isView());
    }

    private static Outcome run(GoldenCase c) {
        String request = Golden.request(c);
        return switch (c.kind()) {
            case CREATE_TABLE -> Metadata.createTable(request, c.uuid(),
                    Golden.WAREHOUSE + "/ns/" + Golden.requestedName(c));
            case COMMIT_TABLE -> Metadata.commitTable(request, Golden.baseJson(c), Golden.baseLocation(c));
            case CREATE_VIEW -> Metadata.createView(request, c.uuid(),
                    Golden.WAREHOUSE + "/ns/" + Golden.requestedName(c), Golden.CLOCK);
            case COMMIT_VIEW -> Metadata.commitView(request, Golden.baseJson(c));
        };
    }

    /** The {@code const} values inside the component schemas whose names match. */
    private static Set<String> consts(List<String> spec, Predicate<String> schemaName) {
        Pattern schema = Pattern.compile("^ {4}(\\w+):$");
        Pattern constant = Pattern.compile("^\\s+const: \"([a-z-]+)\"$");
        Set<String> found = new TreeSet<>();
        String current = "";
        for (String line : spec) {
            Matcher name = schema.matcher(line);
            if (name.matches()) {
                current = name.group(1);
            }
            Matcher value = constant.matcher(line);
            if (value.matches() && schemaName.test(current)) {
                found.add(value.group(1));
            }
        }
        return found;
    }
}
