package nuna.catalog.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The web console's files as they are built, read from the classpath, so this needs only Java: the page holds no
 * inline code, no module writes markup or styles inline, and every module is one this list names, so a new one
 * (as {@code refs.js}, item 109) is held to the same rules. {@link ConsoleTest} serves each of them under the policy.
 */
class ConsoleRulesTest {

    static final List<String> FILES = List.of("index.html", "access.js", "app.js", "api.js", "audit.js", "auth.js",
            "browse.js", "dom.js", "governance.js", "health.js", "maintenance.js", "nights.js", "overview.js", "panels.js",
            "queue.js", "readiness.js", "refs.js", "requests.js", "route.js", "rules.js", "settings.js", "table.js",
            "tree.js", "style.css", "favicon.svg");

    @Test
    void theFilesHoldNoInlineCodeAndNeverWriteMarkup() throws IOException {
        Pattern inlineScript = Pattern.compile("<script(?![^>]*\\bsrc=)[^>]*>", Pattern.CASE_INSENSITIVE);
        Pattern inlineStyle = Pattern.compile("<style|\\sstyle=|\\son[a-z]+=", Pattern.CASE_INSENSITIVE);
        String page = resource("index.html");
        assertThat(inlineScript.matcher(page).find()).as("inline <script> in index.html").isFalse();
        assertThat(inlineStyle.matcher(page).find()).as("inline style or handler in index.html").isFalse();
        for (String file : FILES.stream().filter(f -> f.endsWith(".js")).toList()) {
            String code = resource(file);
            assertThat(code).as(file).doesNotContain(".innerHTML =", ".outerHTML =", "insertAdjacentHTML",
                    "document.write", "eval(", "new Function", "localStorage.setItem('nuna.token", ".style.", "cssText");
        }
        assertThat(resource("auth.js")).contains("sessionStorage.setItem(TOKEN").doesNotContain("localStorage.")
                .doesNotContain("document.cookie");
    }

    @Test
    void everyModuleTheConsoleHasIsOneTheRulesRead() throws IOException, URISyntaxException {
        Path folder = Path.of(ConsoleRulesTest.class.getResource("/nuna/ui/index.html").toURI()).getParent();
        try (Stream<Path> files = Files.list(folder)) {
            assertThat(files.map(f -> f.getFileName().toString()).filter(f -> f.endsWith(".js")).toList())
                    .as("every module is in FILES").allSatisfy(f -> assertThat(FILES).contains(f));
        }
    }

    static String resource(String file) throws IOException {
        try (InputStream in = ConsoleRulesTest.class.getResourceAsStream("/nuna/ui/" + file)) {
            assertThat(in).as(file).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
