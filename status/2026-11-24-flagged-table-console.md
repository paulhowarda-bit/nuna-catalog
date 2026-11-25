# 2026-11-24, the console for a flagged table: why it is read through Trino, the steward's flag button, rule changes in the audit (backlog 241)

Branch `worktree-agent-a12e7e64ff99dc3fc`. Console only, in Atelier's words (backlog 241):

- A flagged table's Overview, Schema and History say "Its data is read through Trino, which applies each group's rules.
  The console shows no rows." instead of the 403, when the table's own flag is true.
- A steward (manage-tags) sees "Flag for row and column rules" or "Remove the flag" on a table's Access tab, in the
  Governance panel (not where backlog 243's rules panel goes). Removing it while rules stand says "Rules stand: the
  grant admin removes them first."
- Rule changes are their own audit kind, "Row and column rules", each with a sentence: who set or removed which
  group's rule on which table.

Checked:
- `./gradlew :catalog-server:test --tests 'nuna.catalog.server.governance.ConsoleFlaggedTableBrowserTest' -Pheavy`,
  demo down: 3 tests, none failing.
- `node --check` on `app.js`, `table.js`, `panels.js`, `api.js` and `audit.js`, each copied to `.mjs`.
- Bites recorded, not run: `bites/UI/console-flagged-table-shows-refusal`, `console-rules-stand-unworded`,
  `console-rule-change-no-kind`; each passes `git apply --check`.

Not covered: no fast or heavy build (the batch runs it); `ConsoleBrowserTest` and `ConsoleLabelsBrowserTest` were not
run (zero-count kinds are not drawn in By kind, and the new button is on tables only, so their exact lists hold by
reading). Not checked live on the demo. Backlog 239 may change the Governance tab's flag line beside this button.
