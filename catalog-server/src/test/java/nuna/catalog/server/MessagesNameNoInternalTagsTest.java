package nuna.catalog.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * No string in main code names a decision-ledger row, a backlog item or a milestone (backlog 313): those strings are
 * the messages clients, operators and demo users read, and the project's own tags mean nothing to them. The tags stay
 * in comments.
 */
class MessagesNameNoInternalTagsTest {

    private static final Pattern STRING = Pattern.compile("\"(?:[^\"\\\\\\n]|\\\\.)*\"");
    private static final Pattern TAG = Pattern.compile(
            "\\bD\\d{1,2}(\\.\\d+)?[a-z]?\\b|\\b(backlog|items?) \\d+|\\bM\\d{1,2}\\b|\\bbuild plan\\b");

    @Test
    void noStringInMainCodeNamesAnInternalTag() throws IOException {
        Path root = Path.of(System.getProperty("repo.root")).toRealPath();
        List<String> found = new ArrayList<>();
        try (Stream<Path> modules = Files.list(root)) {
            for (Path main : modules.map(m -> m.resolve("src/main/java")).filter(Files::isDirectory).toList()) {
                try (Stream<Path> files = Files.walk(main)) {
                    files.filter(p -> p.toString().endsWith(".java")).sorted()
                            .forEach(p -> found.addAll(tagged(root, p)));
                }
            }
        }
        assertThat(found).as("strings naming a D row, backlog item or milestone; put the tag in a comment").isEmpty();
    }

    @Test
    void theScanFindsATagInAStringButNotInAComment() {
        assertThat(tags("throw new X(\"never to individuals (D19)\");")).containsExactly("D19");
        assertThat(tags("    + \" the rule first (D20, backlog 240)\");")).containsExactly("D20", "backlog 240");
        assertThat(tags("    + \" a view (item 152).\", schema));")).containsExactly("item 152");
        assertThat(tags("    // The rule behind this message: D20.")).isEmpty();
        assertThat(tags("     * A {@code \"D19\"} in Javadoc.")).isEmpty();
        assertThat(tags("String sql = \"SELECT d1, M FROM t\";")).isEmpty();
    }

    private static List<String> tagged(Path root, Path file) {
        List<String> found = new ArrayList<>();
        try {
            List<String> lines = Files.readAllLines(file);
            for (int i = 0; i < lines.size(); i++) {
                if (!tags(lines.get(i)).isEmpty()) {
                    found.add(root.relativize(file).toString().replace('\\', '/') + ":" + (i + 1) + ": "
                            + lines.get(i).strip());
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return found;
    }

    private static List<String> tags(String line) {
        String code = line.strip();
        if (code.startsWith("//") || code.startsWith("*") || code.startsWith("/*")) {
            return List.of();
        }
        List<String> tags = new ArrayList<>();
        Matcher string = STRING.matcher(line);
        while (string.find()) {
            Matcher tag = TAG.matcher(string.group());
            while (tag.find()) {
                tags.add(tag.group());
            }
        }
        return tags;
    }
}
