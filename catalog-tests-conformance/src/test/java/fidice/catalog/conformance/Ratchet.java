package fidice.catalog.conformance;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The RCK no-regression rule: a test that passed in the committed baseline must still pass.
 * Files hold one {@code STATUS<TAB>test} line per test, sorted by test.
 */
final class Ratchet {

    static final String PASS = "PASS";

    private Ratchet() {
    }

    /** Tests that pass in {@code baseline} but not in {@code current}, including removed tests. */
    static List<String> regressions(Map<String, String> baseline, Map<String, String> current) {
        return baseline.entrySet().stream()
                .filter(e -> PASS.equals(e.getValue()))
                .filter(e -> !PASS.equals(current.get(e.getKey())))
                .map(Map.Entry::getKey)
                .sorted()
                .toList();
    }

    /** Tests that pass now but are not recorded as passing in {@code baseline}. */
    static List<String> newPasses(Map<String, String> baseline, Map<String, String> current) {
        return current.entrySet().stream()
                .filter(e -> PASS.equals(e.getValue()))
                .filter(e -> !PASS.equals(baseline.get(e.getKey())))
                .map(Map.Entry::getKey)
                .sorted()
                .toList();
    }

    static Map<String, String> read(Path file) throws IOException {
        Map<String, String> outcomes = new TreeMap<>();
        if (Files.exists(file)) {
            for (String line : Files.readAllLines(file)) {
                int tab = line.indexOf('\t');
                if (tab > 0) {
                    outcomes.put(line.substring(tab + 1), line.substring(0, tab));
                }
            }
        }
        return outcomes;
    }

    static void write(Path file, Map<String, String> outcomes) throws IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        StringBuilder text = new StringBuilder();
        new TreeMap<>(outcomes).forEach((test, status) -> text.append(status).append('\t').append(test).append('\n'));
        Files.writeString(file, text);
    }
}
