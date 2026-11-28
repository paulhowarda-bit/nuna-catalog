package nuna.catalog.server;

import static nuna.catalog.server.ConsoleRulesTest.resource;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import org.junit.jupiter.api.Test;

/**
 * Atelier's amendment to backlog 330 (2026-11-27), pinned in the console's modules as {@link ConsoleRefWordsTest} pins
 * hers, so this needs only Java: History folds the snapshot expiries made one after another into one row, opened on
 * click; Contents' column is the last data change; and the worker is "nuna maintenance", its principal in the title.
 * {@code ConsoleBrowserTest#expiriesFoldIntoOneRowAndContentsNamesTheLastDataChange} (heavy) checks them on the page.
 */
class ConsoleHistoryWordsTest {

    @Test
    void historyFoldsTheExpiriesMadeOneAfterAnother() throws IOException {
        String table = resource("table.js");

        assertThat(table).contains(
                "export const expiryOnly = e => Array.isArray(e.updates) && e.updates.some(u => u.action === 'remove-snapshots')",
                "&& e.updates.every(u => EXPIRY_ACTIONS.has(u.action));",
                "const key = expiryOnly(e) ? `${e.principal}\\u0000${e['end-user'] || ''}\\u0000${e.branch || ''}` : null;",
                "r.change.replaceChildren(`Snapshots expired ${fmt(r.entries.length)} times`);",
                "r.span.replaceChildren(`${at(oldest['committed-at'])} to ${at(newest['committed-at'])}`);",
                "r.btn.textContent = open ? 'Hide' : `Show ${r.entries.length}`;",
                "entries.forEach(place);");
        assertThat(table).doesNotContain("tbody.append(...entries.flatMap(row));");
    }

    @Test
    void theWorkerIsNunaMaintenanceWithItsPrincipalInTheTitle() throws IOException {
        assertThat(resource("browse.js")).contains("export const MAINTENANCE_NAME = 'nuna maintenance';",
                "export const committer = c => c.maintenance ? MAINTENANCE_NAME : c.principal;",
                "h('span', {class: 'muted', title: c.maintenance ? c.principal : null}, 'by ' + who)");
        assertThat(resource("table.js"))
                .contains("h('b', {title: e.maintenance ? e.principal : null}, committer(e) || '–')");
    }

    @Test
    void contentsNamesTheLastDataChange() throws IOException {
        String browse = resource("browse.js");

        assertThat(browse).contains("x.ref ? 'Last data change here' : 'Last data change'",
                "h('td', {class: 'last'}, lastDataChange(kept))",
                "last: newestDataChange(t['newest-data-change'])");
        assertThat(browse).doesNotContain("'Last commit'", "lastCommit(kept['last-commit'])");
    }
}
