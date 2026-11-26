package nuna.catalog.server;

import static nuna.catalog.server.ConsoleRulesTest.resource;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import org.junit.jupiter.api.Test;

/**
 * Atelier's words for pages read through a branch or tag, pinned in the console's modules as they are built, as
 * {@link ConsoleRulesTest} pins its rules: so this needs only Java. The browser tests (heavy) check the words on the
 * page; these check, in every fast build, that a module still writes them as she gave them and no longer writes the
 * words they replaced.
 */
class ConsoleRefWordsTest {

    /** Item 168, Atelier's ruling of 2026-11-25, 1: the On @ref cell of a table unchanged on the ref. */
    @Test
    void anUnchangedTableIsAsAtThePointWithItsVersionOnlyWhereItsLineIsKnown() throws IOException {
        String browse = resource("browse.js");

        assertThat(browse).contains("'as at the point' + (!line ? ''",
                "line === 'main' ? ` · v${fmt(kept.ref.version)}` : ` · @${line}'s v${fmt(kept.ref.version)}`");
        assertThat(browse).doesNotContain("as at the point, v", "` of @${kept.ref.line}`");
    }

    /** Item 168, Atelier's ruling of 2026-11-25, 2: the band's point, the branches' changes, and an ended ref. */
    @Test
    void theBandNamesThePointTheBranchesChangesAndARefThatHasEnded() throws IOException {
        String refs = resource("refs.js");

        assertThat(refs).contains(
                "? `, with @${b.branch}'s changes as at ${refAt(b['as-of'])}` : ` and @${b.branch}'s as at ${refAt(b['as-of'])}`",
                "(cut ? refAt(cut) : `@${x}'s point`) + heldWords(r)",
                "` @${x} has ended, so the catalog no longer knows when its point was.`",
                "`: ${where.c} as it was at ${point.at}.`),",
                "point.ended, ' Read-only: no engine and no console page can change anything through it.'",
                "every other table reads as main had it at ${point.at}.${point.ended}`");
        assertThat(refs).doesNotContain("was made from`) + heldWords");
    }

    /** Item 168, Atelier's ruling of 2026-11-25, 3: Contents' footnote through a ref. */
    @Test
    void contentsFootnoteSaysWhatTheColumnsAreAndWhyADash() throws IOException {
        String browse = resource("browse.js");

        assertThat(browse).contains("'Only what your groups may see is listed. '",
                "`Format, snapshots and last commit are as @${x.ref} has each table. Open one to read it through @${x.ref}.`",
                "(lineUnknown(x) ? ' A dash under Last commit means a ref this one came from has ended, so the catalog"
                        + " no longer knows which line wrote that version.' : '')",
                "const lineUnknown = x => [...x.kept.values()].some(t => t.ref && !t.ref.line);");
        assertThat(browse).doesNotContain("Each table as @");
    }

    /** The ref line takes no list since the point comes from {@code cut-at} (Catalog UI Expert's review of 168). */
    @Test
    void theRefLineTakesOnlyTheRefs() throws IOException {
        assertThat(resource("refs.js")).contains("function refLine(c, refs) {", "refLine(c, refs),")
                .doesNotContain("refLine(c, refs, list)");
    }
}
