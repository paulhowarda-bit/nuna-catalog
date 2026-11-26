# 2026-11-26, the five refs browser cases green (backlog 109, 167, 169, 170, 279)

The first heavy run of these `ConsoleBrowserTest` cases (main at 4c5e0084) failed five. Each was the test asking for
the wrong thing; the console was right in every case, so no console code changed and no bite moved.

- 279, `aStewardTakesADroppedTableOutOfATagFromAccessAndAReaderSeesNoButton`: the dialog said "Took … out of @eod_…",
  but the tag is a `cref` chip (inline-flex, Atelier's design in 279), and Selenium's `getText()` puts its `@` and name
  on lines of their own. The wait now reads up to the chip and the whole line is checked by `textContent`.
- 169, `theFeedsRefSearchReadsFromTheLiveRefsMakingAndTheRecordTabAsksByItsId`: the in-page feed answered records with
  no `table.committed-at`, which the server always sends (`AuditReadRoutes#table`), so the status said nothing had been
  committed. The mock now answers as the server does.
- 167, `aRefMadeWithAPurposeShowsItAsText`: the Record tab shows only for an audit reader (`auditOpen`), and the tester
  reads no audit, so it was never drawn. The tester still makes the tag and checks the list and the page's sub-line; a
  grant admin then opens the Record tab, with the record of the making (its purpose included, as `RefChange` carries
  it) answered in the page, since this server has no audit tables.
- 170, `aTableDroppedOnMainMadeAfterThePointOrTakenOutOfARefSaysWhich`: the tester may not read the audit, so
  `dropped-by` is not sent (D23 as amended 2026-11-22; `RefRoutesTest`) and the page rightly says "It was dropped on …".
  The five actor-word checks now assert the words without an actor, with `startsWith`, so an actor shown to a
  non-reader would fail them. The actor words stay covered by `ConsoleRefWordsTest` and `RefRoutesTest`.
- 109, `aBranchIsMadeCommittedOnPreviewedAndMerged`: backlog 168's Contents block had opened the namespace page in the
  middle of the table page's checks, so `#table-page nav.crumbs > a` was looked for on the namespace page. The block
  now runs after the table page's checks (and its Export).
- Checked: `./gradlew :catalog-server:test --tests 'nuna.catalog.server.governance.ConsoleBrowserTest' -Pheavy`,
  35 tests, 0 failures, 0 errors (171 s). The first try failed at setup: Kafka never reached RUNNING
  (`Timed out waiting for log output matching '.*Transitioning from RECOVERY to RUNNING.*'`, exit 143) while the
  demo's kind node (`nuna-control-plane`) was starting up beside it with no nuna-demo pods; the rerun passed. Then the
  fast build after merging `main`, green (all bites apply).
- Not run: `bites/run.sh`.
