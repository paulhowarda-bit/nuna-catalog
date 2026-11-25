# 2026-11-24, the row-column-policy flag goes on tables only (backlog 239)

Branch `worktree-agent-a790ea5556942b3e8`. The set-attribute route refuses the row-column-policy flag on a catalog,
namespace or view with 400; a table's governance answer and the console's Governance tab no longer report a flag from
above as the table's own; and the server removes the flags set above a table at startup (`FlagsAboveTables`), each
with its audit record and a warning in the log (D20).

Checked: `./gradlew :catalog-server:test --tests nuna.catalog.server.governance.FlagOnTablesOnlyTest` (3 tests, green),
`ManagementHiddenNamesTest` (16) and `ConsoleRulesTest` (2) green, `GovernanceRoutesTest -Pheavy` (3, green: the server
starts with the new startup step), `:bitesApply` green. Three bites recorded in `bites/D20/`, each run by hand once and
seen to fail its test on an assertion, then restored: `flag-above-a-table`, `flag-from-above-in-force`,
`flag-above-a-table-kept`.

Not covered: no browser test of the console change (the route and the startup cleanup leave no flag above a table for
it to meet). The fast build was not run (the batch runs it). The demo's seed flags only a table; its kept data was not
read, since the demo was down: the next `up` cleans and logs any flag above a table.
