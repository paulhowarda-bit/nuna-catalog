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

    /** Item 170, Atelier's design of 2026-11-25: dropped on main, read on main, in the verb "keeps". */
    @Test
    void aTableDroppedOnMainSaysWhoAndWhichRefsKeepIt() throws IOException {
        String refs = resource("refs.js");

        assertThat(refs).contains("droppedPill(title, 'Dropped on main');",
                "who ? `${who} dropped it on ${refAt(o['dropped-at'])}.` : `It was dropped on ${refAt(o['dropped-at'])}.`",
                "' Main has no such table.', kept ? [' A ref made before then still keeps it: ', ...keptChips(c, o), '.']",
                "' No branch or tag keeps it. Cleanup can purge it and delete its files.'",
                // Item 279's control goes here.
                "'data-kept-acts': o.id, 'data-may-remove': String(!!(o.may || {}).remove)");
        assertThat(refs).doesNotContain("No ref holds it.", "still reads it: @");
    }

    /** Item 170: dropped on main, read through a ref that keeps it. */
    @Test
    void aTableDroppedOnMainButKeptByTheRefSaysItStaysReadableUntilTakenOut() throws IOException {
        String refs = resource("refs.js");

        assertThat(refs).contains("droppedPill(title, `Dropped on main ${dayMon(o['dropped-at'])}`);",
                "who ? `${who} dropped ${path} on main on ${t}.` : `${path} was dropped on main on ${t}.`",
                "` @${target.ref} was made before then, so it still reads the table as it was; main answers 404 for it.`",
                "' It stays readable here, and its files stay, until it is ', h('a', {href: objectHref(target, 'access'),"
                        + " 'data-kept-link': ''},",
                "'taken out of'), ' every branch and tag that keeps it.'");
        assertThat(resource("table.js")).contains(
                "if (target.ref) droppedThroughRef(target, title).then(note => note && tabs.before(note), () => {});");
    }

    /** Item 170: through a ref, a table made after its point, and the fourth state, taken out of the ref. */
    @Test
    void throughARefATableMadeAfterThePointOrTakenOutOfItSaysWhich() throws IOException {
        String refs = resource("refs.js");

        assertThat(refs).contains(
                "`${path} was made on ${refAt(after['made-at'])}, after @${r.name}'s point, ${refAt(cut)}. `",
                "+ 'A ref holds only what main held then.'",
                "`@${r.name} no longer keeps ${path}, though main had it at the point: it was taken out of it.`",
                "if (who) line.append(` ${who} took it out on ${refAt(x.time)}.`);",
                // Only an audit reader asks the feed, and the match holds with or without item 169's subject filter.
                "if (!r.id || !auditOpen(c)) return null;",
                "api.auditFeed(c, {type: 'nuna.catalog.ref', subject: r.id, pageToken})",
                "x.data.ref === r.id",
                "=== 'OBJECT_REMOVED' && (x.data.tables || []).some(t => t.id === objectId)");
        // The fourth state is judged before "made after the point", so a table taken out never reads as made after.
        assertThat(refs.indexOf("it was taken out of it.")).isLessThan(refs.indexOf("A ref holds only what main held then."));
        assertThat(resource("table.js")).contains("if (error.status !== 404 || error.type !== 'NoSuchTableException') return null;",
                "|| await missingLoad(error, target, title)");
    }

    /** The ref line takes no list since the point comes from {@code cut-at} (Catalog UI Expert's review of 168). */
    @Test
    void theRefLineTakesOnlyTheRefs() throws IOException {
        assertThat(resource("refs.js")).contains("function refLine(c, refs) {", "refLine(c, refs),")
                .doesNotContain("refLine(c, refs, list)");
    }
}
