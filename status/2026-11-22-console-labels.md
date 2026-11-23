# 2026-11-22, the console sets all four labels (item 140); Paul's decisions on labels and properties

Branch `worktree-agent-aa691a9a566f99060`.

- Paul's two decisions of the day are in the ledger: D27 amended (nuna is the catalog; its console sets
  classification, owner, retention class and the regulated flag; the push API stays for an outside tool) and D45
  amended (every Iceberg property shown read-only; only keys that do something edited, from a list; a catalog's
  settings read-only; free-form key entry leaves the console). `docs/design/console/README.md` says the prototype's
  free-form "Set a property" is superseded. Items 140 and 141 added.
- Item 140 built: the governance tab sets the owner, a retention class (chosen from the configured ones, which the
  maintenance policy's answer now lists) and the regulated mark beside the classification, each recorded with
  provenance `console`. Details in `backlog/140.md`.
- Checked: `./gradlew build` after merging main (311b5fc): BUILD SUCCESSFUL in 2m 47s, 1,349 tests in the result
  files, no failure. `GovernanceSummaryTest` (fast) has the two new tests, 11 tests passing.
- Not run: `ConsoleLabelsBrowserTest`, the new browser test, is tagged `heavy`; it runs in the first overnight heavy
  build. No bite: the branch adds no guarded check.
- Left out: a view's labels; the console's view page has no Governance tab.
