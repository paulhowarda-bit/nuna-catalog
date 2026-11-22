# 2026-11-22, a governance summary per catalog and every hold and freeze with its history (item 128)

- Two routes for the console's Governance page (D45), both needing describe on the catalog:
  - `GET /management/v1/catalogs/{catalog}/governance/summary`: the catalog's tables, how many are classified, closed
    until classified, held and frozen; the holds and freezes standing; each regulated area; every closed table.
  - `GET /management/v1/catalogs/{catalog}/governance/holds`: every hold and freeze standing, from the store, and
    those released or lifted, from the audit topic's last 20,000 offsets per partition, with how far that read
    reached.
- Shapes in `build-plan.md`, M7. Choices in `backlog/128.md`: the history's source, who may read, what counts as
  classified and closed.
- Item 133 added: the lifted history read from the audit topic stops short in a busy deployment; keeping it in the
  store makes it complete.
- Checked: `GovernanceSummaryTest` (9) and `HoldHistoryTest` (4), Java-only, pass; `ManagementHiddenNamesTest` (16)
  passes with the new constructor. Five bites recorded in `bites/D45/`, each run once by hand here and failing its
  test on an assertion, each restored and applying with `git apply --check`.
- Fast build (`./gradlew build`) after merging main at c077676: BUILD SUCCESSFUL in 5 m, `:bitesApply` included; the
  test reports hold 1,270 tests, no failure or error.
- Not covered here: `GovernanceSummaryRouteTest` (heavy: PostgreSQL and Kafka) has not run; the overnight heavy
  build runs it. `bites/run.sh` has not run the new bites.
