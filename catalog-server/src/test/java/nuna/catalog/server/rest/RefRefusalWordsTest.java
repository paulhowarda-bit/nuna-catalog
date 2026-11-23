package nuna.catalog.server.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import nuna.catalog.core.CatalogError;
import nuna.catalog.store.CatalogRecord;
import nuna.catalog.store.Name;
import nuna.catalog.store.Ref;
import org.junit.jupiter.api.Test;

/**
 * The console quotes the catalog's 400 for a change through a ref word for word (item 109): {@code refs.js}'s
 * read-only note and a tag's "What it keeps" write it out rather than wait for a refusal. This pins those words to
 * {@link OnRef}'s, so a change to either fails here until the other follows.
 */
class RefRefusalWordsTest {

    private static final CatalogRecord CATALOG = new CatalogRecord(UUID.randomUUID(), "catx", "catx");

    @Test
    void theConsoleQuotesTheRefusalOfAChangeThroughATag() throws IOException {
        OnRef tag = on(Ref.Kind.TAG);
        String refused = refusal(() -> tag.forCommit("opx"));

        assertThat(console()).contains(template(refused, "${operation}", "${catalog}"));
    }

    @Test
    void theConsoleQuotesTheRefusalOfAChangeOnMainOnlyThroughABranch() throws IOException {
        OnRef branch = on(Ref.Kind.BRANCH);
        String refused = refusal(() -> branch.mainOnly("opx"));

        assertThat(console()).contains(template(refused, "${operation}", "${catalog}"));
    }

    @Test
    void aTagsPageQuotesTheRefusalOfACommitThroughIt() throws IOException {
        OnRef tag = on(Ref.Kind.TAG);
        String refused = refusal(() -> tag.forCommit("updateTable"));

        assertThat(console()).contains(template(refused, "updateTable", "${c}"));
    }

    private static OnRef on(Ref.Kind kind) {
        Ref ref = new Ref(CATALOG.id(), UUID.randomUUID(), new Name("refx", "refx"), kind, Instant.EPOCH, "bea",
                Optional.empty(), Ref.MAIN,
                kind == Ref.Kind.BRANCH ? Optional.of(Instant.EPOCH.plusSeconds(86_400)) : Optional.empty());
        return new OnRef(CATALOG, Optional.of(ref));
    }

    /** The message of the 400 that {@code change} throws. */
    private static String refusal(Runnable change) {
        Throwable thrown = catchThrowable(change::run);
        assertThat(thrown).isInstanceOf(CatalogError.class);
        CatalogError error = (CatalogError) thrown;
        assertThat(error.status()).isEqualTo(400);
        return error.getMessage();
    }

    /** The catalog's words with its names put back as the console's template literal writes them. */
    private static String template(String message, String operation, String catalog) {
        return message.replace("opx", operation).replace("refx", "${r.name}").replace("catx", catalog);
    }

    private static String console() throws IOException {
        try (InputStream in = RefRefusalWordsTest.class.getResourceAsStream("/nuna/ui/refs.js")) {
            assertThat(in).as("refs.js on the class path").isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
