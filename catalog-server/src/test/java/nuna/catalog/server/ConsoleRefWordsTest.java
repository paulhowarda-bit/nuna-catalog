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
                "const mark = pill('warn', text);",
                // Every dropped table at the path, newest first, each with its own slot (Atelier's check, 2026-11-25).
                "const dropped = objects.filter(x => x.state !== 'active');",
                "const opening = i === 0 ? [who ? `${who} dropped it on ${t}.` : `It was dropped on ${t}.`, ' Main has no such table.']",
                ": [who ? `${who} dropped an earlier table at this path on ${t}.` : `An earlier table at this path was dropped on ${t}.`];",
                "kept ? [' Still kept by ', ...keptChips(c, o), '.'] : ' No branch or tag keeps it. Cleanup can purge it and delete its files.'",
                "cref(x, {warn: !!x.expired}), x.expired ? ' (past its expiry)' : null)]);",
                // Item 279's control goes here, one for each dropped table.
                "'data-kept-acts': o.id, 'data-may-remove': String(!!(o.may || {}).remove)",
                // The read's walk cut short (the Security Expert's review; Atelier's words, 2026-11-25).
                "objects.cutShort = !!answer['cut-short'];",
                "if (!objects || !(objects.length || objects.cutShort)) return null;",
                "objects.cutShort ? h('p', {class: 'fine', 'data-kept-cut': ''}, 'The catalog reads only part of a namespace\\'s dropped '",
                "+ 'tables and stopped before the end, so an earlier table at this path, and the branches and tags that keep it, may be '",
                "+ 'missing here.') : null",
                "if (!dropped.length && objects.cutShort) {",
                "h('p', {'data-kept-cut': ''}, 'The catalog reads only part of a namespace\\'s dropped tables and found none at this path '",
                "+ 'among them; one dropped earlier may still be kept by a branch or tag.'));",
                // Drops past the newest five: one line, for audit readers only.
                "const leftOut = auditOpen(c) && dropped.some(x => x['dropped-by-left-out']);",
                "leftOut ? h('p', {class: 'fine', 'data-kept-left-out': ''}, 'Who dropped each table past the newest five is not looked up here: ',",
                "h('a', {href: pageHref('audit', c) + '&' + new URLSearchParams({path})}, `search the audit feed for ${path}`), '.') : null,");
        // The pill is drawn only once some dropped table is found.
        assertThat(refs.indexOf("found none at this path")).isLessThan(refs.indexOf("droppedPill(title, 'Dropped on main');"));
        // The left-out line comes after the paragraphs and before the cut-short line.
        assertThat(refs.indexOf("data-kept-left-out")).isLessThan(refs.indexOf("and stopped before the end"));
        // The audit feed reads its path filter from the link (backlog 187).
        assertThat(resource("audit.js")).contains("path: q.get('path') || ''");
        assertThat(refs).doesNotContain("No ref holds it.", "still reads it: @", "A ref made before then still keeps it",
                "pill('bad', text)", "objects.find(x => x.state !== 'active')");
    }

    /** Item 170: dropped on main, read through a ref that keeps it, and main's newer table at the path. */
    @Test
    void aTableDroppedOnMainButKeptByTheRefSaysItStaysReadableUntilTakenOut() throws IOException {
        String refs = resource("refs.js");

        assertThat(refs).contains("droppedPill(title, `Dropped on main ${dayMon(o['dropped-at'])}`);",
                "if (!o || o.state === 'active') return null;",
                "now: objects.find(x => x.state === 'active' && x.id !== o.id) || null",
                "who ? `${who} dropped ${path} on main on ${t}.` : `${path} was dropped on main on ${t}.`",
                "` @${target.ref} still keeps it, so it reads the table as it was; main answers 404 for it.`",
                "' It stays readable here, and its files stay, until it is ',",
                "h('a', {href: objectHref(target, 'access'), 'data-kept-link': ''}, 'taken out of'), ' every branch and tag that keeps it.'",
                // Main made a new table by that name (Atelier, 2026-11-25).
                "id: 'replaced-on-main', 'data-kept': 'replaced'",
                "who ? `${who} dropped ${path} on main on ${t}, and main made a new table by that name on ${refAt(now['made-at'])}.`",
                ": `${path} was dropped on main on ${t}, and main made a new table by that name on ${refAt(now['made-at'])}.`",
                "` @${target.ref} still keeps the old one, so this page reads the table as it was. `",
                "h('a', {href: onMain(target, null), 'data-main-table': ''}, `Main's ${path}`), ' is a different table.', ...untilTakenOut(target)",
                "const onMain = (target, tab) => objectHref({...target, ref: null}, tab);",
                // Access, Maintenance and Audit show the line, not the new table's data, and leave 279's panel room.
                "`These tabs are main's, and main's ${path} is a different table from the one @${target.ref} reads. Open them on `",
                "h('a', {href: onMain(target, tab), 'data-main-tab': tab}, `main's ${path}`), '.')),",
                "h('div', {'data-kept-panel': found.o.id, 'data-tab': tab})");
        assertThat(refs).doesNotContain("was made before then, so it still reads the table as it was",
                "objects.some(x => x.state === 'active')) return null;");
        assertThat(resource("table.js")).contains(
                "if (target.ref) droppedThroughRef(target, title).then(note => note && tabs.before(note), () => {});",
                "const replaced = what && await replacedGuard(target, {health: 'maintenance', access: 'access', audit: 'audit'}[what]);",
                "if (replaced) return replaced;");
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
                "api.auditFeed(c, {type: 'nuna.catalog.ref', subject: objectId, pageToken})",
                "const d = (x.event || {}).data || {};",
                "return d.ref === r.id && String(d.action || '').toUpperCase() === 'OBJECT_REMOVED'",
                "&& (d.tables || []).some(t => t.id === objectId);",
                "const who = x && (x.principal || (((x.event || {}).data || {}).actor || {}).principal);");
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
