# 2026-11-22, Health's DR replication and Store panels (items 138 and 139)

- Item 138: Health's DR replication panel, from `GET /management/v1/dr/watermarks`, 200 a page and at most 5 pages
  (1,000 rows; "at least" past that). Tables with a watermark are counted as behind it, with the most versions behind
  (linking to the table) and the longest lag; tables with none are counted apart as never verified. With no row
  holding a watermark, as with no DR store or before the verifier's first pass, the panel says no DR verifier has
  reported on them instead of counting every table as behind by all its versions.
- Item 139: `GET /management/v1/health/server?store=true` gives the backend and version from the server's own
  connection (`StoreFacts`; CockroachDB told apart from PostgreSQL) and the self-check this server ran at start, with
  each finding `nuna.database.allow-unsafe` let through (`StoreSelfCheck`, kept by `StoreWiring`). Opt-in, so the
  servers gathering each other's figures never wait on the database. Health's Store panel shows both. Point-in-time
  recovery is left out: no source the server can read. `catalog-store-api` is unchanged.
- Docs: `docs/observability/health-page.md`.
- Bites in `bites/D45/`: `health-dr-unverified-counted-as-behind`, `store-read-before-operator-check`,
  `store-cockroach-named-postgresql`, `store-self-check-findings-dropped`; the three store bites were run once by hand
  here and failed their tests on assertions. Recorded again against the moved code, with the same breaks:
  `bites/D38/server-health-for-anyone`, `bites/M8/self-check-finding-only-warns` (run: `StoreWiringTest`'s two
  tests still fail) and `bites/OBS-41/store-not-observed`.
- Checked: `StoreFactsRouteTest` (8) and `StoreWiringTest` (6), Java only, pass. The panels' states (none behind,
  no verifier, mixed, refused, findings, database not reached) rendered in node against a stub DOM and fetch.
- Fast build (`./gradlew build`) after merging main at 311b5fc: BUILD SUCCESSFUL in 2 m 12 s, "All 474 bites
  apply"; the test reports hold 1,355 tests, no failure or error.
- Not run: `ConsoleHealthBrowserTest` (heavy, new) and the bites against heavy tests
  (`health-dr-unverified-counted-as-behind`, `D38/server-health-for-anyone`, `OBS-41/store-not-observed`). The
  session's permission classifier refused the agent's one run of the heavy browser test; the overnight run takes
  them.
